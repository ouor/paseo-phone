# 코드 리뷰: React Native / Expo (phone UI + JS 바인딩)

- 대상: `feat/phone-dev-machine` (`git diff v0.14.0..HEAD`), `packages/app/src/phone/**`, `packages/app/modules/paseo-phone-runtime/index.ts`, `src/types.ts`, `_layout.tsx`·`host-page.tsx` 패치, `app.config.js`
- 기준: `docs/phone-dev-machine/architecture/architecture.md` §4, §7, 저장소 문서(`CLAUDE.md`, `docs/expo-router.md`, `docs/unistyles.md`, `docs/forms.md`, `docs/coding-standards.md`)
- 방법: 읽기 전용. 시나리오는 코드를 따라가며 확인했고, 네이티브 쪽 원인은 `RuntimeController.kt`를 읽어 확인했다. 기기·Gradle·테스트 실행은 하지 않았다.
- 담당: ui = `src/phone/**`와 업스트림 패치 두 개, core-runtime = `index.ts`/`types.ts`(네이티브 컨트롤러 포함), core-rootfs = `app.config.js`

심각도 순서.

---

## 1. [높음] Reinstall 뒤 server-id가 바뀌어 "이 기기" 호스트가 영구히 끊긴다

- 위치: `modules/paseo-phone-runtime/android/.../RuntimeController.kt:208-217` (`reinstall`이 rootfs 전체를 지움), `src/phone/host-sections.android.tsx:23`, `src/runtime/host-runtime.ts:1132-1139`, `:1817`
- 문제: `reinstall()`은 `paths.deleteRootfs()`로 `/root/.paseo/server-id`까지 지운다. 새 데몬은 새 serverId를 만든다. 앱의 호스트 레지스트리에는 옛 serverId로 `127.0.0.1:6767` 항목이 남아 있다.
  - 호스트 컨트롤러는 연결된 데몬의 serverId가 다르면 `Connection resolved to X, expected Y`로 연결을 버린다(`host-runtime.ts:1132`).
  - `bootstrapConfiguredOverride`는 같은 주소가 레지스트리에 있으면 아무것도 하지 않는다(`:1817`). 앱을 다시 켜도 새 serverId로 등록되지 않는다.
  - `ThisDeviceSections`는 `state.daemon.serverId !== serverId`이면 `null`이다. 옛 호스트 페이지에서 Linux·Remote access·Exchange·Reinstall 카드가 사라진다.
- 실패 시나리오: 호스트 페이지에서 Reinstall → 설치 완료 → `ReadyGate`가 새 serverId 연결을 60초 기다리다 포기 → 앱은 "이 기기" 호스트를 계속 오프라인으로 보이고, Linux 제어 UI에 들어갈 길이 없다. 사용자가 호스트를 직접 지우고 앱을 다시 켜야 복구된다(어디에도 안내가 없다).
- 수정 제안: core에서 `deleteRootfs` 전에 `root/.paseo/server-id`를 보관했다가 `paseo` 단계 뒤 복원한다(데몬 config도 같은 방식으로 보존할지 결정). 이게 가장 작다. UI에서만 고치려면 Reinstall 성공 뒤 옛 serverId 호스트를 지우고 부트스트랩을 다시 돌려야 하는데, 업스트림 수정이 필요하다.
- 담당: core-runtime (보조: ui. Reinstall 확인 문구에 "데스크톱의 SSH known_hosts가 바뀐다"도 같이 적을지 검토)

## 2. [높음] Linux를 멈춘 상태에서 Reinstall이 중단되면 다음 실행부터 스플래시에서 영원히 멈춘다

