# QA 보고서: Paseo Phone 통합 1차

> 담당: QA · 기기 devphone01(M2012K11AG, Android 16) · 다음 재검증 때 이 문서를 이어 쓴다.

## 1회차 (2026-10-30, 수정 전 빌드)

**빌드:** `57a88593e`(코드 리뷰 수정 반영 **전**)를 `scripts/phone/build-apk.sh`로 빌드(`BUILD SUCCESSFUL in 8m 5s`), `adb install -r`. 이 회차 중 팀장이 리뷰 수정(rootfs 안전, stop/restart, 재설치 시 server-id·호스트 키 유지, sshd IPv4 전용 등)을 넣었다고 알려 와서 6·7번 일부는 하지 않고 멈췄다. 아래 버그는 모두 수정 전 빌드 기준이므로 재검증에서 다시 본다.

### 체크리스트

| #   | 항목                                                             | 결과             | 근거                                                                                                                              |
| --- | ---------------------------------------------------------------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Host 페이지 섹션 순서(Linux, 원격 접속, 교환 폴더, Linux 초기화) | 통과             | quick-qa, `evidence/03`, `evidence/06`                                                                                            |
| 1   | Linux 멈추기(확인 대화상자) → 멈춤                               | 통과             | 확인 후 "멈춤". 데몬 proot 종료                                                                                                   |
| 1   | Linux 시작                                                       | 통과             | Host 페이지에서 시작 → Linux·데몬 실행 중                                                                                         |
| 1   | 데몬 재시작                                                      | 통과             | 데몬 proot pid 30172 → 31340, 다시 "실행 중"                                                                                      |
| 1   | Linux 재시작(교환 폴더 restart-required)                         | 통과(데몬만)     | 데몬은 바인드 포함으로 다시 뜸. sshd는 B1 때문에 재시작되지 않음                                                                  |
| 1   | SSH 서버 스위치 끄기/켜기                                        | **실패**         | B1                                                                                                                                |
| 1   | 화면 꺼짐 유지 스위치                                            | 통과             | 끄면 `Wake Locks: size=0`, 알림 "SSH 꺼짐"에서 "깨어 있음" 빠짐. 켜면 `PaseoPhone:runtime` partial wake lock                      |
| 1   | 로그 시트(데몬/SSH/설치 탭)                                      | 통과(UX 메모 U2) | `evidence/04`, `evidence/05`                                                                                                      |
| 2   | 알림 권한을 나중에 받으면 알림이 나타남(core-runtime 수정 확인)  | 통과             | `pm revoke` 상태로 실행 → 알림 0개. `pm grant` 후 앱 포그라운드 복귀 → 알림 표시                                                  |
| 2   | 알림 내용(상태, 주소, Stop)                                      | 통과             | "실행 중 / ssh 192.168.31.120:2222 (Wi-Fi) / ssh 100.79.37.5:2222 (Tailscale) · 깨어 있음", 액션 "정지". `evidence/02`            |
| 2   | 알림 Stop → 확인 대화상자                                        | 통과             | Host 페이지에 "Linux를 멈출까요?". `evidence/03`                                                                                  |
| 2   | 딥링크 `…/host?logs=daemon`                                      | 통과             | `am start -d paseo-phone://settings/hosts/srv_…/host?logs=daemon` → 데몬 로그 시트. `evidence/04`                                 |
| 3   | 앱 시트로 키 추가                                                | 통과             | quick-qa 7단계, 지문 `SHA256:+dpc589…` 표시. 행 요약은 U3                                                                         |
| 3   | 키 로그인 `ssh -p 2222 root@devphone01`                          | 통과             | 0.54초. `hostname`=`m2012k11ag`, Debian 13, `node paseo claude codex opencode` 모두 PATH. config.json이 §3.3과 같음               |
| 3   | 데몬 터널 `ssh -W 127.0.0.1:6767`                                | 통과             | `HTTP/1.1 404`(Express) 응답                                                                                                      |
| 3   | 레포 CLI `--host ssh://root@devphone01:2222`                     | 통과             | `daemon status`: serverId `srv_5fBraiVIfqKv`, 0.14.0, claude/codex/opencode available, relay disabled. 이 PC 데몬은 건드리지 않음 |
| 3   | 비밀번호 로그인 거부                                             | 통과             | `Permission denied (publickey)`                                                                                                   |
| 3   | PC → 6767 직접 접속 거부                                         | 통과             | Tailscale·Wi-Fi 주소 모두 연결 안 됨                                                                                              |
| 3   | `-R` 원격 포워딩 거부                                            | 통과             | `remote port forwarding failed`                                                                                                   |
| 4   | 저장소 권한 → restart-required → Linux 재시작                    | 통과             | `evidence/06`. appops `MANAGE_EXTERNAL_STORAGE: allow`                                                                            |
| 4   | 교환 폴더 양방향                                                 | 통과             | ssh로 `/root/exchange`에 쓴 파일이 `/storage/emulated/0/Paseo`에 보이고 반대도 됨. 테스트 파일은 지움                             |
| 5   | 사이드바 "Linux 멈춤" 콜아웃                                     | **실패**         | B2. `evidence/09`                                                                                                                 |
| 5   | 콜아웃에서 시작                                                  | 확인 못 함       | B2 때문에 버튼이 없다                                                                                                             |
| 6   | Linux 다시 설치 → 화면 A 단계 진행 → 완료                        | 통과(UX 버그 B3) | `evidence/08`. 확인 05:06:20경 → 데몬 LISTEN 05:12:44, **약 6분 24초**                                                            |
| 6   | 교환 폴더 내용 유지                                              | 통과             | 재설치 후에도 `qa-from-*.txt` 두 개 남음                                                                                          |
| 6   | 첫 기동 로그에 `model-downloader` 없음                           | 확인 못 함       | 재설치로 키가 지워져 ssh로 못 들어감(B1 고아 sshd도 2222를 잡고 있었음). 재검증 때 본다                                           |
| 6   | 호스트 이름이 기기 기반                                          | 통과(재설치 전)  | `m2012k11ag`. 재설치 후는 확인 못 함                                                                                              |
| 7   | 일반 에이전트 UI 회귀                                            | 일부             | 홈·사이드바·설정·호스트 페이지는 정상으로 열림. 에이전트 화면은 열지 않음                                                         |
| —   | 설치 실패 화면(비행기 모드)과 다시 시도                          | 하지 않음        | 시간 대비 우선순위 낮음                                                                                                           |

