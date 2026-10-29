# core-runtime: 진행 기록과 UI 계약

> 담당: core-runtime · 기준: [architecture.md](../architecture/architecture.md) §3.4, §5, §7, §8, [internal-contract.md](internal-contract.md), [fix-plan.md](../code-reviews/fix-plan.md)
> 이 문서는 다음 라운드에도 이어 쓴다. UI 개발자는 "UI 계약" 절만 보면 된다.

## 1. 진행 상황 (리뷰 1라운드 수정 반영)

| 파일 (`packages/app/modules/paseo-phone-runtime/` 기준)         | 역할                                                                                             |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `package.json`, `expo-module.config.json`                       | 모듈 이름 `PaseoPhoneRuntime`, Android 전용                                                      |
| `index.ts`, `src/types.ts`                                      | JS 진입점. 타입은 `src/state-schema.ts`에서 `z.infer`로 만든다                                   |
| `src/state-schema.ts`                                           | 상태 계약의 원본(zod). 개발 빌드에서 `getState`·리스너 값을 검사하고 어긋나면 `console.error`    |
| `src/__fixtures__/state-*.json`                                 | 골든 스냅샷. TS 테스트와 Kotlin 테스트가 같은 파일을 본다                                        |
| `android/build.gradle`                                          | `BuildConfig.PASEO_DAEMON_VERSION`, `NODE_VERSION`, `NODE_SHA256`(`node-sha256.txt`에서)         |
| `android/node-sha256.txt`                                       | Node 버전별 linux-arm64 tarball sha256. `.tool-versions`의 Node 버전 줄이 없으면 빌드가 실패한다 |
| `runtime/PhoneRuntimeModule.kt`                                 | Expo 브리지만                                                                                    |
| `runtime/RuntimeController.kt`                                  | 단일 상태 기계, 상태의 유일한 작성자. 명령 직렬화, 설치·시작·정지 흐름                           |
| `runtime/ProcessSupervisor.kt`                                  | 데몬·sshd proot 프로세스 표, 준비·헬스 감시, 백오프, 재시작 한도, 포트 점유 검사                 |
| `runtime/StateSnapshot.kt`                                      | 내부 상태 → JS 상태 맵, 알림 뷰. 순수 함수                                                       |
| `runtime/RuntimeService.kt`                                     | FGS, 알림, wake/wifi lock만. 프로세스는 갖지 않는다                                              |
| `runtime/RuntimeStore.kt`                                       | `state.json` 원자적 쓰기(`AtomicFile`)                                                           |
| `runtime/notify/RuntimeNotification.kt`                         | 알림 그리기, 딥링크                                                                              |
| `runtime/ssh/{AuthorizedKeys,HostKeys}.kt`                      | 키 파일. 쓰기는 한 락 아래 fsync 후 rename                                                       |
| `runtime/exchange/ExchangeFolder.kt`                            | 교환 폴더 상태 (`agent-instructions.sh`가 안내 파일을 쓴다)                                      |
| `runtime/system/{DeviceChecks,NetworkAddresses,DaemonProbe}.kt` | 기기 점검, 주소, 데몬 HTTP·포트 확인                                                             |

이번 라운드는 Gradle과 폰 없이 작업했다. TS(typecheck, lint, `state-schema.test.ts` 8개)는 통과했고, Kotlin은 컴파일도 `StateSnapshotTest`도 돌리지 않았다. 픽스처는 손으로 썼으므로 첫 Gradle 실행에서 `StateSnapshotTest`가 어긋나면 출력을 확인한 뒤 `-PupdateFixtures`로 다시 쓴다(§5).

의존 방향: `PhoneRuntimeModule` → `RuntimeController` → `ProcessSupervisor` / `StateSnapshot` / `RuntimeService` / `RuntimeStore` / `setup` / `linux` / `notify` / `ssh` / `exchange` / `system`. 아래 계층은 위를 부르지 않는다. `ProcessSupervisor`는 `onChange` 콜백으로, 서비스는 `RuntimeService.Listener`로 컨트롤러에 알린다.

## 2. UI 계약

