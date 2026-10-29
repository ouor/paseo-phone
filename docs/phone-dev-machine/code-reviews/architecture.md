# 코드 리뷰: 구조 (포크 전체 diff)

> 리뷰: 아키텍트 · 2026-10-30 · 기준 `v0.14.0..HEAD` (`c751ea283`, `feat/phone-dev-machine`)
> 기준 문서: [architecture.md](../architecture/architecture.md), [internal-contract.md](../core/internal-contract.md), 팀 노트 [core-linux](../core/core-linux.md) · [core-rootfs](../core/core-rootfs.md) · [core-runtime](../core/core-runtime.md) · [ui](../ui/ui.md)
> 범위: 줄 단위 버그가 아니라 구조. 단, 구조 결함이 데이터 손실로 이어지는 경우는 경로를 코드로 확인하고 적었다. 코드는 고치지 않았다. Gradle·기기는 돌리지 않았다.

## 요약

전체 모양은 계획과 거의 같다. 업스트림 파일 수정은 계획대로 3개(`app.config.js`, `_layout.tsx`, `host-page.tsx`)이고, 나머지는 모두 새 트리라서 업스트림 머지 충돌 면적은 작다. Kotlin 하위 패키지 사이에 위로 가는 의존은 없고, UI는 모듈 `index.ts`만 import한다.

문제는 세 군데 몰려 있다.

1. **"설치됨" 판정이 `state.json` 한 필드에 달려 있고, 그 값이 틀리면 rootfs(= 사용자의 `/root` 전체)를 지우는 경로로 간다** (1, 2번).
2. **같은 사실을 여러 곳이 따로 들고 있다.** 포트, 경로, 스크립트 env, 알림 딥링크 경로, `no-space` 메시지 형식, 그리고 `types.ts`와 Kotlin의 손으로 만든 `Map`. 컴파일러가 잡아 주지 않는 계약이다 (3~6번).
3. **업스트림 암묵적 계약 목록이 UI 쪽 의존을 빠뜨렸다.** 타입 검사가 잡지 못하는 의미상 계약(라우트 모양, 5초 타이머, i18n 로딩, 부트스트랩 중복 등록 규칙)이 특히 위험하다 (7~9번).

## 우선순위 표

| #   | 항목                                                                | 영향                                    | 담당                         |
| --- | ------------------------------------------------------------------- | --------------------------------------- | ---------------------------- |
| 1   | 첫 설치의 `start` 실패 후 재실행하면 처음부터 다시 받고 rootfs 삭제 | 데이터 손실·재다운로드                  | core-runtime, core-linux     |
| 2   | `state.json`이 없거나 깨지면 기존 rootfs를 지우고 재설치            | 데이터 손실                             | core-runtime                 |
| 3   | `types.ts` ↔ Kotlin `Map` 스냅샷이 손으로 맞춘 이중 정의            | 계약 드리프트                           | core-runtime                 |
| 4   | 상수·스크립트 env·스크립트 실행이 여러 곳에 중복                    | 업스트림·핀 변경 시 누락                | core-runtime, core-linux     |
| 5   | UI가 코어의 영어 오류 문장을 정규식으로 파싱(`no-space`)            | 누설된 계약                             | core-linux, core-runtime, ui |
| 6   | 알림 딥링크 경로·쿼리가 Kotlin 문자열과 TS 파서에 따로 있음         | 업스트림 라우트 변경 시 조용히 깨짐     | core-runtime, ui, architect  |
| 7   | §4.3 암묵적 계약 목록에 UI의 업스트림 내부 의존이 빠짐              | 머지 체크리스트 구멍                    | architect                    |
| 8   | 재설치하면 `server-id`가 바뀌어 기존 호스트 항목과 어긋남           | UX·이 기기 판정 깨짐                    | core-runtime, architect      |
| 9   | Kotlin 단위 테스트가 어떤 CI에도 걸려 있지 않음                     | 업스트림 Expo 업그레이드 후 회귀 무감지 | core-runtime, architect      |
| 10  | `RuntimeController.kt` 870줄, 책임 다섯 개                          | 유지보수                                | core-runtime                 |
| 11  | `state.json` 버전 필드·`rootfsSha256` 없음, 중복 필드               | 장래 마이그레이션                       | core-runtime, architect      |
| 12  | `/root/.paseo/config.json` 소유권이 문서에 없음                     | 업스트림 config 변경 시 기존 설치 방치  | architect                    |
| 13  | `src/phone/format.ts`와 UI 파일 구성                                | 작음                                    | ui, architect                |
| 14  | `app.config.js` 수정 표시가 grep 가능하지 않음                      | 작음                                    | core-rootfs                  |
| 15  | 문서 드리프트 (architecture.md, ui.md, internal-contract.md)        | 기준 문서 신뢰도                        | architect, ui                |

---

## 1. 첫 설치 `start` 단계 실패 → 재실행 시 처음부터, rootfs 삭제

**위치**

- `RuntimeController.kt:343-346` `runSetup`: 러너가 성공하면 `failedStep = null`을 저장하고 `startLinux(mode)`로 간다.
- `RuntimeController.kt:425-431` `startLinux(setupMode)`: 데몬이 120초 안에 응답하지 않으면 `failSetup(setupMode, "start", …)`. `failedStep`은 쓰지 않으므로 영속 값은 `null`이고, `Phase.SetupFailed(step = "start")`는 메모리에만 있다. `installed`도 여전히 `false`다.
- `RuntimeController.kt:150-151, 307-308`: 앱을 다시 켜면 자동 `start()` → `!isInstalled()` → `runSetup(INSTALL, null)` → `download`부터.
- `SetupRunner.kt:78`: 이전 성공 때 다운로드 캐시는 이미 지웠다. `SetupRunner.kt:143`: `extract`는 `rootfs`를 통째로 지운다.
- 같은 계열의 잠재 위험: `SetupRunner.kt:59-61`은 모드에 없는 `fromStep`(예: `"start"`)을 받으면 `coerceAtLeast(0)`로 조용히 `download`부터 돈다.

