# core-runtime: 진행 기록과 UI 계약

> 담당: core-runtime · 기준: [architecture.md](../architecture/architecture.md) §3.4, §5, §7, §8, [internal-contract.md](internal-contract.md)
> 이 문서는 다음 라운드에도 이어 쓴다. UI 개발자는 "UI 계약" 절만 보면 된다.

## 1. 진행 상황 (1라운드)

| 파일 (`packages/app/modules/paseo-phone-runtime/` 기준)         | 상태                                                                                          |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `package.json`, `expo-module.config.json`                       | 완료. 모듈 이름 `PaseoPhoneRuntime`, Android 전용                                             |
| `index.ts`, `src/types.ts`                                      | 완료. typecheck·lint 통과                                                                     |
| `android/build.gradle`                                          | 완료. `BuildConfig.PASEO_DAEMON_VERSION`(server package.json), `NODE_VERSION`(.tool-versions) |
| `android/src/main/AndroidManifest.xml`                          | 완료. 권한 + `RuntimeService`(specialUse)                                                     |
| `android/src/main/res/**`                                       | 완료. strings en·ko, 알림 아이콘 벡터                                                         |
| `runtime/PhoneRuntimeModule.kt`                                 | 완료. Expo 브리지만                                                                           |
| `runtime/RuntimeController.kt`                                  | 완료. 단일 상태 기계, 상태의 유일한 작성자                                                    |
| `runtime/RuntimeService.kt`                                     | 완료. FGS, 알림, wake/wifi lock, proot 핸들 보유                                              |
| `runtime/RuntimeStore.kt`                                       | 완료. `state.json` 원자적 쓰기(`AtomicFile`)                                                  |
| `runtime/notify/RuntimeNotification.kt`                         | 완료                                                                                          |
| `runtime/ssh/{AuthorizedKeys,HostKeys}.kt`                      | 완료                                                                                          |
| `runtime/exchange/ExchangeFolder.kt`                            | 완료 (`AgentInstructions.kt`는 만들지 않음, `agent-instructions.sh`를 실행)                   |
| `runtime/system/{DeviceChecks,NetworkAddresses,DaemonProbe}.kt` | 완료                                                                                          |

Gradle 빌드는 돌리지 않았다(지시대로). Kotlin은 core-linux의 계약 시그니처(`LinuxPaths`, `ProotSpec`, `ProotProcess`, `ExitInfo`, `ProotExec`, `SetupRunner`, `SetupMode`, `SetupEvent`, `SetupResult`)에만 맞춰 썼으므로, 통합 빌드의 컴파일 오류는 대부분 이 경계에서 나올 것이다.

의존 방향: `PhoneRuntimeModule` → `RuntimeController` → `RuntimeService` / `RuntimeStore` / `setup` / `linux` / `notify` / `ssh` / `exchange` / `system`. 아래 계층은 위를 부르지 않는다. 서비스가 컨트롤러에 알릴 일(알림 액션, 서비스 파괴)은 `RuntimeService.Listener` 인터페이스로 받고, 컨트롤러가 구현해서 등록한다.

## 2. UI 계약

UI는 `modules/paseo-phone-runtime/index.ts`만 import한다. 타입은 `src/types.ts`이고 architecture §7.1과 같다. 추가된 것은 `ServiceErrorCode`(이름만 붙인 것)와 `PhoneRuntimeErrorCode`의 `"unavailable"` 하나다.

### 2.1 iOS·웹·일반 Paseo 빌드

- `isPhoneRuntime` = 네이티브 모듈 존재 && `Constants.expoConfig.extra.phoneRuntime === true`.
- `false`일 때: `getState()`는 `{ status: "checking" }`, `addStateListener`는 아무것도 하지 않는 `{ remove() {} }`, 나머지 함수는 `PhoneRuntimeError("unavailable")`로 reject. 크래시하지 않는다. UI는 `isPhoneRuntime`으로 먼저 거른다.
- 네이티브 쪽도 패키지 이름이 `io.github.ouor.paseophone`으로 시작하지 않으면 컨트롤러를 켜지 않는다. 일반 Paseo APK에 모듈이 링크돼도 Linux 설치가 시작되지 않는다.

### 2.2 함수와 resolve 시점

