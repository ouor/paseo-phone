# 가설 검증(스파이크) 보고서

2026-10-30 · 기획서 "먼저 검증할 것과 리스크" 항목을 기존 도구(Termux + proot-distro Debian)만으로 확인한 결과.

## 환경

| 항목                 | 값                                                                                                                                          |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 기기                 | devphone01, Xiaomi M2012K11AG (POCO F3), Android 16 커스텀 롬(`BP4A.251205.006`), 커널 4.19, arm64, RAM 5.6GB + swap 3GB, `/data` 여유 94GB |
| Termux               | 0.118.3 (GitHub 빌드, targetSdk 28), 미러 tuna.tsinghua                                                                                     |
| proot / proot-distro | proot 5.1.107.101, proot-distro 5.10.3                                                                                                      |
| rootfs               | Debian 13 (trixie)                                                                                                                          |
| Node (rootfs 안)     | v22.20.0, nodejs.org 공식 linux-arm64 tarball을 `/usr/local`에 풀어 설치 (레포 `.tool-versions`와 같은 버전)                                |
| 접속                 | PC → 폰 Tailscale(100.79.37.5, RTT 3~6ms)                                                                                                   |

재현 스크립트는 `spike/`에 있다.

| 스크립트                       | 실행 위치    | 하는 일                                                                    |
| ------------------------------ | ------------ | -------------------------------------------------------------------------- |
| `spike/01-debian-base.sh`      | proot Debian | apt 기본 도구, Node 22 설치                                                |
| `spike/02-start-daemon.sh`     | Termux       | `termux-wake-lock` 후 proot 안에서 `paseo daemon run`을 세션과 분리해 실행 |
| `spike/03-start-proot-sshd.sh` | Termux       | proot 안 OpenSSH sshd를 8023 포트로 실행                                   |
| `spike/04-bench.sh`            | 둘 다        | 파일 작업 벤치마크                                                         |

부트스트랩 방법: quick-qa로 Termux 화면에서 `pkg install openssh` 후 테스트 전용 키(이번에 새로 만든 ed25519, `spike-test`)를 `adb reverse`로 받아 `authorized_keys`에 넣고 Termux `sshd`(8022)를 띄웠다. 이후 작업은 전부 PC에서 ssh로 했다. 소유자 자격 증명은 쓰지 않았다.

## 요약

| #   | 가설                                                                   | 판정                                                                         |
| --- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 1   | Claude Code, Codex, OpenCode가 proot Debian에서 설치·실행된다          | **통과** (Codex 샌드박스는 동작 안 함)                                       |
| 2   | Paseo 데몬이 proot에서 안정적으로 돌고, 폰 localhost와 PC에서 접속된다 | **통과** (주의점 있음)                                                       |
| 3   | proot 안 sshd에 PC에서 접속된다                                        | **통과**                                                                     |
| 4   | 실사용 속도가 견딜 만하다                                              | **부분 통과**                                                                |
| 5a  | phantom process killer                                                 | **리스크 확인됨** — 켜진 상태에서 데몬·sshd·proot가 실제로 죽었다. 끄면 해결 |
| 5b  | 화면 꺼짐 상태 생존                                                    | **부분 통과** (wake lock 상태로 2분 확인, 장시간·딥 Doze 미확인)             |

설계를 바꿔야 할 실패는 없다. 다만 4번(파일 메타데이터 작업 10~50배 느림)과 5a(기기 설정 필수)는 설계에 반영해야 한다.

## 1. 세 에이전트 — 통과

설치는 모두 rootfs 안 전역 npm(`npm i -g`)으로 했다.

