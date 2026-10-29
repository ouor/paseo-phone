# core-linux 작업 기록

담당: proot 바이너리, rootfs 다운로드·압축 해제, proot 실행, 설치 단계 실행기. 기준 문서는 [architecture.md](../architecture/architecture.md) §2, §7.5, §8.3, [internal-contract.md](internal-contract.md). 리뷰 수정 배정은 [fix-plan.md](../code-reviews/fix-plan.md).

## 진행 상황

| 라운드                  | 내용                                                                                                                                                    | 상태                         |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| 1차 (2026-10-30)        | Kai 이식: proot 바이너리, `linux/`, `setup/SetupRunner`, 단위 테스트, 기기에서 §2.4 인자 확인                                                           | 완료                         |
| 1차 리뷰 수정 (L1~L12)  | `PhoneConstants`, `RootfsState`, staging extract, `Identity`, `ProotProcess.stop/killTree`, 스크립트 입구, `NODE_SHA256`, `build-proot.sh` 검증, 테스트 | 완료. 20개 테스트 통과(아래) |
| Gradle/APK, 기기 재확인 | 네 담당 머지 후 통합 빌드·QA                                                                                                                            | 대기                         |

## 공개 Kotlin API

internal-contract의 시그니처를 그대로 따른다. 계약에 없는 공개 멤버만 따로 적는다.

### 계약 밖 추가

```kotlin
// linux
object PhoneConstants { … ; const val DAEMON_LISTEN = "127.0.0.1:6767" }   // 계약 상수 + 조합값 하나
class LinuxPaths {
  val nativeLibDir: String
  fun ensureLayout()          // root, tmp, sysdata, logs, downloads 생성
  fun copyLibtalloc()         // libtalloc.so → root/libtalloc.so.2 (크기가 다르면 다시 복사)
  companion object {
    const val DAEMON_PACKAGE_JSON   // "usr/local/lib/node_modules/@getpaseo/cli/package.json"
    fun stateOf(rootfs: File): RootfsState
    fun deleteTree(dir: File)       // 링크를 따라가지 않는 삭제
  }
}
data class ExitInfo { companion fun fromExitCode(code) }
object RootfsSpec { DISTRO_LABEL = "Debian 13"; URL; SHA256; SIZE_BYTES; STRIP_COMPONENTS; ARCHIVE_NAME }
object NodeDist { archiveName(v); url(v); STRIP_COMPONENTS }
object Downloader { suspend download(url, target, sha256, onProgress); sha256Of(file) }
class ChecksumMismatchException : IOException
object TarExtractor { extract(archive, targetDir, strip): Int; extractTar(input, targetDir, strip): Int }
object ProotCommand { GUEST_PATH; nodename(model); build(…): ProotInvocation }
object FakeSysdata { write(sysdata, nodename); stat(..); uptime(..); hostCpuIds(..) }

// setup
class SetupRunner {
  suspend fun runScript(name, logFile, timeoutMs, onLine: (String) -> Unit = {})   // 계약 + 기본값 있는 onLine (packages 진행률용)
  companion object { stepsFor(mode); stepsFrom(mode, fromStep): List<String>?; parseNodeVersionHeader(text) }
}
```

`runScript`의 네 번째 인자는 기본값이 있어 계약대로 세 인자로 부를 수 있다. 내부 `packages` 단계가 진행률을 읽으려고 쓴다.

## 동작

### 설치 판정 (`rootfsState()`)

디스크만 본다. `rootfs/`가 없으면(링크도 없으면) `ABSENT`, `rootfs/usr/local/lib/node_modules/@getpaseo/cli/package.json`이 파일이면 `INSTALLED`, 그 밖은 `EXTRACTED`.

### ProotProcess / ProotExec

