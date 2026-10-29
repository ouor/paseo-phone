# UI: 진행 기록, 화면 목록, 자기 진단

> 담당: UI 개발 · 기준: [design.md](../design/design.md), [architecture.md](../architecture/architecture.md) §4.3·§7.3·§7.4, [core-runtime.md](../core/core-runtime.md) §2
> 다음 라운드(QA 수정, 사용자 리뷰, 2차 사이클)에도 이어 쓴다.

## 1. 진행 상황 (1라운드)

| 파일 (`packages/app/src/phone/` 기준)                                    | 내용                                                                                                                                |
| ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| `runtime.ts` / `runtime.android.ts`                                      | `isPhoneRuntime`, `usePhoneRuntimeState()`(안정 스냅샷 캐시 + `useSyncExternalStore`), `restartLinux()`. 기본 파일은 `false`/`null` |
| `runtime-gate.tsx` / `runtime-gate.android.tsx`                          | `PhoneRuntimeGate`. 기본은 children 그대로                                                                                          |
| `linux-setup-screen.android.tsx`                                         | 화면 A: 설치·업데이트·실패                                                                                                          |
| `host-sections.tsx` / `host-sections.android.tsx`                        | `PhoneHostSections` + Reset Linux 섹션. 기본은 `null`                                                                               |
| `linux-section.android.tsx`                                              | 섹션 B (로그 시트 포함)                                                                                                             |
| `remote-access-section.android.tsx`, `authorized-keys-sheet.android.tsx` | 섹션 C + 키 시트                                                                                                                    |
| `exchange-section.android.tsx`                                           | 섹션 D                                                                                                                              |
| `linux-stopped-callout-source.android.tsx`                               | 사이드바 콜아웃 G                                                                                                                   |
| `i18n/en.json`, `i18n/ko.json`, `i18n/register.ts`                       | 폰 전용 문구. 기존 i18next 인스턴스에 deep merge                                                                                    |
| `format.ts` (+ `format.test.ts`), `i18n/register.test.ts`                | 순수 문자열 함수와 테스트 (§5 편차 1)                                                                                               |

본체 수정은 §4.3 그대로 두 파일: `src/app/_layout.tsx`(import 1 + `<AppShell />`을 `<PhoneRuntimeGate>`로 감쌈), `src/screens/settings/host-page.tsx`(import 1 + `HostAppearanceSection` 다음 한 줄).

검증: `npm run typecheck` 통과, `npm run lint -- <파일>` 0 오류, `npx vitest run src/phone` 7개 통과. 기기·Gradle은 지시대로 돌리지 않았다.

## 2. 화면 목록 (무엇이 어디 있고 어떻게 가는가)