UI는 `modules/paseo-phone-runtime/index.ts`만 import한다. 상태 모양의 원본은 `src/state-schema.ts`이고 architecture §7.1과 같다. 추가된 것: `ServiceErrorCode`(이름만 붙인 것), `PhoneRuntimeErrorCode`의 `"unavailable"`, `SetupErrorCode`의 `"cancelled"`, `setup-failed`의 `space`, 알림 쿼리 이름 `PHONE_NOTIFICATION_QUERY`.

### 2.1 iOS·웹·일반 Paseo 빌드

- `isPhoneRuntime` = 네이티브 모듈 존재 && `Constants.expoConfig.extra.phoneRuntime === true`.
- `false`일 때: `getState()`는 `{ status: "checking" }`, `addStateListener`는 아무것도 하지 않는 `{ remove() {} }`, 나머지 함수는 `PhoneRuntimeError("unavailable")`로 reject. 크래시하지 않는다. UI는 `isPhoneRuntime`으로 먼저 거른다.
- 네이티브 쪽도 패키지 이름이 폰 변형의 `applicationId`(`PhoneConstants.APPLICATION_ID`)로 시작하지 않으면 컨트롤러를 켜지 않는다.

### 2.2 함수와 resolve 시점

| 함수                                  | resolve 시점     | 비고                                                                                                                                           |
| ------------------------------------- | ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `getState()`                          | 동기             | 네이티브 싱글턴의 최신 스냅샷. JS 리로드 후에도 그대로                                                                                         |
| `addStateListener(fn)`                | —                | 값이 바뀔 때만 호출. 다운로드 진행률은 최대 4회/초                                                                                             |
| `start()`                             | **접수 즉시**    | 미설치 → 설치, 버전 다름 → 업데이트, 그 외 → Linux 시작. 이미 starting/running이면 아무것도 안 함                                              |
| `stop()`                              | 접수 즉시        | **진행 중인 명령을 취소**하고 멈춘다(`busy` 없음). 데몬 → sshd 순서. 설치돼 있을 때만 `desired: "stopped"` 저장                                |
| `restartDaemon()`                     | 접수 즉시        | 데몬 proot만. Linux가 멈춰 있으면 `start()`와 같다                                                                                             |
| `restartLinux()`                      | 접수 즉시        | 정지 → 시작을 한 명령으로(같은 `busy` 규칙). `desired`는 바꾸지 않는다. 멈춰 있으면 `start()`와 같다. 교환 폴더 `restart-required` 해소에 쓴다 |
| `retrySetup()`                        | 접수 즉시        | `setup-failed`일 때만 동작, 실패한 단계부터. `start` 단계 실패는 Linux만 다시 띄운다                                                           |
| `startPrevious()`                     | 접수 즉시        | `setup-failed && canStartPrevious`가 아니면 `not-installed`로 reject                                                                           |
| `reinstall()`                         | 접수 즉시        | 정지 → 서버 ID·호스트 키를 rootfs 밖에 보관 → rootfs 삭제(링크를 따라가지 않음) → install 처음부터. 교환 폴더와 신원은 유지                    |
| `setSshEnabled(b)`, `setKeepAwake(b)` | **적용 후**      | `state.json` 저장. Linux 실행 중이면 sshd 시작/정지, wake lock 획득/해제                                                                       |
| `openBatterySettings()`               | 화면을 연 뒤     | 실패 시 배터리 최적화 목록 화면                                                                                                                |
| `requestStoragePermission()`          | 화면을 연 뒤     | "모든 파일 접근" 화면. 돌아오면 상태가 자동 갱신된다(아래)                                                                                     |
| `requestNotificationPermission()`     | 사용자가 답한 뒤 | `true` = 알림 허용. Android 12 이하는 다이얼로그 없이 현재 값                                                                                  |
| `openExchangeFolder()`                | 화면을 연 뒤     | DocumentsUI `Paseo` 폴더, 안 되면 저장소 루트. 둘 다 없으면 `io-error`                                                                         |
| `listAuthorizedKeys()` 등 키 3종      | 파일 쓰기 후     | `busy` 대상 아님. 미설치면 `not-installed`. 키를 지우면 실행 중인 sshd를 다시 띄워 열린 세션을 끊는다                                          |
| `getLogs(kind, maxBytes?)`            | 읽은 뒤          | `daemon`, `ssh`, `setup`. 기본 64 KB 꼬리. 잘린 첫 줄은 버림. 파일이 없으면 `contents: ""`. `path`는 Android 절대 경로                         |

