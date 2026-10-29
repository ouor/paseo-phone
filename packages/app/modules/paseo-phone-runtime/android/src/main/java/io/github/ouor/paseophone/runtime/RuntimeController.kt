package io.github.ouor.paseophone.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.ouor.paseophone.runtime.exchange.ExchangeFolder
import io.github.ouor.paseophone.runtime.linux.Identity
import io.github.ouor.paseophone.runtime.linux.LinuxPaths
import io.github.ouor.paseophone.runtime.linux.PhoneConstants
import io.github.ouor.paseophone.runtime.linux.RootfsSpec
import io.github.ouor.paseophone.runtime.linux.RootfsState
import io.github.ouor.paseophone.runtime.notify.RuntimeNotification
import io.github.ouor.paseophone.runtime.setup.SetupEvent
import io.github.ouor.paseophone.runtime.setup.SetupMode
import io.github.ouor.paseophone.runtime.setup.SetupResult
import io.github.ouor.paseophone.runtime.setup.SetupRunner
import io.github.ouor.paseophone.runtime.ssh.AuthorizedKeys
import io.github.ouor.paseophone.runtime.ssh.HostKeys
import io.github.ouor.paseophone.runtime.system.DaemonProbe
import io.github.ouor.paseophone.runtime.system.DeviceChecks
import io.github.ouor.paseophone.runtime.system.NetworkAddresses
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Error surfaced to JS as `PhoneRuntimeError`; [code] is a `PhoneRuntimeErrorCode` from src/types.ts. */
class RuntimeError(val code: String, message: String) : Exception(message)

