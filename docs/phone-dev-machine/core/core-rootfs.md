# core-rootfs: 진행과 명세

담당: core-rootfs (rootfs 셸 스크립트, `app.config.js`의 `phone` 변형, `scripts/phone/**`). 기준은 [architecture.md](../architecture/architecture.md)와 [internal-contract.md](internal-contract.md)다. 이 문서는 다음 라운드(통합)에서 다시 읽는 작업 노트이기도 하다.

## 진행

| 항목                                              | 상태             | 커밋        |
| ------------------------------------------------- | ---------------- | ----------- |
| `app.config.js` `phone` 변형 (§6, §4.3)           | 완료             | `130ad0763` |
| `scripts/phone/build-apk.sh` (§9)                 | 완료             | `130ad0763` |
| `assets/phone-runtime/*` 스크립트 7개 + sshd 설정 | 완료             | `bbdc6eeba` |
| devphone01 실기 검증                              | 완료             | —           |
| 통합 APK 빌드                                     | 아래 "통합 빌드" | —           |

## 파일

모두 `packages/app/modules/paseo-phone-runtime/android/src/main/assets/phone-runtime/` 아래. SetupRunner가 rootfs `/opt/paseo-phone/`로 복사하고 `/bin/bash /opt/paseo-phone/<script>`로 실행한다. 스크립트는 자기 옆 파일(`sshd_config`, `agent-instructions.md`)을 `${BASH_SOURCE[0]%/*}`로 찾으므로 위치에 묶이지 않는다.

- `.gitattributes`(`eol=lf`): 이 레포는 Windows에서 `core.autocrlf=true`로 체크아웃된다. CRLF가 된 스크립트는 rootfs bash에서 바로 깨지므로 LF를 강제한다. aapt 기본 무시 패턴(`.*`)이라 APK에는 들어가지 않는다. `scripts/phone/.gitattributes`도 같은 이유.

공통 규칙: `exec </dev/null`로 stdin을 닫고, `set -euo pipefail`. 성공 0, 실패는 0이 아닌 값과 마지막 stderr 줄(apt `E:`, npm `ERR!`, bash `:?` 메시지)이 원인. 스크립트는 서비스를 띄우지 않고 `state.json`을 쓰지 않는다.