"접수 즉시"인 명령의 결과(성공, 실패)는 상태 이벤트로만 온다. Promise reject는 접수 거부(`busy`, `not-installed`, `unavailable`)뿐이다. 설치는 몇 분 걸리고 그 사이 JS가 리로드될 수 있어서 이렇게 했다.

상태 자동 갱신: 앱이 포그라운드로 돌아올 때(설정 화면, 권한 다이얼로그에서 복귀 포함), 네트워크가 바뀔 때, Linux 실행 중 30초마다 `device`, `addresses`, `exchange`, `serverId`, `hostKeyFingerprints`를 다시 읽는다. UI가 따로 새로고침할 함수는 없다.

### 2.3 오류 코드 (`PhoneRuntimeError.code`)

| 코드            | 언제                                                                                                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `busy`          | 수명 명령(start/restartDaemon/restartLinux/retrySetup/startPrevious/reinstall)이나 `setSshEnabled`가 진행 중인데 또 옴. `stop()`은 `busy`가 되지 않는다 |
| `not-installed` | 키 명령인데 rootfs가 없음, 또는 `startPrevious`를 쓸 수 없음                                                                                            |
| `invalid-key`   | OpenSSH 공개키 한 줄이 아님, 또는 앞에 옵션(`command=`, `from=` 등)이 붙음. 지원 타입: ed25519, rsa, ecdsa 256/384/521, sk-ed25519, sk-ecdsa            |
| `private-key`   | `-----BEGIN` 또는 `PRIVATE KEY`가 보임. 입력은 저장·로그하지 않는다                                                                                     |
| `duplicate-key` | 같은 지문이 이미 있음                                                                                                                                   |
| `not-found`     | 지울 지문이 없음, 알 수 없는 로그 종류                                                                                                                  |
| `io-error`      | 파일·시스템 화면 실패. 네이티브가 모르는 코드도 여기로 바뀐다                                                                                           |
| `unavailable`   | 폰 런타임이 없는 빌드                                                                                                                                   |

`message`는 영어 원문이다. UI는 코드로 문구를 고른다.

### 2.4 상태 전이

```
checking ──(rootfs 없음·풀다 맒)──▶ setup(install) ──성공──▶ ready(linux: running)
   │                                   │실패·stop()
   │                                   ▼
   │                              setup-failed ──retrySetup──▶ setup(…, 실패한 단계부터)
   │                                   └─startPrevious──▶ ready(starting)
   ├─(설치됨, desired=running)──▶ ready(starting) ─▶ (버전 다름이면 setup(update))
   └─(설치됨, desired=stopped)──▶ ready(stopped)
ready: stopped ─start─▶ starting ─데몬 HTTP 응답─▶ running ─stop─▶ stopping ─▶ stopped
```

