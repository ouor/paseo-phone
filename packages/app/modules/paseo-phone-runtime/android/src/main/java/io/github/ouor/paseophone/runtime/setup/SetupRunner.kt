// Replaces Kai's LinuxInstaller (https://github.com/SimonSchubert/Kai, Apache-2.0): per-step retry
// instead of "wipe everything on failure" (architecture §2.5).
package io.github.ouor.paseophone.runtime.setup

import android.content.Context
import android.os.Build
import android.os.StatFs
import io.github.ouor.paseophone.runtime.BuildConfig
import io.github.ouor.paseophone.runtime.linux.ChecksumMismatchException
import io.github.ouor.paseophone.runtime.linux.Downloader
import io.github.ouor.paseophone.runtime.linux.LinuxPaths
import io.github.ouor.paseophone.runtime.linux.NodeDist
import io.github.ouor.paseophone.runtime.linux.ProotCommand
import io.github.ouor.paseophone.runtime.linux.ProotExec
import io.github.ouor.paseophone.runtime.linux.ProotSpec
import io.github.ouor.paseophone.runtime.linux.RootfsSpec
import io.github.ouor.paseophone.runtime.linux.TarExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class SetupMode { INSTALL, UPDATE_DAEMON, UPDATE_NODE }

sealed interface SetupEvent {
  /** "download" | "extract" | "configure" | "packages" | "paseo" */
  data class Step(val step: String) : SetupEvent

  /** unit: "bytes" | "packages". total is -1 when unknown. */
  data class Progress(val done: Long, val total: Long, val unit: String) : SetupEvent
}

sealed interface SetupResult {
  object Success : SetupResult

  /** code: types.ts SetupErrorCode ("offline" | "no-space" | "checksum" | "command-failed" | "unknown"). */
  data class Failed(val step: String, val code: String, val message: String) : SetupResult
}

private class StepFailure(val code: String, message: String) : Exception(message)

class SetupRunner(context: Context, private val paths: LinuxPaths) {
  private val context = context.applicationContext
  private val daemonVersion = BuildConfig.PASEO_DAEMON_VERSION
  private val nodeVersion = BuildConfig.NODE_VERSION

  /**
   * Runs the steps of [mode] from [fromStep] (or the first; unknown names also start at the first)
   * through "paseo". Deletes the download cache on success. Never throws except
   * CancellationException; the caller keeps state.json (RuntimeStore).
   */
  suspend fun run(mode: SetupMode, fromStep: String?, onEvent: (SetupEvent) -> Unit): SetupResult {
    val steps = stepsFor(mode)
    val start = (fromStep?.let { steps.indexOf(it) } ?: 0).coerceAtLeast(0)
    paths.ensureLayout()
    log("=== setup ${mode.name.lowercase()} from ${steps[start]} (daemon $daemonVersion, node $nodeVersion)")
    for (step in steps.drop(start)) {
      onEvent(SetupEvent.Step(step))
      log("=== step $step")
      try {
        runStep(mode, step, onEvent)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        val failed = classify(step, e)
        log("=== failed $step: ${failed.code}: ${failed.message}")
        return failed
      }
    }
    // Verified archives are only kept for retries (architecture §2.5).
    if ("download" in steps) withContext(Dispatchers.IO) { LinuxPaths.deleteTree(paths.downloads) }
    log("=== setup done")
    return SetupResult.Success
  }

  /** `@getpaseo/cli` version installed in the rootfs, or null. */
  fun installedDaemonVersion(): String? =
    runCatching {
      val pkg = File(paths.rootfs, "usr/local/lib/node_modules/@getpaseo/cli/package.json")
      JSONObject(pkg.readText()).optString("version").ifEmpty { null }
    }.getOrNull()

  /** Node version in /usr/local, read from node_version.h (no proot start needed), or null. */
  fun installedNodeVersion(): String? =
    runCatching {
      parseNodeVersionHeader(File(paths.rootfs, "usr/local/include/node/node_version.h").readText())
    }.getOrNull()