| 기능                                                                 | 위치                                                     | 가는 길                                                    |
| -------------------------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------------------- |
| 설치 진행 (6단계, 다운로드 MB·진행 막대)                             | 화면 A                                                   | 첫 실행 시 자동. 앱 전체를 덮는다                          |
| 업데이트 진행                                                        | 화면 A (update 모드, `paseo`부터; Node 변경 시 처음부터) | 데몬 버전이 바뀐 뒤 앱 시작 또는 Start                     |
| 실패: 오류 원문, 설치 로그, 로그 복사, 다시 시도, 이전 버전으로 시작 | 화면 A 실패 상태                                         | 자동. 오프라인이면 경고, 공간 부족이면 여유/필요 용량 문구 |
| Linux 시작·멈추기(확인 대화상자, 실행 중 에이전트 수 포함)           | Settings › Host(이 기기) › Host › **Linux** 첫 행        | 사이드바 Hosts → 이 기기 톱니 → Host                       |
| 데몬 상태·버전·재시작                                                | Linux 두 번째 행                                         | 같은 곳                                                    |
| SSH 서버 켜기/끄기                                                   | Linux 세 번째 행 스위치                                  | 같은 곳                                                    |
| 화면 꺼짐 유지                                                       | Linux 네 번째 행 스위치                                  | 같은 곳                                                    |
| 로그 (데몬/SSH/설치, 복사)                                           | Linux 다섯 번째 행 → 시트                                | 같은 곳. 알림 "Tap to open logs"도 여기                    |
| 경고: 비정상 종료 / phantom killer / 배터리 최적화 / 알림 꺼짐       | Linux 카드 아래 Alert 하나 (가장 급한 것)                | 해당 상태일 때만                                           |
| 접속 주소 (Wi-Fi/Tailscale 전환)                                     | **Remote access** 첫 행                                  | Host 페이지, Linux 아래                                    |
| `ssh` 명령, Paseo 데스크톱 URI, SSH config 복사                      | Remote access 2~4행 (config는 펼침 행)                   | 같은 곳                                                    |
| 호스트 키 지문                                                       | Remote access 5행                                        | 같은 곳                                                    |
| 허용된 키 목록·추가·삭제                                             | Remote access 마지막 행 → 시트                           | 키가 0개면 카드 위 Alert의 "Add key"로도 열린다            |
| 교환 폴더 열기 / Linux 경로 복사                                     | **Exchange folder**                                      | Host 페이지, Remote access 아래                            |
| 저장소 권한 요청 / 권한 후 Linux 재시작                              | Exchange folder 카드 위 Alert                            | 해당 상태일 때만                                           |
| Linux 다시 설치                                                      | **Reset Linux** 섹션                                     | Exchange folder 아래. 확인 후 화면 A로                     |
| Linux 멈춤 알림 (Start / Details)                                    | 사이드바 콜아웃                                          | 이 세션에서 Linux가 멈추거나 죽으면 자동. 실행되면 사라짐  |
| 알림 Stop → 확인 대화상자                                            | Linux 섹션 (`?confirmStop=1`)                            | 알림. **코어 요청 1 필요**                                 |
| 알림 데몬 응답 없음 → 데몬 로그                                      | 로그 시트 (`?logs=daemon`)                               | 알림. **코어 요청 1 필요**                                 |

모든 섹션은 `serverId === state.daemon.serverId`인 호스트의 Host 페이지에서만 보인다. iOS·웹·Electron·일반 Android 빌드에는 아무것도 바뀌지 않는다(기본 파일은 no-op, `.android` 파일도 `isPhoneRuntime`으로 먼저 거른다).

## 3. 동작 메모 (코드에서 바로 안 보이는 것)

- **게이트.** `checking`은 기존 스플래시, `setup`/`setup-failed`는 화면 A, AppShell은 마운트하지 않는다. `ready`가 되면 Linux가 `starting|running`인 동안 이 기기 호스트가 연결될 때까지(최대 60초) 스플래시를 한 번 더 들고 있다가 놓는다(`useLatchedBoolean`). 업스트림의 5초 포기 타이머가 Welcome으로 보내는 것을 막는다. 설치 직후(`setup` → `ready`)에도 같은 대기가 걸린다.
- **i18n 등록 순서.** i18next 백엔드는 언어를 늦게 읽고 최상위 키를 얕게 덮어쓴다. 먼저 번들을 넣으면 백엔드가 그 언어를 건너뛰기도 한다. 그래서 store에 이미 있는 언어에만 deep merge하고, `loaded` 이벤트마다 다시 넣는다. `register.test.ts`가 ko 전환 후 본체 키와 폰 키가 둘 다 살아 있는지 확인한다.
- **알림 쿼리.** Linux 섹션이 `useLocalSearchParams`로 `confirmStop`/`logs`를 읽고 처리한 뒤 `router.setParams`로 지운다. 같은 알림을 다시 눌러도 다시 동작한다.
- **실행 중 에이전트 수.** 확인 대화상자를 띄우는 순간 `useSessionStore.getState()`에서 센다(구독 없음).
- **개인 키.** 붙여넣는 순간 `-----BEGIN`/`PRIVATE KEY`를 보면 입력을 비우고 네이티브로 보내지 않는다. 코어가 같은 검사를 한 번 더 한다.
- **Linux 재시작(교환 폴더 `restart-required`).** 코어에 재시작 API가 없어서 UI가 `stop()` → 상태가 `stopped`가 될 때까지 대기 → `start()`로 묶는다(`restartLinux`, `shortcut:` 주석).