- 앱 시작 판정은 디스크의 rootfs 상태로 한다(`LinuxPaths.stateOf`): 없음·압축만 풀림 → 설치 이어 하기, 설치 완료 → `desired`대로. `state.json`이 없으면 `desired`는 `"running"`이라 첫 실행에서 버튼 없이 설치가 시작된다. 직전 실행이 설치 중 실패로 끝났으면(`failedStep`) 자동으로 다시 하지 않고 `setup-failed(code: "unknown")`를 보여 준다.
- 설치·업데이트 중 `stop()`: `setup-failed(code: "cancelled")`. 멈춘 단계가 `failedStep`에 남아 `retrySetup()`이 거기서 이어 간다.
- 업데이트 판정은 `start()` 때만 한다(앱 시작의 자동 start 포함). 설치돼 있고 멈춘 상태로 앱을 열면 `ready(stopped)`가 먼저 보이고, Start를 누르면 그때 `setup(update)`로 바뀔 수 있다.
- `setup.step`은 `download → extract → configure → packages → paseo → start`. update는 데몬만 바뀌면 `paseo`부터, Node 핀이 바뀌면 `download`부터. `start` 단계는 컨트롤러가 데몬·sshd를 띄우고 데몬 HTTP 응답을 기다린다(120초). 실패하면 `setup-failed(step: "start", code: "command-failed", message: 원인)`.
- `canStartPrevious`: daemon 업데이트 실패는 항상 `true`, Node 업데이트는 `download` 단계 실패만 `true`(그 뒤는 기존 Node가 이미 바뀌었을 수 있다), install은 `false`.
- `setup-failed.space`: `code === "no-space"`일 때만 `{ freeBytes, neededBytes }`. UI는 이 숫자로 문구를 만든다. `message`는 원문 그대로다.
- `ready.linux`:
  - `starting`: Start 직후. 데몬이 처음 응답하면 `running`. 120초 안에 응답이 없으면 `linux`는 `running`, `daemon.state`는 `error("not-responding")`.
  - `running` 중 데몬이 죽으면 `daemon.state = error("crashed" | "killed-by-system")` → 백오프(1, 2, 4, 8초) 후 `starting` → `running`. `linux`는 `running` 유지.
  - 데몬이 5분 안에 5번 죽으면 sshd까지 멈추고 서비스가 끝난다: `linux = error("restart-limit", "… Last error: <원인>")`.
  - 시작할 때 포트(6767, 2222)를 다른 프로세스가 이미 쥐고 있으면 띄우지 않는다: `error("start-failed", "port in use: 127.0.0.1:<포트>")`. 데몬이면 `linux`도 같은 오류로 끝난다.
  - sshd가 5번 죽으면 `ssh.state = error("restart-limit")`, Linux와 데몬은 그대로.
  - 데몬이 실행 중에 30초 간격 3번 연속 응답이 없으면 `daemon.state = error("not-responding")`. 프로세스는 죽이지 않는다. 다시 응답하면 `running`으로 돌아온다.
- `ssh.state`는 `ssh.enabled === false`면 항상 `stopped`. sshd는 proot가 2초 살아 있으면 `running`으로 본다.
- `exchange.status`: 권한 없음 → `no-permission`. 권한이 있는데 실행 중인 Linux가 바인드 없이 시작됐음 → `restart-required`(`restartLinux()` 안내). 폴더 생성 실패 → `error`. 그 외 `mounted`(Linux가 멈춰 있어도 다음 시작 때 바인드된다는 뜻).
- `hostKeyFingerprints` 항목 형식: `"ssh-ed25519 SHA256:…"` (타입 + 공백 + 지문).
- `daemon.version`: 설치된 `@getpaseo/cli` 버전, 없으면 빌드에 박힌 `PASEO_DAEMON_VERSION`.

상태별 예시는 `src/__fixtures__/state-*.json`을 본다(다운로드 중, 공간 부족, 시작 실패, 실행 중, Linux 오류). 이 파일들은 Kotlin 출력과 비교되는 실제 모양이다.

`message` 중 런타임이 만드는 것(`killed-by-system`, `crashed`, `not-responding`, `restart-limit`)은 기기 언어(en/ko) `strings.xml`에서 온다. 설치 실패 `message`는 SetupRunner 원문(번역 안 함).

### 2.5 알림

채널 `linux_runtime`(LOW), 알림 ID 하나. 서비스가 사는 동안만 있다.

| 상태                             | 제목 / 본문                                                | 진행                                       | 액션 / 탭                                                                                                              |
| -------------------------------- | ---------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| setup(install)                   | Setting up Linux / `{단계} · {n}%`(다운로드), 그 외 단계명 | 다운로드는 결정형, 그 외 비결정형          | 탭 `paseo-phone://`                                                                                                    |
| setup(update)                    | Updating Linux / 단계명                                    | 비결정형                                   | 탭 `paseo-phone://`                                                                                                    |
| setup-failed                     | Linux setup failed / Tap to see what went wrong            | 없음. 쓸어 지울 수 있음, 서비스는 종료     | 탭 `paseo-phone://`                                                                                                    |
| linux starting / stopping        | Starting Linux / Stopping Linux                            | 비결정형                                   | 탭 `paseo-phone://`                                                                                                    |
| running                          | Running / `ssh <주소>:2222 · Awake`, SSH off, No network   | 없음. 주소 2개면 확장 시 둘 다(Wi-Fi 우선) | 탭 `paseo-phone://settings/hosts/<serverId>/host`, **Stop** → `…/host?confirmStop=1`(serverId를 모르면 Stop 액션 없음) |
| running + 데몬 not-responding    | Daemon is not responding / Tap to open logs                | 없음. 쓸어 지울 수 있음                    | 탭 `…/settings/hosts/<serverId>/host?logs=daemon`, **Restart** → 앱을 열지 않고 `restartDaemon()`                      |
| stopped, error(restart-limit 등) | 알림 없음(서비스 종료)                                     |                                            |                                                                                                                        |

