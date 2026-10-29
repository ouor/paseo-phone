# 1차 리뷰 수정 계획

> 작성: 아키텍트 · 2026-10-30 · 대상 리뷰 `19eac9e8e`
> 기준 문서: [architecture.md](../architecture/architecture.md), [internal-contract.md](../core/internal-contract.md). 코드는 문서를 따른다. 문서와 다르게 해야 하면 먼저 아키텍트에게.

리뷰 번호 표기: `AN` = [android-native.md](android-native.md), `RN` = [react-native.md](react-native.md), `SEC` = [security.md](security.md), `AR` = [architecture.md](architecture.md)(리뷰).

## 병렬 작업 규칙

- 네 담당은 **자기 소유 파일만** 고친다(소유 표: internal-contract "소유"). 아래 표는 담당별로 나눠져 있고 서로 파일이 겹치지 않는다.
- 담당 사이 의존은 internal-contract의 시그니처로만 맞물린다. 시그니처가 이미 문서에 고정돼 있으므로 기다리지 말고 동시에 시작한다. core-runtime은 core-linux가 머지하기 전까지 새 API(`PhoneConstants`, `Identity`, `ProotProcess.stop`, `SetupRunner.scriptSpec/runScript`)를 문서대로 호출하는 코드를 쓰고, typecheck·빌드는 core-linux 머지 후에 맞춘다.
- 커밋 순서가 필요하면: core-linux → core-rootfs → core-runtime → ui. 각자 브랜치에서 작업하고 이 순서로 합친다.
- Gradle 빌드와 기기 확인은 넷 다 머지된 뒤 한 번(QA).

## core-linux

| #   | 리뷰               | 바꿀 것                                                                                                                                                                                                    | 문서       |
| --- | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| L1  | AR4                | `linux/PhoneConstants.kt` 신설. 포트·게스트 경로·env 이름·applicationId·scheme. 기존 리터럴을 모두 이것으로                                                                                                | §7.5, 계약 |
| L2  | AN2, AN5, AR1, AR2 | `RootfsState`와 `LinuxPaths.rootfsState()`(디스크만). `rootfsStaging`, `identity` 경로 추가. `isRootfsPresent()` 제거. 로그 이름 `sshd` → `ssh`                                                            | §2.3       |
| L3  | AN2, AN5           | `extract`: `rootfs.staging/`에 풀고 rename. `rootfs/`가 있으면 `Failed`. 성공 후 캐시 삭제 → `Identity.restoreIfPresent`. 러너 안의 `rootfs/` 삭제 코드 전부 제거. 모르는 `fromStep`은 `Failed("unknown")` | §2.3, §2.5 |
| L4  | RN1, AR8           | `linux/Identity.kt`: `save`/`restoreIfPresent`(server-id, `ssh_host_*`, `authorized_keys`, 모드 보존)                                                                                                      | §2.3 D16   |
| L5  | AN6                | `UPDATE_NODE` 경로: `download` → Node만 `rootfs/usr/local`에 다시 풀기 → `paseo`                                                                                                                           | §2.5       |
| L6  | AN3, AN7, AN11     | `ProotProcess.stop(graceMs)`/`killTree()`: `/proc` 자손 스냅샷 → 자손 SIGTERM → 대기 → proot SIGTERM → SIGKILL. `destroy`/`destroyForcibly` 제거. 출력 펌프 종료 시 로그 핸들 닫기                         | §8.3       |
| L7  | AN3                | `ProotExec.run`: 타임아웃과 취소 모두 `stop(5_000)` 순서. `CancellationException`은 다시 던짐                                                                                                              | §8.2, §8.3 |
| L8  | AR4                | `SetupRunner.installScripts/scriptEnv/scriptSpec/runScript` 공개. 내부 단계도 이것만 사용. env에 `PASEO_PHONE_DAEMON_LISTEN` 추가. `PASEO_PHONE_NODENAME`은 ProotProcess/ProotExec가 넣기                  | §7.5, 계약 |
| L9  | AR5                | no-space 사전 검사가 `Failed(freeBytes, neededBytes)`를 채움                                                                                                                                               | §2.5       |
| L10 | SEC4               | Node 기대 sha256 = `BuildConfig.NODE_SHA256`. `SHASUMS256.txt` 다운로드 제거                                                                                                                               | §3.1       |
| L11 | AN13, SEC9         | `build-proot.sh`: `curl -f`, talloc sha256 검증, NDK r29 확인. `PINS.md`에 talloc sha256                                                                                                                   | §2.2       |
| L12 | AR9                | JVM 테스트: `rootfsState()` 세 경우, staging rename과 "rootfs 있으면 extract 거부", Identity 왕복, 모르는 `fromStep`                                                                                       | §9         |

## core-runtime

