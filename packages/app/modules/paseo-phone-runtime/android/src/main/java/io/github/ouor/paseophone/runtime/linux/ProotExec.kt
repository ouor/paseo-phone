// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/ProotLauncher.kt (execute), Apache-2.0.
// Modifications: streams to a log file and a line callback instead of buffering, suspend + timeout.
package io.github.ouor.paseophone.runtime.linux

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** [exitCode] is -1 when [timedOut]. [lastErrorLine] is the last non-blank stderr line. */
data class ExecResult(val exitCode: Int, val timedOut: Boolean, val lastErrorLine: String?)

object ProotExec {
  /**
   * Runs [spec] to completion. stdout and stderr go to [logFile] and [onLine] (pump threads).
   * On timeout or coroutine cancellation proot is SIGKILLed (`--kill-on-exit` ends the guest).
   */
  suspend fun run(
    context: Context,
    paths: LinuxPaths,
    spec: ProotSpec,
    logFile: File,
    timeoutMs: Long,
    onLine: (String) -> Unit = {},
  ): ExecResult = withContext(Dispatchers.IO) {
    val lastErr = AtomicReference<String?>(null)
    val proc = ProotProcess.launch(
      context, paths, spec, logFile,
      onStdout = onLine,
      onStderr = { line ->
        if (line.isNotBlank()) lastErr.set(line.trim())
        onLine(line)
      },
    )
    val exited = CompletableDeferred<ExitInfo>()
    proc.onExit { exited.complete(it) }
    try {
      val info = withTimeoutOrNull(timeoutMs) { exited.await() }
      if (info == null) {
        proc.destroyForcibly()
        withContext(NonCancellable) { exited.await() }
        ExecResult(-1, true, lastErr.get() ?: "Timed out after ${timeoutMs / 1000}s")
      } else {
        ExecResult(info.exitCode, false, lastErr.get())
      }
    } catch (e: Throwable) {
      proc.destroyForcibly()
      throw e
    }
  }
}