**문제.** 첫 설치는 `data.installed = true`를 `start` 단계(데몬 HTTP 응답)가 성공한 뒤에만 쓴다(`RuntimeController.kt:432-439`). 그래서 `download~paseo`가 끝나고 데몬만 120초 안에 안 뜬 기기(느린 기기, 첫 기동 때 무거운 작업)는 앱을 다시 켜면 `installed = false` → `INSTALL` 처음부터 → 약 60 MB 재다운로드 → rootfs 삭제 → apt·npm 재설치(약 6분)가 된다. `retrySetup`은 `startLinux`만 다시 하므로 안전하지만, 앱을 껐다 켜는 순간 이 정보가 사라진다. 또 `"start"`는 `SetupStep`(types.ts)과 컨트롤러에는 있지만 `SetupRunner.stepsFor`에는 없는 이름이라, 나중에 누군가 `failedStep = "start"`를 저장하도록 고치면 러너는 조용히 첫 단계로 돌아간다.

**포크에 왜 중요한가.** "설치 완료"의 뜻이 두 사람 코드에 나뉘어 있다. 러너는 "`paseo`까지 성공", 컨트롤러는 "데몬이 한 번 응답". 업스트림 데몬이 무거워져 첫 기동이 느려지면(업스트림 릴리스마다 일어날 수 있는 변화) 이 경로가 재설치 루프가 된다. 그리고 "모르는 단계 이름은 처음부터"라는 러너의 관대한 규칙이 파괴적인 `extract`와 만나 있다.

**수정**

- core-runtime: `SetupResult.Success`를 받으면 그 자리에서 rootfs 완성을 기록한다(아래 2번처럼 디스크에서 판정하면 기록 자체가 필요 없다). `start` 실패는 "설치됨 + 시작 실패"로 다룬다. 재실행 시 `startOrSetup`이 `startLinux(…)`로 바로 가야 한다.
- core-linux: `SetupRunner.run`은 모드에 없는 `fromStep`을 받으면 첫 단계로 돌지 말고 `Failed(code = "unknown")`를 돌려준다. `extract`(rootfs 삭제)로 가는 길은 명시적인 값에서만 열려야 한다.
- 둘 다: 이 시나리오(첫 설치 `start` 실패 → 앱 재실행)를 QA 항목에 넣는다.

**담당** core-runtime(컨트롤러), core-linux(러너 규칙).

## 2. `state.json`이 없거나 깨지면 기존 rootfs를 지우고 재설치

**위치** `RuntimeStore.kt:20-37`(어떤 예외든 `Data()` 반환, `installed = false`), `RuntimeController.kt:704` `isInstalled() = data.installed && paths.isRootfsPresent()`, `RuntimeController.kt:150-151`(자동 `start()`), 위 1번의 `extract`.

**문제.** 설치 여부의 유일한 근거가 `state.json`의 불리언이다. 파일이 없거나(사용자가 손으로 지움, 파일 시스템 오류, 앞으로의 스키마 변경 실수) 파싱이 실패하면 rootfs가 멀쩡히 있어도 미설치로 보고, 첫 실행 규칙(`desired` 기본값 `running`)에 따라 버튼 없이 재설치를 시작한다. 재설치의 `extract`는 사용자의 `/root`(레포, 에이전트 로그인 정보, `~/.paseo`)를 지운다. `AtomicFile`이라 쓰다 깨질 확률은 낮지만, 실패했을 때 결과가 가장 나쁜 쪽으로 열려 있다.

**포크에 왜 중요한가.** `state.json`은 포크가 앞으로 계속 필드를 더할 파일이다(11번). 스키마 실수 하나가 사용자 작업 공간 전체 삭제로 이어지는 구조는 오래가는 포크에서 받아들일 수 없다. 또 `daemonVersion`/`nodeVersion`도 디스크(`installedDaemonVersion()`, `installedNodeVersion()`)에서 이미 읽을 수 있어서, 같은 사실을 두 곳이 들고 있다.

**수정** (가장 작은 것)

- "설치됨"은 디스크에서 판정한다: `paths.isRootfsPresent() && runner.installedDaemonVersion() != null`(데몬 패키지가 있으면 `paseo` 단계가 한 번 끝난 것). `state.json`의 `installed` 필드는 지운다.
- `RuntimeStore.load()`가 실패했고 rootfs가 있으면 자동 시작하지 않고 `ready(stopped)`로 둔다. 파괴적인 동작(`reinstall`, `INSTALL`의 `extract`)은 rootfs가 없을 때나 사용자가 Reinstall을 눌렀을 때만.
- architecture §2.3/§7.1에 "rootfs 삭제는 사용자 명령 또는 rootfs 부재일 때만"을 불변식으로 적는다(architect).

**담당** core-runtime(구현), architect(불변식 문서화).

## 3. `types.ts` ↔ Kotlin 스냅샷 `Map`의 이중 정의

**위치** `src/types.ts`(UI 계약), `RuntimeController.kt:804-867` `buildSnapshot`/`readySnapshot`, `RuntimeController.kt:60-65` `Svc.toMap`, `PhoneRuntimeModule.kt`(그대로 전달), `index.ts`(검증 없이 캐스팅).

