// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/LinuxPaths.kt, Apache-2.0.
// Modifications: one install under filesDir/phone-runtime, legacy marker logic removed,
// logs/sysdata/downloads added, symlink-safe rootfs deletion.
package io.github.ouor.paseophone.runtime.linux

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Storage layout of the phone runtime (architecture §2.3). */
class LinuxPaths(context: Context) {
  private val appContext = context.applicationContext

  val root: File = File(appContext.filesDir, "phone-runtime")
  val rootfs: File = File(root, "rootfs")
  val tmp: File = File(root, "tmp")
  val sysdata: File = File(root, "sysdata")
  val logs: File = File(root, "logs")
  val downloads: File = File(appContext.cacheDir, "phone-runtime/downloads")
  val stateFile: File = File(root, "state.json")

  /** proot runs straight out of nativeLibraryDir, the one app path Android lets us exec. */
  val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir

  /** "daemon" | "sshd" | "setup" → logs/<kind>.log */
  fun logFile(kind: String): File = File(logs, "$kind.log")

  /** True once a rootfs has been extracted far enough to run `/usr/bin/env`. */
  fun isRootfsPresent(): Boolean = File(rootfs, "usr/bin/env").isFile

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

  /**
   * Deletes the rootfs without following symlinks. A guest symlink can point at a real host
   * path (e.g. /storage/emulated/0/Paseo); File.deleteRecursively would walk into it.
   */
  fun deleteRootfs() = deleteTree(rootfs)

  companion object {
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