- 시작할 때마다 `ensureLayout()`, `copyLibtalloc()`, 가짜 `/proc` 파일 쓰기, `exchangeDir`이 있으면 `rootfs/root/exchange` 생성. 호출하는 쪽이 따로 준비할 것은 없다.
- argv는 §2.4. 앱 환경은 비우고 `PROOT_TMP_DIR`, `PROOT_LOADER`, `LD_LIBRARY_PATH`만 준다. 게스트 환경은 `env -i`로 새로 만든다: `HOME USER LOGNAME PATH LANG TERM TMPDIR PASEO_PHONE_NODENAME` + `spec.env`(덮어씀).
- stdin은 바로 닫는다. stdout·stderr는 줄 단위로 `logFile`에 쌓고 5 MB에서 `.1`로 회전한다. 두 출력 스레드가 끝나면(최대 2초 기다림) 로그 핸들을 닫는다. 닫힌 뒤 고아가 쓰는 줄은 버린다.
- `pid`: Android `Process` 구현의 private `pid` 필드. 실패하면 -1(정지는 `Process.destroy`로 대신한다).
- `stop(graceMs)` (§8.3, 블로킹): `/proc`에서 같은 uid의 proot 자손을 스냅샷(너비 우선, 자식 먼저) → 자손에 SIGTERM → `graceMs` 대기 → proot에 SIGTERM → 2초 → 첫 스냅샷 + 새 스냅샷과 proot에 SIGKILL. 끝나면 로그가 닫힐 때까지 최대 3초 기다린다. 앱 자신의 pid에는 신호를 보내지 않는다.
- `killTree()`: 지금 자손 + proot에 SIGKILL, 기다리지 않는다.
- `onExit`: 한 번, 백그라운드 스레드. 우리가 `stop`/`killTree`한 경우도 부른다. 종료 뒤 등록하면 새 스레드에서 바로 부른다.
- `ExitInfo.signal`: Java가 신호 종료를 `128 + 신호`로 주므로 129..192면 `exitCode - 128`. SIGKILL = `ExitInfo(137, 9)`.
- `ProotExec.run`: 타임아웃이면 `stop(5_000)` 후 `ExecResult(-1, timedOut = true, …)`. 취소면 `NonCancellable`로 `stop(5_000)` 후 다시 던진다. `lastErrorLine`은 마지막 비어 있지 않은 stderr 줄. `onLine`은 stdout·stderr 둘 다.

### SetupRunner

| 모드            | 단계                                              |
| --------------- | ------------------------------------------------- |
| `INSTALL`       | download → extract → configure → packages → paseo |
| `UPDATE_NODE`   | download(Node만) → extract(Node만) → paseo        |
| `UPDATE_DAEMON` | paseo                                             |

- `fromStep`이 null이면 첫 단계. 그 모드에 없는 이름(예: `"start"`)이면 아무것도 하지 않고 `Failed(step = fromStep, code = "unknown")`.
- `download`: `INSTALL`이면 먼저 여유 4 GiB 확인. 부족하면 `Failed(code = "no-space", message = "free 2.1 GB, need 4.0 GB", freeBytes, neededBytes)`. rootfs는 `RootfsSpec.SHA256`, Node는 `BuildConfig.NODE_SHA256`으로 검증한다(`SHASUMS256.txt`는 받지 않는다). 검증된 파일은 다시 받지 않고, `.part`는 `Range`로 이어받는다. 진행률은 두 파일 누적 bytes.
- `extract` (`INSTALL`): `rootfs/`가 있으면 `Failed(code = "unknown", message = "rootfs exists")`. 없으면 `rootfs.staging/`을 비우고 rootfs와 Node(`usr/local`)를 푼 뒤 `rootfs/`로 원자적 rename. `UPDATE_NODE`는 rootfs가 있어야 하고, 이전 Node 파일(`bin/{node,npm,npx,corepack}`, `include/node`, `lib/node_modules/{npm,corepack}`)만 지우고 `usr/local`에 다시 푼다. 전역 npm 패키지는 남는다. 두 경우 모두 이어서 다운로드 캐시 삭제 → `Identity.restoreIfPresent` → `libtalloc.so.2` → `installScripts()`.
- core-linux에는 `rootfs/`를 지우는 코드가 없다. `deleteTree`는 staging, identity, downloads, `UPDATE_NODE`의 Node 파일에만 쓴다.
- `configure`/`packages`/`paseo`: `installScripts()` 후 `runScript`. 타임아웃 10/45/45분. `packages`는 `PASEO_PHONE_TOTAL=<n>` 줄과 `Setting up ` 줄 수로 `Progress(unit = "packages")`.
- `scriptEnv()`: `PASEO_DAEMON_VERSION`, `NODE_VERSION`, `PASEO_PHONE_SSH_PORT=2222`, `PASEO_PHONE_DAEMON_LISTEN=127.0.0.1:6767`. `PASEO_PHONE_NODENAME`은 ProotProcess가 넣는다.
- 모든 단계의 출력과 `=== step/failed/cancelled …` 줄은 `logs/setup.log`.
- `run`은 `CancellationException`만 던진다.

### Identity (D16)

- `save`: rootfs의 `root/.paseo/server-id`, `etc/ssh/ssh_host_*`, `root/.ssh/authorized_keys` 중 **일반 파일**만 `identity/`로 복사(속성 유지, `identity/`는 0700). 없는 파일은 건너뛰고 이미 저장된 파일은 지우지 않는다. 그래서 재설치가 중간에 실패해 rootfs가 없는 상태에서 다시 `save`해도 저장분이 남는다.
- `restoreIfPresent`: `identity/`의 파일을 rootfs에 덮어쓰고 개인 키와 `authorized_keys`는 0600, `/root/.ssh`는 0700으로 맞춘 뒤 `identity/`를 지운다. `extract` 직후라 `packages`의 openssh-server postinst가 이 키를 그대로 둔다.

### 오류 코드 (`SetupResult.Failed.code`)