**문제.** `PhoneRuntimeState` 모양을 Kotlin이 문자열 키 `Map`으로 다시 만든다. 키 이름, 판별자 값(`"setup-failed"`, `"restart-required"`), 숫자 타입(`toDouble()`), 리터럴(`listen: "127.0.0.1:6767"`, `port: 2222`)이 두 언어에 따로 있고, 둘을 맞추는 테스트가 없다. JS 쪽은 `native.getState()` 결과를 그대로 `PhoneRuntimeState`로 믿는다.

**포크에 왜 중요한가.** 이 계약은 2차, 3차 사이클에서 가장 자주 바뀔 곳이다(디자인 질문, 새 경고, 새 상태). 키 오타 하나는 컴파일·typecheck 모두 통과하고 화면에서만 "값 없음"으로 보인다. 코어와 UI가 다른 사람이라 발견이 늦다.

**수정**

- core-runtime: 스냅샷 생성을 순수 함수(`StateSnapshot.kt`, 입력은 `Phase`·`Svc`·`Env`·`Data`)로 빼고, JVM 테스트에서 대표 상태 몇 개(`setup` 다운로드 중, `setup-failed`, `ready` 실행 중·멈춤)를 JSON으로 직렬화해 `src/__fixtures__/state-*.json`으로 저장한다.
- ui(또는 core-runtime의 TS 쪽): 같은 fixture를 vitest에서 읽어 `satisfies PhoneRuntimeState` 수준으로 확인하는 테스트 하나(`modules/paseo-phone-runtime/src/types.test.ts`). 런타임 검증(zod)은 필요 없다. fixture가 양쪽 테스트의 공통 근거가 되면 된다.
- 이 작업은 10번(컨트롤러 분리)과 같이 한다.

**담당** core-runtime.

## 4. 상수·스크립트 env·스크립트 실행의 중복

**위치와 중복된 값**

| 사실                           | 들고 있는 곳                                                                                                                                                               |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SSH 포트 2222                  | `RuntimeController.kt:48`, `SetupRunner.kt:185`(문자열), `assets/sshd_config`의 `Port 2222`, `run-sshd.sh` 기본값, `types.ts` 리터럴                                       |
| 데몬 주소 `127.0.0.1:6767`     | `RuntimeController.kt:49`, `DaemonProbe.kt:9-10`, `paseo.sh` config, `scripts/phone/build-apk.sh`의 `EXPO_PUBLIC_LOCAL_DAEMON`, `types.ts` 리터럴                          |
| `/root/exchange`               | `ExchangeFolder.LINUX_PATH`, `ProotCommand.EXCHANGE_GUEST_PATH`, `ProotProcess.kt:42`(`"root/exchange"`), `types.ts`, UI `exchange-section.android.tsx:19`(`"~/exchange"`) |
| `"Debian 13"`                  | `RootfsSpec.DISTRO_LABEL`(안 쓰임), `RuntimeController.kt:851`(하드코딩)                                                                                                   |
| `/opt/paseo-phone`             | `RuntimeController.kt:54`, `SetupRunner.kt:99,165`                                                                                                                         |
| 스크립트 env                   | `RuntimeController.spec()`(`PASEO_DAEMON_VERSION`, `NODE_VERSION`, 포트) vs `SetupRunner.scriptEnv()`(+`PASEO_PHONE_NODENAME`). `ProotProcess`도 NODENAME을 넣는다         |
| 스크립트 실행 방법             | `SetupRunner.script()`와 `RuntimeController.startLinux()`의 `ProotExec.run(… "agent-instructions.sh" …)`                                                                   |
| `$PASEO_HOME` = `/root/.paseo` | `DaemonProbe.kt:40,48`, `paseo.sh`, `run-daemon.sh` 주석                                                                                                                   |
| 패키지 이름·scheme             | `RuntimeController.kt:55` `PHONE_PACKAGE`, `RuntimeNotification.kt:17` `SCHEME` vs `app.config.js` `phone` 변형                                                            |

**문제.** 포트 하나를 바꾸려면 다섯 곳을 고쳐야 하고, 하나를 빠뜨려도 컴파일은 된다. 컨트롤러가 `ProotExec`로 스크립트를 직접 돌리면서 "rootfs 안 스크립트 실행"이라는 책임이 `setup`과 컨트롤러 둘에 있다(internal-contract는 스크립트 실행 규약을 하나로 정했는데 구현은 둘). env가 달라서 `agent-instructions.sh`를 컨트롤러에서 부르면 `PASEO_PHONE_NODENAME`이 `ProotProcess` 덕분에 우연히 들어간다.

**포크에 왜 중요한가.** 업스트림이 기본 데몬 포트·`PASEO_HOME` 구조·`paseo.pid` 형식을 바꾸면 고칠 곳을 찾기가 어렵다. Termux 같은 다른 앱과 포트 충돌로 2222를 바꿔야 하는 날도 온다(architecture §5.1이 이미 8022 충돌을 겪었다).

**수정** (새 추상화 없이)