| 스크립트                | 입력 env                          | 하는 일 / 출력                                                                                                                                                                                                                                                                                                                                                                                       | 종료 코드                                       | 실측 (devphone01)                                                                                   |
| ----------------------- | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `configure.sh`          | `PASEO_PHONE_NODENAME`(선택)      | `/etc/resolv.conf`(8.8.8.8, 1.1.1.1; 심볼릭 링크면 지우고 파일로), `/etc/hosts`(+`127.0.1.1 <nodename>`), `apt.conf.d/99paseo-phone`(`APT::Sandbox::User "root"`), `dpkg.cfg.d/99paseo-phone`(`force-unsafe-io`), `/usr/sbin/policy-rc.d`(exit 101), `sshd_config.d/phone.conf`, `/root/.ssh`(0700). `ssh-keygen`이 있으면 `-A`. 모든 파일은 임시 파일 + `mv`로 통째 교체. 마지막 줄 `configure: ok` | 0 / 쓰기 실패 시 1                              | 1초. 재실행 1초, 결과 파일 md5 동일                                                                 |
| `packages.sh`           | —                                 | `apt-get update` → `apt-get -s`로 설치될 패키지 수를 세어 `PASEO_PHONE_TOTAL=<n>` 한 줄 → `apt-get install -y --no-install-recommends`(§2.5 목록, `--force-confdef/confold`) → `apt-get clean`. `Setting up …` 줄은 그대로 흐른다                                                                                                                                                                    | 0 / apt 실패 시 apt 종료 코드(100)              | 첫 실행 156초, `PASEO_PHONE_TOTAL=119`, `Setting up` 119줄(일치). 재실행 6초(`TOTAL=0`)             |
| `paseo.sh`              | `PASEO_DAEMON_VERSION`(필수)      | `/root/.paseo/config.json`이 없을 때만 §3.3 내용으로 작성 → 설치된 `@getpaseo/cli` 버전이 다르면 그 버전 설치, 에이전트 3개는 **없을 때만** 설치(한 번의 `npm install -g`) → 설치했으면 `npm cache clean --force` → `agent-instructions.sh`. 마지막 줄 `paseo: ok (<버전>)`                                                                                                                          | 0 / env 없음·node 없음 1 / npm 실패 시 npm 코드 | 첫 실행 154초(npm 309패키지 2분). 재실행 7초(npm 건너뜀)                                            |
| `agent-instructions.sh` | —                                 | `agent-instructions.md`의 관리 블록을 `/root/.claude/CLAUDE.md`, `/root/.codex/AGENTS.md`, `/root/.config/opencode/AGENTS.md`에 쓴다. 기존 블록은 지우고, 블록 밖 사용자 내용은 앞에 유지(빈 줄 하나로 구분), 블록은 파일 끝. 파일·디렉터리가 없으면 만든다                                                                                                                                          | 0 / 쓰기 실패 1                                 | 1초 미만. 재실행 시 바이트 동일, 사용자 줄 보존 확인                                                |
| `run-daemon.sh`         | (PATH, HOME은 ProotCommand가 줌)  | `cd /root; exec paseo daemon run` 포그라운드. config.json 덕에 `127.0.0.1:6767`                                                                                                                                                                                                                                                                                                                      | 데몬 종료 코드                                  | 시작 → `Server listening on http://127.0.0.1:6767` 25초. `model-download` 로그 0건, level 50/60 0건 |
| `run-sshd.sh`           | `PASEO_PHONE_SSH_PORT`(기본 2222) | `mkdir -p /run/sshd`, `ssh-keygen -A`(없는 키만), `exec /usr/sbin/sshd -D -e -p <port>`                                                                                                                                                                                                                                                                                                              | sshd 종료 코드                                  | 즉시 `Server listening on 0.0.0.0 port 2222`                                                        |

`sshd_config`는 §5.1 그대로이고 `configure.sh`가 `/etc/ssh/sshd_config.d/phone.conf`로 설치한다. `agent-instructions.md`는 §5.3 블록 원문이다.

### 설계와 다른 점 (구조 변경 아님, 보고용)

- **호스트 키 생성 시점.** §2.5는 `configure`에서 `ssh-keygen -A`를 하라고 하지만 그 시점에는 `openssh-server`가 아직 없다(`packages`가 설치). 실제로는 `openssh-server` postinst가 `packages` 단계에서 키를 만들고, `configure.sh`(재실행 시)와 `run-sshd.sh`가 빠진 키만 보충한다. 결과(설치 후 키 존재, 재설치 때만 바뀜)는 같다. architecture §2.5 문구만 고치면 된다.
- **`policy-rc.d` 추가.** init 시스템이 없는 rootfs에서 `openssh-server` postinst가 sshd를 띄우려 하는 것을 막는다(서비스 기동은 스크립트 책임이 아님).
- **에이전트는 없을 때만 설치.** §2.5 표의 `npm i -g … 세 에이전트`를 매번 돌리면 업데이트 모드(`UPDATE_DAEMON`)에서 에이전트가 최신으로 올라간다. §2.5 "에이전트 CLI 업그레이드는 앱이 하지 않는다"를 지키려고 없을 때만 설치한다. 데몬은 버전이 다를 때만.
- **`npm cache clean`.** npm 캐시가 0.93 GB였다. 폰에서 재사용하지 않으므로 설치 직후 지운다.

## 실기 검증 (2026-10-30, devphone01)

환경: Termux의 proot 5.1.107 + proot-distro 5.10.3, 새 컨테이너 `pp-test`(`debian:trixie` 이미지, 캐시에서 5초). Node 22.20.0은 SetupRunner `extract`를 흉내 내어 nodejs.org tarball을 `/usr/local`에 풀었다(4초). 각 스크립트는 `proot-distro login pp-test -- env -i HOME=/root PATH=… <계약 env> /bin/bash /opt/paseo-phone/<script>`로 실행했다. 에이전트 버전: paseo 0.14.0, claude 2.1.312, codex-cli 0.165.0, opencode 1.19.5 (모두 `--version` 정상).

