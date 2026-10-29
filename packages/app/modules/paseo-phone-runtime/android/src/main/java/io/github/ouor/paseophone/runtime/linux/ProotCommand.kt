// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/ProotLauncher.kt, Apache-2.0.
// Modifications: proot-distro `login` level arguments (architecture §2.4), fake /proc files,
// `env -i` guest environment, argv-only (process handling lives in ProotProcess/ProotExec).
package io.github.ouor.paseophone.runtime.linux

import java.io.File

data class ProotSpec(
  /** argv inside the rootfs, e.g. listOf("/bin/bash", "/opt/paseo-phone/run-daemon.sh"). */
  val command: List<String>,
  /** Added to the guest environment (overrides the defaults). */
  val env: Map<String, String> = emptyMap(),
  /** When set, bound to /root/exchange. */
  val exchangeDir: File? = null,
  val cwd: String = "/root",
)

/** What to hand to ProcessBuilder. [env] replaces the app's environment entirely. */
data class ProotInvocation(val argv: List<String>, val env: Map<String, String>, val workDir: File)

object ProotCommand {
  const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  const val EXCHANGE_GUEST_PATH = "/root/exchange"
  private const val KERNEL_RELEASE = "6.17.0-PRoot"
  private const val KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"

  /** `Build.MODEL` → hostname: lowercase, runs of other characters become `-` ("POCO F3" → "poco-f3"). */
  fun nodename(model: String): String =
    model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63).ifEmpty { "android" }

  /**
   * proot argv and host environment (architecture §2.4).
   *
   * @param root filesDir/phone-runtime
   * @param fakeProc (sysdata file, guest path) pairs from [FakeSysdata.write]
   */
  fun build(
    nativeLibDir: String,
    root: File,
    nodename: String,
    fakeProc: List<Pair<File, String>>,
    spec: ProotSpec,
  ): ProotInvocation {
    val rootfs = File(root, "rootfs")
    val tmp = File(root, "tmp")
    val argv = buildList {
      add("$nativeLibDir/libproot.so")
      add("--kill-on-exit")
      add("--link2symlink")
      add("--sysvipc")
      add("-L")
      add("--kernel-release=\\Linux\\$nodename\\$KERNEL_RELEASE\\$KERNEL_VERSION\\aarch64\\localdomain\\-1\\")
      add("--change-id=0:0")
      add("--rootfs=${rootfs.path}")
      add("--cwd=${spec.cwd}")
      add("--bind=/dev")
      add("--bind=/proc")
      add("--bind=/sys")
      add("--bind=/dev/urandom:/dev/random")
      add("--bind=${File(root, "sysdata/empty").path}:/sys/fs/selinux")
      fakeProc.forEach { (file, guest) -> add("--bind=${file.path}:$guest") }
      add("--bind=${tmp.path}:/tmp")
      add("--bind=${tmp.path}:/dev/shm")
      spec.exchangeDir?.let { add("--bind=${it.path}:$EXCHANGE_GUEST_PATH") }
      add("/usr/bin/env")
      add("-i")
      (guestEnv(nodename) + spec.env).forEach { (k, v) -> add("$k=$v") }
      addAll(spec.command)
    }
    val env = mapOf(
      "PROOT_TMP_DIR" to tmp.path,
      "PROOT_LOADER" to "$nativeLibDir/libproot-loader.so",
      "LD_LIBRARY_PATH" to root.path, // libtalloc.so.2 (LinuxPaths.copyLibtalloc)
    )
    return ProotInvocation(argv, env, root)
  }

  private fun guestEnv(nodename: String) = linkedMapOf(
    "HOME" to "/root",
    "USER" to "root",
    "LOGNAME" to "root",
    "PATH" to GUEST_PATH,
    "LANG" to "C.UTF-8",
    "TERM" to "xterm-256color",
    "TMPDIR" to "/tmp",
    "PASEO_PHONE_NODENAME" to nodename,
  )
}

/**
 * Static stand-ins for /proc files Android blocks (Node's os.cpus()/os.loadavg() read them).
 * The contents match what proot-distro 5.10.3 writes; proot-distro is GPL-3, so only the data is
 * reproduced, not its code. Like proot-distro, a file is bound only when the real one is unreadable.
 */