- core-linux: `SetupRunner`에 `fun scriptSpec(name: String, exchangeDir: File? = null): ProotSpec`와 `suspend fun runScript(name, timeoutMs): ExecResult`를 공개한다. env는 여기 한 곳(`scriptEnv`)에서 만든다. 포트 상수는 `SetupRunner`(또는 `RootfsSpec`)의 `const val SSH_PORT = 2222` 하나.
- core-runtime: `spec()`, `SCRIPT_DIR`, `SSH_PORT`, `LISTEN`, `"Debian 13"`을 지우고 위 함수·`RootfsSpec.DISTRO_LABEL`·`DaemonProbe.HOST/PORT`·`ExchangeFolder.LINUX_PATH`를 쓴다. `ProotProcess.kt:42`는 `ProotCommand.EXCHANGE_GUEST_PATH`에서 경로를 만든다(core-linux).
- 셸·sshd_config 쪽은 Kotlin과 공유할 수 없으니 그대로 두고, `sshd_config`의 `Port`와 `run-sshd.sh -p`가 둘 다 있는 것 중 하나를 지운다(`-p`가 이기므로 `sshd_config`의 `Port` 줄 삭제, core-rootfs).
- `PHONE_PACKAGE`·`SCHEME`은 `app.config.js`에서 `BuildConfig`로 넘길 방법이 없으니 두 Kotlin 상수 옆에 `app.config.js variants.phone`을 가리키는 한 줄 주석을 두고, §4.3 암묵적 계약 표에 올린다(architect).

**담당** core-linux, core-runtime, core-rootfs(sshd_config 한 줄).

## 5. `no-space` 메시지를 UI가 정규식으로 파싱

**위치** `SetupRunner.kt:117-120`(`"free 2.1 GB, need 4.0 GB"`), `src/phone/format.ts:54-57` `parseNoSpaceMessage`, `ui.md` §6 요청 3("바꾸면 알려 달라").

**문제.** types.ts는 `message`를 "원문, 번역 안 함"이라고 정의했는데 UI가 그 원문의 영어 형식에 기대고 있다. 계약 밖 의존이고, 형식이 바뀌어도 아무 테스트도 깨지지 않는다(UI는 조용히 일반 문구로 떨어진다). architecture Q12는 이미 "런타임이 주는 `free`/`needed` 숫자"를 약속했다.

**수정** `setup-failed`에 선택 필드 `space?: { freeBytes: number; neededBytes: number }`를 더한다(types.ts, core-runtime). `SetupResult.Failed`에 같은 값을 실을 자리를 만든다(core-linux). UI는 숫자를 `formatMegabytes`로 그리고 `parseNoSpaceMessage`를 지운다(ui). internal-contract의 `SetupResult.Failed` 시그니처를 먼저 고친다(architect).

**담당** core-linux → core-runtime → ui, 문서는 architect.

## 6. 알림 딥링크: 경로와 쿼리가 두 언어에 따로 있음

**위치** `RuntimeNotification.kt:113-118`(`"settings/hosts/<id>/host"`, `logs=daemon`, `confirmStop=1`), `src/phone/format.ts:59-74` `readPhoneNotificationIntent`, UI는 `buildSettingsHostSectionRoute(serverId, "host")`(`utils/host-routes.ts:568`)를 쓴다.

**문제.** 같은 라우트를 UI는 업스트림 빌더로, Kotlin은 문자열로 만든다. Kotlin 쪽은 업스트림 `app/settings/hosts/[serverId]/index.tsx`가 `/connections`로 리다이렉트하며 쿼리를 버린다는 사실, 그리고 섹션 slug가 `host`라는 사실에 기대고 있다(core-runtime.md §2.5 각주). architecture §7.4의 URL(`…/hosts/<serverId>`, 서버 ID 없을 때 `paseo-phone://?confirmStop=1`)은 이미 코드와 다르다.

**포크에 왜 중요한가.** 업스트림이 설정 라우트를 바꾸면(이 영역은 v0.14 사이에도 바뀌었다) UI는 typecheck에서 깨져 알 수 있지만, Kotlin 문자열은 알림을 눌렀을 때만 틀린 화면으로 간다.

**수정**

- architect: §7.4 URL을 코드대로 고치고, §4.3 암묵적 계약 표에 "`settings/hosts/[serverId]/[hostSection]` 라우트와 `host` slug, index의 리다이렉트"를 `utils/host-routes.ts`·`app/settings/hosts/[serverId]/`와 함께 올린다. 확인 방법 열에 "알림 탭 수동 확인"을 적는다.
- core-runtime: `RuntimeNotification.hostPath` 옆에 `host-routes.ts` 빌더를 가리키는 주석. 쿼리 키 두 개는 `types.ts`에 `export const PHONE_NOTIFICATION_QUERY = { confirmStop: "confirmStop", logs: "logs" } as const`로 두고 Kotlin 주석에서 그 위치를 가리킨다. UI의 `readPhoneNotificationIntent`가 이 상수를 쓴다(ui).

**담당** architect, core-runtime, ui.

## 7. §4.3 암묵적 계약 목록이 UI의 업스트림 내부 의존을 빠뜨림

**위치** architecture §4.3 표, `src/phone/*.android.tsx` import 목록.

**문제.** §4.3은 호스트 부트스트랩·config 키·데몬 파일만 적었다. 실제 UI는 업스트림 내부를 넓게 쓴다. 타입 검사로 잡히는 것과 안 잡히는 것을 나누면:

| 의존 (위치)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | 업스트림이 바꾸면                                 |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| `@/components/settings`, `ui/{alert,button,switch,status-badge,segmented-control,form-field,text-input,scrollable-code-surface,loading-spinner}`, `adaptive-modal-sheet`, `@/usage/meter` `UsageMeter`, `@/styles/{settings,code-surface,theme}`, `@/utils/confirm-dialog`, `@/data/query` `useFetchQuery`, `@/hooks/{use-stable-event,use-latched-boolean}`, `@/contexts/{toast-api-context,sidebar-callout-context}`, `@/stores/session-store`, `@/runtime/host-runtime` `useHosts`/`useHostRuntimeIsConnected`, `@/screens/startup-splash-screen`, `@/utils/host-routes` | typecheck가 잡는다                                |
| `_layout.tsx:244` `STARTUP_GIVE_UP_TIMEOUT_MS = 5_000`과 그 뒤 Welcome으로 보내는 동작 (게이트가 60초 스플래시로 막는 이유)                                                                                                                                                                                                                                                                                                                                                                                                                                                 | **조용히 깨진다**                                 |
| i18next 백엔드의 지연 로드·얕은 병합 동작 (`src/phone/i18n/register.ts` 주석)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | **조용히 깨진다**(`register.test.ts`가 일부 잡음) |
| `host-runtime.ts:1812-1853` `bootstrapConfiguredOverride`가 같은 연결이 레지스트리에 있으면 건너뜀                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | **조용히 깨진다**(8번)                            |
| 알림 라우트 (6번)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | **조용히 깨진다**                                 |
| `useSessionStore` 에이전트 상태 의미 (Stop 확인의 실행 중 수)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | 의미만 바뀌면 조용히                              |
| `scripts/build-apk.mjs`의 단계 순서·env (`scripts/phone/build-apk.sh`가 bash로 복제)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | **조용히 어긋난다**                               |
| Expo 자동 링크가 `packages/app/modules/`를 훑는 동작, `ExpoModulesCorePlugin.gradle`의 `applyKotlinExpoModulesCorePlugin`/`useCoreDependencies`/`useDefaultAndroidSdkVersions`, `AsyncFunction … Coroutine` 문법                                                                                                                                                                                                                                                                                                                                                            | Gradle 빌드에서 잡힘                              |
| `.tool-versions`의 `nodejs`, `packages/server/package.json`의 `version` (머지만으로 기기에서 `UPDATE_NODE`/`UPDATE_DAEMON`이 돈다)                                                                                                                                                                                                                                                                                                                                                                                                                                          | 의도된 동작, 알고 있어야 함                       |

**포크에 왜 중요한가.** §4.3은 "머지 체크리스트"로 쓰인다(§10.1). 목록에 없으면 머지 때 아무도 보지 않는다. 조용히 깨지는 행이 바로 머지 후 QA가 손으로 확인해야 하는 행이다.

**수정** architect: §4.3 암묵적 계약 표에 위 "조용히" 행들을 넣고 "확인 방법" 열(typecheck / Gradle / vitest 파일 / 수동 확인 항목)을 더한다. typecheck가 잡는 import 목록은 행 하나("`src/phone/**`의 `@/` import — typecheck")로 묶는다. `docs/phone-dev-machine/`에 머지 절차 문서를 따로 만들지 말고 이 표를 체크리스트로 쓴다.

**담당** architect.

## 8. 재설치하면 `server-id`가 바뀐다

**위치** `RuntimeController.kt:208-217` `reinstall`(rootfs 전체 삭제 → `/root/.paseo/server-id` 삭제), `DaemonProbe.readServerId`, `host-runtime.ts:1812-1818`(`registryHasConnection`이면 부트스트랩 건너뜀), UI 판정 `serverId === state.daemon.serverId`(`host-sections.android.tsx`).

**문제.** 재설치 후 데몬은 새 `server-id`를 만든다. 앱 레지스트리에는 `127.0.0.1:6767` 연결을 가진 옛 호스트 항목이 이미 있으므로 부트스트랩은 새로 등록하지 않는다. 그 뒤 옛 항목이 새 데몬에 붙었을 때 업스트림이 serverId 불일치를 어떻게 처리하는지는 이 리뷰에서 확인하지 못했다. 어느 쪽이든 폰 섹션(Linux, Remote access, …)은 `serverId` 비교로 그려지므로 옛 항목의 Host 페이지에서 사라질 수 있다. 데스크톱 Paseo의 Remote SSH 호스트도 serverId로 묶여 있다면 같은 문제가 생긴다.

**포크에 왜 중요한가.** 재설치는 rootfs 버전 업그레이드의 유일한 방법이다(§10.2). 즉 정기적으로 일어날 동작이다.

**수정** (가장 작은 것) core-runtime: `reinstall` 전에 `rootfs/root/.paseo/server-id`를 `filesDir/phone-runtime/server-id`로 복사해 두고, `paseo` 단계 전에(또는 `startLinux` 전에 파일이 없을 때) 되돌린다. 이것은 `$PASEO_HOME/server-id` 형식(한 줄 텍스트)에 대한 새 암묵적 계약이므로 architect가 §4.3에 이미 있는 `server-id` 행에 "쓰기도 함"을 더한다. 보존하지 않기로 한다면 그 결정과 사용자 안내(옛 호스트 삭제)를 architecture §7.2 `reinstall` 설명에 적는다.

**담당** core-runtime, architect. QA: 재설치 후 호스트 목록과 데스크톱 연결 확인.

## 9. Kotlin 단위 테스트가 어떤 CI에도 걸려 있지 않음

**위치** `modules/paseo-phone-runtime/android/src/test/**`(4개 파일), `.github/workflows/`(Gradle 단위 테스트를 돌리는 잡 없음), core-linux.md "검증"(kotlinc 수동 실행).

**문제.** `TarExtractorTest`, `ProotCommandTest`, `AuthorizedKeysTest`, `RestartPolicyTest`는 사람 손으로만 돈다. 업스트림이 Expo SDK·Kotlin·AGP를 올리면(머지마다 일어날 수 있음) 이 테스트가 컴파일조차 안 되는 상태로 남아도 모른다. 업스트림 CI 워크플로를 고치는 것은 머지 충돌 면적을 늘리므로 거기에 잡을 더하는 것도 답이 아니다.