| #   | 리뷰               | 바꿀 것                                                                                                                                                                                                                | 문서       |
| --- | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| R1  | AR10               | `RuntimeController.kt` 분리: `StateSnapshot.kt`(순수 함수, 스냅샷 Map), `ProcessSupervisor.kt`(Managed, 백오프, 재시작 한도, 준비 감시). 컨트롤러는 명령 직렬화와 흐름만(~400줄)                                       | §2.1       |
| R2  | AR2, AR11, AN2     | `RuntimeStore`: `{version: 1, desired, sshEnabled, keepAwake, failedStep}`만. `installed`/버전 필드 제거. 없거나 깨지면 기본값, 깨진 파일은 `.corrupt`로                                                               | §2.3       |
| R3  | AN5, AN9, AR1, RN4 | 초기화: `rootfsState()`로 분기, `checking`에 머무는 경로 없음. `INSTALLED && desired == running`이면 첫 발행부터 `linux: starting`. `start` 실패는 `setup-failed(step: "start")`, Retry는 시작만                       | §2.3, §8.2 |
| R4  | AN1                | `ensureService()`가 정지 중 인스턴스의 `onDestroy`를 기다림. 현재 인스턴스 토큰, `onServiceDestroyed`는 현재일 때만. `restartLinux`/`reinstall`/업데이트는 서비스 유지                                                 | §8.2       |
| R5  | AN4, AN8, RN6      | `stop()`은 `busy` 예외: 시작·설치·백오프 Job 취소 후 정지. 설치 취소 → `setup-failed(cancelled)`, 미설치면 `desired` 유지. 취소에 안전한 기동(NonCancellable 후 정리). 준비 대기는 `busy` 밖 감시 작업                 | §7.2, §8.2 |
| R6  | AN7                | `paseo.pid` 읽기·신호 제거. 시작 전 6767/2222 사용 중이면 `start-failed "port in use"`                                                                                                                                 | §3.4, §8.3 |
| R7  | AN6                | `canStartPrevious`: `UPDATE_DAEMON` 항상, `UPDATE_NODE`는 `download` 실패일 때만                                                                                                                                       | §2.5       |
| R8  | RN1, AR8           | `reinstall()`: 정지(서비스 유지) → `Identity.save` → `rootfs/` 삭제 → `failedStep` 초기화, `desired = running` → 설치                                                                                                  | §2.3, §7.2 |
| R9  | —                  | 설치·업데이트 중에는 keepAwake와 무관하게 wake lock                                                                                                                                                                    | §8.1       |
| R10 | AR3                | `src/state-schema.ts`(zod) 신설, `types.ts`는 `z.infer` + 상수. 포트·주소·경로 타입은 리터럴 → `number`/`string`. `space`, `cancelled` 추가. `PHONE_NOTIFICATION_QUERY` export. 개발 빌드에서만 `getState`/이벤트 검증 | §7.1, §7.4 |
| R11 | AR3                | `StateSnapshotTest`(JVM)가 대표 상태 fixture를 `src/__fixtures__/state-*.json`과 비교(`-PupdateFixtures`로 갱신). `src/state-schema.test.ts`가 fixture 파싱                                                            | §7.1, §9   |
| R12 | SEC4               | `android/node-sha256.txt` 신설(현재 `.tool-versions` Node 줄). `build.gradle`이 읽어 `BuildConfig.NODE_SHA256`, 줄이 없으면 빌드 실패                                                                                  | §3.1       |
| R13 | AN12, SEC7, SEC8   | `AuthorizedKeys`: Mutex 직렬화, 첫 토큰이 키 타입인 줄만(옵션 접두어 → `invalid-key`)                                                                                                                                  | §5.1       |
| R14 | SEC6               | `removeAuthorizedKey`: sshd가 돌고 있으면 sshd 재시작                                                                                                                                                                  | §5.1, §7.2 |
| R15 | AR4, AR6           | 컨트롤러·서비스의 리터럴을 `PhoneConstants`로. 스크립트 실행은 `SetupRunner.runScript/scriptSpec`만. `RuntimeNotification` 경로·쿼리 상수 옆에 `host-routes.ts`·`types.ts` 위치 주석                                   | §7.4, §7.5 |
| R16 | RN9                | 알림: serverId 없으면 Stop 액션 없음, 무응답 시 Restart, 로그 `…/host?logs=daemon`. `agent-instructions.sh` 출력은 `setup.log`                                                                                         | §5.3, §7.4 |

## core-rootfs

| #   | 리뷰        | 바꿀 것                                                                                                                                                         | 문서       |
| --- | ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| F1  | SEC3        | `assets/phone-runtime/versions.env` 신설(빌드 시점 확인 버전). `paseo.sh`가 source해서 에이전트를 없을 때만 그 버전으로                                         | §3.1       |
| F2  | AR4         | `paseo.sh`의 listen을 `$PASEO_PHONE_DAEMON_LISTEN`으로. 스크립트의 포트·주소 리터럴 제거                                                                        | §3.3, §7.5 |
| F3  | SEC5, SEC11 | `sshd_config`를 §5.1 블록으로(`AddressFamily inet`, 포워딩 끔, `LoginGraceTime`, `MaxStartups`, `Port` 줄 삭제). `run-sshd.sh`가 시작마다 `phone.conf`를 덮어씀 | §5.1       |
| F4  | SEC2        | `app.config.js` phone 변형에 `android.allowBackup: false`                                                                                                       | §6         |
| F5  | AR14        | `app.config.js` 포크 줄마다 `phone-fork:` 주석(6줄). `scripts/phone/build-apk.sh`의 `EXPO_PUBLIC_LOCAL_DAEMON` 줄에 `phone-fork:` 주석                          | §4.3       |
| F6  | AR9         | `build-apk.sh`: 같은 Gradle 호출에 `:paseo-phone-runtime:testReleaseUnitTest`                                                                                   | §9         |