/**
 * The runtime state machine and the only writer of state (architecture §7, §8): command serialization
 * and the setup / start / stop flows. Processes live in [ProcessSupervisor], the snapshot shape in
 * [StateSnapshot]. Everything runs on one confined dispatcher; blocking work hops to IO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RuntimeController : RuntimeService.Listener {
  private const val TAG = "PaseoPhoneRuntime"

  private val confined = Dispatchers.Default.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + confined)

  private lateinit var context: Context
  private lateinit var paths: LinuxPaths
  private lateinit var store: RuntimeStore
  private lateinit var runner: SetupRunner
  private lateinit var supervisor: ProcessSupervisor
  @Volatile private var initialized = false

  private var data = RuntimeStore.Data()
  private var phase: Phase = Phase.Checking
  private var linux = Svc.STOPPED
  private var env = Env()
  private var exchangeBound = false
  private var commandJob: Job? = null
  private var unwatchNetwork: (() -> Unit)? = null
  private var lastProgressAt = 0L
  private var lastView: RuntimeNotification.View? = null

  private val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()

  @Volatile private var snapshot: Map<String, Any?> = mapOf("status" to "checking")

  // ---- lifecycle -------------------------------------------------------------------------------

  /** Idempotent. Decides the first state from disk (architecture §2.3, §8.2); never stays in `checking`. */
  @Synchronized
  fun init(appContext: Context) {
    if (initialized) return
    // Autolinking can put this module in the regular Paseo build too; never install Linux there.
    if (!appContext.packageName.startsWith(PhoneConstants.APPLICATION_ID)) return
    initialized = true
    context = appContext.applicationContext
    paths = LinuxPaths(context)
    store = RuntimeStore(paths.stateFile)
    runner = SetupRunner(context, paths)
    supervisor = ProcessSupervisor(context, paths, runner, scope) { reconcile() }
    RuntimeService.listener = this
    scope.launch { initialize() }
  }

  private suspend fun initialize() {
    data = io { store.load() }
    val disk = io { paths.rootfsState() }
    refreshEnv()
    when (disk) {
      RootfsState.ABSENT, RootfsState.EXTRACTED -> {
        val failed = data.failedStep
        if (failed != null) {
          phase = Phase.SetupFailed(SetupMode.INSTALL, failed, "unknown", "Setup stopped at \"$failed\".", false)
          publish()
        } else {
          newCommand { runSetup(SetupMode.INSTALL, resumeStep(disk, null)) }
        }
      }
      RootfsState.INSTALLED -> {
        phase = Phase.Ready
        if (data.desired == "running") {
          linux = Svc.STARTING // never show `stopped` during the automatic start (architecture §7.1)
          publish()
          newCommand { startOrUpdate() }
        } else {
          publish()
        }
      }
    }
  }

  fun state(): Map<String, Any?> = snapshot

  fun addListener(listener: (Map<String, Any?>) -> Unit) {
    listeners += listener
  }

  fun removeListener(listener: (Map<String, Any?>) -> Unit) {
    listeners -= listener
  }

  // ---- commands --------------------------------------------------------------------------------
  // Lifecycle commands resolve once accepted; the outcome arrives as state. One at a time: an
  // overlapping one is rejected with `busy`, except stop(), which cancels it (architecture §7.2).

  suspend fun start() =
    command {
      if (phase == Phase.Ready && linux.active) return@command
      startOrSetup()
    }

  suspend fun stop() {
    withContext(confined) {
      val previous = commandJob
      newCommand {
        previous?.cancelAndJoin()
        stopFlow()
      }
    }
  }

  suspend fun restartLinux() =
    command {
      if (phase != Phase.Ready) return@command startOrSetup()
      supervisor.stopAll()
      startLinux(null)
    }

  suspend fun restartDaemon() =
    command {
      if (phase != Phase.Ready || !linux.active) return@command startOrSetup()
      supervisor.restart(supervisor.daemon)
    }

  suspend fun retrySetup() =
    command {
      val failed = phase as? Phase.SetupFailed ?: return@command
      when {
        failed.step == "start" -> startLinux(failed.mode)
        failed.mode == SetupMode.INSTALL -> runSetup(failed.mode, resumeStep(io { paths.rootfsState() }, failed.step))
        else -> runSetup(failed.mode, failed.step)
      }
    }

  suspend fun startPrevious() {
    val failed = withContext(confined) { phase as? Phase.SetupFailed }
    if (failed == null || !failed.canStartPrevious) throw RuntimeError("not-installed", "No previous installation to start")
    command {
      phase = Phase.Ready
      startLinux(null)
    }
  }

  /** Stop (service kept) → keep identity → delete rootfs → install (architecture §2.3 D15, D16). */
  suspend fun reinstall() =
    command {
      supervisor.autoRestart = false
      supervisor.stopAll()
      stopWatching()
      phase = Phase.Setup(SetupMode.INSTALL, "download", null)
      publish()
      ensureService()
      io {
        if (paths.rootfs.exists()) {
          Identity.save(paths)
          LinuxPaths.deleteTree(paths.rootfs) // never follows links (D15)
        }
      }
      data = data.copy(failedStep = null, desired = "running")
      save()
      linux = Svc.STOPPED
      runSetup(SetupMode.INSTALL, null)
    }

  suspend fun setSshEnabled(enabled: Boolean) =
    command(await = true) {
      data = data.copy(sshEnabled = enabled)
      save()
      if (phase == Phase.Ready && linux.active) {
        if (enabled) supervisor.start(supervisor.sshd) else supervisor.stop(supervisor.sshd)
      }
      publish()
    }

  /** Not a lifecycle command: never `busy`. */
  suspend fun setKeepAwake(enabled: Boolean) =
    withContext(confined) {
      data = data.copy(keepAwake = enabled)
      save()
      publish()
    }

  fun refreshEnvironment() {
    if (!initialized) return
    scope.launch {
      refreshEnv()
      publish()
    }
  }

  fun openBatterySettings() = system { DeviceChecks.openBatterySettings(context) }

  fun requestStoragePermission() = system { ExchangeFolder.requestPermission(context) }

  fun openExchangeFolder() = system { ExchangeFolder.open(context) }

  suspend fun listAuthorizedKeys(): List<Map<String, Any?>> = keys { it.list() }.map(::keyMap)

  suspend fun addAuthorizedKey(text: String): Map<String, Any?> = keyMap(keys { it.add(text) })

  /** Restarts a running sshd so sessions opened with the removed key end (architecture §5.1). */
  suspend fun removeAuthorizedKey(fingerprint: String) {
    keys { it.remove(fingerprint) }
    withContext(confined) {
      if (phase == Phase.Ready && linux.active && supervisor.sshd.process != null) supervisor.restart(supervisor.sshd)
    }
  }

  suspend fun getLogs(kind: String, maxBytes: Int?): Map<String, Any?> {
    if (kind !in setOf("daemon", "ssh", "setup")) throw RuntimeError("not-found", "Unknown log kind: $kind")
    val file = paths.logFile(kind)
    val contents =
      try {
        io { tail(file, (maxBytes ?: 64 * 1024).toLong()) }
      } catch (e: IOException) {
        throw RuntimeError("io-error", e.message ?: "Cannot read ${file.path}")
      }
    return mapOf("path" to file.absolutePath, "contents" to contents)
  }

  // ---- RuntimeService.Listener ----------------------------------------------------------------

  override fun onNotificationAction(action: String) {
    if (action == RuntimeNotification.ACTION_RESTART_DAEMON) {
      scope.launch { runCatching { restartDaemon() }.onFailure { Log.w(TAG, "restart from notification", it) } }
    }
  }

  /** The current service died without our shutdown: the processes cannot outlive it. */
  override fun onServiceDestroyed(service: RuntimeService) {
    scope.launch {
      commandJob?.cancel()
      supervisor.killAll()
      service.setLocks(wake = false, wifi = false)
      stopWatching()
      val message = "The foreground service was stopped"
      when (val p = phase) {
        is Phase.Setup -> phase = Phase.SetupFailed(p.mode, p.step, "unknown", message, canStartPrevious(p.mode, p.step))
        Phase.Ready -> if (linux.active) linux = Svc.error("crashed", message)
        else -> Unit
      }
      publish()
    }
  }

  // ---- setup -----------------------------------------------------------------------------------

  private suspend fun startOrSetup() {
    when (val disk = io { paths.rootfsState() }) {
      RootfsState.INSTALLED -> startOrUpdate()
      else -> runSetup(SetupMode.INSTALL, resumeStep(disk, data.failedStep))
    }
  }

  /** Architecture §2.5 update check: a Node pin change wins over a daemon-only change. */
  private suspend fun startOrUpdate() {
    val mode =
      io {
        when {
          runner.installedNodeVersion()?.removePrefix("v") != BuildConfig.NODE_VERSION -> SetupMode.UPDATE_NODE
          runner.installedDaemonVersion() != BuildConfig.PASEO_DAEMON_VERSION -> SetupMode.UPDATE_DAEMON
          else -> null
        }
      }
    if (mode != null) runSetup(mode, null) else startLinux(null)
  }

  /** Where an install resumes: never `extract` over an existing rootfs, never `configure` without one. */
  private fun resumeStep(disk: RootfsState, failed: String?): String? =
    when (disk) {
      RootfsState.ABSENT -> failed?.takeIf { it == "download" || it == "extract" }
      else -> failed?.takeIf { it == "configure" || it == "packages" || it == "paseo" } ?: "configure"
    }

  private suspend fun runSetup(mode: SetupMode, fromStep: String?) {
    val first = fromStep ?: if (mode == SetupMode.UPDATE_DAEMON) "paseo" else "download"
    phase = Phase.Setup(mode, first, null)
    publish()
    // The service keeps setup alive when the user leaves the app.
    if (ensureService() == null) return failSetup(mode, first, "unknown", "Could not start the foreground service")
    val result =
      try {
        io { runner.run(mode, fromStep) { event -> scope.launch { onSetupEvent(event) } } }
      } catch (e: CancellationException) {
        throw e // stop() turns this into setup-failed(cancelled)
      } catch (e: Exception) {
        SetupResult.Failed(first, "unknown", e.message ?: e.toString())
      }
    when (result) {
      is SetupResult.Failed -> {
        data = data.copy(failedStep = result.step)
        save()
        failSetup(mode, result.step, result.code, result.message, result.freeBytes, result.neededBytes)
      }
      SetupResult.Success -> {
        data = data.copy(failedStep = null)
        save()
        startLinux(mode)
      }
    }
  }

  private fun onSetupEvent(event: SetupEvent) {
    val current = phase as? Phase.Setup ?: return
    when (event) {
      is SetupEvent.Step -> {
        phase = current.copy(step = event.step, progress = null)
        publish()
      }
      is SetupEvent.Progress -> {
        phase = current.copy(progress = event)
        val now = SystemClock.elapsedRealtime()
        // At most 4 progress events per second (architecture §7.2).
        if (now - lastProgressAt >= 250 || event.done >= event.total) {
          lastProgressAt = now
          publish()
        }
      }
    }
  }

  /** Architecture §2.5: npm leaves the old daemon in place; Node is only safe before it is unpacked. */
  private fun canStartPrevious(mode: SetupMode, step: String) =
    when (mode) {
      SetupMode.UPDATE_DAEMON -> true
      SetupMode.UPDATE_NODE -> step == "download"
      SetupMode.INSTALL -> false
    }

  /** Setup failure takes the service down with the dismissible "setup failed" notification left behind. */
  private fun failSetup(
    mode: SetupMode,
    step: String,
    code: String,
    message: String,
    freeBytes: Long? = null,
    neededBytes: Long? = null,
  ) {
    supervisor.autoRestart = false
    phase = Phase.SetupFailed(mode, step, code, message, canStartPrevious(mode, step), freeBytes, neededBytes)
    publish()
    RuntimeService.instance?.shutdown(keepNotification = true)
  }

  // ---- Linux start / stop ----------------------------------------------------------------------

  /**
   * Launches daemon and sshd and returns; readiness is watched outside `busy` and lands in [reconcile].
   * [setupMode] non-null means this is setup's `start` step.
   */
  private suspend fun startLinux(setupMode: SetupMode?) {
    if (setupMode != null) {
      phase = Phase.Setup(setupMode, "start", null)
    } else {
      phase = Phase.Ready
      linux = Svc.STARTING
    }
    data = data.copy(desired = "running")
    save()
    publish()

    if (ensureService() == null) return startFailed(setupMode, "Could not start the foreground service")
    try {
      io { runner.installScripts() }
      // Managed instruction blocks for the three agents (architecture §5.3); output goes to setup.log. Not fatal.
      val result = runner.runScript("agent-instructions.sh", paths.logFile("setup"), 30_000)
      if (result.exitCode != 0) Log.w(TAG, "agent-instructions.sh failed: $result")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return startFailed(setupMode, e.message ?: e.toString())
    }
    val exchangeDir = io { ExchangeFolder.status(context).dir }
    supervisor.exchangeDir = exchangeDir
    exchangeBound = exchangeDir != null
    supervisor.autoRestart = setupMode == null
    supervisor.start(supervisor.daemon)
    if (!isStarting(setupMode)) return // the daemon could not even launch; reconcile has reported it
    if (data.sshEnabled) supervisor.start(supervisor.sshd)
    stopWatching()
    unwatchNetwork = NetworkAddresses.watch(context) { refreshEnvironment() }
    supervisor.startHealth {
      refreshEnv()
      publish()
    }
  }

  private fun isStarting(setupMode: SetupMode?) =
    if (setupMode != null) (phase as? Phase.Setup)?.step == "start" else phase == Phase.Ready && linux.active

  private fun startFailed(setupMode: SetupMode?, message: String) {
    if (setupMode != null) return failSetup(setupMode, "start", "command-failed", message)
    linux = Svc.error("start-failed", message)
    publish()
  }

  /** User stop (architecture §8.2): also ends a cancelled install, start or backoff. */
  private suspend fun stopFlow() {
    supervisor.autoRestart = false
    supervisor.stopAll()
    stopWatching()
    val installed = io { paths.rootfsState() } == RootfsState.INSTALLED
    when (val p = phase) {
      is Phase.Setup -> {
        if (p.step != "start") data = data.copy(failedStep = p.step)
        phase = Phase.SetupFailed(p.mode, p.step, "cancelled", "Stopped", canStartPrevious(p.mode, p.step))
      }
      Phase.Ready -> linux = Svc.STOPPED
      else -> Unit
    }
    // Not installed: keep `desired`, so the next launch still resumes the install.
    if (installed) data = data.copy(desired = "stopped")
    save()
    RuntimeService.instance?.shutdown(keepNotification = false)
    refreshEnv()
    publish()
  }

  private fun stopWatching() {
    supervisor.stopHealth()
    unwatchNetwork?.invoke()
    unwatchNetwork = null
  }

  /** Turns supervisor state changes into Linux / setup transitions. Runs on every supervisor change. */
  private fun reconcile() {
    val daemon = supervisor.daemon.state
    val p = phase
    if (p is Phase.Setup && p.step == "start") {
      if (daemon == Svc.RUNNING) {
        phase = Phase.Ready
        linux = Svc.RUNNING
        supervisor.autoRestart = true
      } else if (daemon.state == "error") {
        val fallback = daemon.message ?: daemon.code ?: "start failed"
        phase = Phase.SetupFailed(p.mode, "start", "command-failed", fallback, canStartPrevious(p.mode, "start"))
        // A crash is better explained by the daemon's last log line; "port in use" already says it all.
        cleanup(keepNotification = true) {
          if (daemon.code == "start-failed") null else io { lastLine(paths.logFile("daemon")) }
        }
      }
    } else if (p == Phase.Ready && linux.active) {
      when {
        daemon == Svc.RUNNING -> linux = Svc.RUNNING
        daemon.code == "not-responding" -> linux = Svc.RUNNING // alive but silent: the daemon row says so
        daemon.code == "restart-limit" || daemon.code == "start-failed" -> {
          // Without the daemon nothing works; report at the Linux level and free the device.
          linux = daemon
          cleanup(keepNotification = false) { null }
        }
      }
    }
    publish()
  }

  /** Stops both processes and the service after a fatal transition, after any running command ends. */
  private fun cleanup(keepNotification: Boolean, betterMessage: suspend () -> String?) {
    val previous = commandJob
    newCommand {
      previous?.join()
      supervisor.autoRestart = false
      supervisor.stopAll()
      stopWatching()
      val failed = phase as? Phase.SetupFailed
      betterMessage()?.let { message -> if (failed != null) phase = failed.copy(message = message) }
      RuntimeService.instance?.shutdown(keepNotification)
      publish()
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private suspend fun command(await: Boolean = false, block: suspend () -> Unit) {
    val job =
      withContext(confined) {
        if (commandJob?.isActive == true) throw RuntimeError("busy", "Another runtime command is in progress")
        newCommand(block)
      }
    if (await) job.join()
  }

  /** Must run on [confined]. Registers [block] as the current command so stop() can cancel it. */
  private fun newCommand(block: suspend () -> Unit): Job {
    lateinit var job: Job
    job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          block()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "command failed", e)
        } finally {
          if (commandJob === job) commandJob = null
        }
      }
    commandJob = job
    job.start()
    return job
  }

  private fun system(block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      throw RuntimeError("io-error", e.message ?: e.toString())
    }
  }

  private suspend fun <T> keys(block: (AuthorizedKeys) -> T): T {
    if (!paths.rootfs.isDirectory) throw RuntimeError("not-installed", "Linux is not installed")
    return try {
      io { block(AuthorizedKeys(paths.rootfs)) }
    } catch (e: AuthorizedKeys.KeyException) {
      throw RuntimeError(e.code, e.message ?: e.code)
    } catch (e: IOException) {
      throw RuntimeError("io-error", e.message ?: e.toString())
    } catch (e: android.system.ErrnoException) {
      throw RuntimeError("io-error", e.message ?: e.toString())
    }
  }

  private fun keyMap(key: AuthorizedKeys.Key): Map<String, Any?> =
    mapOf("type" to key.type, "comment" to key.comment, "fingerprint" to key.fingerprint)

  private suspend fun ensureService(): RuntimeService? =
    try {
      RuntimeService.ensure(context, buildNotification(currentView() ?: RuntimeNotification.View.Starting))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // ForegroundServiceStartNotAllowedException when the app is not in the foreground.
      Log.e(TAG, "cannot start foreground service", e)
      null
    }

  private fun save() = store.save(data)

  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  private fun tail(file: File, maxBytes: Long): String {
    if (!file.isFile) return ""
    RandomAccessFile(file, "r").use { raf ->
      val start = maxOf(0L, raf.length() - maxBytes)
      val bytes = ByteArray((raf.length() - start).toInt())
      raf.seek(start)
      raf.readFully(bytes)
      val text = String(bytes, Charsets.UTF_8)
      return if (start > 0) text.substringAfter('\n') else text
    }
  }

  private fun lastLine(file: File): String? = tail(file, 4_096).lines().lastOrNull { it.isNotBlank() }?.trim()

  private suspend fun refreshEnv() {
    env =
      io {
        Env(
          serverId = DaemonProbe.readServerId(paths.rootfs),
          daemonVersion = runner.installedDaemonVersion() ?: BuildConfig.PASEO_DAEMON_VERSION,
          hostKeys = HostKeys.fingerprints(paths.rootfs),
          addresses = NetworkAddresses.read(),
          exchange = ExchangeFolder.status(context),
          device = DeviceChecks.read(context),
        )
      }
  }

  // ---- snapshot + notification -----------------------------------------------------------------

  private fun publish() {
    val next = StateSnapshot.build(snapshotInput())
    if (next != snapshot) {
      snapshot = next
      listeners.forEach { listener -> runCatching { listener(next) } }
    }
    val service = RuntimeService.instance
    if (service == null) {
      lastView = null
      return
    }
    // Setup always holds the wake lock; otherwise keepAwake decides (architecture §8.1).
    service.setLocks(wake = phase is Phase.Setup || data.keepAwake, wifi = data.keepAwake)
    val view = currentView()
    if (view != null && view != lastView) {
      lastView = view
      service.show(buildNotification(view))
    }
    // publish() runs on app foreground, device-check refresh and the 30 s tick, so a late permission grant shows up.
    service.repostIfAllowed()
  }

  private fun snapshotInput() =
    SnapshotInput(
      phase = phase,
      linux = linux,
      daemon = supervisor.daemon.state,
      ssh = supervisor.sshd.state,
      store = data,
      env = env,
      exchangeBound = exchangeBound,
      targetVersion = BuildConfig.PASEO_DAEMON_VERSION,
      distro = RootfsSpec.DISTRO_LABEL,
    )

  private fun buildNotification(view: RuntimeNotification.View) =
    RuntimeNotification.build(context, view) { action -> RuntimeService.actionIntent(context, action) }

  private fun currentView() = StateSnapshot.notificationView(snapshotInput())
}