**수정** architect: architecture §9 빌드 노트에 "APK 빌드 전 `./gradlew :paseo-phone-runtime:testReleaseUnitTest`"를 넣고, `scripts/phone/build-apk.sh`가 `assembleRelease`와 같은 Gradle 호출에 이 태스크를 더하게 한다(core-rootfs, 한 줄). 그러면 APK를 만들 때마다 돈다. 업스트림 워크플로는 건드리지 않는다.

**담당** architect(문서), core-rootfs(스크립트 한 줄), core-runtime(테스트가 Gradle에서 실제로 도는지 확인).

## 10. `RuntimeController.kt` 870줄, 책임 다섯 개

**위치** `RuntimeController.kt` 전체.

**문제.** 한 `object`에 (a) 명령 직렬화·`busy`, (b) 설치·업데이트 오케스트레이션, (c) 두 proot 프로세스 감시·백오프·재시작 한도(`Managed`, `launch`, `onExit`, `stopManaged`), (d) 헬스 체크·환경 새로고침, (e) 상태 스냅샷·알림 뷰 직렬화가 같이 있다. 다른 파일들은 50~250줄이다. internal-contract가 정한 소유도 컨트롤러를 core-runtime 혼자 갖게 했으니, 다음 사이클 변경이 모두 이 파일에 몰린다.

**수정** 동작을 바꾸지 않는 분리 두 개면 충분하다.

- `StateSnapshot.kt`: `buildSnapshot`, `readySnapshot`, `currentView`, `modeName`. 순수 함수. 3번의 fixture 테스트 대상.
- `ProcessSupervisor.kt`(또는 `Managed.kt`): `Managed`, `launch`, `onExit`, `stopManaged`, `recordFailure`, `restartDelayMs`, `watchDaemonReady`, `watchSshdUp`. 컨트롤러의 confined 디스패처에서 그대로 돈다(새 동시성 모델 없음).

컨트롤러는 명령과 설치 흐름만 남겨 400줄 안팎이 되게 한다. `RuntimeService`/`RuntimeStore`/`PhoneRuntimeModule`은 지금 크기와 책임이 적당하다.

**담당** core-runtime.

## 11. `state.json` 설계: 버전 필드, 중복 필드, 빠진 필드

**위치** `RuntimeStore.kt`, architecture §2.3·§2.5, core-runtime.md §3(`rootfsSha256` 기록 안 함).

**현재 모양과 판단**

| 필드                                 | 판단                                                                                                                                                                                   |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `installed`                          | 지운다(2번). 디스크에서 판정                                                                                                                                                           |
| `desired`, `sshEnabled`, `keepAwake` | 유지. 사용자 의도라 디스크에서 알 수 없다                                                                                                                                              |
| `daemonVersion`, `nodeVersion`       | 실제 판정은 디스크(`installedDaemonVersion()`, `installedNodeVersion()`)가 한다. 표시 폴백으로만 쓰인다. 지우거나 "표시용 캐시"라고 주석                                               |
| `failedStep`                         | 유지하되 러너가 아는 단계 이름만 저장(1번)                                                                                                                                             |
| (없음) `version`                     | 추가. `"version": 1`. 규칙: 필드는 추가만, 뜻이 바뀌면 새 키. 마이그레이션 코드는 그때 쓴다                                                                                            |
| (없음) `rootfsSha256`                | 추가 여부를 architect가 결정. 넣으면 `RootfsSpec.SHA256` 변경 시 "재설치하면 새 rootfs" 안내를 줄 수 있다. 안 넣으면 §10.2에 "핀 변경은 기존 설치에 영향 없음, 알리지도 않음"을 적는다 |

- 원자적 쓰기(`AtomicFile`)와 단일 작성자(confined 디스패처의 컨트롤러)는 맞다.
- 소유는 core-runtime(`RuntimeStore`)인데 경로는 core-linux(`LinuxPaths.stateFile`)가 정한다. 계약대로이며 문제없다.

**재설치·업데이트에서 무엇이 남는가** (코드 기준, architecture §2.3에 표로 넣을 것)

| 경로                                                              | Reinstall       | 앱 업데이트(APK)                  | 앱 삭제 |
| ----------------------------------------------------------------- | --------------- | --------------------------------- | ------- |
| `filesDir/phone-runtime/rootfs` (`/root` 포함)                    | 삭제            | 유지                              | 삭제    |
| `filesDir/phone-runtime/state.json`                               | 유지(값 재설정) | 유지                              | 삭제    |
| `filesDir/phone-runtime/logs`, `tmp`, `sysdata`, `libtalloc.so.2` | 유지            | 유지(talloc은 크기 다르면 재복사) | 삭제    |
| `cacheDir/phone-runtime/downloads`                                | 다시 받음       | 유지(설치 성공 후엔 비어 있음)    | 삭제    |
| `/storage/emulated/0/Paseo`                                       | 유지            | 유지                              | 유지    |
| `rootfs/opt/paseo-phone/*`                                        | 다시 복사       | 서비스 시작마다 덮어씀            | 삭제    |

- 로그: `agent-instructions.sh` 출력이 `daemon.log`에 섞인다(`RuntimeController.kt:406`). `setup.log`가 맞다. 또 `LogKind`는 `"ssh"`, 파일은 `sshd.log`라 컨트롤러가 이름을 바꿔 준다. 이름을 하나로(`ssh`) 맞추면 `getLogs`의 `when`이 사라진다. 둘 다 작음.

