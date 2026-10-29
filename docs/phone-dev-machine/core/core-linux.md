# core-linux 작업 기록

담당: proot 바이너리, rootfs 다운로드·압축 해제, proot 실행, 설치 단계 실행기. 기준 문서는 [architecture.md](../architecture/architecture.md) §2, [internal-contract.md](internal-contract.md).

## 진행 상황 (1차, 2026-10-30)

| 항목                                                                                               | 상태                                                          |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `proot/build-proot.sh` (arm64 전용), `proot/PINS.md`                                               | 완료. 재빌드는 하지 않음(아래)                                |
| `jniLibs/arm64-v8a/{libproot,libproot-loader,libtalloc}.so`                                        | Kai 산출물 그대로. sha256은 PINS.md                           |
| `THIRD_PARTY_NOTICES.md`                                                                           | 완료                                                          |
| `linux/` (LinuxPaths, RootfsSpec, Downloader, TarExtractor, ProotCommand, ProotProcess, ProotExec) | 완료                                                          |
| `setup/SetupRunner.kt`                                                                             | 완료                                                          |
| JVM 단위 테스트 (`android/src/test/.../linux/`)                                                    | 14개. Gradle 없이 kotlinc + JUnit로 돌려 통과(아래 "검증")    |
| 기기 확인 (devphone01)                                                                             | §2.4 인자로 Debian rootfs 안 명령 실행 성공. 올린 파일은 지움 |
| Gradle/APK 빌드                                                                                    | 안 함. core-rootfs 통합 빌드에서 확인                         |

## 공개 Kotlin API

계약의 시그니처는 그대로다. 아래 "추가"는 계약에 없던 공개 멤버로, 시그니처 변경은 없다.

### `linux` 패키지

```kotlin
class LinuxPaths(context: Context) {
  val root; val rootfs; val tmp; val sysdata; val logs; val downloads; val stateFile   // 계약
  fun logFile(kind: String): File           // logs/<kind>.log
  fun isRootfsPresent(): Boolean            // rootfs/usr/bin/env 존재
  // 추가
  val nativeLibDir: String
  fun ensureLayout()                        // root, tmp, sysdata, logs, downloads 생성
  fun copyLibtalloc()                       // libtalloc.so → root/libtalloc.so.2 (크기가 다르면 다시 복사)
  fun deleteRootfs()                        // 심볼릭 링크를 따라가지 않고 rootfs 삭제
  companion object { fun deleteTree(dir: File) }
}

data class ProotSpec(command, env = emptyMap(), exchangeDir: File? = null, cwd = "/root")   // 계약
data class ExitInfo(exitCode: Int, signal: Int?)    // 계약. 추가: ExitInfo.fromExitCode(code)
class ProotProcess { start(context, paths, spec, logFile); pid; isAlive(); destroy(); destroyForcibly(); waitFor(ms); onExit(listener) }  // 계약
data class ExecResult(exitCode: Int, timedOut: Boolean, lastErrorLine: String?)                       // 계약
object ProotExec { suspend fun run(context, paths, spec, logFile, timeoutMs, onLine = {}): ExecResult }  // 계약

// 추가 (core-runtime이 직접 쓸 일은 없다)
object RootfsSpec { DISTRO_LABEL = "Debian 13"; URL; SHA256; SIZE_BYTES; STRIP_COMPONENTS = 1; ARCHIVE_NAME }
object NodeDist { archiveName(v); url(v); shasumsUrl(v); sha256From(shasums, fileName) }
object Downloader { suspend download(url, target, sha256, onProgress); suspend fetchText(url); sha256Of(file) }
class ChecksumMismatchException : IOException
object TarExtractor { extract(archive, targetDir, stripComponents): Int; extractTar(input, targetDir, strip): Int }
object ProotCommand { GUEST_PATH; nodename(model); build(nativeLibDir, root, nodename, fakeProc, spec): ProotInvocation }
data class ProotInvocation(argv, env, workDir)
object FakeSysdata { write(sysdata, nodename): List<Pair<File, String>>; stat(..); uptime(..); hostCpuIds(..) }
```

### `setup` 패키지