용량(`du -sm rootfs`):

| 시점                                                           |     MB |
| -------------------------------------------------------------- | -----: |
| 빈 rootfs                                                      |    153 |
| + Node                                                         |    354 |
| + `packages`                                                   |    852 |
| + `paseo` (npm 캐시 포함)                                      |  3,292 |
| + `paseo` (`npm cache clean` 후, 추정 = 위 − `/root/.npm` 934) | ~2,360 |

주요 항목: `/usr/local/lib/node_modules` 967 MB(@getpaseo 565, @openai 383), `/usr/local/bin` 115, `/usr/lib` 257. architecture §2.5의 "설치 후 약 2 GB"보다 조금 크다. 사전 검사 4 GB는 그대로 충분하다. 처음 설치 시 npm 캐시가 지워지기 전 순간 최대치는 약 3.3 GB이므로 4 GB 기준을 낮추지 말 것.

SSH와 데몬 노출(PC → `adb forward tcp:22222 tcp:2222`):

| 확인                                          | 결과                                                       |
| --------------------------------------------- | ---------------------------------------------------------- |
| 비밀번호/키보드 인증만으로 접속               | `Permission denied (publickey)`                            |
| 테스트 키로 접속                              | 성공, 326 ms, `node paseo claude codex opencode` 모두 PATH |
| `ssh -W 127.0.0.1:6767` (데스크톱 Paseo 경로) | 데몬 HTTP 응답(403, Host 헤더 없는 요청에 대한 데몬 응답)  |
| `-R` 원격 포워딩                              | 거부(`AllowTcpForwarding local`)                           |
| PC에서 `100.79.37.5:6767` 직접                | 연결 안 됨(loopback 바인딩)                                |

### 함정: `run-as`에서 띄운 sshd는 접속 순간 죽는다

`adb shell run-as com.termux`로 띄운 proot 안 sshd는 클라이언트가 붙으면 `sshd-auth`(OpenSSH 10의 사전 인증 프로세스)가 시작되는 순간 proot가 `path.c:598 compare_paths2 … assertion "length1 > 0"`로 abort한다. 스파이크 컨테이너도 같았다. 같은 명령을 Termux 앱 자신의 프로세스(quick-qa로 Termux 화면에 입력)에서 띄우면 정상 접속된다. `run-as`는 SELinux 도메인이 `runas_app`라서 생기는 차이로 보고, 앱 프로세스(`untrusted_app`)에서 도는 실제 제품과는 무관하다. 다음에 sshd를 검증할 때는 `run-as`가 아니라 앱 컨텍스트에서 띄운다. 데몬은 `run-as`에서도 정상이었다.

또 `run-as` 프로세스는 Termux가 포그라운드가 아니면 네트워크가 막힌다(DNS 실패). `termux-wake-lock`으로 Termux 서비스를 올려 두면 통한다. `run-as`로 띄운 프로세스는 `am force-stop`으로 죽지 않으므로 pid로 직접 종료해야 한다.

### 정리

데몬·sshd 종료, `pp-test` 컨테이너 삭제(`proot-distro remove`), 테스트 키(`pp-rootfs-test`) 삭제(PC 사본 포함, Termux·스파이크 rootfs에는 넣지 않았음), `adb forward` 제거, `/data/local/tmp`의 내 파일 삭제, Termux wake lock 해제. PC에서 2222·6767 모두 닫힘 확인. 스파이크 `debian` 컨테이너는 손대지 않았다.

### 실기에서 확인하지 않은 것

- 우리 proot(Kai/termux 커밋 빌드)와 ProotCommand 인자로 실행 — core-linux 몫, 통합 QA에서 확인.
- proot-distro tarball(D3) 대신 Docker `debian:trixie` 이미지로 검증했다. 둘 다 trixie 기본 이미지라 스크립트 동작 차이는 없을 것으로 본다.
- `PASEO_PHONE_NODENAME`이 hostname에 반영되는지(proot-distro는 `localhost`를 씀) — ProotCommand `--kernel-release` 몫.
- 데몬 업데이트 경로(`PASEO_DAEMON_VERSION` 변경)의 실제 npm 재설치. 분기만 있고 실행하지 않았다.

