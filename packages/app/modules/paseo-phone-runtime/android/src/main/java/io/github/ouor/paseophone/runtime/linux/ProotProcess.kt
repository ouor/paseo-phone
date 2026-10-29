// Process handling adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/ProotLauncher.kt (ProotHandle), Apache-2.0.
// Modifications: long-running handle with pid, process-tree stop (architecture §8.3), exit
// classification and log streaming.
package io.github.ouor.paseophone.runtime.linux

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val SIGTERM = 15
private const val SIGKILL = 9

/** [signal] is set when the process died from a signal (Java reports 128 + signal, e.g. 137 = SIGKILL). */
data class ExitInfo(val exitCode: Int, val signal: Int?) {
  companion object {
    fun fromExitCode(code: Int) = ExitInfo(code, if (code in 129..192) code - 128 else null)
  }
}

class ProotProcess private constructor(
  private val process: Process,
  private val pumps: List<Thread>,
  private val log: RotatingLogFile,
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
      if (spec.exchangeDir != null) File(paths.rootfs, PhoneConstants.GUEST_EXCHANGE.removePrefix("/")).mkdirs() // bind target
      val inv = ProotCommand.build(paths.nativeLibDir, paths.root, nodename, fakeProc, spec)

      val log = RotatingLogFile(logFile)
      log.line("=== ${java.time.Instant.now()} start: ${spec.command.joinToString(" ")}")
      val pb = ProcessBuilder(inv.argv).directory(inv.workDir)
      pb.environment().clear()
      pb.environment().putAll(inv.env)
      val process = try {
        pb.start()
      } catch (e: Exception) {
        log.line("=== failed to start proot: ${e.message}")
        log.close()
        throw e
      }
      process.outputStream.close() // guest stdin = EOF; scripts are non-interactive
      val pumps = listOf(
        pump(process.inputStream, log, onStdout, "proot-out"),
        pump(process.errorStream, log, onStderr, "proot-err"),
      )
      return ProotProcess(process, pumps, log)
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
  private val finished = CountDownLatch(1)

  /** Host pid of proot. proot has no pid namespace, so guest pids are host pids too. */
  val pid: Int = readPid(process)

  init {
    Thread({
      val code = waitUninterruptibly()
      // proot's pipes close when the last guest process exits; give the log a moment to drain.
      // An orphan still holding a pipe keeps its pump blocked, so the log is closed regardless.
      pumps.forEach { runCatching { it.join(2_000) } }
      log.close()
      val info = ExitInfo.fromExitCode(code)
      val toCall = synchronized(lock) {
        exitInfo = info
        listeners.toList().also { listeners.clear() }
      }
      finished.countDown()
      toCall.forEach { runCatching { it(info) } }
    }, "proot-wait").apply {
      isDaemon = true
      start()
    }
  }

  fun isAlive(): Boolean = process.isAlive

  /**
   * Stops the tree (architecture §8.3), blocking: SIGTERM to proot's descendants, wait [graceMs],
   * SIGTERM to proot, wait 2 s, then SIGKILL to every descendant seen and proot. Returns once
   * proot is gone and the log file is closed (or after a bounded wait if the kernel refuses).
   */
  fun stop(graceMs: Long) {
    if (process.isAlive) {
      val first = ProcTree.snapshot(pid)
      first.forEach { signal(it, SIGTERM) }
      if (!waitFor(graceMs)) {
        if (pid > 0) signal(pid, SIGTERM) else process.destroy()
        if (!waitFor(2_000)) {
          (first + ProcTree.snapshot(pid)).toSet().forEach { signal(it, SIGKILL) }
          process.destroyForcibly()
          waitFor(2_000)
        }
      }
    }
    finished.await(3, TimeUnit.SECONDS)
  }

  /** SIGKILL to the current descendants and proot, without waiting. Last resort (onDestroy). */
  fun killTree() {
    ProcTree.snapshot(pid).forEach { signal(it, SIGKILL) }
    process.destroyForcibly()
  }

  fun waitFor(timeoutMs: Long): Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

  /**
   * Called once, on a background thread, also when we stopped it ourselves. Registering after
   * exit calls it right away (on a new thread).
   */
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

  private fun signal(target: Int, sig: Int) {
    if (target > 0 && target != android.os.Process.myPid()) runCatching { android.os.Process.sendSignal(target, sig) }
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

/** Descendants of a pid from /proc, limited to processes of our own uid. */
internal object ProcTree {
  fun snapshot(rootPid: Int): List<Int> {
    if (rootPid <= 0) return emptyList()
    return descendantsOf(rootPid, scan(android.os.Process.myUid()))
  }

  /** Breadth-first: children before grandchildren, so a SIGTERM reaches the supervisor first. */
  fun descendantsOf(rootPid: Int, parentOf: Map<Int, Int>): List<Int> {
    val children = parentOf.entries.groupBy({ it.value }, { it.key })
    val out = ArrayList<Int>()
    val queue = ArrayDeque(listOf(rootPid))
    while (queue.isNotEmpty()) {
      for (child in children[queue.removeFirst()].orEmpty()) {
        if (child != rootPid && child !in out) {
          out.add(child)
          queue.add(child)
        }
      }
    }
    return out
  }

  /** `pid (comm) S ppid …`; comm may contain spaces and parentheses, so split after the last ')'. */
  fun ppidFromStat(stat: String): Int? {
    val end = stat.lastIndexOf(')')
    if (end < 0) return null
    return stat.substring(end + 1).trim().split(' ').getOrNull(1)?.toIntOrNull()
  }

  /** `Uid:\treal\teffective…` from /proc/<pid>/status. */
  fun uidFromStatus(status: String): Int? =
    status.lineSequence().firstOrNull { it.startsWith("Uid:") }
      ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

  private fun scan(uid: Int): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    File("/proc").list()?.forEach { name ->
      val pid = name.toIntOrNull() ?: return@forEach
      runCatching {
        if (uidFromStatus(File("/proc/$pid/status").readText()) != uid) return@forEach
        ppidFromStat(File("/proc/$pid/stat").readText())?.let { out[pid] = it }
      }
    }
    return out
  }
}

/** Appends lines; at 5 MB moves the file to `<name>.1` (one kept) and starts over (architecture §2.3). */
internal class RotatingLogFile(private val file: File, private val maxBytes: Long = 5L * 1024 * 1024) {
  private var out: OutputStream? = null
  private var size = 0L
  private var closed = false

  @Synchronized
  fun line(text: String) {
    if (closed) return
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

  @Synchronized
  fun close() {
    closed = true
    runCatching { out?.close() }
    out = null
  }

  private fun open(): OutputStream {
    file.parentFile?.mkdirs()
    size = if (file.isFile) file.length() else 0
    return FileOutputStream(file, true).also { out = it }
  }
}
