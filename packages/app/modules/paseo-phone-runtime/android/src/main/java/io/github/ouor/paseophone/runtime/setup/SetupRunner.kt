// Replaces Kai's LinuxInstaller (https://github.com/SimonSchubert/Kai, Apache-2.0): per-step retry
// instead of "wipe everything on failure" (architecture §2.5).
package io.github.ouor.paseophone.runtime.setup

import android.content.Context
import android.os.StatFs
import io.github.ouor.paseophone.runtime.BuildConfig
import io.github.ouor.paseophone.runtime.linux.ChecksumMismatchException
import io.github.ouor.paseophone.runtime.linux.Downloader
import io.github.ouor.paseophone.runtime.linux.ExecResult
import io.github.ouor.paseophone.runtime.linux.Identity
import io.github.ouor.paseophone.runtime.linux.LinuxPaths
import io.github.ouor.paseophone.runtime.linux.NodeDist
import io.github.ouor.paseophone.runtime.linux.PhoneConstants
import io.github.ouor.paseophone.runtime.linux.ProotExec
import io.github.ouor.paseophone.runtime.linux.ProotSpec
import io.github.ouor.paseophone.runtime.linux.RootfsSpec
import io.github.ouor.paseophone.runtime.linux.RootfsState
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
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

  /** code: types.ts SetupErrorCode. freeBytes/neededBytes are both set when code == "no-space". */
  data class Failed(
    val step: String,
    val code: String,
    val message: String,
    val freeBytes: Long? = null,
    val neededBytes: Long? = null,
  ) : SetupResult
}

internal class StepFailure(
  val code: String,
  message: String,
  val freeBytes: Long? = null,
  val neededBytes: Long? = null,
) : Exception(message)

class SetupRunner(context: Context, private val paths: LinuxPaths) {
  private val context = context.applicationContext
  private val daemonVersion = BuildConfig.PASEO_DAEMON_VERSION
  private val nodeVersion = BuildConfig.NODE_VERSION
  private val nodeSha256 = BuildConfig.NODE_SHA256

  /**
   * Runs the steps of [mode] from [fromStep] (null = the first) through "paseo". An unknown
   * [fromStep] (e.g. "start", the controller's step) returns Failed(code = "unknown").
   * CancellationException is rethrown; everything else becomes Failed. state.json is the
   * caller's (RuntimeStore).
   */
  suspend fun run(mode: SetupMode, fromStep: String?, onEvent: (SetupEvent) -> Unit): SetupResult {
    val steps = stepsFrom(mode, fromStep)
      ?: return SetupResult.Failed(fromStep.orEmpty(), "unknown", "Unknown setup step for ${mode.name}: $fromStep")
    paths.ensureLayout()
    log("=== setup ${mode.name.lowercase()} from ${steps.first()} (daemon $daemonVersion, node $nodeVersion)")
    for (step in steps) {
      onEvent(SetupEvent.Step(step))
      log("=== step $step")
      try {
        runStep(mode, step, onEvent)
      } catch (e: CancellationException) {
        log("=== cancelled $step")
        throw e
      } catch (e: Throwable) {
        val failed = classify(step, e)
        log("=== failed $step: ${failed.code}: ${failed.message}")
        return failed
      }
    }
    log("=== setup done")
    return SetupResult.Success
  }

  /** `@getpaseo/cli` version installed in the rootfs, or null. */
  fun installedDaemonVersion(): String? =
    runCatching {
      JSONObject(File(paths.rootfs, LinuxPaths.DAEMON_PACKAGE_JSON).readText()).optString("version").ifEmpty { null }
    }.getOrNull()

  /** Node version in /usr/local, read from node_version.h (no proot start needed), or null. */
  fun installedNodeVersion(): String? =
    runCatching {
      parseNodeVersionHeader(File(paths.rootfs, "usr/local/include/node/node_version.h").readText())
    }.getOrNull()

  /** APK `assets/phone-runtime/` → rootfs [PhoneConstants.SCRIPT_DIR] (overwrite). */
  fun installScripts() {
    copyAssets("phone-runtime", File(paths.rootfs, PhoneConstants.SCRIPT_DIR.removePrefix("/")))
  }

  /** Env every script gets (architecture §7.5). PASEO_PHONE_NODENAME is added by ProotProcess/ProotExec. */
  fun scriptEnv(): Map<String, String> = mapOf(
    PhoneConstants.ENV_DAEMON_VERSION to daemonVersion,
    PhoneConstants.ENV_NODE_VERSION to nodeVersion,
    PhoneConstants.ENV_SSH_PORT to PhoneConstants.SSH_PORT.toString(),
    PhoneConstants.ENV_DAEMON_LISTEN to PhoneConstants.DAEMON_LISTEN,
  )

  /** `/bin/bash /opt/paseo-phone/<name>` with [scriptEnv]. The only way to run a rootfs script. */
  fun scriptSpec(name: String, exchangeDir: File? = null): ProotSpec =
    ProotSpec(listOf("/bin/bash", "${PhoneConstants.SCRIPT_DIR}/$name"), scriptEnv(), exchangeDir)

  /** Runs [scriptSpec] to completion. [onLine] gets stdout and stderr lines (pump threads). */
  suspend fun runScript(name: String, logFile: File, timeoutMs: Long, onLine: (String) -> Unit = {}): ExecResult =
    ProotExec.run(context, paths, scriptSpec(name), logFile, timeoutMs, onLine)

