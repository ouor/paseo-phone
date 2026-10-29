# 코드 리뷰: Android 네이티브 (paseo-phone-runtime)

- 범위: `packages/app/modules/paseo-phone-runtime/android/**`(Kotlin, build.gradle, AndroidManifest, 테스트), `proot/**`
- 기준: `v0.14.0..HEAD`, 브랜치 `feat/phone-dev-machine` (c751ea283)
- 방식: 읽기 전용. Gradle·기기 실행 없음. 각 항목은 실제 코드를 읽고 확인했다. "확인 필요"로 적은 것은 코드만으로 결론이 안 나는 부분이다.
- 경로 약어: `rt/` = `packages/app/modules/paseo-phone-runtime/android/src/main/java/io/github/ouor/paseophone/runtime/`

심각도 순서로 정렬했다.

---

## 1. [높음] 정지 중인 서비스를 다시 집어 써서 restartLinux·reinstall 직후 Linux가 죽는다

- 위치: `rt/RuntimeController.kt:178-182`(restartLinux), `:208-217`(reinstall), `:460`(stopLinux → shutdown), `:692-702`(ensureService), `rt/RuntimeService.kt:66-76`(onDestroy), `:132-137`(shutdown)
- 문제: `stopLinux()`는 `shutdown()`으로 `stopForeground` + `stopSelf()`를 부른다. `onDestroy`는 나중에 메인 스레드에서 돌고, 그 전까지 `RuntimeService.instance`는 죽어 가는 서비스를 가리킨다. 바로 이어지는 `startOrSetup()` → `ensureService()`는 `instance`가 null이 아니라서 새 서비스를 시작하지 않고 이 인스턴스를 돌려준다.
- 실패 시나리오:
  - restartLinux(교환 폴더 권한 뒤 재시작, c751ea283이 쓰는 경로): 새 daemon·sshd proot가 죽어 가는 서비스에 `track`된다. 곧 `onDestroy`가 그것들을 `destroyForcibly()`하고 wake/wifi lock을 풀고 `onServiceDestroyed()`를 부른다. 그러면 `linux = error("crashed", "The foreground service was stopped")`가 된다. 사용자는 "재시작"을 눌렀는데 Linux가 꺼진다.
  - reinstall: `runSetup`이 죽어 가는 서비스를 받아 30분짜리 설치를 FGS도 wake lock도 없이 진행한다. 앱을 나가면 프로세스가 죽는다.
  - 경쟁 구간은 `refreshEnv()`·`updateMode()`의 IO 몇 ms다. 메인 스레드가 RN 렌더로 바쁘면 이 경쟁에 자주 진다.
  - 덤: `processes`(일반 `mutableMapOf`)와 `wakeLock`은 이 경로에서 Default 스레드(`track`, `setKeepAwake`)와 메인 스레드(`onDestroy`)가 동시에 건드린다. `onDestroy`의 순회 중 `track`이 끼면 `ConcurrentModificationException`이 날 수 있다.
- 제안: `shutdown()`에서 즉시 `instance = null`, `started = CompletableDeferred()`로 바꾸고 "destroyed" deferred를 하나 둔다. `ensureService()`는 정지 중인 인스턴스가 있으면 그 `onDestroy`를 기다린 다음 새로 시작한다. 더 간단한 방법도 있다. restartLinux와 reinstall에서는 서비스를 내리지 않는다(`stopLinux`에 `keepService` 인자). `onServiceDestroyed`는 자기를 만든 인스턴스가 현재 인스턴스일 때만 상태를 바꾸게 한다.
- 소유: core-runtime

## 2. [높음] `/usr/bin/env` 하나만 없어져도 다음 앱 실행 때 확인 없이 rootfs 전체(사용자 /root 포함)를 지운다