## 4. 자기 진단

세 질문으로 화면을 다시 보고 고친 것.

**1) 사용자 친화적인가**

- 발견: 키 인증만 되는 sshd라 키가 0개면 Remote access의 명령·URI가 전부 소용없는데, 키 행은 카드 맨 아래 요약("No keys")으로만 알렸다. → 키가 0개이고 주소·SSH가 살아 있으면 카드 위에 info Alert + "Add key" 버튼을 둔다. (고침)
- 발견: 데스크톱 첫 접속은 BatchMode라 호스트 키를 먼저 받아야 한다(architecture §5.1). 디자인에 자리가 없었다. → Remote access에 "Host key" 행(지문 + "처음 접속할 때 확인" 안내). (고침)
- 발견: 알림 권한이 없으면 FGS 알림(진행률, Stop)이 안 보이는데 어디서도 요청하지 않았다. → Linux Alert 순위 마지막에 "Allow notifications". (고침)
- 발견: 업데이트 실패 시 `canStartPrevious`를 쓸 버튼이 없었다. → 화면 A에 "Start previous version". (고침)
- 남은 것: 화면 A 성공 화면은 없다(디자인대로). 완료된 다운로드 행에 총 용량을 남기지 않는다(상태에 단계별 total이 없음).

**2) 모든 기능이 한눈에 보이는가**

- 코어 index.ts의 함수 18개 모두 화면에서 닿는다(§2 표). 숨은 것은 SSH config(펼침 한 번)와 로그(시트 한 번)뿐이다.
- 상태에 따라서만 나오는 것: 배터리·알림·저장소 권한·phantom 경고. 한 영역에 Alert는 하나(docs/design.md §11), 가장 급한 것부터.
- 위험: Host 페이지가 길다(배지, Appearance, Linux, Remote access, Exchange folder, Reset Linux, 업스트림 Update·Danger zone). 실제 기기 스크롤 길이는 QA가 본다. 길면 2차에 Remote access를 별도 호스트 섹션으로 빼는 안(본체 수정 +1)을 검토.

**3) Paseo 설정처럼 현대적이고 깔끔한가**

- 새 컴포넌트를 만들지 않았다. `SettingsSection(info)`, `SettingsCard/Row/CollapsibleRow/DetailRow`, `StatusBadge`(+점), `Switch`, `Button outline`(행), `Alert`, `AdaptiveModalSheet`, `SegmentedControl`, `ScrollableCodeSurface`, `UsageMeter`, `confirmDialog`, `toast.copied()`만 쓴다. `useUnistyles` 없음, 테마 색은 `StyleSheet.create`와 `withUnistyles` 아이콘으로.
- 화면 A는 스플래시 에러 레이아웃의 스타일 값을 그대로 복사했다(그 스타일이 파일 비공개라서 본체를 고치지 않으려고).
- 진행 막대 자리는 모든 단계 행에 4px로 예약해서 진행률이 생겨도 카드가 움직이지 않는다.
- 위험: Linux·데몬 행 트레일링에 배지 + 버튼 두 개가 붙는다. 좁은 폰에서 라벨이 줄바꿈될 수 있다. QA 스크린샷으로 확인.

## 5. 디자인·계약과 다른 점