| 함수                                  | resolve 시점     | 비고                                                                                              |
| ------------------------------------- | ---------------- | ------------------------------------------------------------------------------------------------- |
| `getState()`                          | 동기             | 네이티브 싱글턴의 최신 스냅샷. JS 리로드 후에도 그대로                                            |
| `addStateListener(fn)`                | —                | 값이 바뀔 때만 호출. 다운로드 진행률은 최대 4회/초                                                |
| `start()`                             | **접수 즉시**    | 미설치 → 설치, 버전 다름 → 업데이트, 그 외 → Linux 시작. 이미 starting/running이면 아무것도 안 함 |
| `stop()`                              | 접수 즉시        | 데몬 → sshd 순서. `desired: "stopped"` 저장(다음 앱 실행 때 자동 시작 안 함)                      |
| `restartDaemon()`                     | 접수 즉시        | 데몬 proot만. Linux가 멈춰 있으면 `start()`와 같다                                                |
| `retrySetup()`                        | 접수 즉시        | `setup-failed`일 때만 동작, 실패한 단계부터                                                       |
| `startPrevious()`                     | 접수 즉시        | `setup-failed && canStartPrevious`가 아니면 `not-installed`로 reject                              |
| `reinstall()`                         | 접수 즉시        | 정지 → `paths.deleteRootfs()`(링크를 따라가지 않음) → install 처음부터. 교환 폴더는 유지          |
| `setSshEnabled(b)`, `setKeepAwake(b)` | **적용 후**      | `state.json` 저장. Linux 실행 중이면 sshd 시작/정지, wake lock 획득/해제                          |
| `openBatterySettings()`               | 화면을 연 뒤     | 실패 시 배터리 최적화 목록 화면                                                                   |
| `requestStoragePermission()`          | 화면을 연 뒤     | "모든 파일 접근" 화면. 돌아오면 상태가 자동 갱신된다(아래)                                        |
| `requestNotificationPermission()`     | 사용자가 답한 뒤 | `true` = 알림 허용. Android 12 이하는 다이얼로그 없이 현재 값                                     |
| `openExchangeFolder()`                | 화면을 연 뒤     | DocumentsUI `Paseo` 폴더, 안 되면 저장소 루트. 둘 다 없으면 `io-error`                            |
| `listAuthorizedKeys()` 등 키 3종      | 파일 쓰기 후     | `busy` 대상 아님. 미설치면 `not-installed`                                                        |
| `getLogs(kind, maxBytes?)`            | 읽은 뒤          | 기본 64 KB 꼬리. 잘린 첫 줄은 버림. 파일이 없으면 `contents: ""`. `path`는 Android 절대 경로      |

"접수 즉시"인 명령의 결과(성공, 실패)는 상태 이벤트로만 온다. Promise reject는 접수 거부(`busy`, `not-installed`, `unavailable`)뿐이다. 설치는 몇 분 걸리고 그 사이 JS가 리로드될 수 있어서 이렇게 했다.

상태 자동 갱신: 앱이 포그라운드로 돌아올 때(설정 화면, 권한 다이얼로그에서 복귀 포함), 네트워크가 바뀔 때, Linux 실행 중 30초마다 `device`, `addresses`, `exchange`, `serverId`, `hostKeyFingerprints`를 다시 읽는다. UI가 따로 새로고침할 함수는 없다.

### 2.3 오류 코드 (`PhoneRuntimeError.code`)

| 코드            | 언제                                                                                                                                                                            |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `busy`          | 수명 명령(start/stop/restartDaemon/retrySetup/startPrevious/reinstall)이나 설정 명령이 진행 중인데 또 수명·설정 명령이 옴. 설치 전체와 시작 대기(최대 120초)도 진행 중으로 친다 |
| `not-installed` | 키 명령인데 rootfs가 없음, 또는 `startPrevious`를 쓸 수 없음                                                                                                                    |
| `invalid-key`   | OpenSSH 공개키 한 줄이 아님(지원 타입: ed25519, rsa, ecdsa 256/384/521, sk-ed25519, sk-ecdsa)                                                                                   |
| `private-key`   | `-----BEGIN` 또는 `PRIVATE KEY`가 보임. 입력은 저장·로그하지 않는다                                                                                                             |
| `duplicate-key` | 같은 지문이 이미 있음                                                                                                                                                           |
| `not-found`     | 지울 지문이 없음, 알 수 없는 로그 종류                                                                                                                                          |
| `io-error`      | 파일·시스템 화면 실패. 네이티브가 모르는 코드도 여기로 바뀐다                                                                                                                   |
| `unavailable`   | 폰 런타임이 없는 빌드                                                                                                                                                           |