- 위치: `rt/RuntimeController.kt:307-308`(startOrSetup), `:704`(isInstalled), `:151`(init 자동 start), `rt/linux/LinuxPaths.kt:35`(isRootfsPresent), `rt/setup/SetupRunner.kt:143`(extract의 deleteTree)
- 문제: `isInstalled() = data.installed && paths.isRootfsPresent()`이고 `isRootfsPresent()`는 `rootfs/usr/bin/env`가 일반 파일인지만 본다. 이 값이 false면 `startOrSetup()`이 `runSetup(INSTALL, …)`을 부르고, `extract`가 `LinuxPaths.deleteTree(paths.rootfs)`로 rootfs를 지운다. `desired == "running"`이면 init이 이 경로를 앱 실행 때마다 탭 없이 탄다.
- 실패 시나리오: 사용자가 Linux 안에서 coreutils를 잘못 지우거나 `/usr/bin`의 권한을 바꾼다(`chmod 000 /usr/bin`은 proot가 호스트 디렉터리를 실제로 바꾸므로 앱의 `isFile`이 false가 된다). 다음 앱 실행 → 자동 start → 다운로드 → extract → `/root`의 레포·에이전트 설정·키가 모두 사라진다. 사용자 확인도 경고도 없다. architecture §2.3이 말하는 "재설치 시 삭제"는 사용자가 reinstall을 고를 때를 가리킨다.
- 제안: `data.installed == true`인데 rootfs 검사가 실패하면 INSTALL로 가지 말고 오류 상태(예: `setup-failed`, `code: "unknown"`, "Linux가 손상됨, 재설치 필요")를 보여 주고 reinstall만 허용한다. 방어를 한 겹 더 두려면 `SetupRunner.extract`(core-linux)가 비어 있지 않은 rootfs를 지우기 전에 호출자의 명시적 플래그(예: `allowWipe`)를 요구하게 한다.
- 소유: core-runtime (주), core-linux (extract 가드)

## 3. [중간~높음, 확인 필요] SIGKILL 경로에서 proot 게스트 프로세스가 고아로 남을 수 있다

- 위치: `rt/RuntimeController.kt:622-630`(stopManaged), `rt/RuntimeService.kt:71`(onDestroy의 destroyForcibly), `rt/linux/ProotProcess.kt:100-106`(destroy/destroyForcibly), `rt/linux/ProotExec.kt:45`, `:52`
- 문제: `--kill-on-exit`는 proot가 종료 처리를 할 수 있을 때(SIGTERM 핸들러, 정상 종료) 게스트를 정리한다. SIGKILL은 잡을 수 없다. proot가 `PTRACE_O_EXITKILL`을 설정하지 않으면, tracer가 죽을 때 tracee는 분리되어 계속 돈다. 그런데 코드는 다음 경로에서 곧바로 SIGKILL을 쓴다.
  - `RuntimeService.onDestroy`: 항상 `destroyForcibly()`
  - 데몬 정지: SIGTERM을 proot가 아니라 supervisor에 보낸다. node-pty 터미널이나 에이전트 자식(claude, codex)이 남아 있으면 proot는 10초 안에 끝나지 않고, 그다음 SIGKILL을 받는다.
  - `ProotExec` 타임아웃: apt·npm 자식에게도 같은 문제가 생긴다.
- 실패 시나리오: 정지나 재시작 뒤에도 이전 `paseo daemon`이 127.0.0.1:6767을 잡고 있다. 새 데몬은 EADDRINUSE로 죽고 재시작 한도(`restart-limit`)에 걸린다. 반대로 `DaemonProbe.isResponding()`은 고아 데몬의 응답을 받아 `running`으로 보고한다. sshd 고아는 2222를 잡는다. 같은 앱 프로세스 그룹 안이라 앱이 죽을 때까지 남는다.
- 확인 방법: 기기에서 Stop 직후 `adb shell ps -A -o PID,PPID,NAME | grep -E 'node|sshd|proot'`. 4dba3af의 `src/tracee/event.c`에서 `PTRACE_O_EXITKILL` 사용 여부를 본다.
- 제안: 강제 종료 전에 항상 proot에 SIGTERM을 먼저 보낸다(`destroy()` → 짧은 대기 → `destroyForcibly()`). `onDestroy`도 같은 순서로 한다. SIGKILL 뒤에는 `/proc/*/stat`에서 ppid 체인이 proot pid로 이어지는 같은 uid 프로세스를 찾아 SIGKILL한다. 시작 전에 6767/2222가 이미 열려 있으면 `start-failed("port in use")`로 보고한다.
- 소유: core-linux (ProotProcess/ProotExec 종료 의미), core-runtime (Service·stopManaged 순서)

## 4. [중간] 재시작 대기 중에 Stop하면 막 띄운 proot가 추적 밖으로 샌다