  /**
   * Copies APK `assets/phone-runtime/` to rootfs `/opt/paseo-phone/` (overwrite). Runs before
   * every script step; core-runtime also calls it on each service start (internal-contract).
   */
  fun installScripts() {
    val dest = File(paths.rootfs, "opt/paseo-phone")
    copyAssets("phone-runtime", dest)
  }

  private suspend fun runStep(mode: SetupMode, step: String, onEvent: (SetupEvent) -> Unit) {
    when (step) {
      "download" -> download(mode, onEvent)
      "extract" -> extract(mode)
      "configure" -> script("configure.sh", 10 * MINUTE, onEvent)
      "packages" -> script("packages.sh", 45 * MINUTE, onEvent)
      "paseo" -> script("paseo.sh", 45 * MINUTE, onEvent)
      else -> error("unknown step $step")
    }
  }

  private suspend fun download(mode: SetupMode, onEvent: (SetupEvent) -> Unit) {
    if (mode == SetupMode.INSTALL) {
      val free = StatFs(paths.root.path).availableBytes
      if (free < REQUIRED_FREE_BYTES) {
        throw StepFailure("no-space", "Need ${gb(REQUIRED_FREE_BYTES)} free, ${gb(free)} available")
      }
    }
    var offset = 0L
    val progress = { done: Long, total: Long ->
      onEvent(SetupEvent.Progress(offset + done, if (total < 0) -1 else offset + total, "bytes"))
    }
    if (mode == SetupMode.INSTALL) {
      val rootfsArchive = File(paths.downloads, RootfsSpec.ARCHIVE_NAME)
      Downloader.download(RootfsSpec.URL, rootfsArchive, RootfsSpec.SHA256, progress)
      offset += rootfsArchive.length()
    }
    val nodeName = NodeDist.archiveName(nodeVersion)
    val nodeSha = NodeDist.sha256From(Downloader.fetchText(NodeDist.shasumsUrl(nodeVersion)), nodeName)
      ?: throw StepFailure("checksum", "$nodeName is not listed in SHASUMS256.txt")
    Downloader.download(NodeDist.url(nodeVersion), File(paths.downloads, nodeName), nodeSha, progress)
  }

  private suspend fun extract(mode: SetupMode) = withContext(Dispatchers.IO) {
    val nodeArchive = File(paths.downloads, NodeDist.archiveName(nodeVersion))
    if (mode == SetupMode.INSTALL) {
      val archive = File(paths.downloads, RootfsSpec.ARCHIVE_NAME)
      if (!archive.isFile) throw StepFailure("unknown", "${archive.name} missing; retry from download")
      LinuxPaths.deleteTree(paths.rootfs)
      val n = TarExtractor.extract(archive, paths.rootfs, RootfsSpec.STRIP_COMPONENTS)
      log("extracted $n rootfs entries")
    }
    if (!nodeArchive.isFile) throw StepFailure("unknown", "${nodeArchive.name} missing; retry from download")
    val usrLocal = File(paths.rootfs, "usr/local")
    if (mode == SetupMode.UPDATE_NODE) {
      // Drop the old Node only; global npm packages in lib/node_modules stay.
      listOf("bin/node", "bin/npm", "bin/npx", "bin/corepack", "include/node", "lib/node_modules/npm", "lib/node_modules/corepack")
        .forEach { LinuxPaths.deleteTree(File(usrLocal, it)) }
    }
    val n = TarExtractor.extract(nodeArchive, usrLocal, NodeDist.STRIP_COMPONENTS)
    log("extracted $n node entries")
    paths.copyLibtalloc()
    listOf("tmp", "root", "opt/paseo-phone").forEach { File(paths.rootfs, it).mkdirs() }
  }