**담당** core-runtime(필드), architect(표·rootfs 핀 결정).

## 12. `/root/.paseo/config.json` 소유권

**위치** `paseo.sh`(없을 때만 작성), `DaemonProbe`(`listen` 하드코딩), architecture §3.3.

**문제.** 문서는 "없을 때만 만든다"까지만 말한다. 누가 그 뒤를 소유하는지, 업스트림이 키 이름을 바꾸면 기존 설치에 무슨 일이 일어나는지가 없다. 실제로는 첫 작성 뒤 데몬(과 사용자)이 소유하고, 포크는 다시 손대지 않는다. 그래서 (a) 업스트림이 `features.dictation` 같은 키를 바꾸면 **새 설치만** 새 키를 받고 기존 설치는 옛 키 그대로 받아쓰기가 다시 켜질 수 있고, (b) 사용자가 `daemon.listen`을 바꾸면 `DaemonProbe`와 `EXPO_PUBLIC_LOCAL_DAEMON`이 어긋나 Linux가 영원히 `starting`이 된다.

**수정** architect: §3.3에 소유 규칙을 적는다. "포크는 첫 작성만 한다. 이후 데몬 소유. 업스트림 머지에서 §4.3 config 키 행이 바뀌면 기존 설치용 패치를 `paseo.sh`에 멱등하게(키가 없을 때만 넣는 식) 추가할지 그때 결정한다. `daemon.listen`은 사용자가 바꾸면 안 되는 값이고 Linux 섹션 문구로 알린다." 코드 변경은 지금 필요 없다.

**담당** architect.

## 13. `src/phone/format.ts`와 UI 파일 구성

**판단: 유지한다. 위치도 맞다.**

- 순수 함수, React·네이티브 import 없음, 의존은 `index.ts` 타입뿐, 테스트가 있다. `src/phone/**`의 다른 파일과 같은 계층이다. architecture §7.3 목록에 한 줄 추가하면 된다(architect).
- 단, 들어 있는 것 중 두 개는 다른 문제의 증상이다. `parseNoSpaceMessage`는 5번에서 지우고, `readPhoneNotificationIntent`는 6번의 공용 쿼리 상수를 쓰게 한다. 남는 것은 SSH 문자열·주소 선택·용량 표기이고, 이름 `format.ts`는 그 내용에 맞다.
- `runtime.android.ts`의 `usePhoneRuntimeState`는 UI 계층의 유일한 상태 구독점으로 맞다. 다만 `.android.tsx` 파일들이 명령 함수는 모듈 `index.ts`에서 직접, 상태는 `runtime.android.ts`에서 가져온다. 계약(UI → `index.ts`만)은 지키고 있으므로 고칠 필요는 없다. ui.md에 "상태는 `runtime`, 명령은 `index.ts`"라고 한 줄 적는다(ui).
- `linux-section.android.tsx` 440줄에는 로그 시트가 들어 있다. `linux-setup-screen`도 같은 로그 보기를 갖고 있으니 `logs-sheet.android.tsx`로 빼면 두 화면이 같이 쓸 수 있다. 우선순위 낮음(ui).
- `linux-setup-screen.android.tsx`가 업스트림 스플래시 에러 레이아웃의 스타일 값을 복사했다(ui.md §4.3). 업스트림이 스플래시 디자인을 바꾸면 화면 A만 옛 모양으로 남는다. 7번 표에 "시각적 계약, 수동 확인"으로 올린다(architect).

**담당** architect(§7.3), ui.

## 14. `app.config.js` 수정을 grep으로 찾을 수 없음

**위치** `packages/app/app.config.js`의 다섯 hunk(상수, `variants.phone`, `scheme`, `useLegacyPackaging`, `extra.phoneRuntime`).

**문제.** 수정 범위는 계획대로 작다. 다만 `scheme`·`expo-build-properties` 줄은 업스트림이 자주 만지는 자리라 충돌은 여기서 난다. 충돌을 풀 때 다섯 곳을 모두 살렸는지 확인할 표시가 없다. `_layout.tsx`, `host-page.tsx`도 마찬가지다.

**수정** core-rootfs·ui: 포크가 넣은 줄마다 `// phone-fork:` 접두 주석을 단다(레포의 `COMPAT(` 관례와 같은 방식). architect: §4.3에 "`rg 'phone-fork:' packages/app` 결과가 5 + 2 + 2 줄이어야 한다"를 적는다. 그러면 머지 후 확인이 명령 하나다.

**담당** core-rootfs(`app.config.js`), ui(`_layout.tsx`, `host-page.tsx`), architect.

## 15. 문서 드리프트

원칙: 코드가 실측·실기 검증으로 바뀐 것은 문서를 고친다. 위 1~8번처럼 코드가 틀린 것은 코드를 고친다. architecture.md를 먼저 고치고, 팀 노트의 "아키텍처와 다른 점" 행은 반영되면 지운다.

### architecture.md를 고칠 것 (architect)