| 에이전트    | 설치                                                                         | 버전              | 실행 확인                                                                                                                                                                                       |
| ----------- | ---------------------------------------------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| OpenCode    | `npm i -g opencode-ai` 17초. `opencode-linux-arm64`(glibc) 바이너리가 선택됨 | 1.19.5            | `--version` 1.4초, `--help` 정상, TUI가 pty에서 정상 렌더("Ask anything…", `/connect` 안내). `opencode serve --hostname 127.0.0.1 --port 4096` 기동 후 `GET /config` 200, `/provider` 응답 정상 |
| Codex       | `npm i -g @openai/codex` (Claude Code와 함께 21초)                           | codex-cli 0.165.0 | `--version`, `--help` 정상. `codex app-server` 기동·종료 정상. TUI는 터미널 색상 질의(OSC 10/11)에 응답을 기다림 — 실제 터미널에서는 정상, 크래시 아님                                          |
| Claude Code | `npm i -g @anthropic-ai/claude-code`                                         | 2.1.312           | `--version` 정상, TUI 첫 화면(테마 선택) 정상 렌더                                                                                                                                              |

Paseo 데몬에서 본 프로바이더 상태(`paseo provider ls`): `claude`, `codex`, `opencode` 모두 **available**, 모드 목록도 정상(Codex: Default Permissions / Auto-review / Full Access).

**Codex 샌드박스는 proot에서 동작하지 않는다.**

```
$ codex sandbox echo SANDBOX_OK
bwrap: Can't bind mount /dev/null on null: readlink(/proc/self/fd/N) for "/proc/self/fd/4": Invalid argument
```

`codex app-server`도 "bubblewrap을 찾을 수 없다"는 경고를 낸다. apt `bubblewrap`을 설치해도 proot의 `/proc/self/fd` 에뮬레이션과 사용자 네임스페이스 부재 때문에 실행되지 않는다. 폰에서 Codex는 **Full Access(샌드박스 없음) 모드로만** 써야 한다. 폰 자체가 격리 단위라는 전제로 받아들이고, 기본 모드를 Full Access로 두는 설정을 Paseo 밖(데몬 config)에서 넣는 쪽을 권한다.

### 소유자가 해야 할 로그인

키·토큰은 입력하지 않았다. 각 CLI는 rootfs 안 `/root` 아래에 자격 증명을 저장하므로, 아래를 proot Debian 셸(폰 Termux 또는 PC에서 `ssh -p 8023 root@<폰>`)에서 한 번씩 하면 된다.

| 에이전트    | 로그인                                       | 비고                                                                                                     |
| ----------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Claude Code | `claude` 실행 → `/login`                     | proot에는 브라우저 연동(xdg-open)이 없으므로 출력된 URL을 다른 기기 브라우저에서 열고 코드를 붙여 넣는다 |
| Codex       | `codex login --device-auth`                  | 디바이스 코드 방식이 가장 단순하다                                                                       |
| OpenCode    | `opencode auth login` (TUI에서는 `/connect`) | 프로바이더별 API 키 또는 OAuth                                                                           |

## 2. Paseo 데몬 — 통과(주의점 있음)

**설치 방식: 공개 npm 패키지 `@getpaseo/cli@0.14.0`을 rootfs 안 전역 설치.** 레포를 rootfs에서 빌드하는 것보다 현실적이다(빌드 의존성·시간 없음, 아래 4번 속도 참고). 네이티브 모듈 확인:

- `node-pty` — `prebuilds/linux-arm64/pty.node`가 패키지에 포함되어 있어 컴파일 불필요. 터미널 워커 프로세스 정상 기동.
- `sherpa-onnx-node` — `sherpa-onnx-linux-arm64` 옵션 패키지가 설치됨. 음성 런타임 정상 기동.
- 따라서 `build-essential`/`python3` 없이도 설치될 것으로 보인다(이번엔 설치되어 있었으므로 미확인).

기동:

```bash
# Termux에서 (spike/02-start-daemon.sh)
setsid nohup proot-distro login debian -- bash -lc \
  'export PASEO_LISTEN=0.0.0.0:6767; exec paseo daemon run' > ~/paseo-daemon.out 2>&1 </dev/null &
```

