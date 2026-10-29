# proot pins

Sources and outputs for `android/src/main/jniLibs/arm64-v8a/`. Rebuild with `./build-proot.sh` (Linux or WSL, NDK r29), then update the sha256 table.

| Input  | Pin                                                                               |
| ------ | --------------------------------------------------------------------------------- |
| proot  | https://github.com/termux/proot commit `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f` |
| talloc | https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz (2.4.3)                      |
| NDK    | r29 (`NDK_MAJOR` in `build-proot.sh`), `MIN_API=26`                               |

## Current binaries

Round 1 ships Kai's arm64-v8a prebuilts unchanged instead of a local rebuild: this Windows machine has no Linux NDK r29, and Kai builds them with the same script and pins above.

- Source: https://github.com/SimonSchubert/Kai `androidApp/src/main/jniLibs/arm64-v8a/`, last changed in commit `5cbb8de251c0448049fdc70c856782cc7c57d240` (checkout at `bd774d32e5790a651bec84f4b16ef23bf1f4d481`).
- `libproot-loader32.so` is not shipped (arm64 rootfs only).

| File                 | sha256                                                             |
| -------------------- | ------------------------------------------------------------------ |
| `libproot.so`        | `452fcfe761d398e6d1180e60c9b5039fcabdba06d8d73e803c5bb866905d2a8c` |
| `libproot-loader.so` | `cb5e5b6900e198ca8160e9d355ea5b98d646333887a769411ff74132c1cec5df` |
| `libtalloc.so`       | `15a8101160fee241e15b241d49bc288c3bd902b0334e34020a1c54e47760a1f7` |

When you rebuild, replace this section with the build date, NDK `Pkg.Revision`, and the new hashes.