```kotlin
enum class SetupMode { INSTALL, UPDATE_DAEMON, UPDATE_NODE }        // 계약
sealed interface SetupEvent { Step(step); Progress(done, total, unit) }   // 계약. total = -1이면 모름
sealed interface SetupResult { Success; Failed(step, code, message) }    // 계약
class SetupRunner(context, paths) {
  suspend fun run(mode, fromStep, onEvent): SetupResult           // 계약
  fun installedDaemonVersion(): String?                           // 계약
  fun installedNodeVersion(): String?                             // 계약. rootfs/usr/local/include/node/node_version.h 파싱
  fun installScripts()                                            // 추가. assets/phone-runtime → rootfs/opt/paseo-phone (덮어쓰기)
  companion object { stepsFor(mode); parseNodeVersionHeader(text) }
}
```

## 동작

### ProotProcess / ProotExec

- 시작할 때마다: `ensureLayout()`, `copyLibtalloc()`, 가짜 `/proc` 파일 쓰기, `exchangeDir`이 있으면 `rootfs/root/exchange` 마운트 지점 생성. 호출하는 쪽이 따로 준비할 것은 없다.
- argv는 §2.4 그대로(`ProotCommand.build`). 앱 프로세스 환경은 비우고 `PROOT_TMP_DIR`, `PROOT_LOADER`, `LD_LIBRARY_PATH`만 준다. rootfs 안 환경은 `env -i`로 새로 만든다: `HOME USER LOGNAME PATH LANG TERM TMPDIR PASEO_PHONE_NODENAME` + `spec.env`(덮어씀).
- stdin은 바로 닫는다(EOF). stdout·stderr는 줄 단위로 `logFile`에 쌓는다. 5 MB를 넘으면 `<name>.1`로 옮기고(1개 보관) 새로 쓴다. 회전은 이 쓰기 경로에서 하므로 서비스가 따로 할 일은 없다.
- `pid`: Android `Process` 구현의 private `pid` 필드(리플렉션). 실패하면 -1.
- `destroy()` = `android.os.Process.sendSignal(pid, SIGTERM)`, `destroyForcibly()` = SIGKILL. `--kill-on-exit`가 게스트 트리를 정리한다.
- `onExit`: 대기 스레드가 종료를 보고, 출력 스레드를 최대 2초 기다린 뒤 한 번 부른다. 종료 뒤에 등록하면 새 스레드에서 바로 부른다.
- `ExitInfo.signal`: Java가 신호 종료를 `128 + 신호`로 돌려주므로 129..192면 `exitCode - 128`. SIGKILL = `ExitInfo(137, 9)`.
- `ProotExec.run`: `Dispatchers.IO`. 타임아웃이면 SIGKILL 후 `ExecResult(-1, timedOut = true, …)`. 코루틴 취소도 SIGKILL 후 다시 던진다. `lastErrorLine`은 마지막 비어 있지 않은 **stderr** 줄. `onLine`은 stdout·stderr 둘 다 받는다(출력 스레드에서 호출).

### SetupRunner

| 모드            | 단계                                              |
| --------------- | ------------------------------------------------- |
| `INSTALL`       | download → extract → configure → packages → paseo |
| `UPDATE_NODE`   | download(Node만) → extract(Node만) → paseo        |
| `UPDATE_DAEMON` | paseo                                             |

- `fromStep`이 null이거나 그 모드에 없는 이름이면 첫 단계부터.
- `download`: `INSTALL`이면 먼저 `/data` 여유 4 GB 확인(`no-space`). rootfs(sha256 고정) → Node(`SHASUMS256.txt`에서 해시를 읽어 검증). 검증된 파일이 캐시에 있으면 네트워크 없이 넘어가고, `.part`가 있으면 `Range`로 이어받는다. 진행률은 bytes, 두 파일을 이어 붙인 누적값(Node 크기는 받기 시작해야 알므로 total이 한 번 커진다).
- `extract`: `INSTALL`이면 rootfs를 지우고(링크를 따라가지 않음) tarball을 strip 1로 푼다. Node는 `rootfs/usr/local`에 strip 1. `UPDATE_NODE`는 rootfs를 건드리지 않고 이전 Node 파일(`bin/node npm npx corepack`, `include/node`, `lib/node_modules/{npm,corepack}`)만 지운 뒤 푼다. 전역 npm 패키지는 남는다. 이어서 `libtalloc.so.2` 복사, `/tmp`, `/root`, `/opt/paseo-phone` 생성.
- `configure` / `packages` / `paseo`: 실행 전에 `installScripts()`로 스크립트를 다시 복사하고 `/bin/bash /opt/paseo-phone/<script>`를 돌린다. env: `PASEO_DAEMON_VERSION`, `NODE_VERSION`, `PASEO_PHONE_SSH_PORT=2222`, `PASEO_PHONE_NODENAME`. 타임아웃 10 / 45 / 45분. `packages`는 `PASEO_PHONE_TOTAL=<n>` 줄과 `Setting up ` 줄 수로 `Progress(unit = "packages")`를 보낸다.
- 성공하면(`download`를 포함한 모드) 다운로드 캐시를 지운다. `state.json`은 쓰지 않는다(RuntimeStore 몫).
- 모든 단계 출력과 `=== step …` / `=== failed …` 줄이 `logs/setup.log`에 쌓인다.
- `run`은 `CancellationException` 말고는 던지지 않는다.