  private suspend fun script(name: String, timeoutMs: Long, onEvent: (SetupEvent) -> Unit) {
    if (!paths.isRootfsPresent()) throw StepFailure("unknown", "Linux is not installed; retry from download")
    withContext(Dispatchers.IO) { installScripts() }
    var total = -1L
    var done = 0L
    val spec = ProotSpec(command = listOf("/bin/bash", "/opt/paseo-phone/$name"), env = scriptEnv())
    val result = ProotExec.run(context, paths, spec, paths.logFile("setup"), timeoutMs) { line ->
      // packages.sh progress contract (internal-contract.md)
      if (line.startsWith("PASEO_PHONE_TOTAL=")) {
        total = line.substringAfter('=').trim().toLongOrNull() ?: -1
        onEvent(SetupEvent.Progress(0, total, "packages"))
      } else if (total > 0 && line.startsWith("Setting up ")) {
        done++
        onEvent(SetupEvent.Progress(minOf(done, total), total, "packages"))
      }
    }
    if (result.timedOut) throw StepFailure("command-failed", "$name timed out after ${timeoutMs / MINUTE} min")
    if (result.exitCode != 0) {
      throw StepFailure("command-failed", result.lastErrorLine ?: "$name exited with ${result.exitCode}")
    }
  }

  private fun scriptEnv() = mapOf(
    "PASEO_DAEMON_VERSION" to daemonVersion,
    "NODE_VERSION" to nodeVersion,
    "PASEO_PHONE_SSH_PORT" to "2222",
    "PASEO_PHONE_NODENAME" to ProotCommand.nodename(Build.MODEL ?: ""),
  )

  private fun copyAssets(assetPath: String, dest: File) {
    val children = context.assets.list(assetPath).orEmpty()
    if (children.isEmpty()) {
      dest.parentFile?.mkdirs()
      val tmp = File(dest.path + ".tmp")
      context.assets.open(assetPath).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
      if (!tmp.renameTo(dest)) throw IOException("Cannot write $dest")
      if (dest.name.endsWith(".sh")) dest.setExecutable(true, false)
      return
    }
    dest.mkdirs()
    children.forEach { copyAssets("$assetPath/$it", File(dest, it)) }
  }

  private fun classify(step: String, e: Throwable): SetupResult.Failed {
    val (code, message) = when {
      e is StepFailure -> e.code to (e.message ?: "")
      e is ChecksumMismatchException -> "checksum" to (e.message ?: "")
      e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is SSLException ->
        "offline" to (e.message ?: e.javaClass.simpleName)
      isNoSpace(e) -> "no-space" to (e.message ?: "No space left on device")
      else -> "unknown" to (e.message ?: e.javaClass.simpleName)
    }
    return SetupResult.Failed(step, code, message)
  }

  private fun isNoSpace(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left") == true }

  private fun log(line: String) {
    runCatching {
      val f = paths.logFile("setup")
      f.parentFile?.mkdirs()
      f.appendText("$line\n")
    }
  }

  companion object {
    private const val MINUTE = 60_000L
    private const val REQUIRED_FREE_BYTES = 4L * 1024 * 1024 * 1024

    fun stepsFor(mode: SetupMode): List<String> = when (mode) {
      SetupMode.INSTALL -> listOf("download", "extract", "configure", "packages", "paseo")
      SetupMode.UPDATE_NODE -> listOf("download", "extract", "paseo")
      SetupMode.UPDATE_DAEMON -> listOf("paseo")
    }

    /** `#define NODE_MAJOR_VERSION 22` … → "22.20.0" */
    fun parseNodeVersionHeader(header: String): String? {
      fun part(name: String) = Regex("#define\\s+NODE_${name}_VERSION\\s+(\\d+)").find(header)?.groupValues?.get(1)
      val major = part("MAJOR") ?: return null
      val minor = part("MINOR") ?: return null
      val patch = part("PATCH") ?: return null
      return "$major.$minor.$patch"
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
  }
}