## ui

| #   | 리뷰      | 바꿀 것                                                                                                                                      | 문서       |
| --- | --------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| U1  | RN3       | 게이트: `ready`, `linux: error`, 이 기기 호스트가 레지스트리에 없음 → 설치 실패 레이아웃으로 오류·로그·`restartLinux()`·"다른 호스트로 계속" | §7.3       |
| U2  | AN9       | `checking` 5초 초과 시 Start·로그 버튼                                                                                                       | §7.3       |
| U3  | RN5       | `LinuxAlert`는 `linux` 상태만 보고, `linux`의 `killed-by-system`에도 phantom 안내                                                            | §7.1       |
| U4  | RN6       | `confirmStop` 의도를 `starting`에서도 처리                                                                                                   | §7.3       |
| U5  | RN7       | 설치 화면 로그 문구 `linuxSetup.logs.*`, 로드 오류 표시                                                                                      | §7.3       |
| U6  | AR5       | `parseNoSpaceMessage` 제거, `state.space` 숫자로 문구                                                                                        | §7.1       |
| U7  | AR4, AR6  | 상수 직접 사용 제거: `ssh.port`, `daemon.listen`, `exchange.linuxPath` 사용. 딥링크 쿼리는 `PHONE_NOTIFICATION_QUERY`                        | §7.4, §7.5 |
| U8  | SEC1      | 저장소 권한 요청 문구: "Linux 안의 모든 프로그램이 폰 공유 저장소 전체를 읽고 쓰고 지울 수 있음"(en·ko)                                      | §5.2       |
| U9  | SEC6, RN1 | 키 삭제 확인 문구에 "열린 SSH 세션이 끊김", Reinstall 확인 문구에 "보존: 호스트 등록·SSH 호스트 키·등록 키 / 삭제: 그 밖의 /root"            | §2.3, §5.1 |
| U10 | AR14      | `_layout.tsx`·`host-page.tsx`의 포크 줄에 `phone-fork:` 주석(각 2줄)                                                                         | §4.3       |
| U11 | —         | 로그 시트 공용 컴포넌트 `logs-sheet.android.tsx`로 합치기. 낮은 우선순위, 시간이 남으면                                                      | §7.3       |

## 문서 (아키텍트, 이 커밋에서 끝남)

AR15 표, RN9의 문서 드리프트: D3 v4.29.0, §2.1 파일 목록, §2.4 조건부 가짜 `/proc`와 커널 문자열, §2.5 호스트 키·`policy-rc.d`·없을 때만 설치·캐시 정리·`start`는 컨트롤러·실측 용량, §3.2 `run-daemon.sh`, §4.2 `localhost:6767`, §6 자동 링크 수용, §7.1 `unavailable`·`ServiceErrorCode`, §7.2 `restartLinux`·접수 시 resolve·`busy` 범위, §7.3 `format.ts`, §7.4 경로·`logs=daemon`, §0의 §12 참조. internal-contract 갱신.

## 받지 않는 것

| 리뷰       | 이유                                                                                                        |
| ---------- | ----------------------------------------------------------------------------------------------------------- |
| AN10, RN8  | 비-phone 변형 자동 링크: 포크는 phone 변형만 빌드하고 네이티브·JS 가드가 있다. 업스트림 파일을 더 고쳐야 함 |
| SEC1(축소) | 모든 파일 권한 범위 축소: 소유자 결정으로 1차 수용. 문구만 고침(U8)                                         |
| SEC10      | 에이전트가 SSH 접근을 늘림: 게스트 root 모델의 본질. 수용, 문서화(§5.1)                                     |
| SEC12      | 업스트림 `#offer=`/`#connect=` 가져오기: 업스트림 동작, 로컬 데몬 노출 없음                                 |
| SEC3 일부  | `--ignore-scripts`: Claude Code 설치 스크립트가 필요. 전이 의존성 고정도 1차 수용(§10.1)                    |
| SEC5 일부  | `PermitOpen 127.0.0.1:6767`: VS Code Remote-SSH가 동적 포트를 쓴다                                          |
| AR9 일부   | CI: 1차 범위 밖. 테스트는 APK 빌드에서 돈다(F6)                                                             |
| AR11 일부  | `rootfsSha256` 기록: 핀이 바뀌어도 기존 설치에 할 일이 없다                                                 |
| AR13       | `format.ts` 분리: 현 구조 유지(리뷰도 유지 권고)                                                            |