- 위치: `RuntimeController.kt:146-151` (`init`), `:208-217` (`reinstall`), `src/phone/linux-setup-screen.android.tsx:64-66`, `src/phone/runtime-gate.android.tsx:33-34`
- 문제: `init`은 `isInstalled()`가 거짓이면 phase를 `Checking`에 두고, `desired == "running"`일 때만 `start()`를 부른다. `reinstall`은 `installed = false`로 저장하지만 `desired`는 그대로 둔다. UI는 `checking`을 버튼 없는 `StartupSplashScreen`으로 그리고 `AppShell`을 마운트하지 않는다.
- 실패 시나리오: 사용자가 Linux를 Stop(`desired = "stopped"`) → Reinstall → 다운로드 중 앱이 죽음(사용자가 스와이프, OOM, 크래시) → 다음 실행: `installed=false`, `desired=stopped` → 상태가 `checking`에서 바뀌지 않음 → 앱 전체가 스플래시에 갇힌다. 다른 호스트도 쓸 수 없고 빠져나갈 버튼이 없다. 앱 데이터 삭제만 해결책이다.
- 수정 제안: core: `reinstall`에서 `desired = "running"`을 저장하거나, `init`에서 설치되지 않았으면 `desired`와 상관없이 설치(또는 `failedStep` 기준 `setup-failed`)로 간다. ui: 방어로 `checking`이 몇 초 넘게 이어지면 `start()`/로그 버튼이 있는 화면을 보여준다.
- 담당: core-runtime (보조: ui)

## 3. [중간] 첫 설치 직후 Linux가 실패하면 Welcome으로 가고 Linux 화면에 들어갈 길이 없다

- 위치: `src/phone/runtime-gate.android.tsx:48-50`, `src/navigation/host-runtime-bootstrap.ts:207-211`, `src/app/_layout.tsx:771`
- 문제: 게이트는 `linux`가 `stopped`/`error`이면 바로 `AppShell`을 놓는다. 업스트림 5초 타이머는 게이트 바깥(`HostRuntimeBootstrapProvider`)에서 이미 끝났으므로 `hasGivenUpWaitingForHost === true`이다. 첫 설치라 등록된 호스트가 없으면 index가 Welcome으로 보낸다. Linux 섹션은 "이 기기" 호스트 페이지에만 있고, 사이드바 콜아웃은 Welcome에서 앱 크롬이 꺼져(`shouldShowAppChrome`) 보이지 않는다.
- 실패 시나리오: 설치는 성공했지만 데몬 proot가 바로 죽음(`error("crashed")`/`killed-by-system`, phantom process killer가 켜진 기기) → Welcome 화면 → 사용자는 로그도, Restart도, phantom 경고도 볼 수 없다. `ReadyGate`가 60초 타임아웃으로 놓아줄 때(데몬이 `not-responding`)도 같다.
- 수정 제안: `ready`인데 이 기기 serverId가 레지스트리에 없고 `linux`가 `error`(또는 타임아웃)이면 `AppShell` 대신 Linux 오류 화면을 그린다. `LinuxSetupScreen`의 실패 레이아웃(메시지, 로그, Retry=`start()`/`restartLinux()`)을 재사용하고, "다른 호스트로 계속" 버튼으로 게이트를 놓는다.
- 담당: ui

## 4. [중간] 콜드 스타트 때 `ready/stopped`가 먼저 발행되어 게이트를 건너뛰고 "Linux stopped" 콜아웃이 뜬다 (타이밍 의존)

- 위치: `RuntimeController.kt:146-151` (`phase = Ready; publish()` 다음에 `start()`), `:381-390` (`startLinux`가 `STARTING` 발행), `src/phone/runtime-gate.android.tsx:48-50`, `src/phone/linux-stopped-callout-source.android.tsx:26-28`
- 문제: 설치된 상태로 시작하면 컨트롤러가 `ready` + `linux: stopped`를 한 번 발행한 뒤 `start()`가 `starting`을 따로 발행한다. JS가 첫 이벤트로 렌더하면:
  - `ReadyGate`: `linuxComingUp`이 거짓 → `useLatchedBoolean`이 바로 `true`로 래치 → 60초 대기(§7.3의 목적)가 무력화된다.
  - `LinuxStoppedCalloutSource`: `down = true` → "Linux is stopped" 콜아웃이 `running`까지(최대 120초) 남는다. 주석의 "앱 시작은 `starting`을 거친다"는 전제가 깨진다.
