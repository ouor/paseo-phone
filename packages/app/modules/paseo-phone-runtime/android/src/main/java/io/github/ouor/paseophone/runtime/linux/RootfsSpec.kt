// Replaces Kai's DistroSpec/LinuxDistro (https://github.com/SimonSchubert/Kai, Apache-2.0):
// one pinned Debian tarball instead of Alpine mirrors and the LXC index.
package io.github.ouor.paseophone.runtime.linux

/**
 * The pinned rootfs (architecture D3). proot-distro 5.x pulls OCI images instead of tarballs;
 * v4.29.0 is the last proot-distro release that publishes a trixie aarch64 tarball, and
 * proot-distro 4.34.2's debian plugin still points at it. Change only these lines to move.
 */
object RootfsSpec {
  const val DISTRO_LABEL = "Debian 13"
  const val URL =
    "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
  const val SHA256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
  const val SIZE_BYTES = 35_409_704L

  /** Every entry sits under `debian-trixie-aarch64/`. */
  const val STRIP_COMPONENTS = 1
  const val ARCHIVE_NAME = "debian-trixie-aarch64-pd-v4.29.0.tar.xz"
}

/** nodejs.org linux-arm64 tarball for a pinned version (architecture D5). */
object NodeDist {
  fun archiveName(version: String) = "node-v$version-linux-arm64.tar.xz"

  fun url(version: String) = "https://nodejs.org/dist/v$version/${archiveName(version)}"

  fun shasumsUrl(version: String) = "https://nodejs.org/dist/v$version/SHASUMS256.txt"

  /** Picks [fileName]'s hash out of a SHASUMS256.txt body, or null. */
  fun sha256From(shasums: String, fileName: String): String? =
    shasums.lineSequence()
      .map { it.trim().split(Regex("\\s+")) }
      .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
      ?.get(0)
      ?.lowercase()
      ?.takeIf { it.length == 64 }

  /** The tarball's top-level `node-v…/` directory is dropped when extracting into /usr/local. */
  const val STRIP_COMPONENTS = 1
}