- 위치: `rt/RuntimeController.kt:496-502`(launch의 `catch (e: Exception)`), `:593-598`(restartJob), `:613`(stopManaged의 `restartJob?.cancel()`)
- 문제: 크래시 백오프 뒤 `restartJob`이 `launch(m)`을 실행해 `io { ProotProcess.start(...) }` 안에 있을 때 사용자가 Stop하면 `stopManaged`가 `restartJob`을 취소한다. `withContext`는 블록이 끝나도 취소된 호출자에게 결과 대신 `CancellationException`을 던진다(prompt cancellation). `launch`의 `catch (e: Exception)`이 이 예외를 잡아 `start-failed`로 바꾼다. 방금 시작된 `process`는 어디에도 저장되지 않고 종료되지도 않는다.
- 실패 시나리오: 데몬이 크래시 루프를 도는 중에 사용자가 Stop을 누른다. UI는 stopped가 되고 FGS도 내려가지만 고아 proot 데몬이 6767을 잡은 채 남는다. 다음 Start에서는 3번과 같은 증상이 난다. 이어서 `m.state`를 error로 덮어써서 정지 직후 데몬 상태도 오류로 보인다.
- 제안: `io { }` 결과를 비취소 구간에서 받는다(`withContext(NonCancellable + Dispatchers.IO)`). 또는 `CancellationException`을 먼저 잡아 시작된 프로세스를 `destroyForcibly()`한 뒤 다시 던진다. `catch (e: Exception)`이 취소를 삼키지 않게 한다.
- 소유: core-runtime

## 5. [중간] 설치의 `start` 단계 실패는 기록되지 않아, 앱을 다시 열면 다운로드부터 전체 재설치한다

- 위치: `rt/RuntimeController.kt:343-347`(성공 시 `failedStep = null` 저장 후 startLinux), `:426-431`(start 실패 시 failedStep 미저장), `rt/setup/SetupRunner.kt:78`(SetupRunner 성공 시 다운로드 캐시 삭제)
- 문제: SetupRunner는 `paseo`까지 성공하면 캐시를 지운다. 컨트롤러는 `failedStep = null`을 저장한 뒤 `start`를 돈다. 첫 기동 데몬이 120초 안에 응답하지 않거나 크래시하면 `failSetup(…, "start")`만 하고 `installed=false, failedStep=null`이 남는다.
- 실패 시나리오: 같은 세션에서 Retry를 누르면 `start`만 다시 돈다. 하지만 그 전에 앱이 죽거나 재실행되면 init이 `runSetup(INSTALL, null)`을 부른다. 그러면 rootfs 삭제, 60MB 재다운로드, apt·npm 30분을 처음부터 다시 한다. architecture §2.5는 다운로드 캐시를 "설치 성공 시 삭제"한다고 했는데, 실제로는 `start` 성공 전에 지운다.
- 제안: `start` 실패 시 `failedStep = "start"`를 저장한다. `startOrSetup`은 `failedStep == "start"`이고 rootfs가 있으면 `startLinux(INSTALL)`로 간다(SetupRunner에 "start"를 넘기면 알 수 없는 단계로 보고 `download`부터 시작한다). 캐시 삭제는 컨트롤러가 `installed=true`를 저장한 뒤로 옮긴다.
- 소유: core-runtime (주), core-linux (캐시 삭제 시점)

## 6. [중간] Node 업데이트가 extract 이후에 실패해도 `canStartPrevious = true`라서 "이전 버전으로 시작"이 크래시 루프가 된다

- 위치: `rt/setup/SetupRunner.kt:149-154`(UPDATE_NODE extract가 기존 node를 먼저 지움), `rt/RuntimeController.kt:370-372`(failSetup의 canStartPrevious)
- 문제: `canStartPrevious = mode != INSTALL && isInstalled()`. UPDATE_NODE의 `extract`는 `bin/node`, `lib/node_modules/npm` 등을 지운 뒤 압축을 푼다. extract 도중(ENOSPC 등)이나 `paseo` 단계(새 Node ABI에서 node-pty 재설치 실패)에서 실패하면 이전 데몬은 그대로 있지 않다.
- 실패 시나리오: 업데이트 실패 화면에서 "이전 버전으로 시작" → `paseo` 바이너리가 node를 못 찾거나 ABI가 맞지 않는 네이티브 모듈을 불러 즉시 종료 → 백오프 재시작 5번 → `restart-limit`. architecture §2.5가 "이전 데몬은 그대로 있으므로"라고 전제한 것과 어긋난다.
- 제안: UPDATE_NODE는 `download` 단계 실패일 때만 `canStartPrevious = true`로 둔다. 또는 extract를 새 디렉터리에 푼 뒤 rename으로 바꾸고, 실패하면 원래대로 되돌린다.
- 소유: core-runtime (canStartPrevious), core-linux (extract 원자성)

