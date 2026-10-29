package io.github.ouor.paseophone.runtime.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Device identity kept across Reinstall (architecture §2.3 D16): daemon server-id, SSH host
 * keys and authorized_keys. Paths are relative to the rootfs and mirrored under identity/.
 */
object Identity {
  private val SERVER_ID = PhoneConstants.GUEST_PASEO_HOME.removePrefix("/") + "/server-id"
  private const val SSH_DIR = "etc/ssh"
  private const val HOST_KEY_PREFIX = "ssh_host_"
  private const val AUTHORIZED_KEYS = "root/.ssh/authorized_keys"

  /** rootfs → identity/. Missing files are skipped; files already in identity/ are kept. */
  fun save(paths: LinuxPaths) = save(paths.rootfs, paths.identity)

  /** identity/ → rootfs, modes kept (private keys 0600), then identity/ is deleted. */
  fun restoreIfPresent(paths: LinuxPaths) = restoreIfPresent(paths.rootfs, paths.identity)

  internal fun save(rootfs: File, identity: File) {
    val hostKeys = File(rootfs, SSH_DIR).listFiles { f -> f.name.startsWith(HOST_KEY_PREFIX) }.orEmpty()
      .map { "$SSH_DIR/${it.name}" }
    val files = listOf(SERVER_ID, AUTHORIZED_KEYS) + hostKeys
    identity.mkdirs()
    setMode(identity, "rwx------")
    for (rel in files) {
      val src = File(rootfs, rel).toPath()
      // Regular files only: a guest symlink must not make us copy a host file.
      if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) continue
      val dst = File(identity, rel)
      dst.parentFile?.mkdirs()
      Files.copy(src, dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    }
  }

  internal fun restoreIfPresent(rootfs: File, identity: File) {
    if (!identity.isDirectory) return
    identity.walkTopDown().filter { it.isFile }.forEach { src ->
      val rel = src.relativeTo(identity).invariantSeparatorsPath
      val dst = File(rootfs, rel)
      dst.parentFile?.mkdirs()
      Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      if (isSecret(rel)) setMode(dst, "rw-------")
    }
    File(rootfs, "root/.ssh").takeIf { it.isDirectory }?.let { setMode(it, "rwx------") }
    LinuxPaths.deleteTree(identity)
  }

  private fun isSecret(rel: String) =
    rel == AUTHORIZED_KEYS || (rel.startsWith("$SSH_DIR/$HOST_KEY_PREFIX") && !rel.endsWith(".pub"))

  private fun setMode(file: File, mode: String) {
    try {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
    } catch (_: UnsupportedOperationException) {
      // Non-POSIX test hosts (Windows). Android always supports it.
    }
  }
}