- 알림 권한이 없을 때 서비스가 시작되면 Android가 알림을 버린다. 서비스가 마지막 알림과 표시 여부를 기억하고, 컨트롤러가 상태를 발행할 때마다(앱 포그라운드 복귀, 30초 주기 포함) 권한이 새로 생겼으면 `startForeground`로 다시 올린다.
- 링크는 `/host`로 끝난다. `settings/hosts/<id>`는 업스트림이 Connections로 리다이렉트하면서 쿼리를 버린다. `serverId`를 아직 모르면 쿼리 없이 `paseo-phone://`로 앱만 연다. 경로는 `packages/app/src/utils/host-routes.ts`의 `buildSettingsHostSectionRoute`와 맞춘다.
- UI가 처리할 쿼리 이름은 `PHONE_NOTIFICATION_QUERY`(`confirmStop`, `logs`)로 import한다. Kotlin 쪽 상수와 같은 문자열이다. `confirmStop=1`은 Linux 섹션의 Stop 확인 대화상자, `logs=daemon`은 데몬 로그.
- 알림 wake lock: 설치·업데이트 중에는 Keep awake와 상관없이 잡는다(화면이 꺼져도 다운로드가 이어지게). Wi-Fi lock은 Keep awake일 때만.

## 3. 아키텍처와 다른 점과 이유

| 항목                            | 아키텍처                            | 구현                                                                                | 이유                                                                                                                    |
| ------------------------------- | ----------------------------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 수명 명령 Promise               | 명시 없음                           | 접수 즉시 resolve, 결과는 상태로                                                    | 설치는 몇 분, JS 리로드를 넘어간다                                                                                      |
| 첫 설치 시작                    | `start()`가 시작                    | 첫 실행에 자동 시작(`desired` 기본 `running`)                                       | `PhoneRuntimeState`에 "미설치·대기" 상태가 없다. 게이트는 `checking`에서 설치 화면을 그리므로 사용자가 누를 버튼이 없다 |
| `PhoneRuntimeErrorCode`         | 7개                                 | `unavailable` 추가                                                                  | iOS·웹·일반 빌드에서 크래시 대신 코드로 거부                                                                            |
| 키·로그 명령의 `busy`           | 모든 명령 직렬화                    | 키·로그·시스템 화면은 `busy` 대상 아님(같은 스레드에서 실행되므로 상태 경합은 없음) | 설치 중에도 로그를 볼 수 있어야 한다                                                                                    |
| `exchange/AgentInstructions.kt` | 있음                                | 없음. 서비스 시작마다 `agent-instructions.sh` 실행(30초 제한, 실패는 경고 로그만)   | internal-contract.md                                                                                                    |
| 알림 문구                       | 디자인 §7은 영어만                  | en·ko                                                                               | 담당 지시                                                                                                               |
| `state.json`의 `rootfsSha256`   | 기록                                | 기록 안 함. `{version, desired, sshEnabled, keepAwake, failedStep}`                 | `SetupResult.Success`가 값을 주지 않는다                                                                                |
| sshd `running` 판정             | 명시 없음                           | proot가 2초 생존                                                                    | 포트 2222 확인은 필요하면 추가                                                                                          |
| 서비스 종료 시점                | §8.2: `stop()`과 setup-failed에서만 | Linux 수준 오류(데몬 `restart-limit`, `start-failed`)에서도 종료                    | 데몬이 없는 채로 FGS와 wake lock을 쥐고 있으면 배터리만 쓴다. 알림이 없어지므로 UI가 오류를 보여 준다                   |
| `RuntimeController.kt` 크기     | 약 400줄                            | 약 650줄                                                                            | 설치·시작·정지·취소 흐름이 한 상태 기계에 남아야 한다. 프로세스 표와 스냅샷은 이미 분리했다                             |