## 7. [중간~낮음] 오래된 `paseo.pid`로 엉뚱한 같은 uid 프로세스(앱 자신 포함)에 SIGTERM을 보낼 수 있다

- 위치: `rt/RuntimeController.kt:623-625`, `rt/system/DaemonProbe.kt:46-52`
- 문제: 데몬이 SIGKILL로 죽으면(phantom killer, 앱 프로세스 종료, 3번의 강제 종료) `paseo.pid`가 남는다. 새 데몬이 pid 파일을 쓰기 전(시작 직후 Stop, 또는 restartDaemon)에 정지하면, 옛 pid가 재사용된 다른 프로세스에 `Os.kill(pid, SIGTERM)`이 간다. 같은 uid라 EPERM으로 막히지도 않는다. 대상은 앱 메인 프로세스, sshd proot, 사용자 SSH 셸일 수 있다. kill이 "성공"하므로 proot에는 `destroy()`도 가지 않는다.
- 실패 시나리오: 앱 재실행 직후 Linux가 starting일 때 Stop → 옛 supervisor pid가 지금 앱 프로세스 pid와 같음 → 앱이 SIGTERM으로 종료된다. 확률은 낮지만 증상을 진단하기 어렵다.
- 제안: SIGTERM 전에 그 pid가 데몬 proot의 자손인지(`/proc/<pid>/stat` ppid 체인) 확인한다. 아니면 pid 파일을 쓰지 말고 proot에 SIGTERM을 보낸다(`--kill-on-exit` 핸들러가 정리한다).
- 소유: core-runtime

## 8. [낮음] 시작 중(최대 120초)과 설치 중에는 Stop을 받지 않는다

- 위치: `rt/RuntimeController.kt:638-651`(accept의 busy), `:425`, `:479-485`(awaitDaemonReady)
- 문제: `start()`의 accept 블록은 `awaitDaemonReady()`까지 busy를 쥔다. 데몬이 응답하지 않으면 120초 동안 Stop·restartDaemon·알림 Restart가 모두 `busy`로 거부된다. 설치(최대 45분짜리 스크립트 단계 3개)도 취소할 방법이 없다. architecture §7.2의 "충돌하면 busy"를 지키지만, 사용자가 탈출할 길이 없다.
- 제안: 준비 대기는 accept 블록 밖(`watchDaemonReady`가 이미 하는 일)으로 빼서 busy를 일찍 푼다. Stop은 시작·설치 Job을 취소하는 예외 명령으로 둔다.
- 소유: core-runtime

## 9. [낮음] 설치 전 `desired = "stopped"`이면 상태가 `checking`에서 영원히 멈춘다

- 위치: `rt/RuntimeController.kt:147`, `:151`, `:449-453`
- 문제: `stop()`은 phase와 무관하게 `desired = "stopped"`를 저장한다(setup-failed 상태에서도). 설치 전이면 다음 실행에서 phase가 `Checking`으로 남고 자동 start도 하지 않는다. UI가 `checking`을 로딩으로 다루면 화면이 계속 돈다.
- 제안: 설치되지 않았으면 init에서 `SetupFailed`(마지막 failedStep) 또는 별도 "미설치" 상태로 내린다. 또는 `stop()`이 Ready가 아닐 때 desired를 바꾸지 않게 한다.
- 소유: core-runtime

## 10. [낮음] 일반 Paseo 변형에도 위험 권한과 proot가 병합된다