## app.config.js

§4.3 그대로 `phone` 변형 하나와 `isPhoneBuild` 분기 3곳(`scheme`, `expo-build-properties.android.useLegacyPackaging`, `extra.phoneRuntime`). `expo config`로 phone/기본 두 변형의 결과를 확인했다(기본 변형 출력은 변경 전과 같음).

### 트레이드오프: 비-phone 변형도 이 모듈을 자동 링크한다

Expo 자동 링크는 `packages/app/modules/`를 무조건 훑고, 옵션은 `package.json`의 `expo.autolinking`에서만 읽는다(`expo-modules-autolinking` 57, `commands/autolinkingOptions.js`). `app.config.js`의 `autolinking` 키는 Gradle 자동 링크에 영향이 없다. 그래서 비-phone 변형에서 빼려면 F-Droid 빌드처럼 config plugin으로 `settings.gradle`의 `expoAutolinking.projectRoot`를 오버레이 `package.json`으로 바꾸는 방법뿐인데, 이는 (1) `package.json` 대신 업스트림 빌드 경로 전체를 바꾸는 큰 수정이고 (2) F-Droid 플러그인의 같은 오버레이와 충돌하며 (3) §4.3의 3개 파일 최소 수정 원칙을 넘는다. 하지 않는다.

영향: 이 포크에서 만든 비-phone APK에 모듈 매니페스트 권한(저장소, FGS)과 proot `.so`가 들어간다. 이 포크는 `phone` 변형만 배포하므로 수용한다. 런타임 동작은 `isPhoneRuntime`(`extra.phoneRuntime`)으로 막혀 있다. 비-phone 배포가 필요해지면 그때 위 플러그인 방식을 architecture에 먼저 올린다.

## scripts/phone/build-apk.sh

`scripts/build-apk.mjs`와 같은 순서를 bash로: `APP_VARIANT=phone EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767 PASEO_WATCHDOG_BUILD=1`, 진단 리비전/시각 env, `npm run build:app-deps` → `expo prebuild --platform android --no-install --clean` → generated assets 삭제 → `gradlew assembleRelease … -PreactNativeArchitectures=arm64-v8a` → `artifacts/paseo-phone-arm64.apk`. 시작할 때 `npm view @getpaseo/cli@<packages/server 버전>`이 실패하면 경고만 낸다(§9). `--clean` prebuild는 `packages/app/android/`를 다시 만든다.

## 통합 빌드

### 1회차 (2026-10-30, `bbdc6eeba` + core-linux `a786c82c7`, core-runtime `63ac51af7`/`0adc69190`)

`bash scripts/phone/build-apk.sh` 실패. `build:app-deps`, `expo prebuild --clean`은 통과했고 Gradle에서 멈췄다(4분 5초, 515 tasks).

```
> Task :paseo-phone-runtime:compileReleaseKotlin FAILED
e: .../runtime/ssh/AuthorizedKeys.kt:73:41 Unsupported escape sequence.   Regex("\s+")
e: .../runtime/ssh/HostKeys.kt:12:58 Unsupported escape sequence.         Regex("\s+")
```

둘 다 core-runtime 파일이다. Kotlin 문자열에서 `"\s"`는 허용되지 않는다(`"\\s+"` 또는 `"""\s+"""`). 이 오류는 어휘 분석 단계라서 고친 뒤 타입 검사에서 다른 오류가 더 나올 수 있다. core-linux 파일에서는 오류가 보고되지 않았다.

빌드 스크립트 자체 버그 하나를 고쳤다: Windows `node`는 Git Bash 경로(`/c/...`)를 `require`하지 못해 버전 확인에서 바로 죽었다. 레포 루트로 `cd`한 뒤 상대 경로로 읽는다.

참고: `--clean` prebuild는 `packages/app/android/`를 phone 변형으로 다시 만든다. 이후 다른 변형을 빌드할 때도 `--clean`이 필요하다.