`message`는 영어 원문이다. UI는 코드로 문구를 고른다.

### 2.4 상태 전이

```
checking ──(미설치)──────────────▶ setup(install) ──성공──▶ ready(linux: running)
   │                                   │실패
   │                                   ▼
   │                              setup-failed ──retrySetup──▶ setup(…, 실패한 단계부터)
   │                                   └─startPrevious (update 실패만)──▶ ready(starting)
   ├─(설치됨, desired=running)──▶ ready(starting) ─▶ (버전 다름이면 setup(update))
   └─(설치됨, desired=stopped)──▶ ready(stopped)
ready: stopped ─start─▶ starting ─데몬 HTTP 응답─▶ running ─stop─▶ stopping ─▶ stopped
```

- 첫 실행: `state.json`이 없으면 `desired`는 `"running"`으로 본다. 그래서 첫 실행에서 버튼 없이 설치가 바로 시작된다(`checking` → `setup`). 실패 후 앱을 다시 켜도 실패한 단계부터 자동으로 다시 시도한다(`state.json`의 `failedStep`).
- 업데이트 판정은 `start()` 때만 한다(앱 시작의 자동 start 포함). 설치돼 있고 멈춘 상태로 앱을 열면 `ready(stopped)`가 먼저 보이고, Start를 누르면 그때 `setup(update)`로 바뀔 수 있다.
- `setup.step`은 `download → extract → configure → packages → paseo → start`. update는 데몬만 바뀌면 `paseo`부터, Node 핀이 바뀌면 `download`부터. `start` 단계는 컨트롤러가 데몬·sshd를 띄우고 데몬 HTTP 응답을 기다린다(120초). 실패하면 `setup-failed(step: "start", code: "command-failed", message: 데몬 로그 마지막 줄)`.
- `ready.linux`:
  - `starting`: Start 직후. 데몬이 처음 응답하면 `running`. 120초 안에 응답이 없으면 `linux`는 `running`, `daemon.state`는 `error("not-responding")`.
  - `running` 중 데몬이 죽으면 `daemon.state = error("crashed" | "killed-by-system")` → 백오프(1, 2, 4, 8초) 후 `starting` → `running`. `linux`는 `running` 유지.
  - 데몬이 5분 안에 5번 죽으면 sshd까지 멈추고 서비스가 끝난다: `linux = error("restart-limit", "… Last error: <원인>")`.
  - sshd가 5번 죽으면 `ssh.state = error("restart-limit")`, Linux와 데몬은 그대로.
  - 데몬이 실행 중에 30초 간격 3번 연속 응답이 없으면 `daemon.state = error("not-responding")`. 프로세스는 죽이지 않는다. 다시 응답하면 `running`으로 돌아온다.
- `ssh.state`는 `ssh.enabled === false`면 항상 `stopped`. sshd는 proot가 2초 살아 있으면 `running`으로 본다.
- `exchange.status`: 권한 없음 → `no-permission`. 권한이 있는데 Linux가 바인드 없이 시작됐음 → `restart-required`(Linux 재시작 안내). 폴더 생성 실패 → `error`. 그 외 `mounted`(Linux가 멈춰 있어도 다음 시작 때 바인드된다는 뜻).
- `hostKeyFingerprints` 항목 형식: `"ssh-ed25519 SHA256:…"` (타입 + 공백 + 지문).
- `daemon.version`: 설치된 `@getpaseo/cli` 버전, 없으면 빌드에 박힌 `PASEO_DAEMON_VERSION`.

예시 (`ready`, 실행 중, 데몬이 시스템에 죽은 직후):

```json
{
  "status": "ready",
  "linux": { "state": "running" },
  "daemon": {
    "state": "error",
    "code": "killed-by-system",
    "message": "Android force-stopped the process. …",
    "serverId": "srv_…",
    "version": "0.14.0",
    "listen": "127.0.0.1:6767"
  },
  "ssh": {
    "enabled": true,
    "port": 2222,
    "state": { "state": "running" },
    "hostKeyFingerprints": ["ssh-ed25519 SHA256:…"]
  },
  "keepAwake": true,
  "distro": "Debian 13",
  "addresses": [
    { "kind": "wifi", "address": "192.168.1.23" },
    { "kind": "tailscale", "address": "100.101.102.103" }
  ],
  "exchange": {
    "status": "mounted",
    "phonePathLabel": "내부 저장공간/Paseo",
    "phonePath": "/storage/emulated/0/Paseo",
    "linuxPath": "/root/exchange"
  },
  "device": {
    "batteryOptimized": true,
    "phantomProcessMonitor": "disabled",
    "notificationsAllowed": true
  }
}
```