object FakeSysdata {
  private const val OVERFLOW_ID = "65534\n"
  private val CPU_ROWS = listOf(
    longArrayOf(31, 0, 226, 12027, 82, 10, 4, 9, 0, 0),
    longArrayOf(45, 0, 664, 11144, 21, 263, 233, 12, 0, 0),
    longArrayOf(494, 0, 537, 11283, 27, 10, 3, 8, 0, 0),
    longArrayOf(359, 0, 234, 11723, 24, 26, 5, 7, 0, 0),
    longArrayOf(295, 0, 268, 11772, 10, 12, 2, 12, 0, 0),
    longArrayOf(270, 0, 251, 11833, 15, 3, 1, 10, 0, 0),
    longArrayOf(430, 0, 520, 11386, 30, 8, 1, 12, 0, 0),
    longArrayOf(30, 0, 172, 12108, 50, 8, 1, 13, 0, 0),
  )
  private val STAT_TAIL =
    "intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277\n" +
      "ctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\n" +
      "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n"
  private val VMSTAT = listOf(
    "nr_free_pages 1743136", "nr_zone_inactive_anon 179281", "nr_zone_active_anon 7183",
    "nr_zone_inactive_file 22858", "nr_zone_active_file 51328", "nr_zone_unevictable 642",
    "nr_zone_write_pending 0", "nr_mlock 0", "nr_inactive_anon 179281", "nr_active_anon 7183",
    "nr_inactive_file 22858", "nr_active_file 51328", "nr_unevictable 642",
    "nr_slab_reclaimable 8091", "nr_slab_unreclaimable 7804", "nr_anon_pages 7723",
    "nr_mapped 8905", "nr_file_pages 253569", "nr_dirty 0", "nr_writeback 0", "nr_shmem 178741",
    "nr_kernel_stack 2780", "nr_page_table_pages 344", "pgpgin 890508", "pgpgout 0", "pswpin 0",
    "pswpout 0", "pgfree 3077011", "pgfault 176973", "pgmajfault 488", "oom_kill 0",
  ).joinToString("\n", postfix = "\n")

  /** /proc/stat with one cpuN line per id; the aggregate line is their column sum. */
  fun stat(cpuIds: List<Int>): String {
    val rows = rows(cpuIds)
    val total = LongArray(10) { col -> rows.sumOf { it.second[col] } }
    val sb = StringBuilder("cpu  ").append(total.joinToString(" ")).append('\n')
    rows.forEach { (id, row) -> sb.append("cpu$id ").append(row.joinToString(" ")).append('\n') }
    return sb.append(STAT_TAIL).toString()
  }

  /** Second field = idle seconds summed over CPUs, consistent with [stat]. */
  fun uptime(cpuIds: List<Int>): String {
    val idleTicks = rows(cpuIds).sumOf { it.second[3] }
    return "124.08 %d.%02d\n".format(idleTicks / 100, idleTicks % 100)
  }

  private fun rows(cpuIds: List<Int>) =
    cpuIds.ifEmpty { listOf(0) }.mapIndexed { n, id -> id to CPU_ROWS[n % CPU_ROWS.size] }

  /** Online CPU ids from /proc/cpuinfo (`processor : N`), else 0 until availableProcessors. */
  fun hostCpuIds(cpuinfo: String?): List<Int> {
    val ids = cpuinfo?.lineSequence()
      ?.map { it.split(':') }
      ?.filter { it.size == 2 && it[0].trim() == "processor" }
      ?.mapNotNull { it[1].trim().toIntOrNull() }
      ?.toSortedSet()?.toList()
    return if (ids.isNullOrEmpty()) (0 until Runtime.getRuntime().availableProcessors()).toList() else ids
  }

  /**
   * Writes the stand-ins under [sysdata] (and sysdata/empty for /sys/fs/selinux) and returns the
   * binds for the ones whose real /proc file this process cannot read.
   */
  fun write(sysdata: File, nodename: String): List<Pair<File, String>> {
    val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
    val cpus = hostCpuIds(cpuinfo)
    val entries = listOf(
      Triple("loadavg", "/proc/loadavg", "0.12 0.07 0.02 2/165 765\n"),
      Triple("stat", "/proc/stat", stat(cpus)),
      Triple("uptime", "/proc/uptime", uptime(cpus)),
      Triple("version", "/proc/version", "Linux version 6.17.0-PRoot ($nodename) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\n"),
      Triple("vmstat", "/proc/vmstat", VMSTAT),
      Triple("cap_last_cap", "/proc/sys/kernel/cap_last_cap", "40\n"),
      Triple("max_user_watches", "/proc/sys/fs/inotify/max_user_watches", "4096\n"),
      Triple("overflowuid", "/proc/sys/kernel/overflowuid", OVERFLOW_ID),
      Triple("overflowgid", "/proc/sys/kernel/overflowgid", OVERFLOW_ID),
    )
    File(sysdata, "empty").mkdirs()
    return entries.mapNotNull { (name, guest, content) ->
      val file = File(sysdata, name)
      if (!file.isFile || file.readText() != content) file.writeText(content)
      if (readable(guest)) null else file to guest
    }
  }

  private fun readable(path: String) = runCatching { File(path).inputStream().use { it.read() } }.isSuccess
}