### 시간

| 항목                                | 값                                                                                 |
| ----------------------------------- | ---------------------------------------------------------------------------------- |
| APK 빌드                            | Gradle 8m 5s (전체 스크립트 약 10분)                                               |
| 앱 실행 → 데몬 LISTEN (설치된 상태) | 약 22초                                                                            |
| 재설치 전체 (확인 → 데몬 LISTEN)    | 약 6분 24초. extract~configure ~40초, packages 3분 20초, paseo 2분 6초, start 15초 |
| SSH 키 로그인                       | 0.54초                                                                             |

### 버그

**B1. SSH 서버를 끄거나 Linux를 멈춰도 sshd가 죽지 않는다** — 심각도 **높음**(보안: UI는 "꺼짐"인데 키 로그인을 계속 받는다), 담당 **core-runtime**(`RuntimeController.stopManaged` / `linux/ProotProcess.destroy*`)

- 재현: Linux 실행 중 Host 페이지 → SSH 서버 스위치 끄기. 깨끗한 재실행(`am force-stop` 후 실행) 뒤에도 매번 재현.
- 기대: sshd proot 종료, 2222 닫힘.
- 실제: 스위치·힌트·알림은 "꺼짐"인데 `ps`에 sshd proot와 `sshd -D -e -p 2222`가 그대로이고, 25초 뒤에도 `ssh … echo` 성공(`STILL-OPEN`). 다시 켜면 새 sshd가 `Bind to port 2222 … Address already in use`로 5번 죽고 "SSH 서버를 시작하지 못했습니다". **Linux 멈추기**도 같다: 데몬은 멈추고 FGS도 사라지는데 sshd는 서비스 없이 남는다. 재설치 중에도 같은 고아 sshd가 rootfs가 지워진 채로 2222를 계속 잡고 있었다. `am force-stop`만 정리한다.
- 관찰: 데몬은 supervisor SIGTERM으로 멈추므로 문제가 없다. proot 자체를 죽이는 경로(`destroy()` SIGTERM → `destroyForcibly()`)만 쓰는 sshd에서 실패하므로 그 경로가 proot를 죽이지 못하거나 호출되지 않는 것으로 보인다(`m.process`가 null인 경우 포함해 확인 필요).
- 근거: `evidence/01-host-page-ssh-off-but-running.png`, `evidence/05-ssh-off-orphan-bind-fail.jpg`.