| code             | 언제                                                                                                                                 |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `no-space`       | 사전 검사 4 GiB 미만(`freeBytes`/`neededBytes` 채움, 메시지 `free 2.1 GB, need 4.0 GB`), 또는 예외 메시지에 `ENOSPC`/`No space left` |
| `offline`        | `UnknownHostException`, `ConnectException`, `SocketTimeoutException`, `SSLException`                                                 |
| `checksum`       | sha256 불일치(부분 파일 삭제)                                                                                                        |
| `command-failed` | 스크립트가 0이 아닌 값으로 끝남(메시지 = 마지막 stderr 줄) 또는 타임아웃                                                             |
| `unknown`        | 모르는 `fromStep`, `rootfs exists`, 이전 단계 산출물 없음, 그 밖의 예외                                                              |

스크립트 안 apt·npm의 네트워크 실패는 `command-failed`다. `cancelled`는 러너가 만들지 않는다(컨트롤러가 취소를 바꾼다).

## 아키텍처와 다른 점, 이유

1. **rootfs URL**: proot-distro 5.x는 tarball을 배포하지 않는다. trixie aarch64 tarball의 마지막 배포는 v4.29.0이라 그 파일로 고정했다(architecture D3에 반영됨).
2. **가짜 `/proc` 바인드는 실제 파일을 못 읽을 때만**(proot-distro와 같음, §2.4에 반영됨). 내용은 proot-distro 5.10.3 데이터, 코드는 가져오지 않음(GPL-3).
3. **proot 바이너리는 Kai 산출물**: 이 PC에 Linux NDK r29가 없다. 출처와 sha256은 `PINS.md`. `build-proot.sh`는 이제 `curl -f`, talloc sha256 확인(termux-packages의 2.4.3 값과 같음), NDK가 r29가 아니면 실패한다.
4. **TarExtractor**: 문자열 경로 검사만(아카이브 둘 다 sha256 고정). 하드 링크는 복사, 장치 파일·FIFO는 만들지 않음, 소유자 rw/rwx 항상 켬, setuid·sticky는 남지 않음.
5. **자손 스냅샷은 같은 uid만**: `/proc/<pid>/status`의 `Uid`로 거른다. Android가 다른 uid의 `/proc`를 숨기므로 사실상 같은 결과다.

## 다른 담당에게

**core-runtime**: `build.gradle`에 `BuildConfig.NODE_SHA256`(R12), `org.tukaani:xz:1.12`, `junit:junit:4.13.2`(test). 이전 API는 없어졌다: `isRootfsPresent()` → `rootfsState()`, `deleteRootfs()` → reinstall에서 `LinuxPaths.deleteTree(paths.rootfs)`(링크를 따라가지 않음), `destroy()`/`destroyForcibly()` → `stop(graceMs)`/`killTree()`, 로그 이름 `"sshd"` → `"ssh"`. 스크립트 실행은 `scriptSpec`/`runScript`.

**core-rootfs**: `useLegacyPackaging: true`(phone 변형)가 있어야 `libproot.so`가 `nativeLibraryDir`에 풀린다. 스크립트는 `PASEO_PHONE_DAEMON_LISTEN`을 받는다.

## 검증

- **단위 테스트 20개**: `TarExtractorTest`(strip, pax, GNU 긴 이름, ustar prefix, 하드 링크, `..` 거부, base-256), `ProotCommandTest`(argv·바인드·env, nodename, 가짜 stat/uptime, `/proc` stat·status 파싱과 자손 순서, ExitInfo), `RootfsIdentityTest`(`rootfsState` 세 경우, Identity 왕복과 재저장), `SetupRunnerTest`(`stepsFrom`과 모르는 단계, staging rename, `rootfs exists` 거부 시 사용자 파일 보존, Node 업데이트가 전역 패키지 유지, node_version.h). Gradle 없이 Gradle 캐시의 kotlin-compiler-embeddable 2.3.21 + android-36 `android.jar` + JUnit 4.13.2로 컴파일·실행(Windows JVM 21, `BuildConfig`는 스텁). POSIX 권한 검사는 Windows에서 건너뛴다.
- **1차 기기 확인(devphone01)**: `ProotCommand.build` argv 그대로 shell uid로 실행해 Debian 13, uid 0, 가짜 `/proc`, exchange 바인드, `env -i`, 종료 코드 전달 확인. 이번 라운드는 기기 확인 없음.
- 확인 못 한 것: 앱 uid에서의 실행, `stop()`의 실제 자손 정리(고아가 남지 않는지), `Downloader` 이어받기. 통합 빌드 후 QA 항목.

## 다음 라운드 메모

- Linux NDK r29 환경에서 `build-proot.sh`로 재빌드해 Kai 산출물과 sha256 비교, `PINS.md` 갱신.
- 앱 uid에서 막히는 `/proc` 파일을 로그로 확인.
- QA에서 Stop 직후 `ps -A -o PID,PPID,NAME | grep -E 'node|sshd|proot'`로 고아 여부 확인.