- 위치: `android/src/main/AndroidManifest.xml:2-10`, `android/build.gradle`, `packages/app/app.config.js:149-151`(autolinking에 phone 전용 제외 없음)
- 문제: 런타임 초기화는 패키지명으로 막지만(`RuntimeController.kt:138`), 모듈이 autolink되면 `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, specialUse 서비스, jniLibs(GPL proot), assets가 이 포크로 만드는 다른 변형 APK에도 들어간다.
- 제안: phone 변형이 아니면 `expo.autolinking.exclude`에 `paseo-phone-runtime`을 넣는다(app.config.js는 core-rootfs 소유이므로 그쪽에 요청).
- 소유: core-runtime (manifest), 조정: core-rootfs

## 11. [낮음] 로그 파일 핸들이 닫히지 않는다

- 위치: `rt/linux/ProotProcess.kt:144-173`(RotatingLogFile)
- 문제: `RotatingLogFile`은 `FileOutputStream`을 열고 회전할 때만 닫는다. 프로세스가 끝나도 닫지 않아 GC가 돌 때까지 fd가 남는다(StrictMode CloseGuard 경고). 시작할 때마다 `agent-instructions.sh`, 크래시 재시작, 설치 스크립트가 하나씩 쌓는다. 3번처럼 고아가 파이프를 쥐고 있으면 옛 펌프가 회전된 `.1`에 계속 쓴다.
- 제안: 두 펌프가 끝나면(wait 스레드의 `join` 뒤) `log.close()`를 부른다.
- 소유: core-linux

## 12. [낮음] authorized_keys 쓰기가 직렬화되지 않는다

- 위치: `rt/RuntimeController.kt:257-261`, `:676-687`(keys는 busy/confined 밖에서 IO로 실행), `rt/ssh/AuthorizedKeys.kt:47-54`
- 문제: add와 remove가 동시에 오면 둘 다 읽고-고치고-쓰기를 하면서 같은 `authorized_keys.tmp`를 쓴다. 갱신 하나가 사라지거나 rename이 실패해 `io-error`가 난다. fsync 없이 rename하는 점도 있다.
- 제안: `AuthorizedKeys`에 객체 단위 `synchronized`를 두거나 `keys {}`를 Mutex로 감싼다.
- 소유: core-runtime

## 13. [낮음] build-proot.sh가 talloc 소스를 검증하지 않는다

- 위치: `proot/build-proot.sh:126`
- 문제: `curl -sL`(`-f` 없음)로 받고 sha256을 확인하지 않는다. HTTP 오류 페이지를 받으면 tar 단계에서야 실패하고, 변조된 tarball은 그대로 빌드된다. NDK 검색도 r29가 없으면 최신 NDK로 조용히 넘어간다(`:72`). 그러면 PINS.md와 다른 산출물이 나온다.
- 제안: `curl -fsSL`과 PINS.md에 적은 talloc sha256 확인을 넣는다. NDK가 r29가 아니면 실패하거나 경고한다.
- 소유: core-linux

---

## 확인했고 문제없던 것

- rootfs 삭제가 링크를 따라가는지: `LinuxPaths.deleteTree`(`rt/linux/LinuxPaths.kt:62-91`)는 `walkFileTree` 기본 옵션(NOFOLLOW)이라 링크는 unlink만 한다. 시작 경로가 링크여도 링크만 지운다. 교환 폴더는 proot `--bind`(경로 번역)라 호스트에서 지울 때 보이지 않는다. 공유 저장소로 새어 나가지 않는다.
- `state.json`: `AtomicFile`로 쓰고 읽는다(`rt/RuntimeStore.kt`). 원자성은 문제없다(단, 2번처럼 손상·불일치를 "미설치"로 해석하는 위험은 남는다).
- FGS: `specialUse` 타입, `FOREGROUND_SERVICE_SPECIAL_USE` 권한, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, SDK 34 분기 모두 맞다. `dataSync`를 쓰지 않아 Android 15의 6시간 제한도 없다.
- POST_NOTIFICATIONS: 거부되어 있어도 FGS는 돈다. 나중에 허용하면 `repostIfAllowed`가 다시 올린다(cd0023698).
- 다운로드: `.part`에서 이어받기, 200 응답이면 처음부터, 416이면 sha256으로 판정, 불일치 시 삭제, `Accept-Encoding: identity`까지 맞다. TarExtractor는 pax/GNU 긴 이름, `..` 거부, 심볼릭 링크 위에 쓰지 않기를 처리한다.
- 레이어: `linux/**`, `setup/**`, `ssh/**`, `exchange/**`, `system/**`, `notify/**`는 상위(`RuntimeController`, `RuntimeService`)를 import하지 않는다. `SetupRunner`의 `BuildConfig` 의존은 모듈 빌드 상수라 허용 범위다. 내부 계약(`internal-contract.md`)의 시그니처는 그대로 지켜졌다.