예시 (`setup`, 다운로드 중):

```json
{
  "status": "setup",
  "mode": "install",
  "step": "download",
  "progress": { "done": 74000000, "total": 182000000, "unit": "bytes" },
  "targetVersion": "0.14.0"
}
```

`message` 중 런타임이 만드는 것(`killed-by-system`, `crashed`, `not-responding`, `restart-limit`)은 기기 언어(en/ko) `strings.xml`에서 온다. 설치 실패 `message`는 SetupRunner 원문(번역 안 함).

### 2.5 알림

채널 `linux_runtime`(LOW), 알림 ID 하나. 서비스가 사는 동안만 있다.

| 상태                             | 제목 / 본문                                                | 진행                                       | 액션 / 탭                                                                                            |
| -------------------------------- | ---------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| setup(install)                   | Setting up Linux / `{단계} · {n}%`(다운로드), 그 외 단계명 | 다운로드는 결정형, 그 외 비결정형          | 탭 `paseo-phone://`                                                                                  |
| setup(update)                    | Updating Linux / 단계명                                    | 비결정형                                   | 탭 `paseo-phone://`                                                                                  |
| setup-failed                     | Linux setup failed / Tap to see what went wrong            | 없음. 쓸어 지울 수 있음, 서비스는 종료     | 탭 `paseo-phone://`                                                                                  |
| linux starting / stopping        | Starting Linux / Stopping Linux                            | 비결정형                                   | 탭 `paseo-phone://`                                                                                  |
| running                          | Running / `ssh <주소>:2222 · Awake`, SSH off, No network   | 없음. 주소 2개면 확장 시 둘 다(Wi-Fi 우선) | 탭 `paseo-phone://settings/hosts/<serverId>`, **Stop** → `…/settings/hosts/<serverId>?confirmStop=1` |
| running + 데몬 not-responding    | Daemon is not responding / Tap to open logs                | 없음. 쓸어 지울 수 있음                    | 탭 `…/settings/hosts/<serverId>?logs=daemon`, **Restart** → 앱을 열지 않고 `restartDaemon()`         |
| stopped, error(restart-limit 등) | 알림 없음(서비스 종료)                                     |                                            |                                                                                                      |

- 알림 권한이 없을 때 서비스가 시작되면 Android가 알림을 버린다. 서비스가 마지막 알림과 표시 여부를 기억하고, 컨트롤러가 상태를 발행할 때마다(앱 포그라운드 복귀, 30초 주기 포함) 권한이 새로 생겼으면 `startForeground`로 다시 올린다.
- `serverId`를 아직 모르면 경로 부분 없이 `paseo-phone://` (+쿼리)로 연다.
- UI가 처리할 쿼리: `confirmStop=1`(Linux 섹션의 Stop 확인 대화상자를 띄움), `logs=daemon`(데몬 로그를 보여줌). expo-router가 받는다.

## 3. 아키텍처와 다른 점과 이유

| 항목                            | 아키텍처           | 구현                                                                                | 이유                                                                                                                    |
| ------------------------------- | ------------------ | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 수명 명령 Promise               | 명시 없음          | 접수 즉시 resolve, 결과는 상태로                                                    | 설치는 몇 분, JS 리로드를 넘어간다                                                                                      |
| 첫 설치 시작                    | `start()`가 시작   | 첫 실행에 자동 시작(`desired` 기본 `running`)                                       | `PhoneRuntimeState`에 "미설치·대기" 상태가 없다. 게이트는 `checking`에서 설치 화면을 그리므로 사용자가 누를 버튼이 없다 |
| `PhoneRuntimeErrorCode`         | 7개                | `unavailable` 추가                                                                  | iOS·웹·일반 빌드에서 크래시 대신 코드로 거부                                                                            |
| 키·로그 명령의 `busy`           | 모든 명령 직렬화   | 키·로그·시스템 화면은 `busy` 대상 아님(같은 스레드에서 실행되므로 상태 경합은 없음) | 설치 중에도 로그를 볼 수 있어야 한다                                                                                    |
| `exchange/AgentInstructions.kt` | 있음               | 없음. 서비스 시작마다 `agent-instructions.sh` 실행(30초 제한, 실패는 경고 로그만)   | internal-contract.md                                                                                                    |
| 알림 Restart 액션               | 디자인 §7.2        | 서비스 인텐트 → `restartDaemon()`                                                   | —                                                                                                                       |
| 알림 문구                       | 디자인 §7은 영어만 | en·ko                                                                               | 담당 지시                                                                                                               |
| `state.json`의 `rootfsSha256`   | 기록               | 기록 안 함                                                                          | `SetupResult.Success`가 값을 주지 않는다                                                                                |
| sshd `running` 판정             | 명시 없음          | proot가 2초 생존                                                                    | 포트 2222 확인은 필요하면 추가                                                                                          |

