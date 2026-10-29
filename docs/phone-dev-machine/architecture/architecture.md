# 폰 개발 머신: 아키텍처

> 작성: 아키텍트 · 1차 사이클 · 2026-10-30 · 1차 코드 리뷰(`19eac9e8e`) 반영
> 입력: [original-plan.md](../original-plan.md), [design/design.md](../design/design.md), [spike-report.md](spike-report.md), `external/Kai`, Paseo v0.14.0 코드, [code-reviews/](../code-reviews/)
> 이 문서가 core·UI·QA·코드 리뷰의 기준이다. 바꿔야 하면 이 문서를 먼저 고친다. 코어 사이의 Kotlin 시그니처는 [internal-contract.md](../core/internal-contract.md), 리뷰 수정 배정은 [fix-plan.md](../code-reviews/fix-plan.md).

## 0. 결정 요약

| #   | 결정                                                                                                                                                                                                                                                                   |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | 리눅스 레이어는 Expo 로컬 네이티브 모듈 `packages/app/modules/paseo-phone-runtime`(Kotlin, Android 전용) 하나에 둔다. Kai의 `linux/` 코드를 옮기고, Compose·Kai 의존은 버린다.                                                                                         |
| D2  | proot는 **arm64-v8a 전용** `libproot.so`·`libproot-loader.so`·`libtalloc.so`를 모듈의 `jniLibs`에 커밋한다. 1차는 Kai의 사전 빌드본(출처·sha256은 `proot/PINS.md`), 재빌드 경로는 `proot/build-proot.sh`. `useLegacyPackaging`으로 `nativeLibraryDir`에 풀어 실행한다. |
| D3  | rootfs는 **Debian 13 trixie**, proot-distro **v4.29.0** 릴리스의 `debian-trixie-aarch64-pd-v4.29.0.tar.xz`(URL·sha256·크기 고정). proot-distro 5.x는 tarball을 배포하지 않는다.                                                                                        |
| D4  | proot 실행 인자는 proot-distro `login`과 같게 맞춘다(`--kill-on-exit --link2symlink --sysvipc -L --change-id=0:0 --kernel-release=…`). 앱 uid로 읽을 수 없는 `/proc` 파일만 가짜 파일로 바인드한다.                                                                    |
| D5  | 데몬은 **공개 npm `@getpaseo/cli@<업스트림 기준 버전>`** 을 rootfs 안에서 설치한다. 이 포크는 데몬 코드를 고치지 않는다. Node는 nodejs.org 공식 tarball, 버전은 `.tool-versions`, sha256은 포크가 고정한다(§3.1).                                                      |
| D6  | 첫 기동 전에 데몬 `config.json`을 **한 번만** 써서 받아쓰기를 끄고, 쓰지 않는 프로바이더를 끄고, `listen`을 고정한다. 그 뒤로는 데몬·사용자 소유(§3.3).                                                                                                                |
| D7  | 포그라운드 서비스(`specialUse`) 하나가 proot 프로세스 둘(데몬, sshd)을 소유한다. 각 프로세스는 **자기 proot 세션의 포그라운드 자식**이다. 설치도 이 서비스 안에서 돈다. 수명 규칙은 §8.                                                                                |
| D8  | **데몬은 loopback(`127.0.0.1:6767`)에만 연다.** 데스크톱 Paseo는 Remote SSH(`ssh://root@<폰>:2222`, 내부적으로 `ssh -W 127.0.0.1:6767`)로 붙는다. 데몬 비밀번호, LAN 바인딩, 릴레이는 없다.                                                                            |
| D9  | sshd는 rootfs 안 OpenSSH, **포트 2222, 사용자 root, 키 인증만, IPv4만, 에이전트·소켓 포워딩 끔**(§5.1).                                                                                                                                                                |
| D10 | "이 기기" 호스트는 **빌드 시 `EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767`** 로 Paseo의 기존 로컬 데몬 부트스트랩을 켜서 페어링 없이 등록한다. Paseo의 `useLocalDaemonServerId` 개념은 **쓰지 않는다**(§4.2).                                                              |
| D11 | Paseo 본체 수정은 **3개 파일**이고, 포크가 넣은 줄마다 `phone-fork:` 주석을 단다(§4.3).                                                                                                                                                                                |
| D12 | 별도 설치: `APP_VARIANT=phone` → 앱 이름 "Paseo Phone", applicationId `io.github.ouor.paseophone`, URL scheme `paseo-phone`, `allowBackup: false`.                                                                                                                     |
| D13 | 교환 폴더: 공유 저장소 `/storage/emulated/0/Paseo` ↔ rootfs `/root/exchange`. 권한은 `MANAGE_EXTERNAL_STORAGE`이며 **게스트 전체에 공유 저장소 전체 접근을 준다**(1차 수용 위험, §5.2).                                                                                |
| D14 | 기기 설정(phantom process killer, 배터리 최적화)은 앱이 바꾸지 않는다. 앱은 **감지해서 알리기만** 한다.                                                                                                                                                                |
| D15 | **rootfs는 사용자의 Reinstall 말고는 절대 지우지 않는다.** "설치됨"은 디스크에서 판정하고 `state.json`에 의존하지 않는다(§2.3).                                                                                                                                        |
| D16 | **Reinstall은 기기 정체성을 보존한다:** 데몬 `server-id`, SSH 호스트 키, `authorized_keys`(§2.3).                                                                                                                                                                      |
| D17 | 상태 계약의 TS 기준은 zod 스키마 하나, Kotlin은 순수 직렬화 함수 하나, 둘은 골든 fixture로 맞춘다(§7.1).                                                                                                                                                               |

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
│     RuntimeController ─ ProcessSupervisor ─ RuntimeService (FGS, 알림, wake lock)                │
│        │ ProcessBuilder                         │ ProcessBuilder                                 │
│        ▼                                        ▼                                                │
│   proot ─ run-daemon.sh (exec paseo daemon run)  proot ─ run-sshd.sh (exec sshd -D)              │
│        │  127.0.0.1:6767                         │  0.0.0.0:2222 (IPv4)                          │
│        ▼                                        ▼                                                │
│   ┌──────────────── rootfs: filesDir/phone-runtime/rootfs (Debian 13) ─────────────────┐          │
│   │ /usr/local/bin/{node,npm,paseo,claude,codex,opencode}   /root/.paseo/  /root/exchange ─┼── bind ─→ /storage/emulated/0/Paseo
│   └────────────────────────────────────────────────────────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ ssh -p 2222 root@<폰>  (터미널, VS Code Remote-SSH)
        ▲ ssh -W 127.0.0.1:6767 (데스크톱 Paseo Remote SSH 호스트)
   데스크톱 ── LAN 또는 Tailscale
