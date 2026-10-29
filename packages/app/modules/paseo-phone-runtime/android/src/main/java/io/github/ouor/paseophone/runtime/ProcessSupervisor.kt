package io.github.ouor.paseophone.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.ouor.paseophone.runtime.linux.ExitInfo
import io.github.ouor.paseophone.runtime.linux.LinuxPaths
import io.github.ouor.paseophone.runtime.linux.PhoneConstants
import io.github.ouor.paseophone.runtime.linux.ProotProcess
import io.github.ouor.paseophone.runtime.setup.SetupRunner
import io.github.ouor.paseophone.runtime.system.DaemonProbe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two long-running proot processes (daemon, sshd): start, stop, readiness and health watches,
 * crash backoff and the restart limit (architecture §3.4, §8.3). Runs on the controller's confined
 * [scope] and reports every state change through [onChange]; it never decides Linux-level state.
 */
internal class ProcessSupervisor(
  private val context: Context,
  private val paths: LinuxPaths,
  private val runner: SetupRunner,
  private val scope: CoroutineScope,
  private val onChange: () -> Unit,
) {
  class Managed(val key: String, val script: String, val logKind: String, val port: Int, val graceMs: Long) {
    var process: ProotProcess? = null
    var state = Svc.STOPPED
    var wanted = false // we want it running; an exit while false is ours
    val failures = ArrayDeque<Long>()
    var restartJob: Job? = null
    var watchJob: Job? = null
    var misses = 0
  }

  val daemon = Managed("daemon", "run-daemon.sh", "daemon", PhoneConstants.DAEMON_PORT, 10_000)
  val sshd = Managed("sshd", "run-sshd.sh", "ssh", PhoneConstants.SSH_PORT, 5_000)

  /** Bound to /root/exchange in processes started from now on. */
  var exchangeDir: File? = null

  /** Restart crashed processes (Ready). Off during setup's start step, which reports the failure instead. */
  var autoRestart = false

  private var healthJob: Job? = null

  /** Starts [m] with a clean failure history. */
  suspend fun start(m: Managed) {
    m.failures.clear()
    m.wanted = true
    launch(m)
  }

  suspend fun restart(m: Managed) {
    stop(m)
    start(m)
  }

  /** Daemon first, then sshd (architecture §8.2). */
  suspend fun stopAll() {
    stopHealth()
    stop(daemon)
    stop(sshd)
  }

  suspend fun stop(m: Managed) {
    m.wanted = false
    m.restartJob?.cancel()
    m.watchJob?.cancel()
    val process = m.process
    if (process == null) {
      setState(m, Svc.STOPPED)
      return
    }
    setState(m, Svc.STOPPING)
    // Stopping must finish even when the caller is cancelled (a stop() that cancels a start).
    withContext(NonCancellable + Dispatchers.IO) { process.stop(m.graceMs) }
    if (m.process === process) m.process = null
    setState(m, Svc.STOPPED)
  }

  /** Last resort when the service died under us: SIGKILL both trees without waiting. */
  fun killAll() {
    stopHealth()
    for (m in listOf(daemon, sshd)) {
      m.wanted = false
      m.restartJob?.cancel()
      m.watchJob?.cancel()
      m.process?.let { runCatching { it.killTree() } }
      m.process = null
      m.state = Svc.STOPPED
    }
    onChange()
  }

  /** Every 30 s: 3 misses in a row → not-responding; a later answer recovers it. Then [onTick]. */
  fun startHealth(onTick: suspend () -> Unit) {
    stopHealth()
    healthJob =
      scope.launch {
        while (true) {
          delay(HEALTH_INTERVAL_MS)
          val process = daemon.process
          if (process != null && process.isAlive() && (daemon.state == Svc.RUNNING || daemon.state.code == "not-responding")) {
            if (withContext(Dispatchers.IO) { DaemonProbe.isResponding() }) {
              daemon.misses = 0
              setState(daemon, Svc.RUNNING)
            } else if (++daemon.misses >= 3) {
              setState(daemon, notResponding())
            }
          }
          onTick()
        }
      }
  }

  fun stopHealth() {
    healthJob?.cancel()
    healthJob = null
  }

  private suspend fun launch(m: Managed) {
    // An orphan still holding the port would otherwise look like a healthy start (architecture §3.4).
    if (withContext(Dispatchers.IO) { DaemonProbe.isListening(m.port) }) {
      m.wanted = false
      return setState(m, Svc.error("start-failed", "port in use: ${PhoneConstants.DAEMON_HOST}:${m.port}"))
    }
    val spec = runner.scriptSpec(m.script, exchangeDir)
    val logFile = paths.logFile(m.logKind)
    // Start and register without cancellation, so a concurrent stop() always finds the process to stop.
    withContext(NonCancellable) {
      val process =
        try {
          withContext(Dispatchers.IO) { ProotProcess.start(context, paths, spec, logFile) }
        } catch (e: Exception) {
          return@withContext setState(m, Svc.error("start-failed", e.message ?: e.toString()))
        }
      if (!m.wanted) {
        withContext(Dispatchers.IO) { process.stop(m.graceMs) }
        return@withContext
      }
      m.process = process
      m.misses = 0
      process.onExit { info -> scope.launch { onExit(m, process, info) } }
      m.watchJob?.cancel()
      m.watchJob = scope.launch { if (m === daemon) watchDaemonReady(process) else watchSshdUp(process) }
      setState(m, Svc.STARTING)
    }
  }

  /** `starting` until the daemon answers HTTP, `error("not-responding")` after 120 s (architecture §3.4). */
  private suspend fun watchDaemonReady(process: ProotProcess) {
    val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
    while (daemon.process === process) {
      if (withContext(Dispatchers.IO) { DaemonProbe.isResponding() }) return setState(daemon, Svc.RUNNING)
      if (SystemClock.elapsedRealtime() > deadline) return setState(daemon, notResponding())
      delay(1_000)
    }
  }

  // shortcut: sshd counts as running once its proot survives 2 s; probe the port if QA sees false positives.
  private suspend fun watchSshdUp(process: ProotProcess) {
    delay(2_000)
    if (sshd.process === process && process.isAlive()) setState(sshd, Svc.RUNNING)
  }

  private fun onExit(m: Managed, process: ProotProcess, info: ExitInfo) {
    if (m.process !== process) return
    m.process = null
    m.watchJob?.cancel()
    if (!m.wanted) return setState(m, Svc.STOPPED)
    val killed = info.signal == 9 || info.exitCode == 137
    val message =
      if (killed) context.getString(R.string.linux_error_killed_by_system)
      else context.getString(R.string.linux_error_crashed, info.exitCode)
    Log.w(TAG, "${m.key} exited: $info")
    if (!autoRestart) return setState(m, Svc.error(if (killed) "killed-by-system" else "crashed", message))
    if (recordFailure(m.failures, SystemClock.elapsedRealtime())) {
      m.wanted = false
      return setState(m, Svc.error("restart-limit", context.getString(R.string.linux_error_restart_limit, message)))
    }
    setState(m, Svc.error(if (killed) "killed-by-system" else "crashed", message))
    val backoff = restartDelayMs(m.failures.size)
    m.restartJob?.cancel()
    m.restartJob =
      scope.launch {
        delay(backoff)
        if (m.wanted && m.process == null) launch(m)
      }
  }

  private fun notResponding() = Svc.error("not-responding", context.getString(R.string.linux_error_not_responding))

  private fun setState(m: Managed, state: Svc) {
    m.state = state
    onChange()
  }

  companion object {
    private const val TAG = "PaseoPhoneRuntime"
    private const val START_TIMEOUT_MS = 120_000L
    private const val HEALTH_INTERVAL_MS = 30_000L
    private const val RESTART_WINDOW_MS = 5 * 60_000L
    private const val RESTART_LIMIT = 5

    /** Records a failure at [now] (elapsed ms); true when 5 failures fall within 5 minutes (architecture §8.3). */
    fun recordFailure(failures: ArrayDeque<Long>, now: Long): Boolean {
      failures.removeAll { now - it > RESTART_WINDOW_MS }
      failures.addLast(now)
      return failures.size >= RESTART_LIMIT
    }

    /** Delay before restarting after the [failures]-th recent failure: 1, 2, 4, 8, 16 s, capped at 60 s. */
    fun restartDelayMs(failures: Int): Long = minOf(1_000L shl (failures - 1).coerceIn(0, 6), 60_000L)
  }
}