- 실패 시나리오: 모듈 `OnCreate`가 JS 번들 실행 뒤로 밀리거나 `init`의 IO가 늦은 기기에서, 앱을 켤 때마다 정상 자동 시작 중인데도 사이드바에 "멈춤" 콜아웃과 Start 버튼이 보인다. 호스트가 하나도 저장되지 않은 상태라면 3번처럼 Welcome으로 간다.
- 수정 제안: core: `desired == "running"`이면 첫 `publish()` 전에 `linux = STARTING`으로 두고 발행한다(계약상 "자동 시작 중"을 `stopped`로 보이지 않게). UI만으로는 `desired`를 볼 수 없으니 core 수정이 맞다.
- 담당: core-runtime

## 5. [낮음] `LinuxAlert`가 계약을 어기고 `linux`가 멈췄을 때도 `daemon.state`를 읽는다

- 위치: `src/phone/linux-section.android.tsx:297`
- 문제: §7.1은 "`linux`가 `stopped`/`error`이면 `daemon`·`ssh`의 `state`는 의미가 없다"고 정한다. `killedBySystem`은 `linuxUp`을 확인하지 않는다. 반대로 `linux` 자체가 `error("killed-by-system")`이면 첫 분기에서 일반 crash 알림만 나와 phantom 안내가 빠진다.
- 실패 시나리오: 데몬이 시스템에 죽은 뒤 사용자가 Linux를 Stop → 남은 `daemon.state`가 그대로면 멈춘 Linux에 phantom 경고가 계속 뜬다. proot 전체가 SIGKILL로 죽으면 원인 안내가 없다.
- 수정 제안: `const killedBySystem = (linuxUp && state.daemon.state === "error" && state.daemon.code === "killed-by-system") || (state.linux.state === "error" && state.linux.code === "killed-by-system")`로 바꾸고, `linux` error 분기에서도 `killed-by-system`이면 phantom 설명을 쓴다.
- 담당: ui

## 6. [낮음] 알림의 Stop 액션이 Linux `starting` 중에는 아무 반응 없이 버려진다

- 위치: `src/phone/linux-section.android.tsx:149-153`
- 문제: `confirmStop` 파라미터를 지운 뒤 `linuxUp`(= `running`)일 때만 확인 대화상자를 연다.
- 실패 시나리오: 시작 중 알림에서 Stop을 누르면 앱이 호스트 페이지만 열고 아무 일도 일어나지 않는다. 사용자는 다시 알림을 찾아야 한다.
- 수정 제안: 파라미터를 지우지 말고 `running`이 될 때까지 의도를 유지하거나, `starting`에서도 확인 대화상자를 열고 `stop()`을 부른다(core가 `starting` 중 정지를 받는지 먼저 확인).
- 담당: ui

## 7. [낮음] 설치 실패 화면의 로그 문구가 "daemon logs"이고 로드 오류를 숨긴다

- 위치: `src/phone/linux-setup-screen.android.tsx:188-190`
- 문제: `startup.logs.loading`/`unavailable`은 업스트림의 "Loading daemon logs..." / "No daemon logs available."이다. 여기서 보여주는 것은 setup 로그다. `logsQuery.error`는 처리하지 않아 `getLogs` 실패도 "로그 없음"으로 보인다.
- 실패 시나리오: 설치가 실패한 사용자가 "데몬 로그 없음"을 보고 엉뚱한 곳을 찾는다. 로그 파일 읽기 실패의 원인 메시지가 사라진다.
- 수정 제안: `linuxSetup.logs.*` 키를 phone i18n에 추가하고, `linux-section.android.tsx:372`처럼 오류 분기를 넣는다.
- 담당: ui