**B2. 사이드바 "Linux 멈춤" 콜아웃이 폰에서 보이지 않는다** — 심각도 중간, 담당 **ui**

- 재현: Host 페이지에서 Linux 멈추기 → 사이드바 열기.
- 기대: "Linux 멈춤" 콜아웃과 시작/자세히 버튼.
- 실제: 콜아웃 없음. 사이드바에는 "아직 프로젝트가 없습니다"와 연결 실패 문구만.
- 원인: 업스트림 `packages/app/src/components/sidebar-callout-slot.native.tsx`가 `null`을 반환한다. 사이드바 콜아웃은 웹 전용이라 `LinuxStoppedCalloutSource`가 등록해도 네이티브에서 그려지지 않는다.
- 근거: `evidence/09-sidebar-no-callout.jpg`.

**B3. 다시 설치 확인 후 화면이 그대로라 두 번 누르게 되고 "busy" 오류가 뜬다** — 심각도 낮음~중간, 담당 **ui**(+ core-runtime: 정지 단계 상태 발행)

- 재현: Linux 초기화 → 다시 설치 → "지우고 다시 설치".
- 기대: 바로 화면 A 또는 진행 표시.
- 실제: Linux를 멈추는 동안(약 10~40초) Host 페이지가 그대로이고 "다시 설치" 버튼도 활성. 다시 누르면 "Linux를 다시 설치하지 못했습니다 … Another runtime command is in progress". 첫 요청은 정상 진행됐다.
- 근거: `evidence/07-reinstall-busy.jpg`.

### UX 메모 (버그 아님, 담당 ui)

- U1. 알림 제목이 "실행 중"뿐이다. 무엇이 실행 중인지는 앱 이름으로만 안다(디자인 확인 필요).
- U2. 로그 시트가 64 KB 꼬리의 **맨 위**부터 보여서 최신 줄을 보려면 길게 스크롤해야 한다. 끝으로 스크롤해서 열거나 역순 표시를 검토. 로그 타임스탬프는 UTC(`Z`).
- U3. 한국어 화면에서 허용된 키 행 요약이 "1 key"(영어)로 나온다. ko에는 `keys.count_other`("키 {{count}}개")가 있는데 en 복수형이 쓰인다. 폰 i18n 번들의 복수형 키 병합 확인 필요.

### 정리 상태

- 테스트 키 `pp-qa-throwaway`: 앱에서 추가 → 재설치로 rootfs와 함께 지워짐(재설치 후 그 키로 `Permission denied` 확인). PC 쪽 키와 임시 known_hosts 삭제.
- 교환 폴더 테스트 파일 삭제. 알림 권한은 허용, 저장소 권한은 허용 상태로 둠.
- 앱은 설치된 채 실행 중(Linux·데몬·sshd 동작, `am force-stop` 후 재실행으로 고아 sshd 정리).

### 재검증 때 할 것

1. B1: SSH 끄기 → `ps`·`ss`·ssh 로그인 실패 확인, 다시 켜기, Linux 멈추기 후 2222 닫힘.
2. B2: 콜아웃 표시와 콜아웃 시작 버튼.
3. B3: 재설치 확인 직후 피드백. 재설치 후 server-id·호스트 키 유지(팀장 메모), `model-downloader` 없음, hostname.
4. 이번에 하지 않은 것: 에이전트 화면 열기, 설치 실패 화면(비행기 모드)과 다시 시도, 키 삭제 UI.

## 2회차 재검증 (2026-10-30, `48e5ace69` 서명 APK)

**빌드:** 팀장이 준 `artifacts/paseo-phone-arm64.apk`(커밋 `48e5ace69`, cert `c8efe9f1…ebc`, sha256 `9092b40e…aa06`). 서명이 달라 `adb uninstall` 후 `adb install -g`(런타임 권한 부여, 저장소 권한은 앱 흐름으로). 새 기기 상태에서 첫 실행부터 봤다. 근거 사진은 `evidence/r2/`.