### 오류 코드 (`SetupResult.Failed.code`)

| code             | 언제                                                                                                      |
| ---------------- | --------------------------------------------------------------------------------------------------------- |
| `no-space`       | 사전 검사 4 GB 미만, 또는 예외 메시지에 `ENOSPC`/`No space left`                                          |
| `offline`        | `UnknownHostException`, `ConnectException`, `SocketTimeoutException`, `SSLException`                      |
| `checksum`       | sha256 불일치(부분 파일 삭제), `SHASUMS256.txt`에 Node 파일이 없음                                        |
| `command-failed` | 스크립트가 0이 아닌 값으로 끝남(메시지 = 마지막 stderr 줄, 없으면 `<script> exited with N`) 또는 타임아웃 |
| `unknown`        | 그 밖(예: 이전 단계 산출물이 없음 → "retry from download"). 메시지는 예외 메시지                          |

스크립트 안 apt·npm의 네트워크 실패는 구분할 수 없어 `command-failed`다.

## 아키텍처와 다른 점, 이유

1. **rootfs URL.** proot-distro 5.x는 tarball을 배포하지 않고 OCI 이미지를 받는다. trixie aarch64 tarball을 마지막으로 배포한 릴리스는 v4.29.0이고, proot-distro 4.34.2의 debian 플러그인도 이 파일을 가리킨다. 그래서 `debian-trixie-aarch64-pd-v4.29.0.tar.xz`(sha256 `3834a11c…7918`, 35,409,704 바이트, 상위 디렉터리 `debian-trixie-aarch64/` → strip 1)로 고정했다. 스파이크의 rootfs(proot-distro 5.10.3 설치)와 내용이 같다는 보장은 없다. QA가 패키지 설치와 데몬 기동을 이 rootfs로 다시 확인해야 한다.
2. **가짜 `/proc` 바인드는 실제 파일을 못 읽을 때만.** proot-distro와 같은 동작이다. 앱 uid에서 읽히는 파일은 실제 값을 쓴다. 파일 이름은 §2.4대로(`empty`, `loadavg`, `stat`, `uptime`, `version`, `vmstat`, `cap_last_cap`, `max_user_watches`, `overflowuid`, `overflowgid`). `/proc/stat`의 `cpuN` 줄은 `/proc/cpuinfo`의 CPU 번호에 맞춰 매번 다시 쓴다. 내용은 proot-distro 5.10.3과 같고(GPL-3이라 코드는 가져오지 않음), `vmstat`은 대표 항목만 남겼다.
3. **커널 버전 문자열**은 §2.4의 축약 대신 proot-distro 전체 값 `#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000`을 쓴다.
4. **proot 바이너리는 재빌드하지 않았다.** 이 PC에는 Windows NDK(26~30)만 있고 스크립트는 Linux NDK r29가 필요하다. 계약대로 Kai 산출물을 쓰고 출처 커밋과 sha256을 `PINS.md`에 적었다. `build-proot.sh`는 arm64 전용으로 줄였고 출력은 `../android/src/main/jniLibs`, 빌드 디렉터리 `proot/.build-native/`는 `proot/.gitignore`로 뺐다.
5. **TarExtractor 경로 검사.** 문자열 기준(`..` 거부, 앞의 `/`·`.` 제거)만 한다. 아카이브 둘 다 sha256이 고정돼 있어서 아카이브가 만든 심볼릭 링크를 따라 밖으로 쓰는 경우는 막지 않는다. 하드 링크는 Android가 `link(2)`를 막아서 Kai처럼 복사한다. 장치 파일(`c`, `b`)과 FIFO는 만들지 않는다(proot가 실제 `/dev`를 바인드). 권한 비트는 그대로 쓰되 소유자 rw(파일)·rwx(디렉터리)는 항상 켠다. setuid·sticky 비트는 남지 않는다.
6. **다운로드 캐시 삭제**를 SetupRunner가 성공 시 한다(§2.5 "성공하면 … 다운로드 캐시를 지운다"). `state.json` 기록은 하지 않는다.