- proot-distro는 `--kill-on-exit`로 proot를 띄우므로 `paseo daemon start`(분리 실행)를 ssh 세션 안에서 쓰면 세션 종료와 함께 죽는다. **데몬이 proot 세션의 포그라운드 자식이어야 한다.** 앱의 포그라운드 서비스도 "proot 프로세스 하나 = 데몬 하나" 구조로 띄우면 된다.
- 로그: `Bootstrap complete` 327ms, `Server listening on http://0.0.0.0:6767` 353ms, `authRequired: false`. 내장 플러그인 로딩이 플러그인당 0.7~0.9초(프로세스 기동 비용)로 전체 준비까지 약 15초.
- 폰 localhost: `paseo daemon status`, `curl 127.0.0.1:6767` 응답.
- PC에서: `npx tsx packages/cli/src/index.js provider ls --host 100.79.37.5:6767` (PC의 6767 데몬은 건드리지 않음) 정상. `ls -a` 정상(에이전트 0개).
- 실행 중 오류(level 50/60) 로그 없음. 시험 내내 크래시·재시작 없음.

주의점:

1. **`daemon.get_status`가 매번 1.8~2.0초** 걸린다(`ws_slow_request`). 핸들러가 프로바이더 가용성을 확인하면서 각 CLI를 `--version`으로 띄우는데, proot에서 프로세스 기동이 비싸다. CLI의 상태 상세 타임아웃이 1500ms라 `paseo daemon status`는 "Status details unavailable (DAEMON_REQUEST_TIMEOUT)"를 낸다(데몬 자체는 reachable). 기능 장애는 아니지만, 앱 쪽에서 비슷한 짧은 타임아웃이 있으면 걸릴 수 있다. 쓰지 않는 프로바이더(copilot, pi, antigravity, muse 등)를 config에서 끄면 줄어든다.
2. **로컬 음성 모델 자동 다운로드.** 첫 기동 시 `parakeet-tdt-0.6b-v2-int8` 모델을 `/root/.paseo/models`에 받는다(백그라운드, 수백 MB). 폰에서는 받아도 되는지, 끌지 결정이 필요하다.
3. **메모리.** 대기 상태 RSS: Paseo Daemon 272MB + `paseo daemon run` 121MB + Supervisor 51MB + 터미널 워커 56MB ≈ 500MB. 이 기기는 대기 중에도 가용 메모리가 1.9GB 정도라 에이전트 2~3개 동시 실행 시 여유가 크지 않다.
4. `PASEO_LISTEN=0.0.0.0`이면 인증 없이 열린다(`authRequired: false`). 데스크톱 접속을 위해 외부에 열 때는 비밀번호(`PASEO_PASSWORD`) 또는 페어링을 설계에 넣어야 한다.

## 3. proot 안 sshd — 통과

- Debian의 OpenSSH sshd를 proot 안에서 8023 포트(앱은 1024 미만 바인딩 불가)로 실행. `Server listening on 0.0.0.0 port 8023`.
- PC에서 `ssh -p 8023 root@100.79.37.5` 접속 1.1초, 셸은 Debian `/bin/bash`, `node`/`claude`/`codex`/`opencode`/`paseo` 모두 PATH에 있음.
- pty 할당(`ssh -tt` → `/dev/pts/1`), sftp, 로컬 포트 포워딩(`-L 16767:127.0.0.1:6767` → 데몬 응답) 모두 동작. VS Code Remote-SSH에 필요한 요소는 갖춰졌다(직접 VS Code 접속은 미시험).
- 권한 분리(privsep) 관련 오류 없음. proot가 root(uid 0)로 위장하므로 `PermitRootLogin prohibit-password` + 키 인증으로 충분하다.
- 이번엔 Termux 쪽 sshd(8022)도 띄웠지만 최종 설계에는 proot 안 sshd 하나면 된다.

## 4. 실사용 속도 — 부분 통과

`spike/04-bench.sh`로 같은 작업을 proot Debian과 Termux 네이티브에서 비교했다. 대상은 `expressjs/express` 전체 클론, `npm install`(설치 결과 6,540 파일). 측정 중 Paseo 데몬이 뒤에서 돌고 있었다.

