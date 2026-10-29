# 폰 개발 머신: 아키텍처

> 작성: 아키텍트 · 1차 사이클 · 2026-10-30
> 입력: [original-plan.md](../original-plan.md), [design/design.md](../design/design.md), [spike-report.md](spike-report.md), `external/Kai`, Paseo v0.14.0 코드
> 이 문서가 core·UI·QA·코드 리뷰의 기준이다. 바꿔야 하면 이 문서를 먼저 고친다.

## 0. 결정 요약

| #   | 결정                                                                                                                                                                                                                                                |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | 리눅스 레이어는 Expo 로컬 네이티브 모듈 `packages/app/modules/paseo-phone-runtime`(Kotlin, Android 전용) 하나에 둔다. Kai의 `linux/` 코드를 옮기고, Compose·Kai 의존은 버린다.                                                                      |
| D2  | proot는 Kai의 `build-proot.sh`로 소스에서 빌드한 **arm64-v8a 전용** `libproot.so`·`libproot-loader.so`·`libtalloc.so`를 모듈의 `jniLibs`에 커밋한다. `useLegacyPackaging`으로 `nativeLibraryDir`에 풀어 실행한다.                                   |
| D3  | rootfs는 **Debian 13 trixie**, proot-distro가 배포하는 aarch64 tarball(URL·sha256 고정)이다. Kai의 LXC bookworm(고정 안 됨, 체크섬 없음)은 쓰지 않는다.                                                                                             |
| D4  | proot 실행 인자는 스파이크에서 검증한 proot-distro `login`과 같게 맞춘다(`--kill-on-exit --link2symlink --sysvipc -L --change-id=0:0 --kernel-release=…` + 가짜 `/proc` 파일 바인드). Kai의 최소 인자(`/dev /proc /sys`만)로는 Node가 깨질 수 있다. |
| D5  | 데몬은 **공개 npm `@getpaseo/cli@<업스트림 기준 버전>`** 을 rootfs 안에서 `npm i -g`로 설치한다. 이 포크는 데몬 코드를 고치지 않는다. Node는 nodejs.org 공식 tarball, 버전은 레포 `.tool-versions`(현재 22.20.0).                                   |
| D6  | 첫 기동 전에 데몬 `config.json`을 써서 받아쓰기(로컬 음성 모델 다운로드)를 끄고, 쓰지 않는 프로바이더(copilot, pi, omp)를 끄고, `listen`을 `127.0.0.1:6767`로 고정한다.                                                                             |
| D7  | 포그라운드 서비스(`specialUse`) 하나가 proot 프로세스 둘(데몬, sshd)을 소유한다. 각 프로세스는 **자기 proot 세션의 포그라운드 자식**이다. 재시작은 지수 백오프, 5분 안에 5번 실패하면 멈추고 오류 상태.                                             |
| D8  | **데몬은 loopback(`127.0.0.1:6767`)에만 연다.** 데스크톱 Paseo는 기존 Remote SSH 연결(`ssh://root@<폰>:2222`, 내부적으로 `ssh -W 127.0.0.1:6767`)로 붙는다. 데몬 비밀번호, LAN 바인딩, 릴레이는 없다. (디자인 Q1 채택)                              |
| D9  | sshd는 rootfs 안 OpenSSH, **포트 2222, 사용자 root, 키 인증만**. 호스트 키는 rootfs에 한 번 생성.                                                                                                                                                   |
| D10 | "이 기기" 호스트는 **빌드 시 `EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767`** 로 Paseo의 기존 로컬 데몬 부트스트랩(재시도 포함)을 켜서 페어링 없이 등록한다. Paseo의 `useLocalDaemonServerId`("로컬 데몬") 개념은 **쓰지 않는다**(§4.2).                 |
| D11 | Paseo 본체 수정은 **3개 파일**: `packages/app/app.config.js`, `packages/app/src/app/_layout.tsx`, `packages/app/src/screens/settings/host-page.tsx`. 나머지는 모두 새 파일이다.                                                                     |
| D12 | 별도 설치: `APP_VARIANT=phone` → 앱 이름 "Paseo Phone", applicationId `io.github.ouor.paseophone`, URL scheme `paseo-phone`.                                                                                                                        |
| D13 | 교환 폴더: 공유 저장소 `/storage/emulated/0/Paseo` ↔ rootfs `/root/exchange`(proot 바인드). 권한은 `MANAGE_EXTERNAL_STORAGE`. 세 에이전트의 전역 지침 파일에 관리 블록을 넣는다.                                                                    |
| D14 | 기기 설정(phantom process killer, 배터리 최적화)은 앱이 바꾸지 않는다. 앱은 **감지해서 알리기만** 한다.                                                                                                                                             |

소유자 확인이 필요한 결정은 §10에 모았다.

---

## 1. 전체 구조

```
┌──────────────────────── Android 앱 프로세스 (io.github.ouor.paseophone) ────────────────────────┐
│                                                                                                  │
│  React Native (Paseo 앱 + src/phone/*)                                                           │
│     │  directTcp ws://127.0.0.1:6767  (기존 Paseo 연결 코드 그대로)                              │
│     │  PaseoPhoneRuntime JS API (상태 구독 + 명령)                                               │
│     ▼                                                                                            │
│  modules/paseo-phone-runtime (Kotlin)                                                            │
│     RuntimeController (싱글턴 상태 기계) ── RuntimeService (포그라운드 서비스, 알림, wake lock)   │
│        │ ProcessBuilder                         │ ProcessBuilder                                 │
│        ▼                                        ▼                                                │
│   proot ─ bash -lc 'exec paseo daemon run'   proot ─ /usr/sbin/sshd -D -e                        │
│        │  127.0.0.1:6767                         │  0.0.0.0:2222                                 │
│        ▼                                        ▼                                                │
│   ┌──────────────── rootfs: filesDir/phone-runtime/rootfs (Debian 13) ─────────────────┐          │
│   │ /usr/local/bin/{node,npm,paseo,claude,codex,opencode}   /root/.paseo/  /root/exchange ─┼── bind ─→ /storage/emulated/0/Paseo
│   └────────────────────────────────────────────────────────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ ssh -p 2222 root@<폰>  (터미널, VS Code Remote-SSH)
        ▲ ssh -W 127.0.0.1:6767 (데스크톱 Paseo Remote SSH 호스트)
   데스크톱 ── LAN 또는 Tailscale
```

폰 안 Paseo 앱과 데스크톱 Paseo 앱은 같은 데몬을 본다. 폰 앱은 loopback으로, 데스크톱은 sshd를 거친 터널로 붙는다.

---

## 2. 리눅스 레이어 (Kai 이식)

### 2.1 위치와 모듈 구성

Expo 로컬 모듈은 `packages/app/modules/` 아래에 두면 자동 링크된다(기존 `paseo-scroll`, `paseo-hardware-keyboard`와 같은 방식). Android 전용이라 iOS 디렉터리는 없다.

