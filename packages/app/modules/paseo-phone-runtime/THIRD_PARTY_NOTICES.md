# Third-party notices

`paseo-phone-runtime` ships or adapts the following third-party work.

## PRoot (Termux fork)

- Files: `android/src/main/jniLibs/arm64-v8a/libproot.so`, `libproot-loader.so`
- Source: https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f, built by `proot/build-proot.sh` (pins in `proot/PINS.md`)
- License: GPL-2.0 (https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)
- Copyright (C) PRoot developers

PRoot runs as a separate executable. It is not linked into the app. The exact source is the commit above; `proot/build-proot.sh` reproduces the binaries.

## talloc

- File: `android/src/main/jniLibs/arm64-v8a/libtalloc.so`
- Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz, built by `proot/build-proot.sh`
- License: LGPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.html)
- Copyright (C) Andrew Tridgell, Stefan Metzmacher, and contributors

Dynamically linked by PRoot only.

## Kai

- Files: `proot/build-proot.sh` and the Kotlin files under `android/src/main/java/io/github/ouor/paseophone/runtime/linux/` that carry a Kai header
- Source: https://github.com/SimonSchubert/Kai
- License: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Copyright (C) Simon Schubert and Kai contributors

Modified: ported out of Kai's Compose app, arm64 only, proot arguments and tar handling changed (see each file header).

## Debian root filesystem

Downloaded at install time, not shipped in the APK: `debian-trixie-aarch64-pd-v4.29.0.tar.xz` from https://github.com/termux/proot-distro/releases/tag/v4.29.0. Debian packages carry their own licenses under `/usr/share/doc` in the rootfs.

## Node.js

Downloaded at install time from https://nodejs.org/dist/ and verified against `SHASUMS256.txt`. MIT license, shipped inside the tarball (`/usr/local/LICENSE`).
