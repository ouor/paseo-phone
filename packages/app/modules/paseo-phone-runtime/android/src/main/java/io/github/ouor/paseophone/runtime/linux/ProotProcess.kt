// Process handling adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/ProotLauncher.kt (ProotHandle), Apache-2.0.
// Modifications: long-running handle with pid, signals, exit classification and log streaming.
package io.github.ouor.paseophone.runtime.linux

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** [signal] is set when the process died from a signal (Java reports 128 + signal, e.g. 137 = SIGKILL). */
data class ExitInfo(val exitCode: Int, val signal: Int?) {
  companion object {
    fun fromExitCode(code: Int) = ExitInfo(code, if (code in 129..192) code - 128 else null)
  }
}

class ProotProcess private constructor(
  private val process: Process,
  private val pumps: List<Thread>,
) {
  companion object {
    fun start(context: Context, paths: LinuxPaths, spec: ProotSpec, logFile: File): ProotProcess =
      launch(context, paths, spec, logFile, onStdout = {}, onStderr = {})

    /** Shared with ProotExec. Callbacks run on the pump threads. */
    internal fun launch(
      context: Context,
      paths: LinuxPaths,
      spec: ProotSpec,
      logFile: File,
      onStdout: (String) -> Unit,
      onStderr: (String) -> Unit,
    ): ProotProcess {
      paths.ensureLayout()
      paths.copyLibtalloc()
      val nodename = ProotCommand.nodename(Build.MODEL ?: "")
      val fakeProc = FakeSysdata.write(paths.sysdata, nodename)
      if (spec.exchangeDir != null) File(paths.rootfs, "root/exchange").mkdirs() // bind target
      val inv = ProotCommand.build(paths.nativeLibDir, paths.root, nodename, fakeProc, spec)

      val log = RotatingLogFile(logFile)
      log.line("=== ${java.time.Instant.now()} start: ${spec.command.joinToString(" ")}")
      val pb = ProcessBuilder(inv.argv).directory(inv.workDir)
      pb.environment().clear()
      pb.environment().putAll(inv.env)
      val process = pb.start()
      process.outputStream.close() // guest stdin = EOF; scripts are non-interactive
      val pumps = listOf(
        pump(process.inputStream, log, onStdout, "proot-out"),
        pump(process.errorStream, log, onStderr, "proot-err"),
      )
      return ProotProcess(process, pumps)
    }

    private fun pump(stream: InputStream, log: RotatingLogFile, onLine: (String) -> Unit, name: String) =
      Thread({
        runCatching {
          stream.bufferedReader().forEachLine { line ->
            log.line(line)
            runCatching { onLine(line) }
          }
        }
      }, name).apply {
        isDaemon = true
        start()
      }
  }

  private val lock = Any()
  private var exitInfo: ExitInfo? = null
  private val listeners = ArrayList<(ExitInfo) -> Unit>()

  /** Host pid of proot. proot has no pid namespace, so guest pids are host pids too. */
  val pid: Int = readPid(process)

  init {
    Thread({
      val code = waitUninterruptibly()
      // proot's pipes close when the last guest process exits; give the log a moment to drain.
      pumps.forEach { runCatching { it.join(2_000) } }
      val info = ExitInfo.fromExitCode(code)
      val toCall = synchronized(lock) {
        exitInfo = info
        listeners.toList().also { listeners.clear() }
      }
      toCall.forEach { runCatching { it(info) } }
    }, "proot-wait").apply {
      isDaemon = true
      start()
    }
  }

  fun isAlive(): Boolean = process.isAlive

  /** SIGTERM to proot; `--kill-on-exit` takes the guest tree down with it. */
  fun destroy() {
    if (pid > 0) android.os.Process.sendSignal(pid, 15) else process.destroy()
  }

  /** SIGKILL. */
  fun destroyForcibly() {
    process.destroyForcibly()
  }

  fun waitFor(timeoutMs: Long): Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

  /** Called once, on a background thread. Registering after exit calls it right away (on a new thread). */
  fun onExit(listener: (ExitInfo) -> Unit) {
    val done = synchronized(lock) {
      exitInfo ?: run {
        listeners.add(listener)
        return
      }
    }
    Thread({ listener(done) }, "proot-exit").apply {
      isDaemon = true
      start()
    }
  }

  private fun waitUninterruptibly(): Int {
    while (true) {
      try {
        return process.waitFor()
      } catch (_: InterruptedException) {
      }
    }
  }

  private fun readPid(p: Process): Int =
    runCatching {
      // Android's ProcessImpl/UNIXProcess keeps it in a private "pid" field; Process.pid() is not available on older APIs.
      val f = p.javaClass.getDeclaredField("pid")
      f.isAccessible = true
      f.getInt(p)
    }.getOrDefault(-1)
}

/** Appends lines; at 5 MB moves the file to `<name>.1` (one kept) and starts over (architecture §2.3). */
internal class RotatingLogFile(private val file: File, private val maxBytes: Long = 5L * 1024 * 1024) {
  private var out: OutputStream? = null
  private var size = 0L

  @Synchronized
  fun line(text: String) {
    runCatching {
      val o = out ?: open()
      val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
      if (size + bytes.size > maxBytes && size > 0) {
        o.close()
        val old = File(file.path + ".1")
        old.delete()
        file.renameTo(old)
        size = 0
        open().write(bytes)
      } else {
        o.write(bytes)
      }
      size += bytes.size
      out?.flush()
    }
  }

  private fun open(): OutputStream {
    file.parentFile?.mkdirs()
    size = if (file.isFile) file.length() else 0
    return FileOutputStream(file, true).also { out = it }
  }
}