### 체크리스트

| 항목                                                  | 결과      | 근거                                                                                                                                                     |
| ----------------------------------------------------- | --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 첫 실행: 설치 화면 → 준비, Welcome 없이 홈            | 통과      | 실행 05:49:52 → 데몬 LISTEN 05:56:01, **약 6분 9초**. 스플래시 → 홈(Welcome 없음). `r2/01`                                                               |
| `model-downloader` 없음                               | 통과      | `/root/.paseo/daemon.log`에 `model-download` 0건. speech-runtime 로그에 `dictationStt … "enabled":false`                                                 |
| hostname 기기 기반                                    | 통과      | `m2012k11ag`(첫 설치, 재설치 후 모두)                                                                                                                    |
| B1: SSH 끄기 → sshd 종료                              | **통과**  | sshd·proot 사라짐, 2222 `Connection refused`. 다시 켜면 새 sshd, 키 로그인 성공                                                                          |
| B1: Linux 멈추기 → 프로세스 없음                      | **통과**  | 15초 뒤 `ps`에 libproot/sshd/node 0개, 2222·6767 닫힘, RuntimeService 없음                                                                               |
| B1: Linux 초기화 → 정지 단계에서 프로세스 없음        | **통과**  | 06:08:02 표본에 스크립트·포트 없음                                                                                                                       |
| sshd IPv4 전용                                        | 통과      | `ss`에 `0.0.0.0:2222`만, `*:2222`(::) 없음. `phone.conf`에 `AddressFamily inet`                                                                          |
| 옵션 붙은 키 거부                                     | 통과      | `command="x" ssh-ed25519 …` → "올바른 공개 키가 아닙니다", 키 추가 안 됨                                                                                 |
| 키 삭제 시 sshd 재시작                                | 통과      | sshd pid 11714 → 12256, 삭제한 키는 `Permission denied`                                                                                                  |
| 시작 중 멈추기                                        | 통과      | 시작 1초 뒤 "시작하는 중..." 상태에서 멈추기 확인(6767 아직 안 열림) → 14초 뒤 프로세스 0개, 포트 닫힘                                                   |
| Linux 초기화가 server-id·호스트 키·등록 키 유지       | 통과      | server-id `srv_VmeAGa9FcPBw`, `ssh_host_ed25519_key.pub`·`authorized_keys` md5 같음, 이전 known_hosts로 그대로 접속. 호스트 페이지 폰 섹션·Online 그대로 |
| Linux 초기화가 나머지 /root를 지움                    | 통과      | `/root/qa-marker`, `/root/proj` 사라짐. 교환 폴더 파일 유지                                                                                              |
| B3: 초기화 확인 직후 피드백                           | 통과      | 확인 바로 다음 단계에서 이미 설치 화면 A. 다시 누를 틈 없음                                                                                              |
| Linux 초기화 시간                                     | —         | 확인 ~06:07:58 → 데몬 LISTEN 06:13:21, **약 5분 23초**. 이후 스플래시 → 홈까지 약 50초                                                                   |
| B2: 사이드바 콜아웃                                   | 해당 없음 | UI가 콜아웃을 지웠다(ui.md §5 편차 8). 멈춤은 Host 페이지 배지와 알림으로 표시. 확인함                                                                   |
| 로그가 최신 줄에서 열림                               | **실패**  | L1                                                                                                                                                       |
| 한국어 키 개수                                        | 통과      | "키 1개"                                                                                                                                                 |
| 알림 + Stop → 확인 대화상자                           | 통과      | "실행 중 / ssh 192.168.31.120:2222 · 깨어 있음", "정지" → "Linux를 멈출까요?". `r2/02`, `r2/03`                                                          |
| SSH 로그인, `-W` 터널, 비밀번호·6767 직접 거부        | 통과      | `-W` → 데몬 HTTP 403(Host 헤더 없음), 비밀번호 `Permission denied (publickey)`, PC → 100.79.37.5:6767 연결 안 됨                                         |
| 교환 폴더(권한 → 재시작 필요 → Linux 재시작 → 양방향) | 통과      | 재시작 후 `~/exchange` 바인드, 양방향 파일 확인                                                                                                          |
| 데몬 재시작                                           | 통과      | 워커 node가 06:05:52에 새로 뜸(supervisor 재시작, proot pid는 유지). 데몬 행 다시 "실행 중"                                                              |