| 작업                        | proot Debian | Termux 네이티브 | 배율 |
| --------------------------- | -----------: | --------------: | ---: |
| `git clone` (전체 이력)     |        4.29s |           2.25s | 1.9x |
| `npm install` (캐시 없음)   |       50.15s |          18.78s | 2.7x |
| `npm install` (캐시 있음)   |       30.25s |           9.65s | 3.1x |
| `npm test` (mocha)          |       20.62s |           7.86s | 2.6x |
| `git status` x5             |        1.24s |           0.20s |   6x |
| `git log --stat -500`       |        1.18s |           0.25s | 4.7x |
| `rg function .`             |        0.24s |           0.06s |   4x |
| `find node_modules -type f` |        2.08s |           0.10s |  21x |
| `tar` node_modules          |       10.29s |           0.20s |  51x |
| `rm -rf node_modules`       |        4.94s |           0.19s |  26x |
| 프로세스 기동 200회         |        2.43s |           4.06s | 0.6x |

해석:

- 오버헤드는 ptrace로 가로채는 **파일 시스템 syscall(stat/open/unlink)** 에 몰려 있다. 네트워크·CPU 위주 작업(clone, 테스트 실행)은 2~3배, 파일 수천 개를 훑는 작업은 20~50배 느리다. 프로세스 기동은 오히려 Termux 쪽이 느렸다(Termux의 `termux-exec` LD_PRELOAD 비용으로 추정).
- proot 옵션은 큰 차이가 없다: `--no-link2symlink` 적용 시 find 2.16→1.78초, tar 11.1→9.0초(약 15~20% 개선). 하드링크 에뮬레이션을 끄면 일부 패키지(하드링크를 쓰는 dpkg 등)가 깨질 수 있으므로 기본값 유지가 무난하다.
- 체감: 소·중형 Node 레포에서 클론과 `npm install`은 1분 안쪽으로 견딜 만하다. 에이전트가 흔히 하는 `rg`, `git status`, 파일 읽기는 1초 안쪽이다. **node_modules 전체를 훑는 작업(대형 모노레포 설치, 파일 감시, tar/rm)은 확실히 느리다.** 기획서의 "무거운 빌드는 하지 않는다"와 맞는 수준.
- 이 레포(Paseo) 같은 대형 모노레포를 폰에서 `npm install`·빌드하는 것은 현실적이지 않다. 데몬은 레포 빌드가 아니라 npm 패키지로 설치해야 하는 이유이기도 하다.

## 5a. phantom process killer — 리스크 확인, 기기 설정으로 해결

처음 상태: `settings_enable_monitor_phantom_procs` = `null`(기본값 = 켜짐), `max_phantom_processes=32`. `dumpsys activity processes`에 Termux 자식 프로세스가 `PhantomProcessRecord`로 추적되고 있었다.

시험: 데몬·sshd가 돌고 있는 상태에서 Termux에 `sleep` 40개를 띄움. 약 1분 뒤 logcat:

```
ActivityManager: Killing PhantomProcessRecord {… :sleep/u0a173}: Trimming phantom processes
```

죽은 프로세스: **Paseo Daemon, Paseo Supervisor, proot ×2, node ×2, sshd(Termux), proot 안 sshd, Termux 셸(bash)**, sleep 8개. 오래된 프로세스부터 죽이므로 상시 실행 서비스가 먼저 희생된다. Termux 셸도 `Process completed (signal 9)`로 끝났다. (같은 시각 다른 앱 uid 10171의 `su` 프로세스도 하나 죽었다 — 기기 전역 한도다.)

조치(기록): **2026-10-30 02:56 KST, adb로 `settings put global settings_enable_monitor_phantom_procs false` 실행.** 이후 같은 시험(sleep 40개 + 데몬 + sshd, 총 70개 이상)에서 `Trimming phantom` 0건, `PhantomProcessRecord` 추적 0건, 데몬·sshd 생존.

설계 의미:

- 데몬 + 에이전트 3개 + LSP/테스트 자식 프로세스를 합치면 32개는 쉽게 넘는다. **이 설정 없이는 앱이 성립하지 않는다.** 기획서 원칙대로 앱이 우회하지는 않는다. 설치 안내에 adb 명령 또는 개발자 옵션 "하위 프로세스 제한 사용 중지" 토글을 적는다. 앱이 `Settings.Global`로 이 값을 읽어 꺼져 있지 않으면 경고하는 방안은 일반 앱 권한으로 읽히는지 미확인이다.
- 이 설정은 OTA·초기화 시 되돌아갈 수 있다.

## 5b. 화면 꺼짐 상태 생존

**부분 통과(짧은 확인만).** 팀 지시에 따라 장시간 소크 시험은 중단했다.

조건: `termux-wake-lock` 보유(`PARTIAL_WAKE_LOCK 'termux:service-wakelock'` 확인), Termux는 배터리 최적화 예외 목록(`deviceidle whitelist`)에 **없음**, phantom killer 꺼짐. `input keyevent 223`으로 화면을 끄고 PC에서 1분마다 확인:

```
screen off 03:00:40
03:01:43 min=1 daemon_http=404 proot_ssh=[ok 1793296898] mWakefulness=Dozing
03:02:45 min=2 daemon_http=404 proot_ssh=[ok 1793296959] mWakefulness=Dozing
```

(404는 데몬 루트 경로의 정상 응답.) 화면이 꺼진 2분 동안 데몬, proot sshd, proot 안 10초 주기 타이머 모두 끊김 없이 동작했다. 딥 Doze(`force-idle`)와 장시간(수십 분 이상) 생존은 **미확인**이다. 설계에서는 포그라운드 서비스 + partial wake lock을 기본으로 하고, 배터리 최적화 예외를 필수 기기 설정 목록에 넣는다. 참고로 quick-qa 실행 중 Termux가 "항상 백그라운드에서 실행 허용" 시스템 대화상자를 띄웠고 거부된 상태다.

## 아키텍처에 주는 시사점

1. **rootfs 구성:** Debian trixie + nodejs.org 공식 Node 22 tarball(`/usr/local`). Debian 패키지 Node(20.x)는 쓰지 않는다. 버전은 Paseo 레포 `.tool-versions`를 따른다.
2. **데몬 설치:** rootfs 안에서 `npm i -g @getpaseo/cli@<버전>`. 포크에서 수정이 생기면 PC에서 `npm pack`한 tarball을 rootfs에 설치한다(폰에서 레포 빌드 금지). arm64 prebuild가 있어 네이티브 컴파일이 필요 없다.
3. **에이전트 설치:** rootfs 안 전역 npm 세 개. 자격 증명은 `/root` 아래 각 CLI 기본 위치. 업데이트도 `npm i -g`로 단순하다.
4. **프로세스 구조:** 포그라운드 서비스가 proot 세션 두 개(데몬, sshd)를 각각 포그라운드 자식으로 띄운다. 데몬은 `paseo daemon run`(내장 supervisor가 워커 크래시 재시작 담당). proot 프로세스가 죽으면 세션 전체가 죽으므로 서비스가 proot 프로세스를 감시·재시작한다.
5. **Codex:** 샌드박스 불가. Full Access 모드 고정.
6. **필수 기기 설정:** phantom process killer 끄기. 배터리 최적화 예외(아래 5b 결과 참고).
7. **성능 기대치:** 파일 메타데이터 작업 10~50배, 일반 작업 2~3배. 대형 모노레포·무거운 빌드는 범위 밖이라는 기획서 판단 유지.
8. **외부 노출:** `0.0.0.0` 바인딩 시 인증 없음 — 비밀번호 또는 페어링을 반드시 켠다. sshd는 키 인증만.
9. **다음 단계에서 확인할 것:** Kai의 proot 빌드가 Termux proot(5.1.107)와 같은 동작(특히 `/proc/self/fd`, link2symlink, sysvipc)을 하는지. 이번 결과는 Termux proot 기준이다.