1. `src/phone/format.ts`(+ 테스트)를 §7.3 목록 밖에 추가했다. SSH 명령·config, 주소 선택, MB 표기, no-space 메시지 파싱, 알림 쿼리 해석을 React 없이 테스트하려고. `src/phone/**` 안이고 의존은 index.ts 타입뿐이다.
2. 포트는 2222(architecture Q7). 디자인 와이어프레임의 8022가 아니다.
3. 데몬 행에 Restart 버튼(코어 `restartDaemon()`)을 뒀다. 업스트림 Danger zone의 "Restart daemon"은 데몬 프로토콜로 재시작하므로 데몬이 응답 없을 때는 못 쓴다.
4. Reinstall은 업스트림 Danger zone이 아니라 Exchange folder 아래 별도 섹션 "Reset Linux"(architecture §7.3).
5. Linux `starting` 중 Stop 버튼을 숨기지 않고 비활성으로 둔다(자리 유지가 같은 효과).
6. 복사 확인은 업스트림 `toast.copied()`. `remoteAccess.copied`, `linuxSetup.logsCopied` 키는 만들지 않았다. "This device"(`settings.host.thisDevice`)도 architecture §4.2대로 쓰지 않는다.
7. 추가 키: `linuxSetup.actions.startPrevious`, `settings.host.linux.{actions.restart, daemon.hint, phantom.*, notifications.*, logs.{modalTitle,empty,loadFailed,kinds.*}, restartFailed, settingFailed, reinstall.{sectionTitle,failed}}`, `settings.host.remoteAccess.{hostKey.*, noKeys.*, keys.{loadFailed,removeFailed}}`, `settings.host.exchange.{restartRequired.*, openFailed}`. `stopConfirm.message`는 복수형(`_one`/`_other`).

## 6. 코어에 요청 (팀장 경유)

1. **알림 딥링크 경로 (필수).** `paseo-phone://settings/hosts/<serverId>`는 업스트림 `settings/hosts/[serverId]/index.tsx`가 `/connections`로 `Redirect`하면서 쿼리를 버린다. Linux 섹션은 `/settings/hosts/<serverId>/host`에 있다. 탭·Stop·로그 URL을 다음으로 바꿔 달라:
   - 탭: `paseo-phone://settings/hosts/<serverId>/host`
   - Stop: `paseo-phone://settings/hosts/<serverId>/host?confirmStop=1`
   - 데몬 응답 없음: `paseo-phone://settings/hosts/<serverId>/host?logs=daemon`
     `serverId`를 모를 때의 `paseo-phone://?confirmStop=1`은 UI가 처리하지 않는다(갈 Host 페이지가 없음). 그 경우 Stop 액션을 빼거나 그냥 앱만 열어 달라.
2. (선택) `restartLinux()` API. 지금은 UI가 stop → `stopped` 대기 → start로 묶는다. 코어에 있으면 `runtime.android.ts`의 `restartLinux`를 지운다.
3. (유지 요청) `no-space` 메시지 형식 `free 2.1 GB, need 4.0 GB`를 바꾸면 알려 달라. UI가 파싱한다(`parseNoSpaceMessage`, 실패하면 일반 실패 문구).

## 7. QA에 넘길 확인 항목

- 첫 실행: 스플래시 → 화면 A 진행(다운로드 MB·막대) → 끝나면 Welcome이 아니라 이 기기 호스트로 들어가는지.
- 실패 재현(비행기 모드): 오프라인 경고, 로그 박스, 로그 복사, Retry.
- Host 페이지 스크롤 길이, Linux/데몬 행 트레일링 줄바꿈(좁은 화면), 다크/라이트.
- 알림 Stop → 확인 대화상자, "Tap to open logs" → 데몬 로그 시트 (코어 요청 1 반영 후).
- 키 붙여넣기: 정상, 중복, 잘못된 키, 개인 키(입력이 즉시 비워지는지).
- 저장소 권한 허용 → `restart-required` Alert → Restart Linux → `mounted`.
- Linux Stop 후 사이드바 콜아웃, Start 시 "Starting..." 비활성, 실행되면 사라짐.
- 한국어 기기 언어에서 폰 문구와 본체 문구가 둘 다 한국어인지.