### 버그

**L1. 로그 시트가 여전히 꼬리의 맨 위에서 열린다** — 심각도 낮음, 담당 **ui**(`packages/app/src/phone/logs-sheet.android.tsx`)

- 재현: `paseo-phone://settings/hosts/<id>/host?logs=daemon`으로 로그 시트를 열고 4초 뒤, 시트를 위로 끌어 펼친다.
- 기대: 마지막 줄(06:06:53, 워커 pid 31392)과 복사 버튼이 보인다.
- 실제: 경로 바로 아래 첫 줄이 06:05:14(pid 31151) 줄이다. 끝으로 스크롤되지 않았다. 버튼으로 연 경우는 quick-qa가 버튼에 닿지 못해 보지 못했다.
- 근거: `evidence/r2/05-logs-newest.png`.

### 메모

- 데몬 재시작은 이제 proot를 다시 띄우지 않고 워커만 바꾼다. 의도된 변경이면 문제없다.
- 교환 폴더 Linux 행은 이번 빌드에서 `/root/exchange`로 나온다(1회차 `~/exchange`).

### 정리 상태

- 테스트 키 `pp-qa2`는 앱 시트에서 지웠다(로그인 거부 확인). PC 쪽 키와 known_hosts 삭제, 교환 폴더 테스트 파일 삭제.
- 앱은 설치된 채 실행 중(Linux·데몬·sshd 동작, 2222·6767 LISTEN). 알림·저장소 권한 허용 상태.
- 하지 않은 것: 설치 실패 화면(비행기 모드)과 다시 시도, 에이전트 화면 열기, 로그 버튼으로 열었을 때의 스크롤 위치.

## 3회차 스팟 체크 (2026-10-30, `2618266e6` 서명 APK, L1 수정만)

팀장이 06:27에 같은 키로 덮어 설치했다(Linux 유지). 근거 사진은 `evidence/r3/`.

| 항목                                          | 결과              | 근거                                                                                                                                                                    |
| --------------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 버튼(로그 열기)으로 연 데몬 로그, 기본 높이   | **실패**          | 시트가 처음 뜨는 반 높이에서 경로 바로 아래 줄이 06:22:51(이전 데몬 pid 11755) 줄이다. 꼬리의 맨 위다. `r3/02-button-logs-half.png`                                     |
| 같은 시트를 손잡이로 끝까지 펼침              | 통과              | 마지막 줄 `ws_runtime_metrics`와 복사 버튼이 바로 보인다. 손잡이만 끌었고 본문은 스크롤하지 않았다. `r3/02-button-logs-expanded.png`                                    |
| `logs=daemon` 딥링크                          | 실패(버튼과 같음) | 첫 번째 열기: 반 높이에서 06:22:21 줄부터 보임. 같은 URL을 다시 보냈을 때는 시트가 다시 열리지 않았다(시트를 닫고 같은 Host 페이지에 있던 상태). 재현은 한 번만 해 봤다 |
| 앱 → 홈, 이 기기 호스트 Online, Linux 실행 중 | 통과              | `am force-stop` 후 실행 → 홈(Welcome 없음), Host 페이지 Online·Linux/데몬 "실행 중", 2222·6767 LISTEN, 알림 "실행 중". `r3/03-home.png`                                 |

### 버그

**L1 (남음). 로그 시트가 기본(반) 높이에서 최신 줄을 보여주지 않는다** — 심각도 낮음, 담당 **ui**(`packages/app/src/phone/logs-sheet.android.tsx`)

- 끝으로 스크롤하는 동작이 시트를 끝까지 펼쳐 레이아웃이 바뀔 때만 일어나는 것으로 보인다. 처음 뜨는 반 높이에서는 본문이 맨 위(오프셋 0)라서 사용자는 오래된 줄을 먼저 본다.
- 기대: 반 높이에서도 마지막 줄이 보이는 영역 아래쪽에 있다.

**D1 (관찰). 같은 Host 페이지에 있는 상태에서 `?logs=daemon` 딥링크를 두 번째로 보내면 시트가 열리지 않았다** — 심각도 낮음, 담당 **ui**. 한 번만 봤다. 알림을 두 번 누르는 경우와 같으므로 다음 회차에 다시 본다.
