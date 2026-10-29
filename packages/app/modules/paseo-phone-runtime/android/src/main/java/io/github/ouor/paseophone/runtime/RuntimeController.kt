package io.github.ouor.paseophone.runtime

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.ouor.paseophone.runtime.exchange.ExchangeFolder
import io.github.ouor.paseophone.runtime.linux.ExitInfo
import io.github.ouor.paseophone.runtime.linux.LinuxPaths
import io.github.ouor.paseophone.runtime.linux.ProotExec
import io.github.ouor.paseophone.runtime.linux.ProotProcess
import io.github.ouor.paseophone.runtime.linux.ProotSpec
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Error surfaced to JS as `PhoneRuntimeError`; [code] is a `PhoneRuntimeErrorCode` from src/types.ts. */
class RuntimeError(val code: String, message: String) : Exception(message)

/**
 * The single runtime state machine and the only writer of state (architecture §7, §8).
 * All state lives on one confined dispatcher, so no locks are needed; blocking work hops to IO.
 * Snapshots are plain maps shaped exactly like `PhoneRuntimeState` in src/types.ts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RuntimeController : RuntimeService.Listener {
  private const val TAG = "PaseoPhoneRuntime"
  private const val SSH_PORT = 2222
  private const val LISTEN = "127.0.0.1:6767"
  private const val START_TIMEOUT_MS = 120_000L
  private const val HEALTH_INTERVAL_MS = 30_000L
  private const val RESTART_WINDOW_MS = 5 * 60_000L
  private const val RESTART_LIMIT = 5
  private const val SCRIPT_DIR = "/opt/paseo-phone"
  private const val PHONE_PACKAGE = "io.github.ouor.paseophone"

  private val confined = Dispatchers.Default.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + confined)

  private data class Svc(val state: String, val code: String? = null, val message: String? = null) {
    val active get() = state == "starting" || state == "running"

    fun toMap(): Map<String, Any?> =
      if (code == null) mapOf("state" to state) else mapOf("state" to state, "code" to code, "message" to (message ?: ""))
  }

  private val STOPPED = Svc("stopped")
  private val STARTING = Svc("starting")
  private val RUNNING = Svc("running")
  private val STOPPING = Svc("stopping")

  private sealed interface Phase {
    object Checking : Phase

    data class Setup(val mode: SetupMode, val step: String, val progress: SetupEvent.Progress?) : Phase

    data class SetupFailed(
      val mode: SetupMode,
      val step: String,
      val code: String,
      val message: String,
      val canStartPrevious: Boolean,
    ) : Phase

    object Ready : Phase
  }

  /** One long-running proot process (daemon or sshd). */
  private class Managed(val key: String, val script: String, val logKind: String) {
    var process: ProotProcess? = null
    var state = Svc("stopped")
    var wanted = false // we want it running; false means an exit is ours
    val failures = ArrayDeque<Long>()
    var restartJob: Job? = null
    var watchJob: Job? = null
    var misses = 0
  }

  private data class Env(
    val serverId: String? = null,
    val daemonVersion: String = BuildConfig.PASEO_DAEMON_VERSION,
    val hostKeys: List<String> = emptyList(),
    val addresses: List<NetworkAddresses.Address> = emptyList(),
    val exchange: ExchangeFolder.Status? = null,
    val device: DeviceChecks.Result? = null,
  )

  private lateinit var context: Context
  private lateinit var paths: LinuxPaths
  private lateinit var store: RuntimeStore
  private lateinit var runner: SetupRunner
  private var initialized = false

  private var data = RuntimeStore.Data()
  private var phase: Phase = Phase.Checking
  private var linux = STOPPED
  private val daemon = Managed("daemon", "run-daemon.sh", "daemon")
  private val sshd = Managed("sshd", "run-sshd.sh", "sshd")
  private var env = Env()
  private var exchangeDir: File? = null // what the running proot processes were started with
  private var busy = false
  private var healthJob: Job? = null
  private var unwatchNetwork: (() -> Unit)? = null
  private var lastProgressAt = 0L
  private var lastView: RuntimeNotification.View? = null

  private val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()

  @Volatile private var snapshot: Map<String, Any?> = mapOf("status" to "checking")

  // ---- lifecycle -------------------------------------------------------------------------------

  /** Idempotent. Restores from state.json and resumes `desired: "running"` (architecture §8.2). */
  @Synchronized
  fun init(appContext: Context) {
    if (initialized) return
    // Autolinking can put this module in the regular Paseo build too; never install Linux there.
    if (!appContext.packageName.startsWith(PHONE_PACKAGE)) return
    initialized = true
    context = appContext.applicationContext
    paths = LinuxPaths(context)
    store = RuntimeStore(paths.stateFile)
    runner = SetupRunner(context, paths)
    RuntimeService.listener = this
    scope.launch {
      data = io { store.load() }
      if (isInstalled()) phase = Phase.Ready
      refreshEnv()
      publish()
      // A fresh install has desired = "running", so first launch starts setup without a tap.
      if (data.desired == "running") runCatching { start() }.onFailure { Log.w(TAG, "auto start", it) }
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
  // Lifecycle commands resolve once accepted; the outcome arrives as state. A command that overlaps
  // another is rejected with `busy` (no queueing, architecture §7.2).

  suspend fun start() =
    accept {
      if (phase == Phase.Ready && linux.active) return@accept
      startOrSetup()
    }

  suspend fun stop() = accept { stopLinux(userInitiated = true) }

  suspend fun restartDaemon() =
    accept {
      if (phase != Phase.Ready || !linux.active) return@accept startOrSetup()
      stopManaged(daemon, 10_000)
      daemon.failures.clear()
      daemon.wanted = true
      launch(daemon)
    }

  suspend fun retrySetup() =
    accept {
      val failed = phase as? Phase.SetupFailed ?: return@accept
      if (failed.step == "start") startLinux(failed.mode) else runSetup(failed.mode, failed.step)
    }

  suspend fun startPrevious() {
    val failed = withContext(confined) { phase as? Phase.SetupFailed }
    if (failed == null || !failed.canStartPrevious) throw RuntimeError("not-installed", "No previous installation to start")
    accept {
      phase = Phase.Ready
      startLinux(null)
    }
  }

  suspend fun reinstall() =
    accept {
      stopLinux(userInitiated = false)
      // deleteRootfs never follows links, so a link to the exchange folder cannot take shared files with it.
      io { paths.deleteRootfs() }
      data = data.copy(installed = false, daemonVersion = null, nodeVersion = null, failedStep = null)
      save()
      phase = Phase.Checking
      runSetup(SetupMode.INSTALL, null)
    }

  suspend fun setSshEnabled(enabled: Boolean) =
    exclusive {
      data = data.copy(sshEnabled = enabled)
      save()
      if (phase == Phase.Ready && linux.active) {
        if (enabled) {
          sshd.failures.clear()
          sshd.wanted = true
          launch(sshd)
        } else {
          stopManaged(sshd, 5_000)
        }
      }
      publish()
    }

  suspend fun setKeepAwake(enabled: Boolean) =
    exclusive {
      data = data.copy(keepAwake = enabled)
      save()
      RuntimeService.instance?.setKeepAwake(enabled)
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

  suspend fun removeAuthorizedKey(fingerprint: String) = keys { it.remove(fingerprint) }

  suspend fun getLogs(kind: String, maxBytes: Int?): Map<String, Any?> {
    val file =
      when (kind) {
        "daemon" -> paths.logFile("daemon")
        "ssh" -> paths.logFile("sshd")
        "setup" -> paths.logFile("setup")
        else -> throw RuntimeError("not-found", "Unknown log kind: $kind")
      }
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

  override fun onServiceDestroyed() {
    scope.launch {
      // Only unexpected when Linux is still meant to run; the service already killed the processes.
      if (phase == Phase.Ready && linux.active) {
        for (m in listOf(daemon, sshd)) {
          m.wanted = false
          m.restartJob?.cancel()
          m.process = null
          m.state = STOPPED
        }
        stopWatching()
        linux = Svc("error", "crashed", "The foreground service was stopped")
        publish()
      }
    }
  }

  // ---- setup -----------------------------------------------------------------------------------

  private suspend fun startOrSetup() {
    if (!isInstalled()) return runSetup(SetupMode.INSTALL, data.failedStep)
    val mode = updateMode()
    if (mode != null) runSetup(mode, null) else startLinux(null)
  }

  /** Architecture §2.5 "업데이트 판정": a Node pin change wins over a daemon-only change. */
  private suspend fun updateMode(): SetupMode? =
    io {
      val node = runner.installedNodeVersion()?.removePrefix("v")
      when {
        node != BuildConfig.NODE_VERSION -> SetupMode.UPDATE_NODE
        runner.installedDaemonVersion() != BuildConfig.PASEO_DAEMON_VERSION -> SetupMode.UPDATE_DAEMON
        else -> null
      }
    }

  private suspend fun runSetup(mode: SetupMode, fromStep: String?) {
    val first = fromStep ?: if (mode == SetupMode.UPDATE_DAEMON) "paseo" else "download"
    phase = Phase.Setup(mode, first, null)
    publish()
    // The service keeps setup alive when the user leaves the app.
    if (ensureService() == null) return failSetup(mode, first, "unknown", "Could not start the foreground service")
    RuntimeService.instance?.setKeepAwake(data.keepAwake)
    val result =
      try {
        io { runner.run(mode, fromStep) { event -> scope.launch { onSetupEvent(event) } } }
      } catch (e: Exception) {
        SetupResult.Failed(first, "unknown", e.message ?: e.toString())
      }
    when (result) {
      is SetupResult.Failed -> {
        data = data.copy(failedStep = result.step)
        save()
        failSetup(mode, result.step, result.code, result.message)
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

  private fun failSetup(mode: SetupMode, step: String, code: String, message: String) {
    val canStartPrevious = mode != SetupMode.INSTALL && isInstalled()
    phase = Phase.SetupFailed(mode, step, code, message, canStartPrevious)
    publish()
    // Keep the dismissible "setup failed" notification after the service goes away.
    RuntimeService.instance?.shutdown(keepNotification = true)
  }

  // ---- Linux start / stop ----------------------------------------------------------------------

  /** Starts daemon and sshd. [setupMode] non-null means this is setup's `start` step. */
  private suspend fun startLinux(setupMode: SetupMode?) {
    if (setupMode != null) {
      phase = Phase.Setup(setupMode, "start", null)
    } else {
      phase = Phase.Ready
      linux = STARTING
    }
    data = data.copy(desired = "running")
    save()
    publish()

    val service = ensureService()
    if (service == null) {
      val message = "Could not start the foreground service"
      if (setupMode != null) return failSetup(setupMode, "start", "unknown", message)
      linux = Svc("error", "start-failed", message)
      return publish()
    }
    service.setKeepAwake(data.keepAwake)

    io {
      runner.installScripts()
      // Managed instruction blocks for the three agents (architecture §5.3). Not fatal.
      val result =
        runCatching {
          ProotExec.run(context, paths, spec("agent-instructions.sh"), paths.logFile("daemon"), 30_000)
        }
      if (result.getOrNull()?.exitCode != 0) Log.w(TAG, "agent-instructions.sh failed: $result")
    }
    exchangeDir = io { ExchangeFolder.status(context).dir }

    for (m in listOf(daemon, sshd)) {
      m.failures.clear()
      m.misses = 0
    }
    daemon.wanted = true
    launch(daemon)
    if (data.sshEnabled) {
      sshd.wanted = true
      launch(sshd)
    }
    stopWatching()
    unwatchNetwork = NetworkAddresses.watch(context) { refreshEnvironment() }

    val ready = awaitDaemonReady()
    if (setupMode != null) {
      if (!ready) {
        val reason = io { lastLine(paths.logFile("daemon")) } ?: context.getString(R.string.linux_error_not_responding)
        stopAll()
        return failSetup(setupMode, "start", "command-failed", reason)
      }
      data =
        data.copy(
          installed = true,
          daemonVersion = io { runner.installedDaemonVersion() },
          nodeVersion = io { runner.installedNodeVersion() },
          failedStep = null,
        )
      save()
      phase = Phase.Ready
    }
    if (linux.state == "starting" || setupMode != null) linux = RUNNING
    healthJob = scope.launch { healthLoop() }
    refreshEnv()
    publish()
  }

  private suspend fun stopLinux(userInitiated: Boolean) {
    if (userInitiated) {
      data = data.copy(desired = "stopped")
      save()
    }
    if (phase != Phase.Ready) return
    if (linux.active) {
      linux = STOPPING
      publish()
    }
    stopAll()
    linux = STOPPED
    RuntimeService.instance?.shutdown(keepNotification = false)
    refreshEnv()
    publish()
  }

  /** Daemon first, then sshd (architecture §8.2). */
  private suspend fun stopAll() {
    stopWatching()
    stopManaged(daemon, 10_000)
    stopManaged(sshd, 5_000)
  }

  private fun stopWatching() {
    healthJob?.cancel()
    healthJob = null
    unwatchNetwork?.invoke()
    unwatchNetwork = null
  }

  private suspend fun awaitDaemonReady(): Boolean {
    while (true) {
      if (daemon.state == RUNNING) return true
      if (!daemon.wanted || daemon.process == null || daemon.state.state == "error") return false
      delay(250)
    }
  }

  // ---- process supervision ---------------------------------------------------------------------

  private suspend fun launch(m: Managed) {
    val service = RuntimeService.instance
    if (service == null) {
      m.state = Svc("error", "start-failed", "Foreground service is not running")
      return publish()
    }
    val logFile = paths.logFile(m.logKind)
    val process =
      try {
        io { ProotProcess.start(context, paths, spec(m.script), logFile) }
      } catch (e: Exception) {
        m.state = Svc("error", "start-failed", e.message ?: e.toString())
        return publish()
      }
    if (!m.wanted || RuntimeService.instance !== service) {
      // Stopped while we were starting it.
      process.destroyForcibly()
      return
    }
    m.process = process
    m.state = STARTING
    m.misses = 0
    service.track(m.key, process)
    process.onExit { info -> scope.launch { onExit(m, process, info) } }
    m.watchJob?.cancel()
    m.watchJob = scope.launch { if (m === daemon) watchDaemonReady(process) else watchSshdUp(process) }
    publish()
  }

  /** `starting` until the daemon answers HTTP, `error("not-responding")` after 120 s (architecture §3.4). */
  private suspend fun watchDaemonReady(process: ProotProcess) {
    val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
    while (daemon.process === process) {
      if (io { DaemonProbe.isResponding() }) {
        daemon.state = RUNNING
        refreshEnv()
        return publish()
      }
      if (SystemClock.elapsedRealtime() > deadline) {
        daemon.state = Svc("error", "not-responding", context.getString(R.string.linux_error_not_responding))
        return publish()
      }
      delay(1_000)
    }
  }

  // shortcut: sshd counts as running once its proot survives 2 s; probe port 2222 if QA sees false positives.
  private suspend fun watchSshdUp(process: ProotProcess) {
    delay(2_000)
    if (sshd.process === process && process.isAlive()) {
      sshd.state = RUNNING
      publish()
    }
  }

  /** Every 30 s: 3 misses in a row → not-responding; a later answer recovers it. Also refreshes env. */
  private suspend fun healthLoop() {
    while (true) {
      delay(HEALTH_INTERVAL_MS)
      val process = daemon.process
      if (process != null && process.isAlive() && (daemon.state == RUNNING || daemon.state.code == "not-responding")) {
        if (io { DaemonProbe.isResponding() }) {
          daemon.misses = 0
          daemon.state = RUNNING
        } else if (++daemon.misses >= 3) {
          daemon.state = Svc("error", "not-responding", context.getString(R.string.linux_error_not_responding))
        }
      }
      refreshEnv()
      publish()
    }
  }

  private suspend fun onExit(m: Managed, process: ProotProcess, info: ExitInfo) {
    if (m.process !== process) return
    m.process = null
    m.watchJob?.cancel()
    RuntimeService.instance?.untrack(m.key, process)
    if (!m.wanted) {
      m.state = STOPPED
      return publish()
    }
    val killed = info.signal == 9 || info.exitCode == 137
    val message =
      if (killed) context.getString(R.string.linux_error_killed_by_system)
      else context.getString(R.string.linux_error_crashed, info.exitCode)
    m.state = Svc("error", if (killed) "killed-by-system" else "crashed", message)
    Log.w(TAG, "${m.key} exited: $info")
    if (phase != Phase.Ready) return publish() // setup's start step reports the failure itself

    val now = SystemClock.elapsedRealtime()
    if (recordFailure(m.failures, now)) {
      m.wanted = false
      m.state = Svc("error", "restart-limit", context.getString(R.string.linux_error_restart_limit, message))
      if (m === daemon) {
        // Without the daemon Linux is useless: stop everything and report at the Linux level.
        stopAll()
        linux = m.state
        RuntimeService.instance?.shutdown(keepNotification = false)
      }
      return publish()
    }
    publish()
    val backoff = restartDelayMs(m.failures.size)
    m.restartJob?.cancel()
    m.restartJob =
      scope.launch {
        delay(backoff)
        if (m.wanted && m.process == null && linux.active) launch(m)
      }
  }

  /** Records a failure at [now] (elapsed ms); true when 5 failures fall within 5 minutes (architecture §8.3). */
  internal fun recordFailure(failures: ArrayDeque<Long>, now: Long): Boolean {
    failures.removeAll { now - it > RESTART_WINDOW_MS }
    failures.addLast(now)
    return failures.size >= RESTART_LIMIT
  }

  /** Delay before restarting after the [failures]-th recent failure: 1, 2, 4, 8, 16 s, capped at 60 s. */
  internal fun restartDelayMs(failures: Int): Long = minOf(1_000L shl (failures - 1).coerceIn(0, 6), 60_000L)

  private suspend fun stopManaged(m: Managed, graceMs: Long) {
    m.wanted = false
    m.restartJob?.cancel()
    m.watchJob?.cancel()
    val process = m.process
    if (process == null) {
      m.state = STOPPED
      return
    }
    m.state = STOPPING
    publish()
    io {
      val supervisor = if (m === daemon) DaemonProbe.readSupervisorPid(paths.rootfs) else null
      // The supervisor stops its workers cleanly on SIGTERM; proot shares Android's pid space.
      if (supervisor == null || runCatching { Os.kill(supervisor, OsConstants.SIGTERM) }.isFailure) process.destroy()
      if (!process.waitFor(graceMs)) {
        process.destroyForcibly()
        process.waitFor(5_000)
      }
    }
    if (m.process === process) m.process = null
    RuntimeService.instance?.untrack(m.key, process)
    m.state = STOPPED
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private suspend fun accept(block: suspend () -> Unit) =
    withContext(confined) {
      claim()
      scope.launch {
        try {
          block()
        } catch (e: Exception) {
          Log.e(TAG, "command failed", e)
        } finally {
          busy = false
        }
      }
      Unit
    }

  private suspend fun <T> exclusive(block: suspend () -> T): T =
    withContext(confined) {
      claim()
      try {
        block()
      } finally {
        busy = false
      }
    }

  private fun claim() {
    if (busy) throw RuntimeError("busy", "Another runtime command is in progress")
    busy = true
  }

  private fun system(block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      throw RuntimeError("io-error", e.message ?: e.toString())
    }
  }

  private suspend fun <T> keys(block: (AuthorizedKeys) -> T): T {
    if (!isInstalled()) throw RuntimeError("not-installed", "Linux is not installed")
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

  private suspend fun ensureService(): RuntimeService? {
    RuntimeService.instance?.let { return it }
    try {
      RuntimeService.start(context, buildNotification(currentView() ?: RuntimeNotification.View.Starting))
    } catch (e: Exception) {
      // ForegroundServiceStartNotAllowedException when the app is not in the foreground.
      Log.e(TAG, "cannot start foreground service", e)
      return null
    }
    return RuntimeService.awaitStarted(10_000)
  }

  private fun isInstalled(): Boolean = data.installed && paths.isRootfsPresent()

  private fun save() = store.save(data)

  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  private fun spec(script: String) =
    ProotSpec(
      command = listOf("/bin/bash", "$SCRIPT_DIR/$script"),
      env =
        mapOf(
          "PASEO_DAEMON_VERSION" to BuildConfig.PASEO_DAEMON_VERSION,
          "NODE_VERSION" to BuildConfig.NODE_VERSION,
          "PASEO_PHONE_SSH_PORT" to SSH_PORT.toString(),
        ),
      exchangeDir = exchangeDir,
    )

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
          daemonVersion =
            (if (paths.isRootfsPresent()) runner.installedDaemonVersion() else null)
              ?: data.daemonVersion
              ?: BuildConfig.PASEO_DAEMON_VERSION,
          hostKeys = HostKeys.fingerprints(paths.rootfs),
          addresses = NetworkAddresses.read(),
          exchange = ExchangeFolder.status(context),
          device = DeviceChecks.read(context),
        )
      }
  }

  // ---- snapshot + notification -----------------------------------------------------------------

  private fun publish() {
    val next = buildSnapshot()
    if (next != snapshot) {
      snapshot = next
      listeners.forEach { listener -> runCatching { listener(next) } }
    }
    val view = currentView()
    val service = RuntimeService.instance
    if (view != null && service != null && view != lastView) {
      lastView = view
      service.show(buildNotification(view))
    } else if (service == null) {
      lastView = null
    }
    // publish() runs on app foreground, device-check refresh and the 30 s tick, so a late permission grant shows up.
    service?.repostIfAllowed()
  }

  private fun buildNotification(view: RuntimeNotification.View) =
    RuntimeNotification.build(context, view) { action -> RuntimeService.actionIntent(context, action) }

  private fun currentView(): RuntimeNotification.View? =
    when (val p = phase) {
      Phase.Checking -> null
      is Phase.Setup ->
        RuntimeNotification.View.Setup(
          update = p.mode != SetupMode.INSTALL,
          step = p.step,
          bytesDone = p.progress?.takeIf { it.unit == "bytes" }?.done,
          bytesTotal = p.progress?.takeIf { it.unit == "bytes" }?.total,
        )
      is Phase.SetupFailed -> RuntimeNotification.View.SetupFailed
      Phase.Ready ->
        when {
          linux.state == "starting" -> RuntimeNotification.View.Starting
          linux.state == "stopping" -> RuntimeNotification.View.Stopping
          linux.state != "running" -> null
          daemon.state.code == "not-responding" -> RuntimeNotification.View.NotResponding(env.serverId)
          else ->
            RuntimeNotification.View.Running(
              serverId = env.serverId,
              sshEnabled = data.sshEnabled,
              sshPort = SSH_PORT,
              keepAwake = data.keepAwake,
              addresses = env.addresses.map { it.kind to it.address },
            )
        }
    }

  private fun buildSnapshot(): Map<String, Any?> =
    when (val p = phase) {
      Phase.Checking -> mapOf("status" to "checking")
      is Phase.Setup ->
        mapOf(
          "status" to "setup",
          "mode" to modeName(p.mode),
          "step" to p.step,
          "progress" to
            p.progress?.let { mapOf("done" to it.done.toDouble(), "total" to it.total.toDouble(), "unit" to it.unit) },
          "targetVersion" to BuildConfig.PASEO_DAEMON_VERSION,
        )
      is Phase.SetupFailed ->
        mapOf(
          "status" to "setup-failed",
          "mode" to modeName(p.mode),
          "step" to p.step,
          "code" to p.code,
          "message" to p.message,
          "canStartPrevious" to p.canStartPrevious,
        )
      Phase.Ready -> readySnapshot()
    }

  private fun readySnapshot(): Map<String, Any?> {
    val exchange = env.exchange
    val exchangeStatus =
      when {
        exchange == null -> mapOf("status" to "no-permission")
        exchange.status == "mounted" && linux.active && exchangeDir == null -> mapOf("status" to "restart-required")
        exchange.status == "error" -> mapOf("status" to "error", "message" to (exchange.message ?: ""))
        else -> mapOf("status" to exchange.status)
      }
    val device = env.device
    return mapOf(
      "status" to "ready",
      "linux" to linux.toMap(),
      "daemon" to
        daemon.state.toMap() + mapOf("serverId" to env.serverId, "version" to env.daemonVersion, "listen" to LISTEN),
      "ssh" to
        mapOf(
          "enabled" to data.sshEnabled,
          "port" to SSH_PORT,
          "state" to (if (data.sshEnabled) sshd.state else STOPPED).toMap(),
          "hostKeyFingerprints" to env.hostKeys,
        ),
      "keepAwake" to data.keepAwake,
      "distro" to "Debian 13",
      "addresses" to env.addresses.map { mapOf("kind" to it.kind, "address" to it.address) },
      "exchange" to
        exchangeStatus +
          mapOf(
            "phonePathLabel" to (exchange?.phonePathLabel ?: ""),
            "phonePath" to (exchange?.phonePath ?: ExchangeFolder.phoneDir().path),
            "linuxPath" to ExchangeFolder.LINUX_PATH,
          ),
      "device" to
        mapOf(
          "batteryOptimized" to (device?.batteryOptimized ?: true),
          "phantomProcessMonitor" to (device?.phantomProcessMonitor ?: "unknown"),
          "notificationsAllowed" to (device?.notificationsAllowed ?: false),
        ),
    )
  }

  private fun modeName(mode: SetupMode) = if (mode == SetupMode.INSTALL) "install" else "update"
}