## 다른 코어에게 필요한 것

**core-runtime** (`android/build.gradle`):

- `android.namespace = "io.github.ouor.paseophone.runtime"`, `buildFeatures.buildConfig = true`, `buildConfigField "String", "PASEO_DAEMON_VERSION"`, `buildConfigField "String", "NODE_VERSION"`. SetupRunner가 `io.github.ouor.paseophone.runtime.BuildConfig`를 import한다.
- `implementation "org.tukaani:xz:1.12"` — rootfs와 Node가 `.tar.xz`라서 필요하다(Kai와 같은 버전). Android에는 xz 디코더가 없다.
- kotlinx-coroutines (`useCoreDependencies()`로 들어오는지 확인 부탁), `testImplementation "junit:junit:4.13.2"`.
- 재설치에서 rootfs를 지울 때 `File.deleteRecursively()` 대신 `paths.deleteRootfs()`를 쓴다. 사용자가 rootfs 안에 `/storage/emulated/0/Paseo` 같은 실제 경로를 가리키는 링크를 만들면 `deleteRecursively`는 그 안을 지운다.
- 서비스 시작 때 스크립트 복사는 `SetupRunner(context, paths).installScripts()`를 부르면 된다.
- 데몬·sshd 실행: `ProotProcess.start(context, paths, ProotSpec(listOf("/bin/bash", "/opt/paseo-phone/run-daemon.sh"), env = …, exchangeDir = …), paths.logFile("daemon"))`. 로그 회전은 ProotProcess가 한다.

**core-rootfs**: `phone` 변형의 `useLegacyPackaging: true`가 있어야 `libproot.so`가 `nativeLibraryDir`에 풀린다(§4.3).

## 검증

- **단위 테스트**: `TarExtractorTest`(strip, pax `path`/`size`, GNU `L` 긴 이름, ustar prefix, 하드 링크, `..` 거부 후 스트림 정렬 유지, base-256 크기), `ProotCommandTest`(argv 순서·바인드·env, exchange 바인드, nodename, 가짜 stat/uptime 일치, SHASUMS 파싱, ExitInfo, 단계 목록, node_version.h). Gradle 대신 Gradle 캐시의 kotlin-compiler-embeddable 2.3.21 + android-36 `android.jar` + JUnit 4.13.2로 컴파일·실행해 14개 통과(Windows JVM 21).
- **실제 tarball**: WSL에서 `TarExtractor.extract`로 v4.29.0 rootfs를 풀어 GNU tar 결과와 파일 목록을 비교. 6,996개 경로 일치, 6,995 엔트리(장치 8개 제외). 심볼릭 링크(`bin -> usr/bin`)와 권한 비트 확인.
- **기기(devphone01, Android 16)**: Kai 바이너리 3개와 rootfs를 `/data/local/tmp`에 올리고 `ProotCommand.build`가 만든 argv 그대로 실행(shell uid). 결과: `uname -a` = `Linux m2012k11ag 6.17.0-PRoot #1 SMP PREEMPT_DYNAMIC … aarch64`, Debian 13, uid 0, 가짜 `/proc/loadavg`·`/proc/stat`·`cap_last_cap` 보임, `/root/exchange` 바인드에 쓰기 성공, `env -i` 환경 적용, `apt 3.0.3`, 종료 코드 0 전달. 올린 파일은 모두 지웠다.
- 확인 못 한 것: 앱 uid(untrusted_app SELinux)에서의 실행, `--kill-on-exit`·SIGTERM 정리, Node·데몬 실행(이 rootfs에서), `Downloader`의 실제 이어받기. 통합 빌드 후 QA 항목.

## 다음 라운드 메모

- Linux NDK r29가 있는 환경(WSL에 NDK 설치)에서 `build-proot.sh`로 재빌드해 Kai 산출물과 sha256을 비교하고 `PINS.md`를 갱신한다.
- 앱 uid에서 어떤 `/proc` 파일이 막히는지 로그로 확인해 가짜 파일 목록을 정리한다.