## 4. 팀원에게 필요한 것 (팀장 경유)

core-linux (커밋 `7aad67882` 기준, [core-linux.md](core-linux.md)):

- 서비스 시작마다 `SetupRunner.installScripts()`로 스크립트를 `/opt/paseo-phone`에 복사한 뒤 `agent-instructions.sh`(`runScript`), `run-daemon.sh`, `run-sshd.sh`(`scriptSpec`)를 돌린다.
- 재설치는 `LinuxPaths.deleteTree(paths.rootfs)`로 지운다. `deleteRecursively()`는 rootfs 안의 링크를 따라가 교환 폴더의 사용자 파일까지 지울 수 있다.
- 프로세스 정지는 `ProotProcess.stop(graceMs)`(데몬 10초, sshd 5초), 서비스가 죽을 때는 `killTree()`.
- 로그 회전과 `PASEO_PHONE_NODENAME`은 `ProotProcess`가 한다.

core-rootfs:

1. 일반 Paseo 변형에서 이 모듈을 autolinking에서 빼 달라. 빼지 않으면 일반 APK에 `MANAGE_EXTERNAL_STORAGE`, FGS 권한, proot `.so`가 들어간다.
2. 알림 딥링크 scheme은 `PhoneConstants.URL_SCHEME`을 쓴다. 변형의 scheme이 바뀌면 그 상수와 함께 바꾼다.

UI 개발자:

- 쿼리 이름은 `PHONE_NOTIFICATION_QUERY`로 import한다(§2.5).
- `setup-failed.code`의 `cancelled`, `unknown`(직전 실행 실패) 문구, `space` 숫자로 만드는 공간 부족 문구.

## 5. 다음 라운드 메모

- 통합 빌드 컴파일 오류는 이 경계부터 본다: Expo `AsyncFunction(...) Coroutine { -> }` 문법, `ServiceCompat.startForeground` 타입 인자, core-linux 시그니처(`LinuxPaths.stateOf`, `deleteTree`, `ProotProcess.stop`/`killTree`, `runScript`).
- 단위 테스트(`:paseo-phone-runtime:testReleaseUnitTest`): `ssh/AuthorizedKeysTest.kt`(파서, 지문, 개인 키·옵션 거부), `RestartPolicyTest.kt`(`ProcessSupervisor`의 백오프, 5분 5회 제한), `StateSnapshotTest.kt`(픽스처와 비교, `restart-required` 판정). JVM에서 돌도록 `java.util.Base64`를 쓴다.
- 픽스처 갱신: 스냅샷 모양을 바꾸면 `src/state-schema.ts`를 먼저 고치고, `./gradlew :paseo-phone-runtime:testReleaseUnitTest -PupdateFixtures`로 JSON을 다시 쓴 뒤 `npx vitest run packages/app/modules/paseo-phone-runtime/src/state-schema.test.ts`로 TS 쪽을 확인한다. 비교는 공백을 무시하고 키 순서는 따진다.
- Node 핀을 올릴 때: `.tool-versions`와 함께 `node-sha256.txt`에 `<버전> <sha256>` 한 줄을 추가한다. 값은 nodejs.org `SHASUMS256.txt`의 `node-v<버전>-linux-arm64.tar.xz` 줄이다. 22.20.0 값은 HTTPS로 받았고 서명(`SHASUMS256.txt.asc`)은 검증하지 않았다.
- QA에 넘길 확인 항목: 첫 실행 자동 설치, 앱을 나가도 설치 계속, 설치 중 Stop → `cancelled` → 이어 하기, 알림 Stop → 확인 대화상자, Restart 액션, 포트 6767 점유 시 `start-failed`, 재설치 후 서버 ID·호스트 키 유지, phantom killer 켠 상태에서 `killed-by-system` 표시, 화면 꺼짐 30분 이상 SSH 응답성.
