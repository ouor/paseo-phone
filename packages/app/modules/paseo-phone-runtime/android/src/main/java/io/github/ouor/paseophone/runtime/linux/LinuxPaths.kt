// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/LinuxPaths.kt, Apache-2.0.
// Modifications: one install under filesDir/phone-runtime, legacy marker logic removed,
// logs/sysdata/downloads/staging/identity added, disk-based install state, symlink-safe tree deletion.
package io.github.ouor.paseophone.runtime.linux

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Install state read from disk only, never from state.json (architecture §2.3, D15). */
enum class RootfsState { ABSENT, EXTRACTED, INSTALLED }

/** Storage layout of the phone runtime (architecture §2.3). */
class LinuxPaths(context: Context) {
  private val appContext = context.applicationContext

  val root: File = File(appContext.filesDir, "phone-runtime")
  val rootfs: File = File(root, "rootfs")
  val rootfsStaging: File = File(root, "rootfs.staging")
  val identity: File = File(root, "identity")
  val tmp: File = File(root, "tmp")
  val sysdata: File = File(root, "sysdata")
  val logs: File = File(root, "logs")
  val downloads: File = File(appContext.cacheDir, "phone-runtime/downloads")
  val stateFile: File = File(root, "state.json")

  /** proot runs straight out of nativeLibraryDir, the one app path Android lets us exec. */
  val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir

  /** "daemon" | "ssh" | "setup" → logs/<kind>.log */
  fun logFile(kind: String): File = File(logs, "$kind.log")

  fun rootfsState(): RootfsState = stateOf(rootfs)

  fun ensureLayout() {
    listOf(root, tmp, sysdata, logs, downloads).forEach { it.mkdirs() }
  }

  /**
   * Android strips the `.so.2` suffix from jniLibs, so proot cannot find the soname it was
   * linked against until a correctly named copy sits on its LD_LIBRARY_PATH ([root]).
   * Recopied when the APK's copy changes size (app update with a rebuilt talloc).
   */
  fun copyLibtalloc() {
    val source = File(nativeLibDir, "libtalloc.so")
    val target = File(root, "libtalloc.so.2")
    if (!source.exists()) throw IOException("libtalloc.so missing from $nativeLibDir")
    if (target.exists() && target.length() == source.length()) return
    root.mkdirs()
    source.copyTo(target, overwrite = true)
  }

  companion object {
    /** Marker of a finished `paseo` step: the daemon package is on disk. */
    const val DAEMON_PACKAGE_JSON = "usr/local/lib/node_modules/@getpaseo/cli/package.json"

    fun stateOf(rootfs: File): RootfsState = when {
      !Files.exists(rootfs.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) -> RootfsState.ABSENT
      File(rootfs, DAEMON_PACKAGE_JSON).isFile -> RootfsState.INSTALLED
      else -> RootfsState.EXTRACTED
    }

    /**
     * Deletes [dir] without following symlinks. A guest symlink can point at a real host path
     * (e.g. /storage/emulated/0/Paseo); File.deleteRecursively would walk into it. core-linux
     * calls this for staging, identity and downloads only; rootfs/ is deleted by reinstall().
     */
    fun deleteTree(dir: File) {
      val start = dir.toPath()
      if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
      // walkFileTree does not follow links by default: a link is visited as a file and unlinked.
      Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
            // Read-only dirs (mode 0555 from the tarball) would block deleting their children.
            d.toFile().setWritable(true, true)
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
            Files.delete(d)
            return FileVisitResult.CONTINUE
          }
        },
      )
    }
  }
}