```
packages/app/modules/paseo-phone-runtime/
  package.json                       # name: paseo-phone-runtime
  expo-module.config.json            # platforms: ["android"], android.modules: [PhoneRuntimeModule]
  index.ts                           # JS API (§7.2). requireOptionalNativeModule
  src/types.ts                       # 상태·명령 타입 (§7.1) — UI와의 계약
  proot/
    build-proot.sh                   # Kai build-proot.sh 복사, arm64 전용으로 축소
    PINS.md                          # proot 커밋, talloc 버전, NDK 버전, 빌드 산출물 sha256
  android/
    build.gradle                     # BuildConfig에 PASEO_DAEMON_VERSION, NODE_VERSION 주입 (§3.1)
    src/main/AndroidManifest.xml     # 권한, 서비스 선언 (앱 매니페스트에 병합됨)
    src/main/jniLibs/arm64-v8a/      # libproot.so, libproot-loader.so, libtalloc.so
    src/main/assets/phone-runtime/   # configure.sh, packages.sh, paseo.sh, sshd_config, agent-instructions.md
    src/main/res/values{,-ko}/strings.xml   # 알림 문구 (en, ko)
    src/main/java/io/github/ouor/paseophone/runtime/
      PhoneRuntimeModule.kt          # Expo Module 정의: Function/AsyncFunction/Events
      RuntimeController.kt           # 단일 상태 기계, 상태 스냅샷 발행, 명령 직렬화
      RuntimeService.kt              # 포그라운드 서비스: 알림, wake lock, wifi lock, 자식 감시
      RuntimeStore.kt                # filesDir/phone-runtime/state.json 읽기/쓰기 (원자적)
      linux/
        LinuxPaths.kt                # (Kai) 경로, libtalloc.so.2 복사
        RootfsSpec.kt                # (Kai DistroSpec 대체) trixie aarch64 URL + sha256
        Downloader.kt                # (Kai RootfsDownloader) HttpURLConnection, 이어받기, sha256 검증
        TarExtractor.kt              # (Kai) + pax/GNU 긴 이름, strip-components
        ProotCommand.kt              # (Kai ProotLauncher) argv/env 생성 (§2.4)
        ProotProcess.kt              # 장기 실행 proot 프로세스 핸들: 시작/정지/종료 신호 판정
        ProotExec.kt                 # 일회성 명령 실행, 로그 스트리밍, 타임아웃
      setup/
        SetupRunner.kt               # 단계 실행·재시도 (§2.5)
      ssh/
        AuthorizedKeys.kt            # 공개키 검증·지문·원자적 쓰기
        HostKeys.kt                  # 호스트 키 지문 읽기
      exchange/
        ExchangeFolder.kt            # 권한 확인, 폴더 생성, 표시 문자열
        AgentInstructions.kt         # 전역 지침 관리 블록 쓰기 (§5.3)
      system/
        DeviceChecks.kt              # 배터리 최적화, phantom 설정 읽기 시도
        NetworkAddresses.kt          # Wi-Fi / Tailscale 주소
        DaemonProbe.kt               # 127.0.0.1:6767 준비 확인, server-id 읽기
      notify/
        RuntimeNotification.kt       # 채널 linux_runtime, 상태별 알림
```

### 2.2 Kai에서 가져오는 것

Kai 경로는 `external/Kai/` 기준. 원본은 Apache-2.0이므로 각 파일 머리에 출처와 라이선스를 남긴다.

| Kai 파일                                                                                                             | 가져오는 곳                                   | 바꾸는 것                                                                                                                                                                                                                                                             |
| -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `build-proot.sh`                                                                                                     | `proot/build-proot.sh`                        | ABI를 arm64-v8a 하나로 줄인다(loader32 빌드 제거). termux/proot 커밋(현재 `4dba3af…`)과 talloc 2.4.3을 `PINS.md`에 고정한다. 산출물 sha256을 `PINS.md`에 적는다.                                                                                                      |
| `androidApp/src/main/jniLibs/arm64-v8a/{libproot,libproot-loader,libtalloc}.so`                                      | `android/src/main/jniLibs/arm64-v8a/`         | 우리 스크립트로 다시 빌드한 산출물로 교체. `libproot-loader32.so`는 넣지 않는다.                                                                                                                                                                                      |
| `composeApp/src/androidMain/.../linux/LinuxPaths.kt`                                                                 | `linux/LinuxPaths.kt`                         | 두 디렉터리(legacy) 마커 로직 제거. 루트를 `filesDir/phone-runtime`으로. `copyLibtalloc()` 유지(`libtalloc.so` → `libtalloc.so.2`).                                                                                                                                   |
| `.../linux/DistroSpec.kt`, `LinuxDistro.kt`, `PackageManagerSpec.kt`, `AptPackageManager.kt`, `ApkPackageManager.kt` | `linux/RootfsSpec.kt` 하나                    | Alpine과 LXC 인덱스 조회 삭제. 고정 URL + sha256 + strip-components 값 하나만 둔다.                                                                                                                                                                                   |
| `.../linux/RootfsDownloader.kt`                                                                                      | `linux/Downloader.kt`                         | Ktor 제거 → `HttpURLConnection`. 캐시 디렉터리에 저장, `Range`로 이어받기, 끝나면 sha256 검증, 진행률 콜백(bytes).                                                                                                                                                    |
| `.../linux/TarExtractor.kt`                                                                                          | `linux/TarExtractor.kt`                       | pax(`x`) 헤더의 `path`/`linkpath`, GNU `L`/`K` 긴 이름 지원. 첫 경로 요소 제거(strip-components 1). 실행 비트 외 모드(0755/0644)도 반영. 절대 경로 심볼릭 링크를 지나는 항목을 건너뛰는 버그(경로 검사가 링크를 따라감)를 고친다: 검사는 `normalize()`로 문자열 기준. |
| `.../linux/LinuxInstaller.kt`                                                                                        | `setup/SetupRunner.kt`                        | 다시 쓴다. Kai의 "실패하면 전부 지우고 아카이브도 지움" 대신 단계별 재시도(§2.5).                                                                                                                                                                                     |
| `.../linux/ProotLauncher.kt`                                                                                         | `linux/ProotCommand.kt`, `linux/ProotExec.kt` | 인자를 proot-distro 수준으로(§2.4). `smartTruncate` 제거. 출력은 로그 파일로 스트리밍.                                                                                                                                                                                |
| `.../linux/LinuxInstalls.kt`                                                                                         | `RuntimeStore.kt`에 흡수                      | 설치 마커를 `state.json`의 필드로.                                                                                                                                                                                                                                    |

가져오지 않는 것: `LinuxSandboxManager.kt`(Compose 상태), `SessionShell.kt`, `PersistentSandboxShell.kt`·`ProotExecutor.kt`(채팅용 파이프 셸), `BuildProotExecutor.kt`·`BuildEnvironmentManager.kt`(Kai Build용 PTY). 사용자 터미널은 Paseo 데몬의 터미널(node-pty, 스파이크에서 동작 확인)과 SSH가 맡는다.

라이선스: proot는 GPL-2.0, talloc은 LGPL-3.0이다. 바이너리를 GitHub Releases로 배포하므로 `proot/build-proot.sh`와 `PINS.md`(소스 커밋)를 레포에 두는 것으로 소스 제공 의무를 채우고, 앱 정보 화면이 아니라 `modules/paseo-phone-runtime/THIRD_PARTY_NOTICES.md`에 고지한다.

### 2.3 rootfs 레이아웃

```
filesDir/phone-runtime/
  rootfs/                 # Debian 13. 사용자 데이터(/root)도 여기. 재설치 시 삭제됨
  tmp/                    # PROOT_TMP_DIR, rootfs /tmp 바인드
  sysdata/                # 가짜 /proc 파일 (§2.4)
  libtalloc.so.2
  logs/{setup,daemon,sshd}.log   # 5MB에서 .1로 회전, 1개 보관
  state.json              # §7.1의 영속 부분
cacheDir/phone-runtime/downloads/   # rootfs·node tarball (검증 후 보관, 설치 성공 시 삭제)
```

`/root`를 외부 저장소로 빼지 않는다. 공유 저장소는 noexec, 심볼릭 링크·권한 비트 불가라서 레포와 `node_modules`가 깨진다(기획서 "작업 공간은 온전한 리눅스 파일시스템에").

### 2.4 proot 실행 (ProotCommand)

스파이크에서 동작을 확인한 proot-distro 5.10.3의 `login` 인자와 같게 만든다. Kai 인자(`--bind=/dev --bind=/proc --bind=/sys -0`)만으로는 Android가 막는 `/proc/stat`, `/proc/loadavg` 등을 Node(`os.cpus()`, `os.loadavg()`)가 읽다 실패한다.