## 4. 팀원에게 필요한 것 (팀장 경유)

core-linux (커밋 `a786c82c7`에서 해결, [core-linux.md](core-linux.md)):

- 서비스 시작마다 `SetupRunner.installScripts()`로 스크립트를 `/opt/paseo-phone`에 복사한 뒤 `agent-instructions.sh`, `run-daemon.sh`, `run-sshd.sh`를 돌린다.
- 재설치는 `paths.deleteRootfs()`로 지운다. `deleteRecursively()`는 rootfs 안의 링크를 따라가 교환 폴더의 사용자 파일까지 지울 수 있다.
- 로그 회전(5 MB → `.1`)은 `ProotProcess`의 쓰기 경로가 하고, `onExit`는 종료 뒤 등록해도 불린다. 컨트롤러는 따로 하지 않는다.
- `PASEO_PHONE_NODENAME`은 `ProotProcess`가 넣는다. 컨트롤러는 `PASEO_DAEMON_VERSION`, `NODE_VERSION`, `PASEO_PHONE_SSH_PORT=2222`만 `ProotSpec.env`에 넣는다.
- `build.gradle`: `org.tukaani:xz:1.12`(tar.xz), `testImplementation junit:junit:4.13.2`. 코루틴과 androidx.core는 `useCoreDependencies()`의 expo-modules-core가 `api`로 준다.
- 남은 요청: `no-space`일 때 `message`에 여유/필요 용량을 넣어 주면 UI가 그대로 보여준다(디자인 §3.4는 숫자를 원한다).

core-rootfs:

1. **일반 Paseo 변형에서 이 모듈을 autolinking에서 빼 달라**(`app.config.js`/`package.json`의 `expo.autolinking.exclude` 등). 빼지 않으면 일반 APK에 `MANAGE_EXTERNAL_STORAGE`, FGS 권한, proot `.so`가 들어간다. 네이티브 init 가드는 있어서 동작은 안 하지만 권한과 용량은 남는다.
2. 스크립트 이름은 `run-daemon.sh`, `run-sshd.sh`, `agent-instructions.sh`를 그대로 쓴다. 모두 `/bin/bash /opt/paseo-phone/<이름>`으로 실행한다.
3. 알림 딥링크는 scheme `paseo-phone`을 하드코딩한다. 변형의 scheme이 바뀌면 알려 달라.

UI 개발자:

- `confirmStop=1`, `logs=daemon` 쿼리 처리(§2.5).
- `exchange.status === "restart-required"` 문구(디자인 상태 표에 한 줄 추가, architecture §5.2).

## 5. 다음 라운드 메모

- 통합 빌드 컴파일 오류는 이 경계부터 본다: Expo `AsyncFunction(...) Coroutine { -> }` 문법, `ServiceCompat.startForeground` 타입 인자, core-linux 시그니처.
- QA에 넘길 확인 항목: 첫 실행 자동 설치, 앱을 나가도 설치 계속, 알림 Stop → 확인 대화상자, Restart 액션, phantom killer 켠 상태에서 `killed-by-system` 표시, 화면 꺼짐 30분 이상 SSH 응답성, 일반 Paseo APK에서 설치가 안 시작됨.
- 단위 테스트: `android/src/test/.../ssh/AuthorizedKeysTest.kt`(파서, 지문, 개인 키 거부), `RestartPolicyTest.kt`(백오프, 5분 5회 제한). `:paseo-phone-runtime:testReleaseUnitTest`로 돈다. 테스트가 JVM에서 돌도록 `android.util.Base64` 대신 `java.util.Base64`를 쓴다.