```

---

## 2. 리눅스 레이어 (Kai 이식)

### 2.1 위치와 모듈 구성

Expo 로컬 모듈은 `packages/app/modules/` 아래에 두면 자동 링크된다. Android 전용이다. 소유자는 [internal-contract.md](../core/internal-contract.md) "소유" 표.

```
packages/app/modules/paseo-phone-runtime/
  package.json, expo-module.config.json
  index.ts                           # JS API (§7.2)
  src/state-schema.ts                # zod 스키마 — 상태 계약의 TS 기준 (§7.1)
  src/types.ts                       # z.infer 타입 + 상수 재노출
  src/__fixtures__/state-*.json      # Kotlin 테스트가 만드는 골든 스냅샷
  src/state-schema.test.ts           # fixture를 스키마로 파싱
  proot/build-proot.sh, proot/PINS.md, THIRD_PARTY_NOTICES.md
  android/
    build.gradle                     # BuildConfig: PASEO_DAEMON_VERSION, NODE_VERSION, NODE_SHA256
    node-sha256.txt                  # "<node 버전> <linux-arm64 tarball sha256>" 한 줄씩
    src/main/AndroidManifest.xml
    src/main/jniLibs/arm64-v8a/      # libproot.so, libproot-loader.so, libtalloc.so
    src/main/assets/phone-runtime/   # configure.sh, packages.sh, paseo.sh, agent-instructions.{sh,md},
                                     # run-daemon.sh, run-sshd.sh, sshd_config, versions.env
    src/main/res/values{,-ko}/strings.xml
    src/main/java/io/github/ouor/paseophone/runtime/
      PhoneRuntimeModule.kt          # Expo Module: Function/AsyncFunction/Events
      RuntimeController.kt           # 명령 직렬화, 설치·업데이트 흐름 (~400줄 목표)
      ProcessSupervisor.kt           # Managed 프로세스, 백오프, 재시작 한도, 준비 감시
      StateSnapshot.kt               # 순수 함수: 내부 상태 → 스냅샷 Map (§7.1)
      RuntimeService.kt              # FGS: 알림, wake/wifi lock, 소멸 신호
      RuntimeStore.kt                # state.json (§2.3)
      linux/
        PhoneConstants.kt            # 포트, 게스트 경로, env 이름 — Kotlin 쪽 유일한 출처 (§7.5)
        LinuxPaths.kt, RootfsSpec.kt, Downloader.kt, TarExtractor.kt
        ProotCommand.kt, ProotProcess.kt, ProotExec.kt
        Identity.kt                  # Reinstall 정체성 보관·복원 (§2.3)
      setup/SetupRunner.kt           # download~paseo 단계, 스크립트 실행의 유일한 입구
      ssh/{AuthorizedKeys,HostKeys}.kt
      exchange/ExchangeFolder.kt
      system/{DaemonProbe,DeviceChecks,NetworkAddresses}.kt
      notify/RuntimeNotification.kt
    src/test/**                      # JVM 단위 테스트 (§9)
```

전역 지침은 Kotlin(`AgentInstructions.kt`)이 아니라 `agent-instructions.sh`가 쓴다.

### 2.2 Kai에서 가져오는 것

Kai 경로는 `external/Kai/` 기준. 원본은 Apache-2.0이므로 각 파일 머리에 출처와 라이선스를 남긴다.

| Kai 파일                                                                        | 가져오는 곳                             | 바꾸는 것                                                                                                                                 |
| ------------------------------------------------------------------------------- | --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `build-proot.sh`                                                                | `proot/build-proot.sh`                  | arm64-v8a만. termux/proot 커밋, talloc 버전·**sha256**, NDK r29를 `PINS.md`에 고정하고 스크립트가 검증한다(NDK가 다르면 실패).            |
| `androidApp/src/main/jniLibs/arm64-v8a/{libproot,libproot-loader,libtalloc}.so` | `android/src/main/jniLibs/arm64-v8a/`   | 1차는 Kai 산출물 그대로(sha256 `PINS.md`). `libproot-loader32.so`는 넣지 않는다.                                                          |
| `.../linux/LinuxPaths.kt`                                                       | `linux/LinuxPaths.kt`                   | legacy 마커 제거. 루트 `filesDir/phone-runtime`. `copyLibtalloc()` 유지.                                                                  |
| `.../linux/DistroSpec.kt` 외 배포판 파일들                                      | `linux/RootfsSpec.kt`                   | 고정 URL + sha256 + 크기 + strip 1 하나.                                                                                                  |
| `.../linux/RootfsDownloader.kt`                                                 | `linux/Downloader.kt`                   | `HttpURLConnection`, `Range` 이어받기, sha256 검증. 기대 sha256은 **항상 고정값**(rootfs: `RootfsSpec`, Node: `BuildConfig.NODE_SHA256`). |
| `.../linux/TarExtractor.kt`                                                     | `linux/TarExtractor.kt`                 | pax·GNU 긴 이름, strip, 모드 비트, 문자열 기준 경로 검사.                                                                                 |
| `.../linux/LinuxInstaller.kt`                                                   | `setup/SetupRunner.kt`                  | 다시 씀. 단계별 재시도, 스테이징 후 rename(§2.5).                                                                                         |
| `.../linux/ProotLauncher.kt`                                                    | `linux/ProotCommand.kt`, `ProotExec.kt` | proot-distro 수준 인자, 로그 파일 스트리밍, 프로세스 트리 종료(§8.3).                                                                     |

가져오지 않는 것: `LinuxSandboxManager.kt`, `SessionShell.kt`, `PersistentSandboxShell.kt`·`ProotExecutor.kt`, `BuildProotExecutor.kt`·`BuildEnvironmentManager.kt`. 사용자 터미널은 Paseo 데몬 터미널과 SSH가 맡는다.

라이선스: proot GPL-2.0, talloc LGPL-3.0. `proot/build-proot.sh`·`PINS.md`로 소스 제공, `THIRD_PARTY_NOTICES.md`로 고지.

### 2.3 rootfs 레이아웃과 불변식

```
filesDir/phone-runtime/
  rootfs/                 # Debian 13. 사용자 데이터(/root) 포함
  rootfs.staging/         # extract 중에만 존재. 다 풀리면 rootfs/로 rename
  identity/               # Reinstall 동안만 존재: server-id, ssh_host_*, authorized_keys
  tmp/, sysdata/, libtalloc.so.2
  logs/{setup,daemon,ssh}.log    # 5MB에서 .1로 회전
  state.json
cacheDir/phone-runtime/downloads/   # tarball. extract 성공 직후 삭제
```

**불변식 (D15).**

1. `rootfs/`를 지우는 코드는 `reinstall()` 하나뿐이다. 사용자가 확인 대화상자를 거친 경우에만 불린다.
2. `extract`는 `rootfs.staging/`에 풀고 끝나면 `rootfs/`로 rename한다. `rootfs/`가 이미 있으면 `extract`는 실행되지 않고 실패를 돌려준다. `rootfs.staging/`은 언제 지워도 안전하다.
3. 앱이 rootfs를 "깨졌다"고 판단해도 지우지 않는다. 시작 실패(`start-failed`)로 보여 주고, 복구는 사용자의 Reinstall이다.

**설치 판정은 디스크에서 한다.** `LinuxPaths.rootfsState()`:

| 디스크                                                                              | 판정        | 앱 시작 시 동작                                                                  |
| ----------------------------------------------------------------------------------- | ----------- | -------------------------------------------------------------------------------- |
| `rootfs/` 없음                                                                      | `ABSENT`    | 설치(`INSTALL`, `download`부터. 캐시가 있으면 재사용). `desired`와 무관하게 진행 |
| `rootfs/` 있음, `rootfs/usr/local/lib/node_modules/@getpaseo/cli/package.json` 없음 | `EXTRACTED` | 설치 이어하기(`INSTALL`, `state.failedStep` 또는 `configure`부터). extract 안 함 |
| 둘 다 있음                                                                          | `INSTALLED` | 버전 비교 → 업데이트 또는 시작. `desired == "stopped"`면 `ready(stopped)`        |

- `state.json`은 사용자 의도(`desired`, `sshEnabled`, `keepAwake`)와 마지막 실패 단계(`failedStep`)만 갖는다. 파일이 없거나 파싱에 실패하면 기본값(`desired: "running"`, ssh 켬, keepAwake 켬)으로 시작하고 깨진 파일은 `state.json.corrupt`로 옮긴다. 설치 판정에 영향이 없으므로 파괴적인 결과가 없다.
- 설치 후 첫 `start` 실패는 설치 실패가 아니다. 디스크는 `INSTALLED`이므로 다음 실행은 바로 시작을 다시 시도한다.
- `state.json` 모양: `{ "version": 1, "desired": "running"|"stopped", "sshEnabled": bool, "keepAwake": bool, "failedStep": string|null }`. 필드는 추가만 한다. 뜻이 바뀌면 새 키를 쓰고, 마이그레이션 코드는 그때 쓴다. `installed`, `daemonVersion`, `nodeVersion`, `rootfsSha256`은 두지 않는다(버전은 디스크에서 읽는다).
- `RootfsSpec`의 핀이 바뀌어도 기존 설치에는 영향이 없고 알리지도 않는다. 새 rootfs는 Reinstall로만 받는다.

**Reinstall이 보존하는 것 (D16).** `reinstall()`은 rootfs를 지우기 전에 아래를 `identity/`로 복사하고, `SetupRunner`가 다음 `extract` 직후(= `packages` 전, 그래서 `openssh-server` postinst가 기존 키를 덮지 않는다) 새 rootfs에 되돌린 뒤 `identity/`를 지운다.

| 파일                            | 이유                                                           |
| ------------------------------- | -------------------------------------------------------------- |
| `root/.paseo/server-id`         | 앱과 데스크톱에 저장된 호스트 항목이 그대로 유효               |
| `etc/ssh/ssh_host_*_key{,.pub}` | 데스크톱 `known_hosts`가 그대로 유효(BatchMode 연결이 안 깨짐) |
| `root/.ssh/authorized_keys`     | 데스크톱 키를 다시 등록하지 않아도 됨                          |

그 밖의 `/root`(레포, 에이전트 로그인, `config.json`)는 지워진다. 확인 문구가 이를 말한다.

**경로별 생존 표.**

| 경로                                       | Reinstall                 | 앱 업데이트(APK)       | 앱 삭제 |
| ------------------------------------------ | ------------------------- | ---------------------- | ------- |
| `rootfs/` (`/root` 포함)                   | 삭제(정체성 3종 보존)     | 유지                   | 삭제    |
| `state.json`                               | 유지(`failedStep` 초기화) | 유지                   | 삭제    |
| `logs`, `tmp`, `sysdata`, `libtalloc.so.2` | 유지                      | 유지                   | 삭제    |
| `cacheDir/.../downloads`                   | 다시 받음                 | 유지                   | 삭제    |
| `/storage/emulated/0/Paseo`                | 유지                      | 유지                   | 유지    |
| `rootfs/opt/paseo-phone/*`                 | 다시 복사                 | 서비스 시작마다 덮어씀 | 삭제    |

`/root`를 외부 저장소로 빼지 않는다. 공유 저장소는 noexec, 심볼릭 링크·권한 비트 불가라서 레포와 `node_modules`가 깨진다.

### 2.4 proot 실행 (ProotCommand)

proot-distro 5.10.3의 `login` 인자와 같게 만든다.

```
<nativeLibraryDir>/libproot.so
  --kill-on-exit --link2symlink --sysvipc -L
  --kernel-release=\Linux\<nodename>\6.17.0-PRoot\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\aarch64\localdomain\-1\
  --change-id=0:0
  --rootfs=<root>/rootfs --cwd=/root
  --bind=/dev --bind=/proc --bind=/sys --bind=/dev/urandom:/dev/random
  [--bind=<root>/sysdata/<name>:/proc/…]      # 앱 uid로 실제 파일을 읽을 수 없을 때만
  --bind=<root>/sysdata/empty:/sys/fs/selinux
  --bind=<root>/tmp:/tmp  --bind=<root>/tmp:/dev/shm
  [--bind=/storage/emulated/0/Paseo:/root/exchange]      # 권한이 있을 때만 (§5.2)
  /usr/bin/env -i <ENV…> <command…>
```

- 가짜 파일 이름: `empty`, `loadavg`, `stat`, `uptime`, `version`, `vmstat`, `cap_last_cap`, `max_user_watches`, `overflowuid`, `overflowgid`. 내용은 proot-distro와 같게 맞추되 코드는 복사하지 않는다(GPL-3).
- `<nodename>`: `Build.MODEL`을 `[a-z0-9-]`로(예: `m2012k11ag`). 데몬이 이 값을 hostname으로 보고하고 앱이 호스트 기본 라벨로 쓴다.
- 게스트 환경: `HOME=/root`, `USER=root`, `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, `LANG=C.UTF-8`, `TERM=xterm-256color`, `TMPDIR=/tmp`, 그리고 §7.5의 `PASEO_PHONE_*`. proot 프로세스 환경: `PROOT_TMP_DIR`, `PROOT_LOADER`, `LD_LIBRARY_PATH=<root>`.
- W^X: proot는 `nativeLibraryDir`에서, rootfs 바이너리는 proot loader가 매핑한다.

### 2.5 설치·업데이트 단계 (SetupRunner + 컨트롤러)

`SetupRunner`는 `download`~`paseo`만 한다. `start`는 컨트롤러 단계다. 러너가 모르는 `fromStep`(예: `"start"`)을 받으면 첫 단계로 돌지 않고 `Failed(code = "unknown")`를 돌려준다.

| 단계        | 하는 일                                                                                                                                                                                                                                                                                                                                        | 재시도 시                                                    |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| (사전 검사) | `/data` 여유 ≥ 4 GB. 부족하면 `download` 실패, `code: "no-space"`, `freeBytes`/`neededBytes` 숫자 포함.                                                                                                                                                                                                                                        | —                                                            |
| `download`  | rootfs tarball, Node tarball을 캐시에 받는다. 둘 다 **고정 sha256**으로 검증.                                                                                                                                                                                                                                                                  | 검증된 파일은 건너뜀, `.part`는 이어받기                     |
| `extract`   | `rootfs.staging/`을 비우고 rootfs를 푼다 → Node를 staging의 `usr/local`에 푼다 → `rootfs/`로 rename → 다운로드 캐시 삭제 → `identity/`가 있으면 복원. `rootfs/`가 있으면 실행하지 않는다(§2.3).                                                                                                                                                | staging만 지우고 다시                                        |
| `configure` | `configure.sh`: `resolv.conf`, `hosts`, apt 샌드박스 사용자 root, dpkg `force-unsafe-io`, **`/usr/sbin/policy-rc.d`(exit 101)**, `/root/.ssh`. 멱등.                                                                                                                                                                                           | 다시 실행                                                    |
| `packages`  | `packages.sh`: `apt-get install -y --no-install-recommends ca-certificates curl git openssh-server build-essential python3 procps xz-utils ripgrep unzip less nano` → `apt-get clean`. **SSH 호스트 키는 `openssh-server` postinst가 만든다**(identity가 복원돼 있으면 그대로 둔다). 진행률은 `PASEO_PHONE_TOTAL=<n>` 줄과 `Setting up` 줄 수. | 다시 실행                                                    |
| `paseo`     | `paseo.sh`: `config.json`이 없을 때만 작성(§3.3) → `@getpaseo/cli`는 **설치된 버전이 다를 때만**, 세 에이전트는 **없을 때만** `versions.env`의 고정 버전으로 설치 → 설치했으면 `npm cache clean --force` → `agent-instructions.sh`.                                                                                                            | 다시 실행(멱등)                                              |
| `start`     | 컨트롤러: 데몬·sshd 기동, 데몬 준비 대기(120초). 실패하면 `setup-failed(step: "start")`. Retry는 `start`만.                                                                                                                                                                                                                                    | 시작만 다시. 앱 재실행 시는 디스크가 `INSTALLED`라 바로 시작 |

- **업데이트 판정:** 서비스 시작 때 디스크의 데몬 버전 ≠ `PASEO_DAEMON_VERSION` → `UPDATE_DAEMON`(`paseo`). Node 버전 ≠ `NODE_VERSION` → `UPDATE_NODE`(`download` → Node만 `usr/local`에 다시 풀기 → `paseo`). 업데이트 후 `start`.
- **`canStartPrevious`:** `UPDATE_DAEMON` 실패는 true(npm이 실패해도 이전 패키지는 남는다). `UPDATE_NODE`는 `download` 단계 실패일 때만 true. Node를 풀기 시작한 뒤에는 이전 데몬이 돈다는 보장이 없다.
- apt 패키지와 에이전트 CLI 업그레이드는 앱이 하지 않는다. 사용자가 터미널에서 한다.
- 실측(devphone01): `packages` 156초(119개), `paseo` 154초. 설치 후 rootfs 약 2.36 GB, 설치 중 순간 최대 3.3 GB. 사전 검사 4 GB는 이 최대값에 여유를 둔 값이다.

---

## 3. 데몬

### 3.1 설치 경로와 버전

공개 npm `@getpaseo/cli`를 rootfs 안에서 설치한다. 버전은 이 포크가 기반으로 삼은 업스트림 릴리스(`packages/server/package.json`의 `version`)와 같다. 포크는 데몬 코드를 고치지 않으므로 업스트림 패키지가 곧 우리 데몬이다.

- 모듈 `android/build.gradle`이 빌드 시 `packages/server/package.json`의 `version` → `BuildConfig.PASEO_DAEMON_VERSION`, `.tool-versions`의 `nodejs` → `BuildConfig.NODE_VERSION`, `android/node-sha256.txt`에서 그 버전의 줄 → `BuildConfig.NODE_SHA256`. **해당 버전 줄이 없으면 빌드가 실패한다.** 업스트림이 Node를 올리면 머지 후 첫 빌드에서 이 파일을 갱신하게 된다(sha256은 nodejs.org `SHASUMS256.txt`와 서명을 사람이 확인해서 적는다).
- 세 에이전트 CLI 버전은 `assets/phone-runtime/versions.env`에 고정한다(`CLAUDE_CODE_VERSION=…`, `CODEX_VERSION=…`, `OPENCODE_VERSION=…`). "빌드 시점에 확인한 버전"이며 설치가 재현되게 하려는 것이다. 설치 뒤 업데이트는 사용자 몫이다.
- `@getpaseo/cli`의 전이 의존성은 고정되지 않는다(패키지에 shrinkwrap이 없으면 npm이 범위로 푼다). 1차 수용 위험(§10.1).
- `--ignore-scripts`는 쓰지 않는다. Claude Code의 설치 스크립트가 네이티브 바이너리를 내려받는다.
- 예외 경로(1차 미구현): 데몬 패치가 필요해지면 `npm pack` tarball을 APK assets에 넣는다. 그 전에 이 문서를 고친다.

### 3.2 실행

`/bin/bash /opt/paseo-phone/run-daemon.sh`(내용: `cd /root; exec paseo daemon run`)를 proot 세션의 포그라운드 자식으로 띄운다. `paseo daemon start`(분리 실행)는 쓰지 않는다. `paseo daemon run`의 supervisor가 워커 크래시를 재시작하고, 서비스는 proot 프로세스가 끝났을 때만 개입한다. `PASEO_HOME`은 `/root/.paseo`.

### 3.3 config.json (소유권)

`paseo.sh`가 `/root/.paseo/config.json`이 **없을 때만** 아래로 만든다. `listen` 값은 `PASEO_PHONE_DAEMON_LISTEN` env에서 받는다(§7.5).

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

- **소유:** 포크는 첫 작성만 한다. 이후 데몬과 사용자 소유이고 포크는 다시 쓰지 않는다.
- 업스트림 머지에서 §4.3의 config 키 행이 바뀌면, 기존 설치용 보정을 `paseo.sh`에 멱등하게(키가 없을 때만 넣는 식) 넣을지 그때 결정한다. 그러지 않으면 새 설치만 새 키를 받는다.
- `daemon.listen`은 사용자가 바꾸면 안 되는 값이다. 바꾸면 앱 부트스트랩·준비 판정과 어긋나 Linux가 `starting`에 머문다. Linux 섹션의 Daemon 행 힌트가 이 주소를 보여 준다.
- 받아쓰기를 끄면 첫 기동의 로컬 음성 모델 다운로드가 없다(core-rootfs 실측: `model-download` 로그 0건).
- Codex 샌드박스는 proot에서 동작하지 않는다. 사용자가 Codex 에이전트에 "Full Access"를 고른다. 전역 지침에 적혀 있다.

### 3.4 준비 판정 (DaemonProbe)

- `running`: 데몬 proot가 살아 있고 `127.0.0.1:6767`이 HTTP로 응답(상태 코드 무관).
- `starting`: 프로세스는 살아 있고 아직 응답 없음. 시작 후 120초까지. 대기는 명령 직렬화(`busy`) 밖에서 감시 작업이 한다(§8.2).
- 30초 간격 3번 연속 무응답 → `error("not-responding")`. 프로세스는 죽이지 않는다.
- 시작 전에 6767(데몬)이나 2222(sshd)가 이미 열려 있으면 띄우지 않고 `error("start-failed", "port in use")`. 고아 프로세스가 남은 경우를 `running`으로 착각하지 않게 한다.
- `serverId`: `rootfs/root/.paseo/server-id`를 읽는다. 쓰기는 §2.3 정체성 복원뿐이다.
- `paseo.pid`는 쓰지 않는다(§8.3).

---

## 4. "이 기기" 호스트와 Paseo 본체 접점

### 4.1 자동 등록 (페어링 없음)

빌드 스크립트가 `EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767`로 빌드한다. 업스트림 `host-runtime.ts`가 앱 시작 때 그 주소를 등록될 때까지 재시도하며 probe하고 호스트로 넣는다. 본체 수정 0줄. 호스트 라벨은 데몬 hostname(= proot nodename)이다.

같은 주소가 레지스트리에 이미 있으면 부트스트랩은 아무것도 하지 않는다. Reinstall이 `server-id`를 보존하는 이유다(D16).

### 4.2 `useLocalDaemonServerId`를 쓰지 않는 이유 (디자인 Q5)

이 hook은 Paseo 전체에서 "데몬과 앱이 같은 파일시스템을 본다"는 뜻으로 쓰인다(파일 탐색기·diff의 `isLocalExecution`, 터미널 파일 드롭, open-in-editor, 사이드바 로컬 경로, 폴더 찾아보기, `RemoveHostSection`의 Electron 호출). 폰에서 앱과 데몬은 경로가 다르다. 그래서 "이 기기"는 폰 모듈의 `serverId === state.daemon.serverId` 비교로만 판정한다.

1차에서 디자인과 달라지는 점:

| 디자인                       | 1차 동작                                                       |
| ---------------------------- | -------------------------------------------------------------- |
| 호스트 목록 첫 줄 고정       | 정렬 바꾸지 않음                                               |
| 설명 줄 "This device"        | 기존 그대로 loopback 주소(실기에서 `localhost:6767`로 보임)    |
| 이 기기에서 Remove host 숨김 | 숨기지 않음. 지우면 다음 앱 시작 때 부트스트랩이 다시 등록한다 |
| Pair device 숨김 (Q6)        | 숨기지 않음. 릴레이가 꺼져 있다                                |

### 4.3 Paseo 본체 수정과 머지 체크리스트

포크가 업스트림 파일에 넣은 줄에는 모두 `phone-fork:` 주석을 단다(레포의 `COMPAT(` 관례와 같은 방식). 머지 후 `rg -n "phone-fork:" packages/app scripts` 결과가 아래 표의 수와 같아야 한다.

| 파일                                              | 수정                                                                                                                               | `phone-fork:` 줄 |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| `packages/app/app.config.js`                      | `variants.phone`, phone일 때 `scheme: "paseo-phone"`, `useLegacyPackaging: true`, `allowBackup: false`, `extra.phoneRuntime: true` | 6                |
| `packages/app/src/app/_layout.tsx`                | `<AppShell />`을 `<PhoneRuntimeGate>`로 감쌈 (import + JSX)                                                                        | 2                |
| `packages/app/src/screens/settings/host-page.tsx` | `<PhoneHostSections serverId={serverId} />` (import + JSX)                                                                         | 2                |

비-phone 변형도 모듈을 자동 링크한다(권한·`.so` 포함). 이 포크는 phone 변형만 빌드하고, 네이티브 초기화가 패키지 이름으로, JS가 `extra.phoneRuntime`으로 막으므로 수용한다(§6).

**암묵적 계약 (머지 체크리스트).** 파일을 고치지 않지만 업스트림이 바꾸면 우리가 깨지는 것. "조용히"인 행은 머지 후 손으로 확인한다.

| 계약                                                                                                                                                   | 위치                                                                      | 깨지면              | 확인 방법                                       |
| ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------- | ------------------- | ----------------------------------------------- |
| `EXPO_PUBLIC_LOCAL_DAEMON` 부트스트랩과 재시도, 같은 연결이 있으면 건너뜀                                                                              | `packages/app/src/runtime/host-runtime.ts`                                | 조용히              | 수동: 새 설치·Reinstall 후 호스트 1개, 연결됨   |
| 호스트 라벨 기본값 = 데몬 hostname                                                                                                                     | 같은 파일                                                                 | 조용히              | 수동: 호스트 이름이 기기 모델                   |
| `STARTUP_GIVE_UP_TIMEOUT_MS`(5초) 뒤 Welcome으로 보내는 동작 — 게이트가 스플래시로 막는 이유                                                           | `packages/app/src/app/_layout.tsx`                                        | 조용히              | 수동: 콜드 스타트가 Welcome을 거치지 않음       |
| `HostSettingsPage`, `AppShell`/`RuntimeProviders` 구조                                                                                                 | `host-page.tsx`, `_layout.tsx`                                            | typecheck/충돌      | `rg phone-fork:` 수                             |
| 알림 딥링크 라우트 `settings/hosts/[serverId]/[hostSection]`, 섹션 slug `host`, index의 리다이렉트                                                     | `packages/app/src/utils/host-routes.ts`, `app/settings/hosts/[serverId]/` | 조용히              | 수동: 알림 탭 → Host 페이지, `confirmStop` 동작 |
| i18next 백엔드의 지연 로드·얕은 병합                                                                                                                   | `packages/app/src/i18n/`                                                  | 조용히(일부 테스트) | `src/phone/i18n/register.test.ts`               |
| `useSessionStore` 에이전트 상태의 뜻(Stop 확인의 실행 중 수)                                                                                           | `packages/app/src/stores/session-store.ts`                                | 조용히              | 수동: 실행 중 에이전트 수                       |
| 스플래시 에러 레이아웃 스타일(설치 화면이 값을 복사함)                                                                                                 | `packages/app/src/screens/startup-splash-screen.tsx`                      | 시각적              | 수동: 설치 실패 화면                            |
| `src/phone/**`의 `@/` import 전부(설정 컴포넌트, ui 컴포넌트, 훅, 스토어)                                                                              | 여러 곳                                                                   | typecheck           | `npm run typecheck`                             |
| `scripts/build-apk.mjs`의 단계·env(`scripts/phone/build-apk.sh`가 bash로 복제)                                                                         | `scripts/build-apk.mjs`                                                   | 조용히              | diff로 비교                                     |
| Expo 자동 링크가 `modules/`를 훑는 동작, Expo 모듈 Gradle 플러그인, `AsyncFunction … Coroutine`                                                        | Expo                                                                      | Gradle              | APK 빌드                                        |
| `config.json` 키: `daemon.listen`, `daemon.relay.enabled`, `features.dictation.enabled`, `features.voiceMode.enabled`, `agents.providers.<id>.enabled` | `packages/server/src/server/persisted-config.ts`                          | 조용히              | 수동: 첫 기동 로그에 모델 다운로드 없음         |
| `paseo daemon run`, `$PASEO_HOME/server-id`(한 줄 텍스트, 포크가 **복원 시 씀**)                                                                       | `packages/cli`, `packages/server/src/server/server-id.ts`                 | 조용히              | 수동: Reinstall 후 같은 호스트                  |
| SSH 전송이 `ssh -W 127.0.0.1:<daemonPort>`를 씀                                                                                                        | `packages/protocol/src/ssh-transport.ts`                                  | 조용히              | 수동: 데스크톱 Remote SSH 연결                  |
| npm `@getpaseo/cli` 이름, linux-arm64 prebuild(node-pty, sherpa-onnx), 해당 버전 게시 여부                                                             | npm                                                                       | 설치 실패           | `npm view @getpaseo/cli@<버전>`                 |
| `.tool-versions` `nodejs`, `packages/server/package.json` `version` — 머지만으로 기기에서 업데이트가 돈다                                              | 레포                                                                      | 의도된 동작         | `node-sha256.txt` 줄 존재(빌드가 강제)          |
| 패키지 이름·scheme(Kotlin `PhoneConstants`의 두 상수와 `app.config.js variants.phone`)                                                                 | `app.config.js`                                                           | 조용히              | 수동: 알림 탭                                   |
| `EXPO_PUBLIC_LOCAL_DAEMON` 값 = `PhoneConstants` 데몬 주소                                                                                             | `scripts/phone/build-apk.sh`                                              | 조용히              | 같은 값인지 눈으로                              |

---

## 5. 데스크톱 접근, 보안, 교환 폴더

### 5.1 노출 정책

| 포트        | 바인딩                | 인증           | 접근 주체                                                      |
| ----------- | --------------------- | -------------- | -------------------------------------------------------------- |
| 6767 (데몬) | `127.0.0.1`만         | 없음(loopback) | 폰 Paseo 앱, sshd 터널(`ssh -W`)                               |
| 2222 (sshd) | `0.0.0.0`, **IPv4만** | 공개키만       | 데스크톱 터미널, VS Code Remote-SSH, 데스크톱 Paseo Remote SSH |

데스크톱 Paseo는 Remote SSH로 붙는다. Electron 전송은 시스템 `ssh -T -o BatchMode=yes … -W 127.0.0.1:<daemonPort>`라서 처음 보는 호스트 키를 거부한다. 소유자는 데스크톱에서 먼저 `ssh -p 2222 root@<폰>`으로 한 번 접속해 호스트 키를 받아들인다. UI는 ed25519 호스트 키 지문을 보여 준다. 호스트 키는 Reinstall에도 유지된다(D16).

sshd 설정 `/etc/ssh/sshd_config.d/phone.conf` — **`run-sshd.sh`가 sshd를 띄울 때마다 자산에서 다시 쓴다**(게스트 안에서 바뀌어도 다음 시작에 되돌아간다). 포트는 `run-sshd.sh`의 `-p $PASEO_PHONE_SSH_PORT`가 정하고 이 파일에는 `Port`를 두지 않는다.

```
AddressFamily inet
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
AllowTcpForwarding local
AllowAgentForwarding no
AllowStreamLocalForwarding no
PermitTunnel no
GatewayPorts no
X11Forwarding no
LoginGraceTime 20
MaxStartups 3:50:10
```

- `PermitOpen`은 두지 않는다. VS Code Remote-SSH가 동적 포트로 포워딩한다.
- `AddressFamily inet`: 셀룰러에서 받는 공인 IPv6로 2222가 인터넷에 열리는 것을 막는다. IPv4 셀룰러는 보통 CGNAT 뒤다. Wi-Fi와 Tailscale(100.x)은 IPv4로 된다.
- 사용자 `root`, 포트 2222 고정.
- `authorized_keys`: 모듈이 Android 쪽에서 직접 읽고 쓴다. 쓰기는 한 Mutex로 직렬화, 임시 파일 fsync 후 rename, 0600. `add`는 첫 토큰이 키 타입인 줄만 받는다(`command=`, `from=` 같은 옵션 접두어는 `invalid-key`). 키를 지우면 **sshd가 돌고 있으면 재시작해서** 그 키로 열린 세션과 터널을 끊는다(다른 SSH 세션도 함께 끊긴다. 확인 문구가 말한다).

수용한 위험(1차):

- 폰의 다른 앱이 `127.0.0.1:6767`에 붙을 수 있다.
- 게스트의 에이전트는 게스트 root라서 `authorized_keys`와 `/root/.paseo/config.json`을 고칠 수 있다(프롬프트 인젝션 한 번이 영구 원격 접근이 될 수 있다). sshd 설정은 시작마다 되돌리고, 키 목록은 UI가 매번 파일에서 읽어 보여 준다.
- 업스트림 `OfferLinkListener`가 `paseo-phone://…#offer=`/`#connect=` 링크로 확인 없이 호스트를 추가한다. 업스트림 동작 그대로이며 로컬 데몬을 노출하지 않는다.

### 5.2 교환 폴더

- 폰 쪽 `/storage/emulated/0/Paseo`(없으면 서비스가 만든다), 표시 문자열은 저장소 볼륨 설명 + "/Paseo". 리눅스 쪽 `/root/exchange`.
- 권한 `MANAGE_EXTERNAL_STORAGE`. **이 권한은 앱 uid 전체에 붙고 proot는 보안 경계가 아니므로, 권한을 주면 게스트 안의 모든 프로그램이 공유 저장소 전체(사진, 다운로드, 문서)를 읽고 쓰고 지울 수 있다.** 1차에서 소유자 결정으로 수용한다. 기본은 꺼져 있고(권한 없음), UI 권한 요청 문구가 이 사실을 그대로 말한다. 범위를 좁히는 방법(SAF 트리 URI 또는 `getExternalFilesDir` 바인드)은 2차 후보.
- 바인드는 proot 시작 때 정해진다. 권한을 새로 받으면 `exchange.status = "restart-required"`, UI는 `restartLinux()`를 제안한다.
- FUSE라 실행 비트·심볼릭 링크·하드링크가 안 된다. 교환 전용이다.

### 5.3 전역 에이전트 지침

`agent-instructions.sh`가 `paseo` 단계와 매 서비스 시작 때 세 파일의 관리 블록만 갱신한다. 블록 밖 사용자 내용은 보존하고, 파일이 없으면 만든다. 원문은 `assets/phone-runtime/agent-instructions.md`. 실행 출력은 `setup.log`에 남는다.

| 에이전트    | 파일                               |
| ----------- | ---------------------------------- |
| Claude Code | `/root/.claude/CLAUDE.md`          |
| Codex       | `/root/.codex/AGENTS.md`           |
| OpenCode    | `/root/.config/opencode/AGENTS.md` |

블록 내용: proot Debian on Android(시스템 서비스·Docker 없음), 레포는 `/root` 아래, `~/exchange`는 Android와 파일 교환 전용(심볼릭 링크·실행 비트 없음), 파일 작업이 느리니 전체 스캔·전체 재빌드 피하기, Codex 샌드박스 없음.

---

## 6. 별도 설치 (applicationId)

`app.config.js`의 `variants.phone`: 이름 "Paseo Phone", `packageId` `io.github.ouor.paseophone`, Google 서비스 파일 없음. phone 변형일 때만 `scheme: "paseo-phone"`, `android.allowBackup: false`, `expo-build-properties.android.useLegacyPackaging: true`, `extra.phoneRuntime: true`.

- `allowBackup: false`: rootfs에 SSH 호스트 개인키와 에이전트 토큰이 있다. 클라우드 백업, 기기 간 이전, `adb backup` 대상에서 뺀다.
- Firebase가 없으므로 원격 푸시는 없다(F-Droid 빌드와 같은 조건).
- 비-phone 변형의 자동 링크는 §4.3대로 수용한다.

---

## 7. 네이티브 코어 ↔ UI 계약

UI는 `modules/paseo-phone-runtime/index.ts`만 import한다.

### 7.1 상태

**한 곳에서 정의한다.** TS 기준은 `src/state-schema.ts`의 zod 스키마이고 `src/types.ts`는 `z.infer`로 타입을 낸다. Kotlin은 `StateSnapshot.kt`의 순수 함수 하나가 스냅샷 `Map`을 만든다. 둘은 골든 fixture로 맞춘다.

- Kotlin JVM 테스트 `StateSnapshotTest`가 대표 상태(`checking`, `setup` 다운로드 중, `setup-failed` no-space, `setup-failed` start, `ready` 실행 중, `ready` 멈춤·error)를 JSON으로 만들어 `src/__fixtures__/state-*.json`과 비교한다. 다르면 실패하고, `-PupdateFixtures`로 다시 쓴다.
- vitest `src/state-schema.test.ts`가 모든 fixture를 스키마로 `parse`한다.
- `index.ts`는 개발 빌드에서만 `getState()`/이벤트 값을 스키마로 검증해 어긋나면 콘솔 오류를 낸다(릴리스는 검증하지 않음).

모양(스키마가 기준이고 이건 요약):

```ts
type SetupStep = "download" | "extract" | "configure" | "packages" | "paseo" | "start";
type SetupErrorCode =
  | "offline"
  | "no-space"
  | "checksum"
  | "command-failed"
  | "cancelled"
  | "unknown";
type ServiceErrorCode =
  | "crashed"
  | "killed-by-system"
  | "not-responding"
  | "start-failed"
  | "restart-limit";
type ServiceState =
  | { state: "starting" | "running" | "stopping" | "stopped" }
  | { state: "error"; code: ServiceErrorCode; message: string };

type PhoneRuntimeState =
  | { status: "checking" }
  | {
      status: "setup";
      mode: "install" | "update";
      step: SetupStep;
      progress: SetupProgress | null;
      targetVersion: string;
    }
  | {
      status: "setup-failed";
      mode: "install" | "update";
      step: SetupStep;
      code: SetupErrorCode;
      message: string; // 원문, 번역 안 함. UI는 파싱하지 않는다
      space?: { freeBytes: number; neededBytes: number }; // code === "no-space"일 때
      canStartPrevious: boolean;
    }
  | {
      status: "ready";
      linux: ServiceState;
      daemon: ServiceState & { serverId: string | null; version: string; listen: string };
      ssh: { enabled: boolean; port: number; state: ServiceState; hostKeyFingerprints: string[] };
      keepAwake: boolean;
      distro: string;
      addresses: NetworkAddress[];
      exchange: ExchangeStatus & { phonePathLabel: string; phonePath: string; linuxPath: string };
      device: DeviceChecks;
    };
```

- 포트·주소·경로는 리터럴 타입이 아니라 값이다. UI는 상수를 직접 쓰지 않고 상태의 `ssh.port`, `daemon.listen`, `exchange.linuxPath`를 쓴다.
- `ready`에서 `linux`가 `stopped`/`error`이면 `daemon`·`ssh`의 `state`는 의미가 없다. UI는 `linux`만 본다. 단 `linux`의 `killed-by-system`도 phantom 안내 대상이다.
- `checking`은 초기화의 디스크 읽기 동안만 나온다(수백 ms). 초기화는 항상 `setup`/`setup-failed`/`ready` 중 하나로 끝난다(§8.2).
- `desired == "running"`이고 설치돼 있으면 첫 발행부터 `linux: starting`이다. 자동 시작 중에 `stopped`가 한 번이라도 보이지 않게 한다.

### 7.2 JS API (`index.ts`)

```ts
export const isPhoneRuntime: boolean; // 네이티브 모듈 존재 && extra.phoneRuntime === true

export function getState(): PhoneRuntimeState;
export function addStateListener(listener: (state: PhoneRuntimeState) => void): { remove(): void };

// 수명 — 접수되면 resolve, 결과는 상태로 온다
export function start(): Promise<void>; // 설치돼 있으면 시작, 아니면 설치(이어하기)
export function stop(): Promise<void>; // 시작 중·설치 중에도 받는다 (§8.2)
export function restartLinux(): Promise<void>; // 데몬·sshd 정지 → 시작. 서비스는 내리지 않는다
export function restartDaemon(): Promise<void>; // 데몬 proot만
export function retrySetup(): Promise<void>; // 실패한 단계부터. step === "start"면 시작만
export function startPrevious(): Promise<void>; // setup-failed && canStartPrevious
export function reinstall(): Promise<void>; // 정지 → 정체성 보관 → rootfs 삭제 → 설치

export function setSshEnabled(enabled: boolean): Promise<void>;
export function setKeepAwake(enabled: boolean): Promise<void>;

export function openBatterySettings(): Promise<void>;
export function requestStoragePermission(): Promise<void>;
export function requestNotificationPermission(): Promise<boolean>;
export function openExchangeFolder(): Promise<void>;

export function listAuthorizedKeys(): Promise<AuthorizedKey[]>;
export function addAuthorizedKey(text: string): Promise<AuthorizedKey>;
export function removeAuthorizedKey(fingerprint: string): Promise<void>; // sshd 재시작 포함

export function getLogs(
  kind: LogKind,
  maxBytes?: number,
): Promise<{ path: string; contents: string }>;
```

규칙:

- 수명 명령(`start`, `restartLinux`, `restartDaemon`, `retrySetup`, `startPrevious`, `reinstall`, `setSshEnabled`)은 컨트롤러에서 직렬화된다. 진행 중인 명령과 충돌하면 `busy`. **`stop`은 예외**로, 진행 중인 시작·설치·백오프 작업을 취소하고 정지한다.
- 키, 로그, 시스템 화면, `setKeepAwake`는 `busy` 대상이 아니다.
- 오류는 `PhoneRuntimeError(code)`: `busy`, `not-installed`, `invalid-key`, `private-key`, `duplicate-key`, `not-found`, `io-error`, `unavailable`(네이티브 런타임이 없는 빌드).
- `addAuthorizedKey`는 `-----BEGIN`이 보이면 `private-key`. 입력은 저장·로그하지 않는다.
- 상태 이벤트는 값이 바뀔 때만, 진행률은 최대 4회/초.
- `LogKind`는 `"daemon" | "ssh" | "setup"`이고 파일 이름도 같다(`ssh.log`).

### 7.3 UI 쪽 파일 (UI 개발자 소유)

```
packages/app/src/phone/
  runtime.ts / runtime.android.ts              # 상태 구독 usePhoneRuntimeState() — 상태는 여기서
  runtime-gate.tsx / runtime-gate.android.tsx
  host-sections.tsx / host-sections.android.tsx
  linux-setup-screen.android.tsx
  linux-section.android.tsx, logs-sheet.android.tsx
  remote-access-section.android.tsx, authorized-keys-sheet.android.tsx
  exchange-section.android.tsx
  linux-stopped-callout-source.android.tsx
  format.ts (+ format.test.ts)                 # SSH 문자열, 주소 선택, 용량 표기. 순수 함수
  i18n/en.json, i18n/ko.json, i18n/register.ts (+ register.test.ts)
```

- 상태는 `runtime`에서, 명령은 `index.ts`에서 가져온다.
- i18n: 폰 문구는 본체 로케일 파일에 넣지 않는다. en·ko만, 나머지는 영어 폴백.
- **PhoneRuntimeGate:**
  - `checking`, `setup`, `setup-failed` → `LinuxSetupScreen`, `AppShell`은 마운트하지 않는다. `checking`이 5초를 넘으면 Start·로그 버튼을 보여 준다(네이티브 결함 대비 탈출구).
  - `ready`이고 `linux: starting` → 이 기기 호스트가 온라인이 될 때까지(최대 60초) 스플래시.
  - `ready`이고 `linux`가 `error`이며 이 기기 호스트가 레지스트리에 없을 때(첫 설치 직후 실패) → `LinuxSetupScreen`의 실패 레이아웃으로 오류·로그·`restartLinux()`와 "다른 호스트로 계속"을 보여 준다. Welcome으로 떨어져 Linux 화면에 갈 길이 없어지는 것을 막는다.
  - 그 외(사용자가 멈춤 포함)는 `AppShell`.
- **알림 의도 처리:** `confirmStop`은 `running`뿐 아니라 `starting`에서도 확인 대화상자를 연다.
- 설치 화면 로그는 `linuxSetup.logs.*` 키를 쓰고 로드 오류를 보여 준다. `no-space` 문구는 `space` 숫자로 그린다.

### 7.4 알림

- 채널 `linux_runtime`, 중요도 LOW. 설치 중에도 같은 서비스·채널.
- 탭: `paseo-phone://settings/hosts/<serverId>/host`. `serverId`가 아직 없으면(설치 중) `paseo-phone://`. 업스트림 index 라우트가 쿼리를 버리므로 섹션 경로까지 직접 쓴다. Kotlin의 경로 문자열 옆에 `utils/host-routes.ts`의 `buildSettingsHostSectionRoute`를 가리키는 주석을 둔다.
- 쿼리 키는 `types.ts`의 `PHONE_NOTIFICATION_QUERY = { confirmStop: "confirmStop", logs: "logs" }`가 기준이다. Kotlin 상수 옆에 그 위치를 적는다.
- 액션: Stop(`…/host?confirmStop=1`, serverId가 없으면 액션 없음), 데몬 무응답 시 Restart(서비스로 직접), 로그 열기(`…?logs=daemon`).
- 에이전트 수는 표시하지 않는다(네이티브는 모른다). Stop 확인의 수는 UI가 센다.

### 7.5 상수의 출처

| 사실                                                 | Kotlin 출처                                        | 셸                                                 | TS/UI                      |
| ---------------------------------------------------- | -------------------------------------------------- | -------------------------------------------------- | -------------------------- |
| SSH 포트 2222                                        | `PhoneConstants.SSH_PORT`                          | env `PASEO_PHONE_SSH_PORT`(`run-sshd.sh -p`)       | 상태 `ssh.port`            |
| 데몬 주소 `127.0.0.1:6767`                           | `PhoneConstants.DAEMON_HOST`/`DAEMON_PORT`         | env `PASEO_PHONE_DAEMON_LISTEN`(`paseo.sh` config) | 상태 `daemon.listen`       |
| `/root/exchange`, `/root/.paseo`, `/opt/paseo-phone` | `PhoneConstants`                                   | 스크립트 경로 고정(`/opt/paseo-phone`은 실행 위치) | 상태 `exchange.linuxPath`  |
| 배포판 표시 이름                                     | `RootfsSpec.DISTRO_LABEL`                          | —                                                  | 상태 `distro`              |
| 버전                                                 | `BuildConfig.PASEO_DAEMON_VERSION`, `NODE_*`       | env `PASEO_DAEMON_VERSION`, `NODE_VERSION`         | 상태 `targetVersion`       |
| applicationId, scheme                                | `PhoneConstants` (+ `app.config.js` 가리키는 주석) | —                                                  | `app.config.js`            |
| 알림 쿼리 키                                         | `RuntimeNotification` (+ `types.ts` 가리키는 주석) | —                                                  | `PHONE_NOTIFICATION_QUERY` |

스크립트 env는 `SetupRunner.scriptEnv()` 한 곳에서 만들고, rootfs 안 스크립트 실행은 `SetupRunner.runScript()`/`scriptSpec()`가 유일한 입구다(컨트롤러의 `agent-instructions.sh`, `run-*.sh`도 이것을 쓴다). `PASEO_PHONE_NODENAME`은 `ProotProcess`/`ProotExec`가 넣는다. `EXPO_PUBLIC_LOCAL_DAEMON`은 빌드 스크립트의 리터럴로 남고 §4.3 체크리스트로 맞춘다.

---

## 8. 포그라운드 서비스와 수명

### 8.1 소유

`RuntimeService`(`specialUse`). 서비스가 소유하는 것:

| 자원                                      | 언제                                            |
| ----------------------------------------- | ----------------------------------------------- |
| 알림                                      | 서비스가 사는 동안                              |
| 데몬 proot                                | Linux running                                   |
| sshd proot                                | Linux running && sshEnabled                     |
| `PARTIAL_WAKE_LOCK`                       | keepAwake, **그리고 설치·업데이트 중에는 항상** |
| `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) | keepAwake                                       |
| 설치 작업                                 | setup 중 (앱을 나가도 계속)                     |

서비스는 앱 프로세스 안에서 돈다. 서비스의 프로세스 표와 lock은 한 스레드(컨트롤러의 confined 디스패처)에서만 바꾼다. `onDestroy`(메인 스레드)는 직접 만지지 않고 컨트롤러에 정리를 맡긴다.

### 8.2 시작·정지와 경쟁 규칙

- **서비스 시작:** 설치·업데이트·시작은 모두 먼저 서비스를 확보한다(`ensureService()`). 정지 중인 인스턴스가 있으면 그 `onDestroy`가 끝날 때까지 기다린 뒤 새로 시작한다. 정지를 시작하는 순간 그 인스턴스는 "현재 인스턴스"에서 빠진다. `onServiceDestroyed`는 자기가 현재 인스턴스일 때만 상태를 바꾼다.
- **서비스를 내리는 경우는 둘뿐:** 사용자 `stop()`과 설치 실패(`setup-failed`). `restartLinux`, `restartDaemon`, `reinstall`, 업데이트는 서비스를 유지한 채 프로세스만 바꾼다.
- **초기화(앱 시작):** 디스크 판정(§2.3) 뒤 항상 상태를 정한다. `ABSENT`/`EXTRACTED`는 `failedStep`이 기록돼 있으면 `setup-failed`(Retry 대기), 아니면 설치를 시작한다(`desired`와 무관). `INSTALLED`는 `desired == "running"`이면 `linux: starting`으로 발행하고 시작, 아니면 `ready(stopped)`. `checking`에 머무는 경로는 없다.
- **`stop()` 의미:** 설치되지 않았으면 `desired`를 바꾸지 않는다. 진행 중인 설치를 취소하면 `setup-failed(code: "cancelled")`(Retry로 이어하기), 시작 중이면 `ready(stopped)`. 백오프 대기 중이면 재시작 작업을 취소한다.
- **준비 대기는 `busy` 밖:** 시작 명령은 프로세스를 띄우고 바로 끝난다. 120초 준비 대기는 감시 작업이 하고 상태로만 알린다.
- **취소에 안전한 기동:** 프로세스를 띄우는 구간은 취소되지 않게(`NonCancellable`) 하고, 띄운 직후 취소됐으면 그 프로세스를 정지 순서(§8.3)로 내린다. `CancellationException`을 일반 실패(`start-failed`)로 바꾸지 않는다.
- 부팅 시 자동 시작은 1차에 없다.

### 8.3 정지 순서, 크래시, 재시작

**프로세스 트리 정지 (`ProotProcess.stop(graceMs)`, 데몬·sshd·`ProotExec` 타임아웃·`onDestroy` 모두 같은 함수):**

1. `/proc`에서 proot pid의 자손(같은 uid, ppid 체인)을 스냅샷한다.
2. 자손에게 `SIGTERM`(데몬은 supervisor가 워커를 정리한다).
3. `graceMs`(데몬 10초, sshd 5초, `ProotExec` 5초) 동안 proot 종료를 기다린다.
4. 남았으면 proot에 `SIGTERM`(→ `--kill-on-exit`가 tracee 정리), 2초 대기.
5. 그래도 남았으면 1의 스냅샷 + 새 스냅샷 전체와 proot에 `SIGKILL`.

`paseo.pid`는 쓰지 않는다. 오래된 pid가 재사용된 다른 프로세스(앱 자신 포함)를 죽일 수 있다. 신호는 항상 "지금 이 proot의 자손"에게만 간다.

**크래시와 재시작:**

- proot가 우리가 정지시키지 않았는데 끝나면 재시작한다. 백오프 1, 2, 4, 8, 16초(최대 60초). 5분 안에 5번 실패하면 멈추고 `error("restart-limit")`.
- `SIGKILL`(signal 9 또는 137)인데 우리가 보낸 게 아니면 `killed-by-system`(phantom process killer 또는 메모리 부족 안내).
- 데몬 재시작은 sshd를 건드리지 않고, 그 반대도 같다.
- 로그 파일 핸들은 프로세스의 출력 펌프가 끝나면 닫는다.

### 8.4 기기 설정 감지

| 설정                    | 감지                                                                                                                               | UI                                                       |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| phantom process killer  | `Settings.Global` `settings_enable_monitor_phantom_procs`: `"false"` → disabled, 그 외 → enabled, 예외 → unknown. 사후 판정은 §8.3 | `enabled`면 Linux 섹션 경고 + 개발자 옵션/adb 안내 한 줄 |
| 배터리 최적화           | `PowerManager.isIgnoringBatteryOptimizations`                                                                                      | 경고 + `openBatterySettings()`                           |
| 알림 권한 (Android 13+) | `areNotificationsEnabled`                                                                                                          | Linux 섹션 힌트 한 줄                                    |

devphone01: 2026-10-30 02:56 KST에 adb로 `settings put global settings_enable_monitor_phantom_procs false`, 유지 중. 배터리 최적화 예외는 설정돼 있지 않다.

---

## 9. 빌드 노트 (core 개발자)

- 빌드 스크립트: `scripts/phone/build-apk.sh`(bash). `scripts/build-apk.mjs`는 Windows에서 `npm`/`gradlew`를 셸 없이 spawn해서 실패하므로 같은 순서를 bash로 따른다:
  ```bash
  export APP_VARIANT=phone EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767 PASEO_WATCHDOG_BUILD=1
  npm run build:app-deps
  (cd packages/app && npx expo prebuild --platform android --no-install --clean)
  rm -rf packages/app/android/app/build/generated/assets
  (cd packages/app/android && ./gradlew :paseo-phone-runtime:testReleaseUnitTest assembleRelease --no-daemon \
      --max-workers=2 -Dorg.gradle.parallel=false -PreactNativeArchitectures=arm64-v8a)
  cp packages/app/android/app/build/outputs/apk/release/app-release.apk artifacts/paseo-phone-arm64.apk
  ```
- **Kotlin 단위 테스트는 APK를 만들 때마다 같은 Gradle 호출에서 돈다.** 테스트가 실패하면 APK가 나오지 않는다. CI는 1차 범위 밖이다. 업스트림 워크플로는 건드리지 않는다.
- TS 테스트: `npx vitest run packages/app/modules/paseo-phone-runtime/src/state-schema.test.ts packages/app/src/phone --bail=1`.
- Gradle 빌드 캐시는 `~/.gradle/gradle.properties`에서 켜져 있다.
- npm 11은 허용 목록에 없는 install script를 건너뛴다. `react-native-enriched-markdown`의 postinstall은 Windows bsdtar(`C:\Windows\System32\tar.exe`)가 필요하다. 이를 피하려고 `package.json`을 고치지 않는다.
- 업스트림 머지 후: `npm view @getpaseo/cli@<packages/server 버전> version`, `node-sha256.txt`에 새 Node 버전 줄, §4.3 체크리스트.
- proot 재빌드: `proot/build-proot.sh`(NDK r29, Linux/WSL), 산출물 sha256을 `PINS.md`에.
- 검사: `npm run typecheck`, `npm run lint`, `npm run format`.

---

## 10. 위험과 1차에서 하지 않는 것

### 10.1 위험

| 위험                                                        | 대응                                                                |
| ----------------------------------------------------------- | ------------------------------------------------------------------- |
| 모든 파일 접근 권한이 게스트 전체에 넘어감                  | 1차 수용(소유자). 기본 꺼짐, 문구로 알림. 2차: SAF/앱 전용 디렉터리 |
| proot 바이너리가 Kai 사전 빌드본(소스에서 재현한 해시 아님) | `PINS.md` 해시 고정. Linux/WSL 재빌드 후 재현 해시로 교체           |
| `@getpaseo/cli` 전이 의존성 미고정                          | 수용. 최상위 버전 고정                                              |
| 파일 작업 20~50배 느림                                      | 범위 밖 작업 안 함, 지침에 명시                                     |
| 메모리: 데몬 대기 ~500MB                                    | 프로바이더 끄기, 실사용 관찰                                        |
| phantom process killer / 저메모리 kill                      | 기기 설정 + `killed-by-system`                                      |
| 업스트림 npm 게시 지연                                      | §9 확인. 업데이트 실패 시 `canStartPrevious` 규칙                   |
| 업스트림이 §4.3을 바꿈                                      | 머지 체크리스트                                                     |
| 폰의 다른 앱이 loopback 데몬에 접근                         | 1차 수용                                                            |
| 에이전트가 게스트 root로 SSH 접근을 늘림                    | 1차 수용, sshd 설정은 시작마다 복원                                 |
| 화면 꺼짐 장시간·딥 Doze 생존                               | FGS + wake lock + wifi lock. QA 소크                                |
| Codex 샌드박스 불가                                         | Full Access, 지침에 명시                                            |
| rootfs tarball URL이 사라짐                                 | 고정 URL·sha256. 바뀌면 `RootfsSpec.kt`만                           |

### 10.2 1차에서 하지 않는 것

- 데몬 비밀번호, LAN 직접 노출, 릴레이
- 부팅 시 자동 시작
- 알림에 에이전트 수, 네이티브의 데몬 프로토콜 클라이언트
- 호스트 목록 정렬·"This device"·Remove host/Pair device 숨김
- 포크 데몬 빌드, 데몬 코드 수정
- apt·에이전트 자동 업그레이드, rootfs 버전 업그레이드(Reinstall만)
- 비-phone 변형의 자동 링크 제외
- 교환 폴더 권한 범위 축소(SAF)
- CI
- arm64 외 ABI, 다른 배포판, 폰 안 터미널 화면, MagicDNS, 공유 저장소 레포 작업

---

## 11. 디자인 질문 답변

| Q   | 답                                                                                                                                  |
| --- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Q1  | 채택. 데몬은 loopback만, 데스크톱은 Remote SSH. 첫 접속 전 호스트 키 수락 필요. §5.1                                                |
| Q2  | 채택하되 판별 객체로, 필드 추가(`hostKeyFingerprints`, `device`, `restart-required`, `canStartPrevious`, `serverId`, `space`). §7.1 |
| Q3  | 실패한 단계부터. 다운로드는 extract 성공 때까지 캐시. extract는 staging만 다시. §2.5                                                |
| Q4  | 네이티브는 에이전트 수를 모른다. Stop은 앱에서 확인. §7.4                                                                           |
| Q5  | `useLocalDaemonServerId`를 바꾸지 않는다. §4.2                                                                                      |
| Q6  | 숨기지 않는다. §4.2                                                                                                                 |
| Q7  | `root`, 2222, 키만. §5.1                                                                                                            |
| Q8  | 런타임이 `phonePathLabel`을 준다. `/root/exchange`. `restart-required` 추가. §5.2                                                   |
| Q9  | `src/phone/i18n` en·ko, 알림은 `strings.xml` en·ko. §7.3                                                                            |
| Q10 | 딥링크 `paseo-phone://settings/hosts/<serverId>/host`. §7.4                                                                         |
| Q11 | 1차에 없음                                                                                                                          |
| Q12 | 사전 검사 4 GB, 설치 후 약 2.36 GB, 순간 최대 3.3 GB. 화면 숫자는 `space` 필드. §2.5                                                |
| Q13 | 맞다. 앱은 데몬과 Node만 업데이트. §2.5                                                                                             |

---

## 12. 소유자 확인이 필요한 결정

1. 데스크톱 접속을 SSH 경유로 한다(D8). 첫 접속 전 `ssh -p 2222 root@<폰>` 한 번.
2. 폰의 다른 앱이 loopback 데몬에 붙을 수 있는 것을 수용한다(§5.1).
3. applicationId `io.github.ouor.paseophone`, "Paseo Phone", scheme `paseo-phone`(D12).
4. SSH 포트 2222, IPv4만(D9).
5. "이 기기" 표시 축소(§4.2).
6. phantom process killer 상태를 앱이 읽어 경고한다(§8.4).
7. **모든 파일 접근 권한이 게스트 전체에 공유 저장소 전체를 연다는 것을 1차에서 수용한다(§5.2). 소유자 기본값으로 기록.**
8. **Reinstall이 `server-id`, SSH 호스트 키, `authorized_keys`를 보존한다(D16).** 깨끗한 재설치를 원하면 앱 삭제 후 재설치.
9. **세 에이전트 CLI의 첫 설치 버전을 빌드 시점에 고정한다(§3.1).** 이후 업데이트는 사용자가 한다.