| 절                    | 지금 문서                                                                                | 코드                                                                                                                                                           |
| --------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D3, §2.2              | proot-distro 배포 tarball (5.x 전제)                                                     | v4.29.0 `debian-trixie-aarch64-pd-v4.29.0.tar.xz` 고정. 5.x는 tarball을 배포하지 않음 (`RootfsSpec.kt`)                                                        |
| §2.1                  | `exchange/AgentInstructions.kt`, assets 5개                                              | 파일 없음, `agent-instructions.sh`가 대신. assets에 `run-daemon.sh`, `run-sshd.sh`, `agent-instructions.sh`, `.gitattributes` 추가. `android/src/test/**` 추가 |
| §2.4                  | 커널 문자열 축약, 가짜 `/proc`는 항상 바인드                                             | 전체 문자열, 실제 파일을 읽을 수 없을 때만 가짜 바인드                                                                                                         |
| §2.5 configure        | `ssh-keygen -A`로 호스트 키 생성                                                         | 키는 `packages`의 `openssh-server` postinst가 만들고, `configure.sh`(재실행)·`run-sshd.sh`가 빠진 키만 보충. `policy-rc.d` 추가                                |
| §2.5 paseo            | 매번 `npm i -g` 데몬 + 세 에이전트                                                       | 데몬은 버전이 다를 때만, 에이전트는 없을 때만, 설치 후 `npm cache clean`                                                                                       |
| §2.5 표·업데이트 판정 | `start`가 단계 표의 한 행, Node 변경 시 "download(Node만) → Node 재설치 → paseo → start" | `start`는 컨트롤러 소유(러너 단계 아님). `UPDATE_NODE` = download → extract → paseo. 1번 수정 후 그 결과를 반영                                                |
| §2.5 성공 시 기록     | `rootfsSha256` 기록                                                                      | 기록 안 함 (11번 결정에 따라)                                                                                                                                  |
| §2.5 용량             | 설치 후 약 2 GB                                                                          | 실측 약 2.36 GB, 순간 최대 3.3 GB (core-rootfs.md). 4 GB 사전 검사 유지 근거로 적음                                                                            |
| §3.2                  | `bash -lc 'exec paseo daemon run'`                                                       | `/bin/bash /opt/paseo-phone/run-daemon.sh` (internal-contract와 같음)                                                                                          |
| §4.2 표               | 설명 줄 `127.0.0.1:6767`                                                                 | 스모크에서 호스트 주소가 `localhost:6767`로 보임 (core-rootfs.md). 어느 쪽이 맞는지 QA로 확인 후 고침                                                          |
| §6, §4.3              | 언급 없음                                                                                | 비-phone 변형도 모듈을 자동 링크(권한·`.so` 포함), 네이티브 패키지 이름 가드로 막음 (core-rootfs.md 트레이드오프). 수용 결정으로 기록                          |
| §7.1                  | `PhoneRuntimeErrorCode` 7개, `ServiceErrorCode` 이름 없음                                | `unavailable` 추가, `ServiceErrorCode` 타입. 5번의 `space` 필드 추가 예정                                                                                      |
| §7.2                  | `restartLinux` 없음, Promise 시점 명시 없음, 모든 명령 `busy`                            | `restartLinux` 있음. 수명 명령은 접수 즉시 resolve·결과는 상태로. 키·로그·시스템 화면은 `busy` 제외                                                            |
| §7.4                  | `paseo-phone://settings/hosts/<id>`, 서버 ID 없으면 `paseo-phone://?confirmStop=1`       | `…/hosts/<id>/host`, 서버 ID 없으면 Stop 액션 없음, `logs=daemon`·Restart 액션 추가                                                                            |
| §8.2                  | 첫 설치는 UI `start()`                                                                   | 첫 실행에 자동 설치(`desired` 기본 `running`)                                                                                                                  |
| §4.3                  | 암묵적 계약 표                                                                           | 7번, 6번, 8번, 12번, 13번 행 추가                                                                                                                              |
| §7.3                  | UI 파일 목록                                                                             | `format.ts`(+테스트), `i18n/register.test.ts` 추가                                                                                                             |

### internal-contract.md (architect)

- `ProotSpec.env`: `PASEO_PHONE_NODENAME`은 `ProotProcess`/`ProotExec`가 넣는다고 적는다(지금 "호출하는 쪽이 넣는다"). 4번 수정 후에는 `SetupRunner.scriptSpec`/`runScript`가 스크립트 실행의 유일한 입구라고 적는다.
- `SetupResult.Failed`: 5번의 용량 필드.
- `SetupRunner.run`: 모르는 `fromStep`은 실패로 돌려준다(1번).
- `SetupRunner` 공개 API에 core-linux가 추가한 `installScripts()`를 계약으로 올린다(core-runtime이 이미 의존).

### ui.md (ui)

- §1 표, §3 "Linux 재시작", §6 요청 2: `restartLinux`는 이제 코어 API다(`c751ea283`). `runtime.android.ts`에는 없다. 세 군데를 고친다.
- §6 요청 3(`no-space` 형식 유지)은 5번 반영 후 지운다.

### core-runtime.md (core-runtime)

- §4 "core-rootfs 요청 1"(비-phone 변형 자동 링크 제외)은 core-rootfs가 "하지 않음"으로 결정했다. 요청을 닫고 architecture §6 링크로 바꾼다.

---

## 확인한 것, 하지 않은 것

- 확인: `git diff --stat v0.14.0..HEAD`의 업스트림 파일은 위 3개뿐(+ 새 트리). Kotlin 하위 패키지 import(위로 가는 의존 없음, 서비스 → `linux.ProotProcess`는 아래 방향), UI import(모듈은 `index.ts`만), 1·2번 경로는 코드를 따라가며 확인.
- 하지 않음: 8번에서 serverId가 바뀐 데몬에 옛 호스트 항목이 붙을 때 업스트림의 실제 동작, `app.config.js` 수정을 config plugin으로 옮길 수 있는지(`scheme`·`extra`가 `Constants.expoConfig`에 반영되는지), Gradle 빌드와 단위 테스트 실행.