## 8. [낮음] phone이 아닌 변형에도 런타임 모듈이 자동 링크되어 권한과 proot 바이너리가 들어간다

- 위치: `packages/app/app.config.js:149-151` (`autolinking`), 모듈 `android/src/main/AndroidManifest.xml`
- 문제: `modules/` 아래 로컬 모듈은 변형과 상관없이 자동 링크된다. 일반 Paseo Android 빌드(`production`/`development`)에도 `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RuntimeService`, `libproot*.so`가 병합된다. JS(`isPhoneRuntime`)와 Kotlin(`init`의 패키지명 검사)은 막고 있어 크래시는 없다.
- 실패 시나리오: 이 포크에서 일반 변형 APK를 만들면 스토어 심사에 걸리는 권한과 수 MB의 네이티브 라이브러리가 따라간다. 업스트림 F-Droid 빌드도 같은 영향을 받는다.
- 수정 제안: `isPhoneBuild`가 아니면 `autolinking.exclude: ["paseo-phone-runtime"]`를 넣는다(현재 Expo SDK가 app config의 `autolinking.exclude`를 받는지 확인 필요). 아키텍처 §4.3 수정 목록에 같이 적는다.
- 담당: core-rootfs

## 9. [낮음] 아키텍처 §7.2와 실제 계약이 어긋난다

- 위치: `modules/paseo-phone-runtime/index.ts:103-106`, `src/types.ts:84-92`, `architecture.md` §7.2
- 문제: `restartLinux()`와 오류 코드 `unavailable`이 코드에는 있고 §7.2 API·규칙 목록에는 없다. `ServiceErrorCode`도 별도 타입으로 나뉘었다(동작 차이는 없음). UI(`exchange-section.android.tsx:291`)는 이미 `restartLinux`를 쓴다.
- 실패 시나리오: 문서를 기준으로 작업하는 다음 개발자가 `restartLinux`를 모르거나 `unavailable`을 처리하지 않는다.
- 수정 제안: §7.2에 `restartLinux`(정지→시작 한 명령, 수락 시 resolve)와 `unavailable`을 추가한다.
- 담당: core-runtime

---

## 확인했고 문제없던 것

- 플랫폼 파일: `runtime.ts`, `runtime-gate.tsx`, `host-sections.tsx`의 기본 파일은 네이티브 모듈을 import하지 않고 타입만 쓴다. iOS, web, Electron은 안전하다. 일반 Android 변형은 `isPhoneRuntime === false`로 children을 그대로 그린다.
- 단방향 의존: `src/phone/**`는 `../../modules/paseo-phone-runtime`(= `index.ts`)만 import한다. `src/types.ts`나 네이티브를 직접 부르는 곳은 없다.
- 금지 패턴: `useUnistyles`, 가드 없는 DOM, `onPointerEnter`, `Platform.OS` 없음. 아이콘 색은 `withUnistyles`로 처리했다.
- i18n: 코드가 쓰는 정적 키와 동적 키(`steps`, `logs.kinds`, `network`)가 en·ko 모두에 있다. 공용 키(`common.actions.cancel/copy`, `common.loading`)는 본체에 있다. 복수형은 en `_one/_other`, ko `_other`로 맞다. `register.ts`는 백엔드의 얕은 병합 뒤 `loaded`에서 다시 깊게 병합한다.
- 구독: `useSidebarCallouts()`는 안정 객체라 콜아웃 effect가 반복되지 않는다. 런타임 상태 리스너는 모듈 싱글턴이라 누수가 아니다(개발 중 Fast Refresh에서만 중복 등록).
- 알림 라우팅: `NotificationOpenRouter`는 `useRootNavigationState().key`를 기다리므로, 게이트가 `AppShell`을 내린 동안 에이전트 알림 탭이 사라지지 않는다.
- `_layout.tsx`·`host-page.tsx` 패치는 §4.3의 위치·크기와 같다.