```
<nativeLibraryDir>/libproot.so
  --kill-on-exit --link2symlink --sysvipc -L
  --kernel-release=\Linux\<nodename>\6.17.0-PRoot\#1 SMP PREEMPT_DYNAMIC\aarch64\localdomain\-1\
  --change-id=0:0
  --rootfs=<root>/rootfs --cwd=/root
  --bind=/dev --bind=/proc --bind=/sys --bind=/dev/urandom:/dev/random
  --bind=<root>/sysdata/empty:/sys/fs/selinux
  --bind=<root>/sysdata/{loadavg,stat,uptime,version,vmstat}:/proc/{…}
  --bind=<root>/sysdata/cap_last_cap:/proc/sys/kernel/cap_last_cap
  --bind=<root>/sysdata/max_user_watches:/proc/sys/fs/inotify/max_user_watches
  --bind=<root>/sysdata/overflowuid:/proc/sys/kernel/overflowuid
  --bind=<root>/sysdata/overflowgid:/proc/sys/kernel/overflowgid
  --bind=<root>/tmp:/tmp  --bind=<root>/tmp:/dev/shm
  [--bind=/storage/emulated/0/Paseo:/root/exchange]      # 권한이 있을 때만 (§5.2)
  /usr/bin/env -i <ENV…> <command…>
```