  private suspend fun runStep(mode: SetupMode, step: String, onEvent: (SetupEvent) -> Unit) {
    when (step) {
      "download" -> download(mode, onEvent)
      "extract" -> extract(mode)
      "configure" -> script("configure.sh", 10 * MINUTE, onEvent)
      "packages" -> script("packages.sh", 45 * MINUTE, onEvent)
      "paseo" -> script("paseo.sh", 45 * MINUTE, onEvent)
    }
  }

  private suspend fun download(mode: SetupMode, onEvent: (SetupEvent) -> Unit) {
    if (mode == SetupMode.INSTALL) {
      val free = StatFs(paths.root.path).availableBytes
      if (free < REQUIRED_FREE_BYTES) {
        throw StepFailure("no-space", "free ${gb(free)}, need ${gb(REQUIRED_FREE_BYTES)}", free, REQUIRED_FREE_BYTES)
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
    Downloader.download(NodeDist.url(nodeVersion), File(paths.downloads, nodeName), nodeSha256, progress)
  }

  private suspend fun extract(mode: SetupMode) = withContext(Dispatchers.IO) {
    val nodeArchive = File(paths.downloads, NodeDist.archiveName(nodeVersion))
    if (mode == SetupMode.INSTALL) {
      extractInstall(File(paths.downloads, RootfsSpec.ARCHIVE_NAME), nodeArchive, paths.rootfsStaging, paths.rootfs) { log(it) }
    } else {
      extractNode(nodeArchive, paths.rootfs) { log(it) }
    }
    // Archives are only kept for retries before extract succeeds (architecture §2.3).
    LinuxPaths.deleteTree(paths.downloads)
    // Before `packages`, so openssh-server's postinst keeps the restored host keys (D16).
    Identity.restoreIfPresent(paths)
    paths.copyLibtalloc()
    installScripts()
  }

  private suspend fun script(name: String, timeoutMs: Long, onEvent: (SetupEvent) -> Unit) {
    if (paths.rootfsState() == RootfsState.ABSENT) {
      throw StepFailure("unknown", "Linux is not extracted; retry from download")
    }
    withContext(Dispatchers.IO) { installScripts() }
    var total = -1L
    var done = 0L
    val result = runScript(name, paths.logFile("setup"), timeoutMs) { line ->
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
    if (e is StepFailure) return SetupResult.Failed(step, e.code, e.message.orEmpty(), e.freeBytes, e.neededBytes)
    val (code, message) = when {
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

    /** Steps to run, or null when [fromStep] is not a step of [mode]. */
    fun stepsFrom(mode: SetupMode, fromStep: String?): List<String>? {
      val steps = stepsFor(mode)
      if (fromStep == null) return steps
      val i = steps.indexOf(fromStep)
      return if (i < 0) null else steps.drop(i)
    }

    /**
     * INSTALL extract (architecture §2.3 invariant 2): refuses when [rootfs] exists, unpacks into a
     * fresh [staging], adds Node under usr/local, then renames staging to rootfs.
     */
    internal fun extractInstall(rootfsArchive: File, nodeArchive: File, staging: File, rootfs: File, log: (String) -> Unit) {
      if (Files.exists(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) throw StepFailure("unknown", "rootfs exists")
      requireArchive(rootfsArchive)
      requireArchive(nodeArchive)
      LinuxPaths.deleteTree(staging)
      log("extracted ${TarExtractor.extract(rootfsArchive, staging, RootfsSpec.STRIP_COMPONENTS)} rootfs entries")
      log("extracted ${TarExtractor.extract(nodeArchive, File(staging, "usr/local"), NodeDist.STRIP_COMPONENTS)} node entries")
      listOf("tmp", "root", PhoneConstants.SCRIPT_DIR.removePrefix("/")).forEach { File(staging, it).mkdirs() }
      Files.move(staging.toPath(), rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** UPDATE_NODE extract: replaces only Node in [rootfs]/usr/local; global npm packages stay. */
    internal fun extractNode(nodeArchive: File, rootfs: File, log: (String) -> Unit) {
      if (LinuxPaths.stateOf(rootfs) == RootfsState.ABSENT) {
        throw StepFailure("unknown", "Linux is not installed")
      }
      requireArchive(nodeArchive)
      val usrLocal = File(rootfs, "usr/local")
      listOf("bin/node", "bin/npm", "bin/npx", "bin/corepack", "include/node", "lib/node_modules/npm", "lib/node_modules/corepack")
        .forEach { LinuxPaths.deleteTree(File(usrLocal, it)) }
      log("extracted ${TarExtractor.extract(nodeArchive, usrLocal, NodeDist.STRIP_COMPONENTS)} node entries")
    }

    private fun requireArchive(f: File) {
      if (!f.isFile) throw StepFailure("unknown", "${f.name} missing; retry from download")
    }

    /** `#define NODE_MAJOR_VERSION 22` … → "22.20.0" */
    fun parseNodeVersionHeader(header: String): String? {
      fun part(name: String) = Regex("#define\\s+NODE_${name}_VERSION\\s+(\\d+)").find(header)?.groupValues?.get(1)
      val major = part("MAJOR") ?: return null
      val minor = part("MINOR") ?: return null
      val patch = part("PATCH") ?: return null
      return "$major.$minor.$patch"
    }

    private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
  }
}