- 가짜 `/proc` 파일 내용은 proot-distro의 `setup_fake_sysdata` 출력과 같게 정적 문자열로 만든다(proot-distro는 GPL-3이므로 코드를 복사하지 말고 내용만 맞춘다).
- `<nodename>`: `Build.MODEL`을 소문자·하이픈으로(예: `poco-f3`). 데몬은 이 값을 `hostname`으로 보고하고, Paseo 앱은 그걸 호스트 기본 라벨로 쓴다. 그래서 본체 수정 없이 호스트 이름이 "localhost"가 아닌 기기명이 된다.
- `/apex`, `/system` 등 Android 경로 바인드는 넣지 않는다(proot-distro는 Termux 연동 때문에 넣는다. 우리는 필요 없음).
- 환경: `HOME=/root`, `USER=root`, `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, `LANG=C.UTF-8`, `TERM=xterm-256color`, `TMPDIR=/tmp`. proot 프로세스 쪽 환경은 `PROOT_TMP_DIR=<root>/tmp`, `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`, `LD_LIBRARY_PATH=<root>`.
- 실행 위치: proot 바이너리는 `nativeLibraryDir`(실행 허용)에서, rootfs 안 바이너리는 proot loader가 매핑한다. targetSdk 29+의 W^X 제한은 이 방식으로 피한다(Kai, Termux와 같음).

### 2.5 설치·업데이트 단계 (SetupRunner)

| 단계        | 하는 일                                                                                                                                                                                                                                           | 재시도 시                                         |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| (사전 검사) | `/data` 여유 공간 ≥ 4 GB 확인. 부족하면 `download` 단계 실패로 보고(`no-space`).                                                                                                                                                                  | —                                                 |
| `download`  | rootfs tarball, Node tarball(`node-v<NODE_VERSION>-linux-arm64.tar.xz`, nodejs.org `SHASUMS256.txt`로 검증)을 캐시에 받는다.                                                                                                                      | 검증된 파일이 있으면 건너뜀, 부분 파일은 이어받기 |
| `extract`   | `rootfs/`를 지우고 tarball을 푼다. Node를 `rootfs/usr/local`에 푼다(strip 1). `libtalloc.so.2` 복사, `sysdata/` 생성.                                                                                                                             | 항상 처음부터(부분 rootfs 삭제)                   |
| `configure` | `assets/configure.sh`: `resolv.conf`, `hosts`, `apt.conf.d` 샌드박스 사용자 root(Kai), dpkg `force-unsafe-io`, `ssh-keygen -A`, `sshd_config.d/phone.conf`, `/root/.ssh` 생성.                                                                    | 다시 실행(멱등)                                   |
| `packages`  | `assets/packages.sh`: `apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git openssh-server build-essential python3 procps xz-utils ripgrep unzip less nano`. 진행률은 `Setting up` 줄 수 / `apt-get -s` 결과 수. | 다시 실행(apt 멱등)                               |
| `paseo`     | `assets/paseo.sh`: 데몬 `config.json` 쓰기(§3.3) → `npm i -g --no-fund --no-audit @getpaseo/cli@<PASEO_DAEMON_VERSION> @anthropic-ai/claude-code @openai/codex opencode-ai` → 전역 지침 쓰기(§5.3).                                               | 다시 실행(npm 멱등)                               |
| `start`     | 서비스가 데몬·sshd를 띄우고 데몬 준비(§3.4)까지 기다린다(최대 120초).                                                                                                                                                                             | 다시 실행                                         |

- 성공하면 `state.json`에 `installed`, 설치된 버전들(`daemonVersion`, `nodeVersion`, `rootfsSha256`)을 쓰고 다운로드 캐시를 지운다.
- **업데이트 판정(디자인 Q13):** 서비스 시작 시 `rootfs/usr/local/lib/node_modules/@getpaseo/cli/package.json`의 버전이 `PASEO_DAEMON_VERSION`과 다르면 `mode: "update"`로 `paseo` → `start`만 돈다. Node 버전이 다르면 `download`(Node만) → Node 재설치 → `paseo` → `start`. 업데이트 실패 시 이전 데몬은 그대로 있으므로, 오류를 보여주되 "Retry"와 함께 기존 데몬으로 시작할 수 있게 둔다(`setup-failed` + `canStartPrevious: true`).
- apt 패키지와 에이전트 CLI 업그레이드는 앱이 하지 않는다. 사용자가 터미널에서 `apt upgrade`, `npm i -g …`, 또는 에이전트 자체 업데이트를 쓴다.
- 모든 단계 출력은 `logs/setup.log`에 쌓인다. 실패 메시지는 마지막 비어 있지 않은 stderr 줄.

예상 용량(스파이크 기준 추정, QA가 실측해서 이 표를 고친다): 다운로드 rootfs ~30 MB + Node ~27 MB + apt ~150 MB + npm ~350 MB. 설치 후 rootfs 약 2 GB. 사전 검사 기준 4 GB는 여유를 둔 값이다.

---

## 3. 데몬

### 3.1 설치 경로와 버전

**결정:** 공개 npm `@getpaseo/cli`를 rootfs 안에서 설치한다. 버전은 이 포크가 기반으로 삼은 업스트림 릴리스(`packages/server/package.json`의 `version`)와 같게 한다.

이유:

- 스파이크에서 그대로 동작했다. node-pty와 sherpa-onnx가 linux-arm64 prebuild를 갖고 있어 폰에서 컴파일이 없다.
- 폰에서 이 레포를 빌드하는 건 비현실적이다(파일 작업 20~50배 느림).
- 포크가 데몬을 고치지 않으므로 업스트림 패키지가 곧 우리 데몬이다. 업스트림을 따라가면 버전 숫자만 바뀐다.

구현: 모듈 `android/build.gradle`이 빌드 시 `packages/server/package.json`의 `version`과 루트 `.tool-versions`의 `nodejs` 값을 읽어 `BuildConfig.PASEO_DAEMON_VERSION`, `BuildConfig.NODE_VERSION`에 넣는다. 업스트림 머지 후 손으로 고칠 상수가 없다.

예외 경로(1차에서 구현하지 않음): 데몬 패치가 필요해지면 PC에서 `npm pack`한 tarball을 APK assets에 넣고 `npm i -g <tarball>`로 설치한다. 이 경로가 필요해지면 이 문서를 먼저 고친다.

업스트림 릴리스가 npm에 아직 없으면 `paseo` 단계가 실패한다. 업스트림을 머지한 뒤 `npm view @getpaseo/cli@<버전>`으로 게시 여부를 확인하고 APK를 만든다(§9 빌드 노트).

### 3.2 실행

```
proot … /usr/bin/env -i <ENV> /bin/bash -lc 'exec paseo daemon run'
```

- `paseo daemon start`(분리 실행)를 쓰지 않는다. proot `--kill-on-exit`와 서비스의 프로세스 감시가 "proot 프로세스 = 데몬"을 전제로 한다(스파이크 발견).
- `paseo daemon run`은 내부 supervisor가 워커 크래시를 재시작한다. 서비스는 proot 프로세스 자체가 끝났을 때만 개입한다.
- `PASEO_HOME`은 기본값 `/root/.paseo`.

### 3.3 첫 기동 전 config.json

`paseo` 단계가 `/root/.paseo/config.json`이 **없을 때만** 아래로 만든다. 이미 있으면 건드리지 않는다(사용자가 고친 설정 보존).

```json
{
  "version": 1,
  "daemon": { "listen": "127.0.0.1:6767", "relay": { "enabled": false } },
  "features": { "dictation": { "enabled": false }, "voiceMode": { "enabled": false } },
  "agents": {
    "providers": {
      "copilot": { "enabled": false },
      "pi": { "enabled": false },
      "omp": { "enabled": false }
    }
  }
}
```

- 받아쓰기를 끄면 첫 기동 때의 로컬 음성 모델(parakeet, 수백 MB) 다운로드가 일어나지 않는다고 판단한다(스파이크 로그에서 다운로드는 `dictationStt` 기본 활성에서 시작됨). **QA가 첫 기동 로그에 `model-downloader`가 없는지 확인한다.** 사용자는 나중에 설정에서 켤 수 있다.
- 프로바이더를 끄면 `daemon.get_status`가 매번 띄우는 `--version` 확인이 줄어든다(스파이크: 1.8~2.0초).
- Codex 샌드박스는 proot에서 동작하지 않는다(스파이크 §1). 기본 모드를 바꾸는 설정 키는 0.14.0 스키마에 없으므로 1차에서는 사용자가 Codex 에이전트를 만들 때 "Full Access"를 고른다. 전역 지침 파일(§5.3)에 이 사실을 적는다.

### 3.4 준비 판정 (DaemonProbe)

- `running`: proot 프로세스가 살아 있고 `127.0.0.1:6767`에 TCP 연결 + HTTP 응답(상태 코드 무관, 스파이크에서 `/`는 404)이 온다.
- `starting`: 프로세스는 살아 있지만 아직 응답 없음. 시작 후 120초까지.
- 응답이 30초 간격 3번 연속 없으면 `error("not-responding")`. 프로세스는 죽이지 않는다(사용자가 Restart).
- `serverId`: `rootfs/root/.paseo/server-id` 파일을 읽는다. UI가 이 기기 호스트를 식별하는 데 쓴다(§4).

---

## 4. "이 기기" 호스트와 Paseo 본체 접점

### 4.1 자동 등록 (페어링 없음)

빌드 스크립트가 `EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767`로 빌드한다. 업스트림 `host-runtime.ts`(`readConfiguredLocalDaemonOverride`, `bootstrapConfiguredOverride`)가 이 값이 있으면 앱 시작 때 그 주소를 등록될 때까지 재시도하며 probe하고 호스트로 넣는다. 데몬이 늦게 떠도(설치 직후, 재시작) 붙는다. 본체 수정 0줄.

호스트 라벨은 데몬이 보고하는 hostname(= proot `nodename`, §2.4)이다. 사용자는 기존 Appearance에서 바꾼다.

### 4.2 `useLocalDaemonServerId`를 쓰지 않는 이유 (디자인 Q5)

디자인은 이 hook이 폰 데몬의 serverId를 내도록 제안했다. 하지 않는다. 이 hook은 Paseo 전체에서 **"데몬과 앱이 같은 파일시스템을 본다"** 는 뜻으로 쓰인다:

- `file-explorer-pane.tsx`, `git/diff-pane.tsx`의 `isLocalExecution`, `terminal-pane.tsx`의 파일 드롭(`hostIsLocal`), `workspace/open-in-editor/*`, `open-in-file-manager/*`, `sidebar-workspace-list.tsx`의 로컬 경로 해석, `add-project-flow.tsx`의 폴더 찾아보기.
- `host-page.tsx`의 `RemoveHostSection`은 로컬 데몬이면 Electron 전용 `updateSettings`/`stopDesktopDaemon`을 부른다.

폰에서 앱은 `/data/data/…/rootfs/root/x`를, 데몬은 `/root/x`를 본다. 로컬로 판정하면 이 기능들이 틀린 경로로 동작하거나 Electron 브리지를 부르다 실패한다. 그래서 "이 기기"는 **폰 모듈 안의 별도 판정**(`serverId === state.daemon.serverId`)으로만 쓴다.

그 결과 1차에서 디자인과 달라지는 점:

| 디자인                                         | 1차 동작                                                                                         |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 호스트 목록 첫 줄 고정(`orderHostsLocalFirst`) | 정렬 바꾸지 않음. 폰에서는 이 기기 호스트가 보통 유일하거나 첫 등록 호스트라 실사용 차이가 작다. |
| 설명 줄 "This device"                          | 기존 그대로 `127.0.0.1:6767`. loopback 주소가 곧 "이 기기"라는 뜻이다.                           |
| 이 기기에서 Remove host 숨김                   | 숨기지 않음. 지우면 다음 앱 시작 때 §4.1 부트스트랩이 다시 등록한다.                             |
| Pair device 숨김 (Q6)                          | 숨기지 않음. 데몬 config에서 릴레이가 꺼져 있어 눌러도 동작하지 않는다(대안 수용).               |

나중에 이 셋이 불편하면 그때 본체 수정을 하나씩 추가하고 §4.3 목록에 올린다.

### 4.3 Paseo 본체 수정 목록 (업스트림 머지마다 다시 확인)

| 파일                                              | 수정                                                                                                                                                              | 크기  |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| `packages/app/app.config.js`                      | `variants`에 `phone` 추가(§6). `phone` 변형일 때만 `expo-build-properties.android.useLegacyPackaging: true`, `scheme: "paseo-phone"`, `extra.phoneRuntime: true`. | ~20줄 |
| `packages/app/src/app/_layout.tsx`                | `RuntimeProviders` 안에서 `<AppShell />`을 `<PhoneRuntimeGate>`로 감싼다. import 1줄 + JSX 2줄.                                                                   | 3줄   |
| `packages/app/src/screens/settings/host-page.tsx` | `HostSettingsPage`에서 `HostAppearanceSection` 다음에 `<PhoneHostSections serverId={serverId} />` 한 줄. import 1줄.                                              | 2줄   |

새 파일(`modules/paseo-phone-runtime/**`, `src/phone/**`, `scripts/phone/**`)은 업스트림과 충돌하지 않는다.

**암묵적 계약** — 파일을 고치지는 않지만 업스트림이 바꾸면 우리가 깨지는 것. 머지 때 같이 확인한다:

| 계약                                                                                                                                                   | 위치                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------- |
| `EXPO_PUBLIC_LOCAL_DAEMON` 부트스트랩과 재시도                                                                                                         | `packages/app/src/runtime/host-runtime.ts`                           |
| 호스트 라벨 기본값 = 데몬 hostname                                                                                                                     | 같은 파일 `label: input.label ?? hostname`                           |
| `HostSettingsPage`, `AppShell`/`RuntimeProviders` 구조                                                                                                 | 위 두 파일                                                           |
| `config.json` 키: `daemon.listen`, `daemon.relay.enabled`, `features.dictation.enabled`, `features.voiceMode.enabled`, `agents.providers.<id>.enabled` | `packages/server/src/server/persisted-config.ts`                     |
| `paseo daemon run`, `$PASEO_HOME/server-id`, `$PASEO_HOME/paseo.pid`                                                                                   | `packages/cli`, `packages/server/src/server/{server-id,pid-lock}.ts` |
| SSH 전송이 `ssh -W 127.0.0.1:<daemonPort>`를 쓴다                                                                                                      | `packages/protocol/src/ssh-transport.ts`                             |
| npm 패키지 이름 `@getpaseo/cli`, linux-arm64 prebuild(node-pty, sherpa-onnx) 포함                                                                      | npm                                                                  |

---

## 5. 데스크톱 접근, 보안, 교환 폴더

### 5.1 노출 정책 (디자인 Q1, Q7)

| 포트        | 바인딩        | 인증           | 접근 주체                                                      |
| ----------- | ------------- | -------------- | -------------------------------------------------------------- |
| 6767 (데몬) | `127.0.0.1`만 | 없음(loopback) | 폰 Paseo 앱, sshd 터널(`ssh -W`)                               |
| 2222 (sshd) | `0.0.0.0`     | 공개키만       | 데스크톱 터미널, VS Code Remote-SSH, 데스크톱 Paseo Remote SSH |

**Q1 결정: 디자인 안(Remote SSH)을 채택한다.** 스파이크에서 `PASEO_LISTEN=0.0.0.0`은 `authRequired: false`로 열렸다. 기획서의 "LAN/Tailscale 직접 연결"을 그대로 하면 데몬 비밀번호 설정·저장·UI가 추가로 필요하고, 비밀번호 하나가 에이전트 전체 권한을 지킨다. SSH 경로는 키 인증 하나로 셸·VS Code·Paseo를 모두 덮고, 데몬은 네트워크에 나가지 않는다. 경로(LAN/Tailscale)는 기획서와 같다. 바뀌는 건 "데몬 포트 직결"이 "sshd 경유"가 되는 것뿐이다.

근거 확인: Electron의 SSH 전송은 시스템 `ssh -T -o BatchMode=yes … -W 127.0.0.1:<daemonPort> host`를 쓴다(`packages/protocol/src/ssh-transport.ts`). 스파이크에서 proot sshd로 `-L` 포워딩이 동작했고 `-W`는 같은 `direct-tcpip` 채널을 쓴다. **데스크톱 앱으로의 실제 연결은 QA 1차 항목이다.**

주의: `BatchMode=yes`라서 처음 보는 호스트 키는 거부된다. 소유자는 데스크톱에서 먼저 `ssh -p 2222 root@<폰>`으로 한 번 접속해 호스트 키를 받아들여야 한다. 그래서 UI(Remote access)에 호스트 키 지문을 보여준다(`ssh.hostKeyFingerprints`).

sshd 설정(`/etc/ssh/sshd_config.d/phone.conf`):

```
Port 2222
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
AllowTcpForwarding local
X11Forwarding no
```

- 사용자는 `root`(proot가 uid 0으로 위장). 별도 사용자를 만들면 `change-id`·홈 경로·에이전트 설치 위치가 갈라져 얻는 것 없이 복잡해진다.
- 포트 2222 고정. Termux sshd 기본 8022와 겹치지 않게 했다(이 기기에 Termux가 있다). 포트 설정 UI는 두지 않는다.
- 호스트 키는 `configure` 단계에서 한 번 생성(`ssh-keygen -A`), rootfs 재설치 때만 바뀐다.
- authorized_keys는 모듈이 Android 쪽에서 `rootfs/root/.ssh/authorized_keys`를 직접 읽고 쓴다(원자적 교체, 0600). proot나 sshd 재시작이 필요 없다.

알려진 위험(수용): 폰의 **다른 앱**도 `127.0.0.1:6767`에 붙을 수 있다. 개인 기기이고 설치 앱을 소유자가 통제한다는 전제로 1차에서 수용한다. 막아야 하면 데몬 비밀번호(`PASEO_PASSWORD`) + 앱의 호스트 연결 비밀번호로 2차에 닫는다(§10).

### 5.2 교환 폴더 (디자인 Q8)

- 폰 쪽: `/storage/emulated/0/Paseo` (없으면 서비스가 만든다). 표시 문자열은 `StorageManager.primaryStorageVolume.getDescription(context) + "/Paseo"`(예: "내부 저장공간/Paseo").
- 리눅스 쪽: `/root/exchange` (`~/exchange`). proot `--bind`.
- 권한: `MANAGE_EXTERNAL_STORAGE`. proot는 앱 uid로 돌기 때문에 앱이 이 권한을 가져야 rootfs 안에서도 보인다. 처음 필요할 때 UI가 `requestStoragePermission()`으로 시스템 "모든 파일 접근" 화면을 연다.
- 바인드는 proot 시작 때 결정된다. 권한을 새로 받으면 `exchange.status = "restart-required"`가 되고, UI는 Linux 재시작을 안내한다(디자인 상태 표에 한 줄 추가 필요).
- 공유 저장소는 FUSE라 실행 비트·심볼릭 링크·하드링크가 안 된다. 교환 전용이다.

### 5.3 전역 에이전트 지침

`paseo` 단계와 매 서비스 시작 때 아래 세 파일에 관리 블록을 넣는다. 블록 밖의 사용자 내용은 건드리지 않는다. 파일이 없으면 만든다.

| 에이전트    | 파일                               |
| ----------- | ---------------------------------- |
| Claude Code | `/root/.claude/CLAUDE.md`          |
| Codex       | `/root/.codex/AGENTS.md`           |
| OpenCode    | `/root/.config/opencode/AGENTS.md` |

```markdown
<!-- paseo-phone:begin (managed by Paseo Phone; edits inside this block are overwritten) -->

## This machine

- You run inside Debian (proot) on an Android phone. There is no systemd, no Docker, no root kernel access.
- Keep repositories and dependencies under /root. Never work inside ~/exchange.
- ~/exchange is shared with Android apps. Use it only to receive or hand over files. It does not support symlinks or executable bits.
- File-heavy operations are slow here. Avoid repo-wide scans of node_modules and full rebuilds when a targeted command works.
- Codex sandboxing is unavailable on this machine.
<!-- paseo-phone:end -->
```

원문은 `assets/phone-runtime/agent-instructions.md` 하나에 두고 세 곳에 복사한다.

---

## 6. 별도 설치 (applicationId)

`app.config.js`의 `variants`에 추가:

```js
phone: {
  name: "Paseo Phone",
  packageId: "io.github.ouor.paseophone",
  googleServicesFile: undefined,
  googleServiceInfoPlist: undefined,
},
```

- 공식 Paseo(`sh.paseo`)와 나란히 설치된다. URL scheme도 `paseo-phone`으로 분리해 딥링크가 공식 앱과 섞이지 않게 한다.
- Firebase 설정이 없으므로 원격 푸시는 없다. F-Droid 빌드가 같은 조건으로 돌아가므로 앱은 동작한다(QA 확인 항목).
- 앱 아이콘은 1차에서 공식과 같다. 구분이 필요하면 2차에 `phone` 변형용 아이콘만 바꾼다.

---

## 7. 네이티브 코어 ↔ UI 계약

UI 개발자는 `modules/paseo-phone-runtime/index.ts`만 import한다. 네이티브 내부 구조는 UI 계약이 아니다.

### 7.1 상태 (`src/types.ts`)

디자인 Q2 안을 기반으로, 하위 상태를 문자열/객체 혼합 대신 판별 가능한 객체로 정리했다.

```ts
export type SetupStep = "download" | "extract" | "configure" | "packages" | "paseo" | "start";

export type SetupErrorCode = "offline" | "no-space" | "checksum" | "command-failed" | "unknown";

export interface SetupProgress {
  done: number;
  total: number;
  unit: "bytes" | "packages";
}

export type ServiceState =
  | { state: "starting" }
  | { state: "running" }
  | { state: "stopping" }
  | { state: "stopped" }
  | {
      state: "error";
      // killed-by-system: proot가 우리가 보내지 않은 SIGKILL로 끝남 (phantom killer, 저메모리)
      code: "crashed" | "killed-by-system" | "not-responding" | "start-failed" | "restart-limit";
      message: string;
    };

export interface NetworkAddress {
  kind: "wifi" | "tailscale";
  address: string; // IPv4
}

export type ExchangeStatus =
  | { status: "mounted" }
  | { status: "no-permission" }
  | { status: "restart-required" }
  | { status: "error"; message: string };

export interface DeviceChecks {
  batteryOptimized: boolean; // true = 최적화 대상(경고)
  phantomProcessMonitor: "disabled" | "enabled" | "unknown"; // Settings.Global 읽기 실패 시 unknown
  notificationsAllowed: boolean;
}

export type PhoneRuntimeState =
  | { status: "checking" }
  | {
      status: "setup";
      mode: "install" | "update";
      step: SetupStep;
      progress: SetupProgress | null;
      targetVersion: string; // 설치할 Paseo 데몬 버전
    }
  | {
      status: "setup-failed";
      mode: "install" | "update";
      step: SetupStep;
      code: SetupErrorCode;
      message: string; // 원문, 번역 안 함
      canStartPrevious: boolean; // update 실패 시 이전 데몬으로 시작 가능
    }
  | {
      status: "ready";
      linux: ServiceState; // 전체 대표. running = proot 둘 다 살아 있음
      daemon: ServiceState & { serverId: string | null; version: string; listen: "127.0.0.1:6767" };
      ssh: { enabled: boolean; port: 2222; state: ServiceState; hostKeyFingerprints: string[] };
      keepAwake: boolean;
      distro: string; // "Debian 13"
      addresses: NetworkAddress[];
      exchange: ExchangeStatus & {
        phonePathLabel: string;
        phonePath: string;
        linuxPath: "/root/exchange";
      };
      device: DeviceChecks;
    };

export interface AuthorizedKey {
  type: string; // "ssh-ed25519"
  comment: string; // 없으면 ""
  fingerprint: string; // "SHA256:…"
}

export type LogKind = "daemon" | "ssh" | "setup";
```

`ready`일 때 `linux`가 `stopped`/`error`이면 `daemon`·`ssh`의 `state`는 의미가 없다(UI는 `linux`만 본다. 디자인 §4.2와 같음).

### 7.2 JS API (`index.ts`)

```ts
export const isPhoneRuntime: boolean;
// = 네이티브 모듈 존재 && Constants.expoConfig?.extra?.phoneRuntime === true

export function getState(): PhoneRuntimeState; // 동기, 최신 스냅샷
export function addStateListener(listener: (state: PhoneRuntimeState) => void): { remove(): void };

// 수명
export function start(): Promise<void>; // 설치돼 있으면 서비스 시작, 아니면 설치 시작
export function stop(): Promise<void>; // 확인은 UI 책임. 데몬 → sshd 순으로 정지
export function restartDaemon(): Promise<void>; // 데몬 proot만 재시작
export function retrySetup(): Promise<void>; // 실패한 단계부터
export function startPrevious(): Promise<void>; // setup-failed && canStartPrevious
export function reinstall(): Promise<void>; // 정지 → rootfs 삭제 → install. 교환 폴더는 유지

// 설정 (state.json에 저장, 즉시 반영)
export function setSshEnabled(enabled: boolean): Promise<void>;
export function setKeepAwake(enabled: boolean): Promise<void>;

// 시스템 화면
export function openBatterySettings(): Promise<void>; // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
export function requestStoragePermission(): Promise<void>; // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
export function requestNotificationPermission(): Promise<boolean>;
export function openExchangeFolder(): Promise<void>; // DocumentsUI, 실패 시 루트

// SSH 키
export function listAuthorizedKeys(): Promise<AuthorizedKey[]>;
export function addAuthorizedKey(text: string): Promise<AuthorizedKey>;
export function removeAuthorizedKey(fingerprint: string): Promise<void>;

// 로그
export function getLogs(
  kind: LogKind,
  maxBytes?: number,
): Promise<{ path: string; contents: string }>;
```

규칙:

- 모든 명령은 컨트롤러에서 직렬화된다. 진행 중인 명령과 충돌하면 `PhoneRuntimeError("busy")`로 거부한다(큐잉하지 않음).
- 오류는 `PhoneRuntimeError`(`code` 필드)로 던진다. 코드: `busy`, `not-installed`, `invalid-key`, `private-key`, `duplicate-key`, `not-found`, `io-error`. UI는 코드로 문구를 고른다. `addAuthorizedKey`는 `-----BEGIN`이 보이면 `private-key`를 던지고 입력을 어디에도 저장하거나 로그에 남기지 않는다.
- 상태 이벤트는 값이 바뀔 때만, 다운로드 진행률은 최대 4회/초로 묶어 보낸다.
- 앱 JS가 다시 로드돼도 상태는 네이티브 싱글턴에 있으므로 `getState()`로 바로 복원된다.

### 7.3 UI 쪽 파일 (UI 개발자 소유)

```
packages/app/src/phone/
  runtime.ts / runtime.android.ts          # usePhoneRuntimeState(), isPhoneRuntime 재노출. 기본 파일은 null/false
  runtime-gate.tsx / runtime-gate.android.tsx       # 기본: children 그대로
  host-sections.tsx / host-sections.android.tsx     # 기본: null
  linux-setup-screen.android.tsx
  linux-section.android.tsx
  remote-access-section.android.tsx
  authorized-keys-sheet.android.tsx
  exchange-section.android.tsx
  linux-stopped-callout-source.android.tsx
  i18n/en.json, i18n/ko.json, i18n/register.ts      # i18next.addResourceBundle(lng, "translation", …, true, false)
```

- **i18n (디자인 Q9):** 폰 전용 문구는 본체 로케일 파일에 넣지 않는다(9개 로케일 parity 테스트와 머지 충돌을 피함). `src/phone/i18n`에 en·ko만 두고 `register.ts`가 기존 i18next 인스턴스에 번들을 더한다. 다른 로케일은 i18next 폴백으로 영어. 키 이름은 디자인 문서의 키를 그대로 쓴다. 알림 문구는 Android `strings.xml`(en, ko).
- **PhoneRuntimeGate** (`_layout.tsx`에서 `AppShell`을 감쌈):
  - `checking`, `setup`, `setup-failed` → `LinuxSetupScreen`을 그리고 `AppShell`은 마운트하지 않는다(라우터가 아직 웰컴/호스트를 고르지 않게).
  - `ready`이고 `linux`가 `starting`이며 앱이 막 시작했을 때 → 이 기기 호스트가 온라인이 될 때까지(최대 60초) 스플래시. 이후 `AppShell`. 이렇게 해야 업스트림의 5초 "호스트 대기 포기" 타이머가 웰컴 화면으로 보내지 않는다.
  - 그 외(사용자가 멈춤 포함)는 바로 `AppShell`. 다른 호스트를 쓸 수 있어야 한다.
  - 사이드바 콜아웃 소스(`linux-stopped-callout-source`)는 게이트 안에서 마운트한다. `SidebarCalloutProvider` 안쪽이므로 본체 수정이 없다.
  - expo-router 시작 규칙은 [docs/expo-router.md](../../expo-router.md)를 먼저 읽는다.
- **PhoneHostSections**: `serverId === state.daemon.serverId`일 때만 Linux, Remote access, Exchange folder, Reinstall 카드를 그린다. 기존 Danger zone은 본체 것이 그대로 남으므로 Reinstall은 별도 카드로 Exchange folder 아래에 둔다.
- Remote access의 데스크톱 문자열: `ssh -p 2222 root@<addr>`, `ssh://root@<addr>:2222`(`daemonPort` 생략), SSH config `Host <label>` / `HostName` / `Port 2222` / `User root`.

### 7.4 알림 (디자인 F, Q4, Q10)

- 채널 `linux_runtime`, 중요도 LOW. 설치 중에도 같은 서비스·채널.
- 탭: `paseo-phone://settings/hosts/<serverId>`(설치 중·serverId 없음이면 `paseo-phone://`). expo-router가 받는다. `notification-open`(에이전트 알림 경로)은 건드리지 않는다.
- **Q4 결정: 네이티브는 실행 중 에이전트 수를 모른다.** 서비스가 데몬 WebSocket 프로토콜을 구현하면 본체 프로토콜에 묶인 두 번째 클라이언트가 생긴다. 1차 알림은 에이전트 수를 보여주지 않는다. 알림의 Stop 액션은 항상 앱을 열어 Linux 섹션의 확인 대화상자를 띄운다(`paseo-phone://settings/hosts/<serverId>?confirmStop=1`). 확인 문구의 에이전트 수는 UI가 앱 안 세션 상태에서 센다.
- 텍스트: Keep awake 상태, SSH 주소 한 줄(Wi-Fi 우선, 없으면 Tailscale), 확장 시 둘 다.

---

## 8. 포그라운드 서비스

### 8.1 소유

`RuntimeService`(`foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="Runs a local Linux environment for development tools"`). `dataSync`는 Android 15부터 하루 6시간 제한이 있어 쓰지 않는다. Play 배포를 하지 않으므로 `specialUse` 심사 문제가 없다.

서비스가 소유하는 것:

| 자원                                      | 언제                                              |
| ----------------------------------------- | ------------------------------------------------- |
| 알림                                      | 서비스가 사는 동안 항상                           |
| 데몬 proot 프로세스                       | Linux running                                     |
| sshd proot 프로세스                       | Linux running && sshEnabled                       |
| `PARTIAL_WAKE_LOCK`                       | keepAwake (기본 켬)                               |
| `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) | keepAwake. 화면이 꺼져도 데스크톱 SSH 응답성 유지 |
| 설치 작업                                 | setup 중 (앱을 나가도 계속)                       |

서비스는 앱 프로세스 안에서 돈다(별도 `:process` 아님). 모듈 싱글턴과 상태를 공유하기 위해서다. Android가 앱 프로세스를 죽이면 proot도 함께 죽는다(같은 cgroup). 다음 앱 실행 때 `state.json`의 `desired: "running"`을 보고 다시 띄운다.

### 8.2 시작·정지

- 시작: UI의 `start()`(앱이 포그라운드일 때만 FGS 시작 가능). 앱 실행 시 `desired === "running"`이면 컨트롤러가 자동으로 `start()`.
- 부팅 시 자동 시작은 1차에 하지 않는다(디자인 Q11). Android 15의 부팅 시 FGS 제한과 얽혀 있고 기획서에 없다.
- 데몬 정지 순서: `$PASEO_HOME/paseo.pid`의 supervisor pid에 `SIGTERM`(proot는 pid 네임스페이스가 없어 Android pid와 같다) → proot 프로세스 종료를 10초 기다림 → 남아 있으면 `Process.destroyForcibly()`(→ `--kill-on-exit`가 자식 정리).
- sshd 정지: proot 프로세스에 `destroy()` → 5초 → `destroyForcibly()`.
- 둘 다 멈추면 wake lock·wifi lock 해제, `stopForeground` + `stopSelf`. 사용자가 멈췄으면 `desired: "stopped"` 저장.

### 8.3 크래시·재시작

- proot가 우리가 정지시키지 않았는데 끝나면 재시작한다. 백오프 1, 2, 4, 8, 16초(최대 60초). 5분 안에 5번 실패하면 멈추고 `error("restart-limit")`.
- 종료 원인 판정: `SIGKILL`(종료 코드 137 또는 signal 9)인데 우리가 보낸 게 아니면 `killed-by-system`. 메시지에 "Android가 프로세스를 강제 종료했습니다. phantom process 제한 또는 메모리 부족일 수 있습니다"를 담는다. 이게 phantom process killer를 사후에 알아채는 주 경로다.
- 데몬 재시작은 sshd를 건드리지 않고, 그 반대도 같다.

### 8.4 기기 설정 감지 (기획서: 앱은 우회하지 않는다)

| 설정                    | 감지                                                                                                                                                       | UI                                                                                                        |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| phantom process killer  | `Settings.Global.getString(cr, "settings_enable_monitor_phantom_procs")`. `"false"` → disabled, 그 외 값/null → enabled, 예외 → unknown. 사후 판정은 §8.3. | `enabled`면 Linux 섹션에 경고 + 설정 방법(개발자 옵션 "하위 프로세스 제한 사용 중지" 또는 adb 명령) 한 줄 |
| 배터리 최적화           | `PowerManager.isIgnoringBatteryOptimizations`                                                                                                              | 디자인 §4.1 경고 + `openBatterySettings()`                                                                |
| 알림 권한 (Android 13+) | `NotificationManagerCompat.areNotificationsEnabled`                                                                                                        | 꺼져 있어도 FGS는 돈다. 알림이 안 보일 뿐이라 Linux 섹션 힌트 한 줄                                       |

`phantomProcessMonitor` 경고는 디자인 §4.1이 "다루지 않는다"고 한 부분과 다르다. 스파이크에서 이 설정이 켜져 있으면 데몬·sshd·proot가 실제로 죽었고, 증상(갑자기 오프라인)만으로는 원인을 알 수 없다. 앱이 바꾸지 않고 읽어서 알리는 것은 기획서 원칙 안이다. 디자이너가 문구와 자리를 정한다.

devphone01 현황: 스파이크 중 2026-10-30 02:56 KST에 adb로 `settings put global settings_enable_monitor_phantom_procs false`를 실행했고 그대로 유지한다. 배터리 최적화 예외는 설정돼 있지 않다.

---

## 9. 빌드 노트 (core 개발자)

- 빌드 스크립트: `scripts/phone/build-apk.sh`(bash, 새 파일). `scripts/build-apk.mjs`는 Windows에서 `npm`/`gradlew`를 셸 없이 spawn해서 실패하므로 쓰지 않고, 같은 순서를 bash로 따른다:
  ```bash
  export APP_VARIANT=phone EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767 PASEO_WATCHDOG_BUILD=1
  npm run build:app-deps
  (cd packages/app && npx expo prebuild --platform android --no-install --clean)
  rm -rf packages/app/android/app/build/generated/assets
  (cd packages/app/android && ./gradlew assembleRelease --no-daemon --max-workers=2 \
      -Dorg.gradle.parallel=false -PreactNativeArchitectures=arm64-v8a)
  cp packages/app/android/app/build/outputs/apk/release/app-release.apk artifacts/paseo-phone-arm64.apk
  ```
  변형을 바꾸면 applicationId가 바뀌므로 prebuild는 `--clean`으로 한다.
- Gradle 빌드 캐시는 `~/.gradle/gradle.properties`에서 켜져 있다. 레포 설정으로 옮기지 않는다.
- npm 11은 허용 목록에 없는 install script를 건너뛴다. `react-native-enriched-markdown`의 postinstall은 Windows bsdtar(`C:\Windows\System32\tar.exe`)가 필요하고 Git Bash `tar`로는 실패한다. 필요하면 PATH에서 System32 tar가 먼저 오게 하고 수동으로 그 스크립트를 돌린다.
- 위 문제들을 피하려고 `package.json`을 고치지 않는다.
- 업스트림 머지 후 APK를 만들기 전에 `npm view @getpaseo/cli@<packages/server 버전> version`으로 npm 게시 여부를 확인한다(§3.1).
- proot 바이너리 재빌드는 `modules/paseo-phone-runtime/proot/build-proot.sh`(NDK r29, Linux 또는 WSL). 산출물 sha256을 `PINS.md`에 갱신한다.
- 검사: `npm run typecheck`, `npm run lint`, `npm run format`. 테스트는 바뀐 파일만(`npx vitest run <file> --bail=1`).

---

## 10. 위험과 1차에서 하지 않는 것

### 10.1 위험

| 위험                                                                              | 대응                                                                                                                                  |
| --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Kai proot(termux/proot `4dba3af`)가 스파이크의 Termux proot 5.1.107과 다르게 동작 | core 1단계 = Kai 바이너리로 스파이크 재현(데몬·sshd·세 에이전트 `--version`). 다르면 proot 커밋을 Termux 패키지 버전에 맞춰 다시 빌드 |
| TarExtractor가 proot-distro tarball을 완전히 풀지 못함                            | 풀린 파일 수를 `tar -tf` 목록과 비교하는 계측 테스트를 core에 둔다                                                                    |
| 파일 작업 20~50배 느림                                                            | 범위 밖 작업(대형 모노레포, 무거운 빌드)은 하지 않는다. 지침 파일에 명시                                                              |
| 메모리: 데몬 대기 RSS ~500MB, 기기 가용 ~1.9GB                                    | 프로바이더 끄기. 에이전트 동시 실행 수는 실사용에서 관찰                                                                              |
| phantom process killer / 저메모리 kill                                            | 기기 설정 + `killed-by-system` 감지·표시                                                                                              |
| 업스트림 npm 게시 지연                                                            | §3.1, §9 확인 절차. 업데이트 실패 시 이전 데몬으로 시작 가능                                                                          |
| 업스트림이 §4.3의 파일·암묵적 계약을 바꿈                                         | 머지 체크리스트 = §4.3 표                                                                                                             |
| 폰의 다른 앱이 loopback 데몬에 접근 가능                                          | 1차 수용. 2차 후보: 데몬 비밀번호                                                                                                     |
| 화면 꺼짐 장시간·딥 Doze 생존 미확인(스파이크는 2분)                              | FGS + wake lock + wifi lock. QA가 30분 이상 소크로 확인                                                                               |
| Codex 샌드박스 불가                                                               | Full Access로 사용, 지침에 명시                                                                                                       |
| Debian trixie·proot-distro tarball URL이 바뀌거나 사라짐                          | URL·sha256 고정. 바뀌면 `RootfsSpec.kt`만 고친다                                                                                      |

### 10.2 1차에서 하지 않는 것

- 데몬 비밀번호, LAN 직접 노출, 릴레이
- 부팅 시 자동 시작
- 알림에 에이전트 수 표시, 네이티브의 데몬 프로토콜 클라이언트
- 호스트 목록 정렬·"This device" 설명 줄·Remove host/Pair device 숨김(§4.2)
- 포크 데몬 빌드(npm pack 경로), 데몬 코드 수정
- apt·에이전트 자동 업그레이드, rootfs 버전 업그레이드(재설치만)
- arm64 외 ABI, Alpine 등 다른 배포판
- 폰 안 터미널 화면 신설(Paseo 터미널과 SSH로 충분)
- MagicDNS 이름 표시(Tailscale은 IP만)
- 공유 저장소 레포 작업, 동기화

---

## 11. 디자인 질문 답변

| Q   | 답                                                                                                                                                                                                                              |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q1  | **채택.** 데몬은 `127.0.0.1:6767`만, 데스크톱은 Remote SSH(`ssh://root@<폰>:2222`). Electron 전송은 `ssh -W`를 쓰고 `daemonPort` 쿼리를 그대로 쓴다. 첫 접속 전 호스트 키 수락이 필요(BatchMode). 실제 연결은 QA 1차 항목. §5.1 |
| Q2  | 대체로 채택, 하위 상태를 판별 객체로 바꾸고 필드 추가(`hostKeyFingerprints`, `device`, `restart-required`, `canStartPrevious`, `serverId`). §7.1                                                                                |
| Q3  | 실패한 단계부터. 다운로드는 sha256 검증된 캐시로 유지(설치 성공 시 삭제), `extract`만 항상 rootfs를 지우고 다시. 문구 "Retry starts that step again." 그대로 맞다. §2.5                                                         |
| Q4  | 네이티브는 에이전트 수를 모른다. 알림은 수를 보이지 않고, Stop은 항상 앱을 열어 확인. 확인 문구의 수는 UI가 센다. §7.4                                                                                                          |
| Q5  | `useLocalDaemonServerId`를 바꾸지 않는다(로컬 파일시스템 의미가 섞여 있음). 이 기기 판정은 폰 모듈의 `daemon.serverId` 비교. "This device" 설명 줄은 1차에서 하지 않음. §4.2                                                    |
| Q6  | 숨기지 않는다(대안 수용). 데몬 config에서 릴레이 꺼짐. §4.2                                                                                                                                                                     |
| Q7  | 사용자 `root`, 포트 **2222** 고정(디자인의 8022는 Termux 기본값과 겹침), 키 인증만. 디자인 문구·와이어프레임의 8022를 2222로 바꾼다. §5.1                                                                                       |
| Q8  | 예. 런타임이 `phonePathLabel`(볼륨 설명 + "/Paseo")을 준다. 리눅스 쪽은 `/root/exchange`(`~/exchange`). 권한 획득 후 재시작이 필요한 `restart-required` 상태가 하나 추가된다. §5.2                                              |
| Q9  | 앱 문구는 `src/phone/i18n`에 en·ko, 다른 로케일은 영어 폴백. 본체 로케일 파일은 건드리지 않는다. 알림은 `strings.xml` en·ko. §7.3                                                                                               |
| Q10 | 딥링크 `paseo-phone://settings/hosts/<serverId>`. `notification-open`에 kind를 추가하지 않는다. §7.4                                                                                                                            |
| Q11 | 1차에 없음. §8.2                                                                                                                                                                                                                |
| Q12 | 사전 검사 4 GB. 다운로드 약 0.55 GB, 설치 후 약 2 GB(추정). QA 실측 후 §2.5 표를 고친다. 화면에는 런타임이 주는 `free`/`needed` 숫자가 나간다.                                                                                  |
| Q13 | 맞다. 앱은 Paseo 데몬(과 Node 핀이 바뀌면 Node)만 업데이트한다. apt와 에이전트 CLI는 사용자 몫. §2.5                                                                                                                            |

---

## 12. 소유자 확인이 필요한 결정

1. **데스크톱 접속을 SSH 경유로 바꾼다(D8).** 기획서의 "LAN/Tailscale 직접 연결"과 다르다. 경로는 같고 인증이 SSH 키로 통일된다. 대신 데스크톱에서 처음 한 번 `ssh -p 2222 root@<폰>`으로 호스트 키를 받아야 한다.
2. **폰의 다른 앱이 loopback 데몬에 붙을 수 있는 것을 1차에서 수용한다(§5.1).**
3. **applicationId `io.github.ouor.paseophone`, 앱 이름 "Paseo Phone", scheme `paseo-phone`(D12).**
4. **SSH 포트 2222(D9).**
5. **"이 기기" 표시를 1차에서 축소한다(§4.2):** 정렬, "This device" 설명 줄, Remove host/Pair device 숨김을 하지 않는다. 본체 수정 파일을 3개로 유지하기 위해서다.
6. **phantom process killer 상태를 앱이 읽어 경고한다(§8.4).** 디자인은 다루지 않는다고 했다.
