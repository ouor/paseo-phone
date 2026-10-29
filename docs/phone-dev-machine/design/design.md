# 폰 개발 머신: 추가 화면 설계

> 작성: 디자인 담당 · 1차 사이클 · 2026-10-30
> 입력: [original-plan.md](../original-plan.md), `docs/design.md`, `docs/forms.md`, `docs/menus.md`, `docs/glossary.md`, `docs/i18n.md`, `packages/app/src` 현재 코드
> 범위: 이 포크가 **더하는** 화면·흐름·상태만. 기존 Paseo 화면과 디자인 시스템은 그대로 둔다.

---

## 0. 요약

새 화면은 **하나**다(리눅스 설치/업데이트 화면). 나머지는 모두 이미 있는 자리에 섹션이나 상태를 끼운다.

| 추가 요소                                           | 들어갈 자리                                                                                   | 새 화면?          |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------- | ----------------- |
| A. 리눅스 설치·업데이트                             | 시작 경로의 `StartupSplashScreen` 자리 (startup blocker에 새 kind)                            | 예, 1개           |
| B. 런타임 상태 (Linux, daemon, SSH, 화면 꺼짐 유지) | Settings → Host(이 기기) → Host 페이지의 새 섹션 **Linux**                                    | 아니오            |
| C. 데스크톱 접속 정보 + SSH 키                      | 같은 Host 페이지의 새 섹션 **Remote access** + 키 관리 시트 1개                               | 아니오 (시트 1개) |
| D. 교환 폴더                                        | 같은 Host 페이지의 새 섹션 **Exchange folder**                                                | 아니오            |
| E. "이 기기" 호스트                                 | 기존 호스트 목록(사이드바 Hosts 피커, Settings 호스트 피커)에 그대로. 설명 줄만 "This device" | 아니오            |
| F. 상시 알림                                        | Android 포그라운드 서비스 알림 (네이티브)                                                     | 해당 없음         |
| G. 런타임 정지 알림                                 | 기존 사이드바 콜아웃 (`useSidebarCallouts`)                                                   | 아니오            |

핵심 결정 세 가지:

1. **데스크톱 Paseo는 Remote SSH로 붙는다.** 데몬은 폰 안 loopback(`127.0.0.1:6767`)에만 열고, 데스크톱은 이미 있는 Remote SSH 연결(`ssh://user@host:port?daemonPort=6767`)로 sshd를 거쳐 터널링한다. 그러면 데몬을 LAN에 노출할 필요도, 데몬 비밀번호도, 릴레이도 없다. 데스크톱 Paseo, 터미널 SSH, VS Code Remote-SSH가 같은 경로·같은 키 하나를 쓴다. (아키텍트 확인 필요: §9 Q1)
2. **데스크톱 내장 데몬 UI가 원형이다.** Electron의 "built-in daemon"은 이미 `useLocalDaemonServerId` / `orderHostsLocalFirst` / `isLocalDaemon` / `LocalDaemonSection` / startup blocker(`managed-daemon-starting|error`) 를 갖고 있다. 폰은 같은 개념의 두 번째 구현이다. 새 개념을 만들지 않고 "로컬 데몬" 판정에 Android 런타임을 하나 더 꽂는다.
3. **모든 추가 UI는 폰 런타임이 있을 때만 보인다.** 판정은 하나(`isPhoneRuntime`, §8). 웹·Electron·iOS·일반 Android 빌드에는 아무것도 바뀌지 않는다. 파일은 `.android.tsx` + 빈 기본 파일로 나눠 Metro가 고르게 한다 (CLAUDE.md "Prefer Metro file extensions").

하지 않는 것 (기획서 "하지 않을 것"에 따라): 온보딩/안내 화면, 배포판 선택, 릴레이·클라우드 경로, 공유 저장소 동기화 UI. 설치 전 "시작하기" 확인 화면도 두지 않는다. 앱을 처음 열면 바로 설치가 시작된다.

---

## 1. 기존 시스템에서 가져다 쓰는 것

조사한 접점. 이 문서의 모든 매핑은 이 목록 안에서 고른다.

| 쓰임                    | 기존 요소                                                                                                                                                          | 위치                                                                                     |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| 시작 차단 상태          | `StartupBlocker` (`none` / `managed-daemon-starting` / `managed-daemon-error`)                                                                                     | `src/navigation/host-runtime-bootstrap.ts:43`                                            |
| 시작/에러 화면 레이아웃 | `StartupSplashScreen` (로고 shimmer, 에러 시 로그 + Copy logs + Retry)                                                                                             | `src/screens/startup-splash-screen.tsx`                                                  |
| 로컬 데몬 판정          | `useLocalDaemonServerId`, `useIsLocalDaemon`, `orderHostsLocalFirst`                                                                                               | `src/hooks/use-is-local-daemon.ts`, `src/types/host-connection.ts`                       |
| 로컬 데몬 섹션 원형     | `LocalDaemonSection` / `DaemonInfoCard` (Status, Manage, Keep running, Log file, Full status)                                                                      | `src/desktop/components/desktop-updates-section.tsx:221-470`                             |
| 로그 시트               | `DaemonLogsModal` (`{ logPath, contents }`)                                                                                                                        | 같은 파일                                                                                |
| Host 페이지             | `HostSettingsPage` (StatusBadges → Appearance → [로컬이면 LocalDaemonSection] → Update → Danger zone)                                                              | `src/screens/settings/host-page.tsx:360`                                                 |
| 호스트 피커 행          | `HostPickerOption` (상태 점 + 라벨 + 연결 설명)                                                                                                                    | `src/components/hosts/host-picker.tsx`                                                   |
| Remote SSH 연결         | `AddRemoteSshHostModal`, `parseSshTransportUri` (`ssh://user@host:port?daemonPort=N`)                                                                              | `src/components/add-remote-ssh-host-modal.tsx`, `packages/protocol/src/ssh-transport.ts` |
| 섹션/카드/행            | `SettingsSection`(+`info`, `trailing`), `SettingsCard`, `SettingsRow`, `SettingsSwitch`, `SettingsCollapsibleRow`, `settingsStyles.row/rowTitle/rowHint/rowBorder` | `src/components/settings/`, `src/styles/settings.ts`                                     |
| 버튼/상태               | `Button`(default/secondary/outline/ghost/destructive), `StatusBadge`, `HostStatusDot`, `LoadingSpinner`, `Alert`, `confirmDialog`, `SegmentedControl`, `Switch`    | `src/components/ui/`, `src/utils/confirm-dialog.ts`                                      |
| 코드 표시               | `ScrollableCodeSurface` / `SurfaceCard`                                                                                                                            | `src/components/ui/scrollable-code-surface.tsx`                                          |
| 진행 막대               | `UsageMeter` (track 4px, tone `default` = `foregroundMuted` 채움)                                                                                                  | `src/usage/meter.tsx`                                                                    |
| 폼 시트                 | `AdaptiveModalSheet` + `FormTextInput`                                                                                                                             | `src/components/adaptive-modal-sheet.tsx`, `src/components/ui/form-field.tsx`            |
| 앱 전역 알림            | `useSidebarCallouts` + `SidebarCallout`                                                                                                                            | `src/contexts/sidebar-callout-context.tsx`                                               |
| 복사 피드백             | `useToast()`                                                                                                                                                       | `src/contexts/toast-api-context.tsx`                                                     |

---

## 2. 내비게이션 배치

```
앱 시작 (/)
 ├─ [폰 런타임 & 리눅스 미설치/업데이트 필요] → A. Linux setup (StartupSplashScreen 자리)
 │       └─ 완료 → 기존 시작 경로 (/h/[thisDevice] → 마지막 workspace 또는 /open-project)
 └─ [그 외] → 기존 시작 경로 그대로

사이드바 footer ─ Hosts 아이콘 → HostPicker
 └─ 첫 줄: "Pixel 8" · This device  (E)  ─ 톱니 → Settings › Host

Settings › Host (이 기기 선택 시)
 └─ Host 페이지 (기존 HostSettingsPage)
     ├─ StatusBadges            (기존)
     ├─ Appearance              (기존)
     ├─ Linux                   (B, 신규 섹션 — 데스크톱의 LocalDaemonSection 자리)
     ├─ Remote access           (C, 신규 섹션)
     │    └─ Authorized keys ›  → AdaptiveModalSheet (신규 시트)
     ├─ Exchange folder         (D, 신규 섹션)
     └─ Danger zone             (기존 섹션, 내용 교체: Restart daemon 유지 + Reinstall Linux)

Android 알림 영역 → F. 상시 알림 (탭 → 앱 열기 → Settings › Host › 이 기기)
사이드바 콜아웃 → G. "Linux is stopped" (Start)
```

새 최상위 내비게이션 없음. 새 라우트 없음 (A는 `/` 위에서 그려지는 시작 차단 화면, 시트는 페이지 안 모달).

Settings의 호스트 섹션 목록(`HOST_SECTION_ITEMS`)은 건드리지 않는다. 단, **이 기기 호스트에서 "Pair device"는 숨긴다** — 그 페이지는 릴레이 동의 화면이고 릴레이는 범위 밖이다 (§9 Q6).

---

## 3. 화면 A — Linux setup (설치·업데이트)

**목적.** 첫 실행 때 Debian rootfs를 받고, 풀고, 설정하고, 패키지·Paseo 데몬을 넣고, 런타임을 띄운다. 앱 업데이트로 번들 데몬 버전이 바뀌면 같은 화면이 짧은 "업데이트" 경로로 나온다.

**진입.** 시작 경로. 폰 런타임이 `linux.status`를 `not-installed | installing | updating | install-failed`로 보고하면 startup blocker가 새 kind(`linux-setup`)를 낸다. `resolveStartupNavigationReady`가 `managed-daemon-starting`처럼 이 kind에서도 대기하므로 라우팅 규칙(docs/expo-router.md "Startup")은 그대로다. Welcome 화면은 폰 런타임에서 나오지 않는다 — 설치가 끝나면 이 기기 호스트가 자동 등록되어 호스트가 0개인 순간이 없다.

**왜 새 화면인가.** 기존 `StartupSplashScreen`은 shimmer 아니면 에러 두 가지뿐이고, 몇 분 걸리는 단계별 진행을 보여줄 자리가 없다. 다만 **새 레이아웃을 만들지 않는다.** 스플래시 에러 화면의 틀(로고 64 + 제목 + 설명 + 본문 + 로그 + 액션 줄)을 그대로 쓰고 본문만 단계 카드로 바꾼다. 구현은 `StartupSplashScreen` 옆의 `LinuxSetupScreen` 하나 (`src/phone/linux-setup-screen.android.tsx`), 폰이 아니면 파일 자체가 번들에 없다.

### 3.1 와이어프레임 — 진행 중 (설치)

```
┌──────────────────────────────────────┐
│                                      │
│              [Paseo logo 64]         │
│                                      │
│          Setting up Linux            │  ← title (스플래시 title 스타일)
│   Installs Debian and the Paseo      │  ← description, foregroundMuted
│   daemon on this phone               │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ ✓  Download Debian        182 MB │ │  ← 완료: Check, statusSuccess
│ │──────────────────────────────────│ │
│ │ ◌  Extract                       │ │  ← 진행: LoadingSpinner 14
│ │──────────────────────────────────│ │
│ │ ○  Configure                     │ │  ← 대기: Circle, foregroundMuted
│ │──────────────────────────────────│ │
│ │ ○  Install packages              │ │
│ │──────────────────────────────────│ │
│ │ ○  Install Paseo                 │ │
│ │──────────────────────────────────│ │
│ │ ○  Start                         │ │
│ └──────────────────────────────────┘ │
│                                      │
│   You can leave the app. Setup       │  ← hint, sm, foregroundMuted
│   continues in the background        │
│                                      │
└──────────────────────────────────────┘
```

다운로드 단계가 진행 중일 때 그 행:

```
│ │ ◌  Download Debian    74 / 182 MB │ │
│ │    ▓▓▓▓▓▓▓▓░░░░░░░░░░░░  (UsageMeter)│ │
```

- 행 높이는 진행 막대가 있든 없든 같게 잡는다 (막대 자리를 예약; design.md §11 "Changing state must not move the layout"). 막대는 진행 중인 다운로드 행에서만 보이고, 나머지 행은 같은 높이의 빈 자리.
- `Install packages` 진행 중 우측 값은 현재 패키지 수 `12 / 48`(apt가 줄 수 있으면), 못 주면 비움.
- 퍼센트·MB는 `fontSize.sm`, `foregroundMuted`, 숫자 폭 고정(tabular) — 값이 바뀌어도 줄이 흔들리지 않게.

### 3.2 와이어프레임 — 실패

```
┌──────────────────────────────────────┐
│              [Paseo logo 64]         │
│          Linux setup failed          │
│   Setup stopped at "Install          │
│   packages". Retry starts that step  │
│   again.                             │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ ✓  Download Debian        182 MB │ │
│ │ ✓  Extract                       │ │
│ │ ✓  Configure                     │ │
│ │ ✕  Install packages              │ │  ← X, statusDanger
│ │ ○  Install Paseo                 │ │
│ │ ○  Start                         │ │
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ E: Unable to fetch some archives │ │  ← 에러 원문 (CODE surface, 번역 안 함)
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ (setup log tail, scroll)         │ │  ← 스플래시 logsContainer 그대로
│ └──────────────────────────────────┘ │
│                                      │
│   [Copy logs]            [Retry]     │  ← secondary / default(accent, 화면 유일)
└──────────────────────────────────────┘
```

스플래시 에러 화면에 있던 "Open GitHub issue"·"Docs" 버튼은 이 화면에서 뺀다. 개인용 포크이고 업스트림 이슈가 아니다.

### 3.3 와이어프레임 — 업데이트 (앱 업데이트 후 번들 데몬 버전 변경)

같은 화면, 제목과 단계만 짧다.

```
          Updating Linux
   Paseo 0.14.1 is being installed
   inside Linux

 ┌──────────────────────────────────┐
 │ ◌  Install Paseo                 │
 │ ○  Start                         │
 └──────────────────────────────────┘
```

### 3.4 상태 표

| 상태                                                | 표시                                                                                            | 액션                                                |
| --------------------------------------------------- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| `checking` (런타임이 설치 여부 확인 중, 보통 < 1초) | 기존 스플래시 shimmer 그대로. 단계 카드 안 보임                                                 | —                                                   |
| `installing` / `updating`                           | 3.1 / 3.3                                                                                       | 없음. 취소 버튼 없음 (개인용, 실패 시 Retry로 충분) |
| `install-failed`                                    | 3.2. 실패 단계만 ✕, 이후 단계 ○                                                                 | Copy logs, Retry                                    |
| 오프라인으로 다운로드 실패                          | 3.2와 같고 에러 원문 위에 `Alert variant="warning"` 한 줄: "No internet connection"             | Retry                                               |
| 저장 공간 부족 (사전 검사)                          | 3.2와 같고 실패 단계는 Download, 설명: "Not enough storage. {{free}} free, {{needed}} needed"   | Retry                                               |
| `installed` (성공)                                  | 성공 화면 없음. 마지막 `Start`가 ✓되면 바로 기존 시작 경로로 넘어간다                           | —                                                   |
| 앱이 백그라운드로 감                                | 설치는 포그라운드 서비스 안에서 계속. 알림이 진행률 표시 (§7.2). 돌아오면 현재 단계로 다시 그림 | —                                                   |

Retry 의미: 실패한 단계부터 다시. Kai 설치기는 실패 시 부분 rootfs를 지우고 다시 받는다 — 다운로드를 다시 하는지는 아키텍트가 정한다 (§9 Q3). 설명 문구는 그 결정에 맞춰 바꾼다.

### 3.5 문구 (English, i18n key)

키는 화면 단위로 `linuxSetup.*` 아래 묶는다 (docs/i18n.md "grouped by product surface").

| key                             | en                                                         |
| ------------------------------- | ---------------------------------------------------------- |
| `linuxSetup.installTitle`       | Setting up Linux                                           |
| `linuxSetup.installDescription` | Installs Debian and the Paseo daemon on this phone         |
| `linuxSetup.updateTitle`        | Updating Linux                                             |
| `linuxSetup.updateDescription`  | Paseo {{version}} is being installed inside Linux          |
| `linuxSetup.backgroundHint`     | You can leave the app. Setup continues in the background   |
| `linuxSetup.steps.download`     | Download Debian                                            |
| `linuxSetup.steps.extract`      | Extract                                                    |
| `linuxSetup.steps.configure`    | Configure                                                  |
| `linuxSetup.steps.packages`     | Install packages                                           |
| `linuxSetup.steps.paseo`        | Install Paseo                                              |
| `linuxSetup.steps.start`        | Start                                                      |
| `linuxSetup.progress.bytes`     | {{done}} / {{total}}                                       |
| `linuxSetup.progress.count`     | {{done}} / {{total}}                                       |
| `linuxSetup.failedTitle`        | Linux setup failed                                         |
| `linuxSetup.failedDescription`  | Setup stopped at "{{step}}". Retry starts that step again. |
| `linuxSetup.offline`            | No internet connection                                     |
| `linuxSetup.noSpace`            | Not enough storage. {{free}} free, {{needed}} needed       |
| `linuxSetup.actions.copyLogs`   | Copy logs                                                  |
| `linuxSetup.actions.retry`      | Retry                                                      |
| `linuxSetup.logsCopied`         | Logs copied                                                |

번역하지 않는 것: 에러 원문, 로그, 패키지 이름, 크기 단위 숫자, 버전.

### 3.6 요소 매핑

| 요소                                 | 기존 컴포넌트·토큰                                                                                                                              |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 화면 틀, 로고, 제목, 설명, 로그 상자 | `StartupSplashScreen` 에러 레이아웃의 `styles.errorContent/errorHeader/title/errorDescription/logsContainer` — 공용으로 빼서 재사용             |
| 단계 카드                            | `SettingsCard` + 행마다 `SettingsRow`(label=단계 이름, children=우측 값). 선행 아이콘은 `SettingsRow`에 없으므로 아래 "신규 최소 확장" 참조     |
| 상태 아이콘                          | `Check`(statusSuccess) / `LoadingSpinner size={14} color={foregroundMuted}` / `Circle`(foregroundMuted) / `X`(statusDanger), 모두 `iconSize.sm` |
| 진행 막대                            | `UsageMeter percent tone="default"`                                                                                                             |
| 에러 원문                            | 스플래시 `styles.errorMessage` + `CODE_SURFACE_DATASET`                                                                                         |
| 오프라인 경고                        | `Alert variant="warning" size="sm"`                                                                                                             |
| Copy logs / Retry                    | `Button variant="secondary"` / `Button variant="default"` (화면의 유일한 accent)                                                                |
| 복사 확인                            | `useToast()`                                                                                                                                    |

**신규 최소 확장 — 단계 행의 선행 아이콘.** `SettingsRow`는 선행 아이콘 슬롯이 없다. 두 안 중 하나:
(a) 단계 카드 전용 작은 행(`SetupStepRow`)을 `linux-setup-screen` 파일 안에 둔다 — 한 화면에서만 쓰므로 design.md §2 "One of a kind is a screen"에 맞다. 행 레이아웃은 `settingsStyles.row/rowContent/rowTitle`을 그대로 쓴다.
(b) `SettingsRow`에 `leading` prop 추가 — Paseo 본체 수정이므로 피한다.
→ **(a)를 권한다.**

**진행 막대 승격 메모.** 진행 막대가 `download-toast.tsx`, `usage/meter.tsx`, 그리고 이번 화면으로 세 번째 쓰임이 된다. design.md §2 기준으로는 `components/ui/progress-bar.tsx` 승격 대상이지만, 업스트림 파일을 옮기는 일이라 이번엔 `UsageMeter`를 import만 한다. 업스트림이 승격하면 따라간다.

---

## 4. 섹션 B — Linux (런타임 상태)

**목적.** 이 기기의 Linux 런타임과 그 안의 데몬·sshd 상태를 보고, 멈추고 켜고, 화면이 꺼져도 작업이 이어지게 할지 정한다.

**진입.** Settings › Host(이 기기) › Host 페이지. `HostSettingsPage`에서 `isLocalDaemon`일 때 이미 `<LocalDaemonSection />`을 그리는 바로 그 자리에, 폰에서는 `<PhoneRuntimeSection />`이 그려진다. 데스크톱 섹션이 `shouldUseDesktopDaemon()`이 아니면 `null`을 돌려주듯, 폰 섹션도 `.android.tsx` + `null` 기본 파일로 나눈다. host-page.tsx에는 한 줄만 추가된다.

알림(§7)과 사이드바 콜아웃(§G)이 모두 이 페이지로 온다.

### 4.1 와이어프레임 — 실행 중

```
Linux                                         ⓘ
┌──────────────────────────────────────────────┐
│ Linux                         ● Running      │ ← StatusBadge success + dot
│ Debian 12 · proot                   [Stop]   │ ← rowHint / outline sm
│──────────────────────────────────────────────│
│ Daemon                        ● Running      │
│ 127.0.0.1:6767                               │
│──────────────────────────────────────────────│
│ SSH server                          [ ◉ ]    │ ← Switch
│ Port 8022 · Running                          │
│──────────────────────────────────────────────│
│ Keep awake with screen off          [ ◉ ]    │ ← Switch
│ Holds a wake lock so agents keep working.    │
│ Uses more battery.                           │
│──────────────────────────────────────────────│
│ Logs                            [Open logs]  │ ← outline sm
│ Daemon, SSH and setup logs                   │
└──────────────────────────────────────────────┘
```

`ⓘ`(섹션 `info`) 툴팁: "Linux runs in a foreground service. Stopping it stops the daemon, SSH and every agent on this phone."

Keep awake가 켜져 있는데 배터리 최적화가 걸려 있으면 카드 아래에 한 개의 `Alert`:

```
┌ ⚠ Battery optimization is on ───────────────┐
│ Android may still stop Linux in the          │
│ background.                                  │
│ [Open battery settings]                      │ ← outline sm
└──────────────────────────────────────────────┘
```

phantom process killer 등 앱이 판단할 수 없는 기기 설정은 화면에 다루지 않는다 (기획서: "기기 설정 한 번으로 해결되는 문제는 앱이 우회하지 않는다").

### 4.2 상태

Linux 행이 전체 상태를 대표한다. 데몬·SSH 행은 Linux가 `running`일 때만 자기 상태를 따로 보인다.

| Linux 상태                    | Linux 행 배지 / 버튼                                                                                    | Daemon 행               | SSH 행                                     |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- | ----------------------- | ------------------------------------------ |
| 로딩 (런타임 상태 첫 응답 전) | 카드 전체 대신 `settingsStyles.card` + 가운데 `LoadingSpinner small` (데스크톱 `loadingCard`와 동일)    | —                       | —                                          |
| `starting`                    | `StatusBadge muted` "Starting..." / 버튼 숨김 (자리 유지)                                               | "Starting..."           | "Starting..."                              |
| `running`                     | `success` "Running" / [Stop]                                                                            | 아래 표                 | 아래 표                                    |
| `stopping`                    | `muted` "Stopping..." / [Stopping...] 비활성                                                            | —                       | —                                          |
| `stopped`                     | `muted` "Stopped" / [Start]                                                                             | "Stopped" (hint 그대로) | 스위치 상태 유지, hint "Starts with Linux" |
| `error`                       | `error` "Stopped unexpectedly" / [Start] + 카드 아래 `Alert variant="error"`: 원문 메시지 + [Open logs] | —                       | —                                          |

| Daemon 상태 (Linux running일 때) | 표시                                                                                                     |
| -------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `running`                        | `success` "Running"                                                                                      |
| `starting`                       | `muted` "Starting..."                                                                                    |
| `error` / 재시작 반복            | `error` "Not responding" + 행 hint를 원문 마지막 에러로. 복구 액션은 Danger zone의 기존 "Restart daemon" |

| SSH (Linux running일 때) | 표시                                                                          |
| ------------------------ | ----------------------------------------------------------------------------- |
| 스위치 on, 실행 중       | hint "Port 8022 · Running"                                                    |
| 스위치 on, 시작 실패     | hint를 `settingsStyles.rowError`로 "Unable to start SSH server" (원문은 로그) |
| 스위치 off               | hint "Off"                                                                    |
| 전환 중                  | 스위치 disabled (opacity)                                                     |

**Stop은 확인을 받는다.** Linux를 멈추면 실행 중인 에이전트가 모두 끊긴다. 데스크톱 "Stop daemon"과 같은 규칙으로 `confirmDialog({ destructive: true })`:

- title: "Stop Linux?"
- message: "The daemon, SSH and {{count}} running agents on this phone will stop." (0개면 "The daemon and SSH on this phone will stop.")
- confirm: "Stop" / cancel: "Cancel"

Start는 확인 없음. SSH 스위치 off도 확인 없음 (붙어 있던 SSH 세션은 끊기지만 데이터 손실은 없다).

### 4.3 문구

| key                                           | en                                                                                                   |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `settings.host.linux.title`                   | Linux                                                                                                |
| `settings.host.linux.info`                    | Linux runs in a foreground service. Stopping it stops the daemon, SSH and every agent on this phone. |
| `settings.host.linux.runtime.title`           | Linux                                                                                                |
| `settings.host.linux.runtime.hint`            | {{distro}} · proot                                                                                   |
| `settings.host.linux.status.running`          | Running                                                                                              |
| `settings.host.linux.status.starting`         | Starting...                                                                                          |
| `settings.host.linux.status.stopping`         | Stopping...                                                                                          |
| `settings.host.linux.status.stopped`          | Stopped                                                                                              |
| `settings.host.linux.status.crashed`          | Stopped unexpectedly                                                                                 |
| `settings.host.linux.status.notResponding`    | Not responding                                                                                       |
| `settings.host.linux.actions.start`           | Start                                                                                                |
| `settings.host.linux.actions.stop`            | Stop                                                                                                 |
| `settings.host.linux.stopConfirm.title`       | Stop Linux?                                                                                          |
| `settings.host.linux.stopConfirm.message`     | The daemon, SSH and {{count}} running agents on this phone will stop.                                |
| `settings.host.linux.stopConfirm.messageIdle` | The daemon and SSH on this phone will stop.                                                          |
| `settings.host.linux.daemon.title`            | Daemon                                                                                               |
| `settings.host.linux.ssh.title`               | SSH server                                                                                           |
| `settings.host.linux.ssh.running`             | Port {{port}} · Running                                                                              |
| `settings.host.linux.ssh.off`                 | Off                                                                                                  |
| `settings.host.linux.ssh.startsWithLinux`     | Starts with Linux                                                                                    |
| `settings.host.linux.ssh.startFailed`         | Unable to start SSH server                                                                           |
| `settings.host.linux.keepAwake.title`         | Keep awake with screen off                                                                           |
| `settings.host.linux.keepAwake.hint`          | Holds a wake lock so agents keep working. Uses more battery.                                         |
| `settings.host.linux.battery.title`           | Battery optimization is on                                                                           |
| `settings.host.linux.battery.description`     | Android may still stop Linux in the background.                                                      |
| `settings.host.linux.battery.action`          | Open battery settings                                                                                |
| `settings.host.linux.logs.title`              | Logs                                                                                                 |
| `settings.host.linux.logs.hint`               | Daemon, SSH and setup logs                                                                           |
| `settings.host.linux.logs.open`               | Open logs                                                                                            |
| `settings.host.linux.startFailed`             | Unable to start Linux                                                                                |
| `settings.host.linux.stopFailed`              | Unable to stop Linux                                                                                 |

"Daemon"은 glossary상 시스템 맥락에서만 쓰는 UI 라벨이다. 이 섹션이 그 맥락이다.

### 4.4 요소 매핑

| 요소                 | 기존                                                                                                                                                                                                              |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| 섹션                 | `SettingsSection title info`                                                                                                                                                                                      |
| 카드/행              | `settingsStyles.card`, `settingsStyles.row/rowContent/rowTitle/rowHint/rowBorder` — `DaemonInfoCard`와 같은 짜임                                                                                                  |
| 상태 값              | `StatusBadge` + 선행 점(`HostStatusBadges`가 하는 방식: `statusDotSuccess/Warning/Danger`)                                                                                                                        |
| Start/Stop/Open logs | `Button variant="outline"` (행 트레일링, 크기는 행이 정함)                                                                                                                                                        |
| SSH·Keep awake       | `Switch` (트레일링)                                                                                                                                                                                               |
| 로딩                 | `LoadingSpinner` + `styles.loadingCard` 패턴                                                                                                                                                                      |
| 에러/배터리 경고     | `Alert variant="error"                                                                                                                                                                                            | "warning"`, 액션은 children의 `Button outline sm` |
| 확인                 | `confirmDialog`                                                                                                                                                                                                   |
| 로그                 | `DaemonLogsModal` 재사용. 로그가 셋(daemon / sshd / setup)이므로 시트 상단에 `SegmentedControl size="sm"` [Daemon][SSH][Setup]. 기존 모달이 단일 로그만 받으면 폰 쪽에서 감싸는 얇은 래퍼로 해결 (본체 수정 피함) |
| 실패 즉시 오류       | `Alert.alert(t("...startFailed"), message)` (RN Alert, 데스크톱 `stopFailed`와 같은 방식)                                                                                                                         |

---

## 5. 섹션 C — Remote access (데스크톱 접속 정보)

**목적.** 데스크톱에서 이 폰에 붙는 데 필요한 것 전부: 주소, 포트, 붙여넣을 명령, 키 등록.

**진입.** Host 페이지, Linux 섹션 바로 아래.

### 5.1 와이어프레임 — Wi-Fi + Tailscale 둘 다 있을 때

```
Remote access                                 ⓘ
┌──────────────────────────────────────────────┐
│ Network                 [ Wi-Fi | Tailscale ]│ ← SegmentedControl (둘 다 있을 때만)
│ 192.168.1.23                                 │ ← 선택된 주소, rowHint
│──────────────────────────────────────────────│
│ SSH                                  [Copy]  │
│ ssh -p 8022 root@192.168.1.23                │ ← 코드 글꼴 hint, 줄바꿈 허용
│──────────────────────────────────────────────│
│ Paseo desktop                        [Copy]  │
│ ssh://root@192.168.1.23:8022                 │
│ Add host › Remote SSH, then paste            │ ← 두 번째 hint
│──────────────────────────────────────────────│
│ SSH config                              ›    │ ← SettingsCollapsibleRow
│   ┌────────────────────────────────────────┐ │
│   │ Host pixel                             │ │ ← ScrollableCodeSurface
│   │   HostName 192.168.1.23                │ │
│   │   Port 8022                            │ │
│   │   User root                            │ │
│   └────────────────────────────────────────┘ │
│   For VS Code Remote-SSH            [Copy]   │
│──────────────────────────────────────────────│
│ Authorized keys                    2 keys ›  │ ← SettingsDetailRow → 시트
└──────────────────────────────────────────────┘
```

`ⓘ` 툴팁: "Desktops connect over SSH. Paseo desktop tunnels to the daemon through the same connection, so the daemon is never exposed on the network."

- 주소 하나만 있으면 SegmentedControl 대신 "Wi-Fi" 또는 "Tailscale" 라벨을 hint에 쓴다: `Wi-Fi · 192.168.1.23`.
- Tailscale은 MagicDNS 이름이 있으면 주소 대신 이름을 쓴다 (`pixel.tailnet.ts.net`) — IP가 바뀌어도 데스크톱 설정이 유지된다.
- SSH config의 `Host` 별칭은 이 호스트의 라벨을 소문자·하이픈으로 바꾼 것 (`Pixel 8` → `pixel-8`).
- `ssh://` URI는 데몬 포트가 기본 6767이면 `?daemonPort=`를 생략한다. 기본이 아니면 붙인다 (`parseSshTransportUri` 형식).

### 5.2 상태

| 상태                                      | 표시                                                                                                                                   |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 로딩 (주소 조회 중)                       | Network 행 hint 자리에 `LoadingSpinner 14` (행 높이 유지). 아래 행들은 같은 높이로 hint "—"                                            |
| 네트워크 없음 (Wi-Fi·Tailscale 모두 없음) | 카드 위 `Alert variant="warning"`: "No network address" / "Connect to Wi-Fi or start Tailscale." 명령 행들의 Copy 비활성               |
| SSH 꺼짐 또는 Linux 정지                  | 카드 위 `Alert variant="default"`: "SSH server is off" / "Turn it on in Linux above." 행 내용은 그대로 보이되 Copy 비활성 (opacity 50) |
| 키 0개                                    | Authorized keys 요약 "No keys". 카드 아래 `settingsStyles.rowHint`가 아니라, 그 행 자체의 summary로만 알린다 (조용하게)                |
| 주소 바뀜 (Wi-Fi 재접속)                  | 실시간 갱신. 레이아웃 고정 폭이 아니므로 줄 길이만 바뀐다                                                                              |
| 복사                                      | `useToast()` "Copied"                                                                                                                  |

### 5.3 시트 — Authorized keys

**목적.** 데스크톱 공개키를 `~/.ssh/authorized_keys`에 넣고 빼기. 키를 붙여넣는 게 기본 경로다. 폰 클립보드로 공개키를 옮기는 방법(메신저, 클립보드 공유, 교환 폴더의 `.pub` 파일)은 사용자 몫이다.

**진입.** Remote access › Authorized keys 행.

```
┌──────────────────────────────────────────────┐
│ Authorized keys                          ✕   │ ← AdaptiveModalSheet header
│                                              │
│ ┌──────────────────────────────────────────┐ │
│ │ ouor@desktop                    [Remove] │ │ ← 키 comment, outline sm
│ │ ED25519 SHA256:kq3…f9A                   │ │ ← 타입 + 지문, sm muted
│ │──────────────────────────────────────────│ │
│ │ ouor@laptop                     [Remove] │ │
│ │ ED25519 SHA256:0Pz…m2c                   │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ Public key                                   │ ← Field label
│ ┌──────────────────────────────────────────┐ │
│ │ ssh-ed25519 AAAA… user@host              │ │ ← FormTextInput multiline
│ └──────────────────────────────────────────┘ │
│ Not a valid public key                       │ ← 검증 에러 (있을 때만, Field가 자리 관리)
│                                              │
│                              [Add key]       │ ← default (시트의 유일한 accent)
└──────────────────────────────────────────────┘
```

| 상태                                            | 표시                                                                                                                                                               |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 로딩                                            | 키 카드 자리에 card-level 한 줄 "Loading..." (design.md §11: 카드 로딩은 스피너 아님)                                                                              |
| 키 0개                                          | 키 카드 대신 muted 한 줄 "No keys yet"                                                                                                                             |
| 붙여넣은 값이 공개키가 아님 / 개인키를 붙여넣음 | Field 에러: "Not a valid public key" / 개인키면 "This is a private key. Paste the .pub file instead." 그리고 입력 내용을 지운다 (개인키를 화면·상태에 남기지 않음) |
| 중복                                            | Field 에러: "This key is already authorized"                                                                                                                       |
| 추가 중                                         | 버튼 "Adding...", 비활성                                                                                                                                           |
| 추가 성공                                       | 필드 비우고 목록 맨 아래에 키 추가. 시트는 열린 채                                                                                                                 |
| 추가 실패 (파일 쓰기 실패 등)                   | Field 에러로 원문                                                                                                                                                  |
| Remove                                          | `confirmDialog({ destructive: true })` title "Remove key?" message "{{comment}} will no longer be able to sign in."                                                |

폼 규칙은 docs/forms.md를 따른다: 필드는 `FormTextInput`(→ `EditingTextInput`), 크기는 compact `md`, 모델은 작으니 화면 컴포넌트 안 상태로 충분하지만 열 때마다 새로 마운트 (`key`로).

### 5.4 문구

| key                                                  | en                                                                                                                                         |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `settings.host.remoteAccess.title`                   | Remote access                                                                                                                              |
| `settings.host.remoteAccess.info`                    | Desktops connect over SSH. Paseo desktop tunnels to the daemon through the same connection, so the daemon is never exposed on the network. |
| `settings.host.remoteAccess.network.title`           | Network                                                                                                                                    |
| `settings.host.remoteAccess.network.wifi`            | Wi-Fi                                                                                                                                      |
| `settings.host.remoteAccess.network.tailscale`       | Tailscale                                                                                                                                  |
| `settings.host.remoteAccess.network.noneTitle`       | No network address                                                                                                                         |
| `settings.host.remoteAccess.network.noneDescription` | Connect to Wi-Fi or start Tailscale.                                                                                                       |
| `settings.host.remoteAccess.sshOff.title`            | SSH server is off                                                                                                                          |
| `settings.host.remoteAccess.sshOff.description`      | Turn it on in Linux above.                                                                                                                 |
| `settings.host.remoteAccess.ssh.title`               | SSH                                                                                                                                        |
| `settings.host.remoteAccess.desktop.title`           | Paseo desktop                                                                                                                              |
| `settings.host.remoteAccess.desktop.hint`            | Add host › Remote SSH, then paste                                                                                                          |
| `settings.host.remoteAccess.sshConfig.title`         | SSH config                                                                                                                                 |
| `settings.host.remoteAccess.sshConfig.hint`          | For VS Code Remote-SSH                                                                                                                     |
| `settings.host.remoteAccess.copy`                    | Copy                                                                                                                                       |
| `settings.host.remoteAccess.copied`                  | Copied                                                                                                                                     |
| `settings.host.remoteAccess.keys.title`              | Authorized keys                                                                                                                            |
| `settings.host.remoteAccess.keys.count_one`          | {{count}} key                                                                                                                              |
| `settings.host.remoteAccess.keys.count_other`        | {{count}} keys                                                                                                                             |
| `settings.host.remoteAccess.keys.none`               | No keys                                                                                                                                    |
| `settings.host.remoteAccess.keys.emptyList`          | No keys yet                                                                                                                                |
| `settings.host.remoteAccess.keys.fieldLabel`         | Public key                                                                                                                                 |
| `settings.host.remoteAccess.keys.add`                | Add key                                                                                                                                    |
| `settings.host.remoteAccess.keys.adding`             | Adding...                                                                                                                                  |
| `settings.host.remoteAccess.keys.remove`             | Remove                                                                                                                                     |
| `settings.host.remoteAccess.keys.removeTitle`        | Remove key?                                                                                                                                |
| `settings.host.remoteAccess.keys.removeMessage`      | {{comment}} will no longer be able to sign in.                                                                                             |
| `settings.host.remoteAccess.keys.invalid`            | Not a valid public key                                                                                                                     |
| `settings.host.remoteAccess.keys.privateKey`         | This is a private key. Paste the .pub file instead.                                                                                        |
| `settings.host.remoteAccess.keys.duplicate`          | This key is already authorized                                                                                                             |

번역 안 함: 주소, 포트, 명령, URI, SSH config 본문, 키 comment, 지문.

### 5.5 요소 매핑

| 요소               | 기존                                                                                                                                               |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| 섹션 + info        | `SettingsSection info`                                                                                                                             |
| Network 선택       | `SegmentedControl` (행 트레일링, 크기는 행이 정함)                                                                                                 |
| 명령 행            | `SettingsRow` label + hint(코드 글꼴: `theme.fontFamily.mono`, `fontSize.sm`, `foregroundMuted`, `selectable`) + `Button variant="outline"` "Copy" |
| SSH config         | `SettingsCollapsibleRow` (중첩 행 밀도 자동) + `ScrollableCodeSurface` + Copy 버튼                                                                 |
| Authorized keys 행 | `SettingsDetailRow label summary onPress`                                                                                                          |
| 키 시트            | `AdaptiveModalSheet` + `SettingsCard`(키 목록) + `Field`/`FormTextInput multiline` + `Button default`                                              |
| 경고               | `Alert variant="warning"                                                                                                                           | "default"` |
| 복사 피드백        | `Clipboard.setStringAsync` + `useToast()`                                                                                                          |
| 삭제 확인          | `confirmDialog({ destructive: true })`                                                                                                             |

---

## 6. 섹션 D — Exchange folder

**목적.** 폰의 다른 앱과 Linux 사이에 파일을 주고받는 폴더가 어디 있는지, 어떻게 여는지.

**진입.** Host 페이지, Remote access 아래.

### 6.1 와이어프레임 — 정상

```
Exchange folder                               ⓘ
┌──────────────────────────────────────────────┐
│ On this phone                        [Open]  │ ← outline sm → 파일 앱
│ Internal storage/Paseo                       │
│──────────────────────────────────────────────│
│ In Linux                             [Copy]  │
│ ~/exchange                                   │ ← 코드 글꼴
└──────────────────────────────────────────────┘
```

`ⓘ` 툴팁: "One shared folder for moving files in and out of Linux. Keep projects in your Linux home folder, not here."

"Open"은 Android 파일 앱(DocumentsUI)을 그 폴더에서 연다. 폴더가 그 경로로 열리지 않는 기기면 파일 앱 루트를 연다. 공유 시트로 파일을 보내는 기능은 두지 않는다.

### 6.2 상태

| 상태                      | 표시                                                                                                                                                                                                                           |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 정상                      | 6.1                                                                                                                                                                                                                            |
| 저장소 권한 없음          | 카드 위 `Alert variant="warning"`: "Storage access needed" / "Allow Paseo to manage files so Linux can see this folder." + [Allow access] (outline sm → 시스템 설정의 "모든 파일 접근" 화면). In Linux 행 hint는 "Not mounted" |
| 권한은 있는데 마운트 실패 | `Alert variant="error"`: "Unable to mount the exchange folder" + 원문 + [Open logs]                                                                                                                                            |
| Linux 정지                | 카드는 그대로. In Linux 행 hint 뒤에 " · Linux is stopped" 없이 그대로 둔다 — Linux 상태는 위 섹션 하나가 말한다 (한 사실, 한 곳)                                                                                              |

권한 요청은 설치 화면에 넣지 않는다. 처음 필요할 때(이 섹션, 또는 첫 마운트 실패) 묻는다. 권한 없이도 나머지 기능은 모두 돈다.

### 6.3 문구

| key                                             | en                                                                                                         |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `settings.host.exchange.title`                  | Exchange folder                                                                                            |
| `settings.host.exchange.info`                   | One shared folder for moving files in and out of Linux. Keep projects in your Linux home folder, not here. |
| `settings.host.exchange.phone.title`            | On this phone                                                                                              |
| `settings.host.exchange.phone.open`             | Open                                                                                                       |
| `settings.host.exchange.linux.title`            | In Linux                                                                                                   |
| `settings.host.exchange.linux.notMounted`       | Not mounted                                                                                                |
| `settings.host.exchange.permission.title`       | Storage access needed                                                                                      |
| `settings.host.exchange.permission.description` | Allow Paseo to manage files so Linux can see this folder.                                                  |
| `settings.host.exchange.permission.action`      | Allow access                                                                                               |
| `settings.host.exchange.mountFailed`            | Unable to mount the exchange folder                                                                        |

"Internal storage/Paseo"는 경로 표시이므로 번역하지 않는다 — 단, "Internal storage"는 Android가 로케일별로 다르게 부르므로 런타임이 표시용 문자열을 주는 게 맞다 (§9 Q8).

### 6.4 요소 매핑

`SettingsSection info`, `settingsStyles.card/row`, `Button outline`, `Alert warning|error`, 코드 글꼴 hint. 신규 없음.

---

## 7. F — 상시 알림 (Android 포그라운드 서비스)

알림 문구는 네이티브(Kotlin) 쪽에 있다. 앱의 i18n 리소스가 아니라 Android `strings.xml`로 두고, 1차에는 영어만 둔다 (§9 Q9). 그래서 키 대신 Android 리소스 이름을 적는다.

**채널.** `linux_runtime` — 이름 "Linux runtime", 중요도 LOW (소리·진동 없음, 상태바 아이콘만). 에이전트 알림(`notification-open`) 채널과 분리한다. 사용자가 이 채널만 끌 수 있게.

**탭.** 앱을 열고 Settings › Host › 이 기기로 간다. 경로는 `notification-open`의 기존 payload 형식에 맞춰 새 kind(`host-settings`)를 추가하거나, 앱 딥링크 `/settings/hosts/<serverId>`를 쓴다 (§9 Q10). 알림 탭 처리 규칙은 docs/notifications.md를 따른다.

### 7.1 실행 중

```
┌──────────────────────────────────────────────┐
│ [P] Paseo · Linux                            │
│ Running · 2 agents active                    │ ← title
│ ssh 192.168.1.23:8022 · Awake                │ ← text
│ [Stop]                                       │ ← action
└──────────────────────────────────────────────┘
```

| 요소                        | 내용                        | Android 리소스                         |
| --------------------------- | --------------------------- | -------------------------------------- |
| title (에이전트 0)          | Running                     | `linux_notif_running`                  |
| title (에이전트 n)          | Running · {n} agents active | `linux_notif_running_agents` (plurals) |
| text                        | ssh {address}:{port}        | `linux_notif_ssh_address`              |
| text 덧붙임 (Keep awake on) | · Awake                     | `linux_notif_awake_suffix`             |
| text (SSH off)              | SSH off                     | `linux_notif_ssh_off`                  |
| text (네트워크 없음)        | No network                  | `linux_notif_no_network`               |
| action                      | Stop                        | `linux_notif_action_stop`              |

주소는 Remote access 섹션에서 선택한 네트워크(Wi-Fi/Tailscale)를 따른다. 확장 시(BigTextStyle) 두 주소를 모두 줄로 보여준다:

```
ssh 192.168.1.23:8022 (Wi-Fi)
ssh pixel.tailnet.ts.net:8022 (Tailscale)
```

**Stop 액션.** 알림에서는 확인 대화상자를 띄울 수 없다. 규칙:

- 에이전트 0개 → 바로 멈춘다.
- 에이전트 1개 이상 → 바로 멈추지 않고 앱을 열어 §4.2의 `confirmDialog`를 띄운다.
  이렇게 하면 "빨간색은 의도를 밝힌 뒤에" 규칙(design.md §3)을 알림에서도 지킨다. 실행 중 에이전트 수를 데몬에서 알림까지 가져오는 방법은 §9 Q4.

"Copy address" 같은 액션은 두지 않는다 — 주소는 데스크톱에 쳐야 하는 것이라 폰 클립보드에 넣어도 쓸 데가 없다.

### 7.2 설치 중 / 시작 중 / 오류

| 상태             | title                                                                   | text                       | 진행                                               | action                       |
| ---------------- | ----------------------------------------------------------------------- | -------------------------- | -------------------------------------------------- | ---------------------------- |
| 설치 중          | Setting up Linux                                                        | {step} · {percent}%        | 결정형 진행 막대 (다운로드), 그 외 단계는 비결정형 | 없음                         |
| 업데이트 중      | Updating Linux                                                          | Install Paseo              | 비결정형                                           | 없음                         |
| 설치 실패        | Linux setup failed                                                      | Tap to see what went wrong | 없음                                               | 없음 (탭 → 화면 A 실패 상태) |
| 시작 중          | Starting Linux                                                          | —                          | 비결정형                                           | 없음                         |
| 데몬 응답 없음   | Daemon is not responding                                                | Tap to open logs           | 없음                                               | Restart                      |
| 예기치 않은 종료 | 서비스가 죽으면 알림도 사라진다. 다음 앱 실행 때 §4 error 상태로 보인다 |                            |                                                    |                              |
| 정지됨           | 알림 없음 (서비스 종료)                                                 |                            |                                                    |                              |

설치 실패·데몬 응답 없음 알림만 "ongoing"이 아니어서 쓸어 지울 수 있다.

---

## 8. E·G — "이 기기" 호스트와 앱 전역 신호

### 8.1 호스트 목록 안의 이 기기

기존 목록 행을 그대로 쓴다. 바뀌는 건 두 가지뿐.

```
Hosts ─────────────────────────────
 ● Pixel 8                    ⚙     ← 첫 줄 고정 (orderHostsLocalFirst)
   This device                      ← 설명 줄: 엔드포인트 대신
 ● desktop-ouor               ⚙
   100.64.0.12:6767
 + Add host
```

1. **로컬 판정.** `useLocalDaemonServerId()`가 지금은 Electron에서만 값을 낸다. 폰 런타임에서도 이 기기 데몬의 `serverId`를 내도록 한다. 그러면 정렬(`orderHostsLocalFirst`), Host 페이지의 `isLocalDaemon` 분기, Settings 사이드바 순서가 손대지 않고 따라온다. Paseo 본체의 접점은 이 hook 하나다 (기획서 "Paseo 본체에 손대는 거의 유일한 지점").
2. **설명 줄.** `formatActiveConnectionLabel`은 TCP 연결이면 `127.0.0.1:6767`을 보여준다. 이 기기 호스트는 "This device"로 바꾼다. 데스크톱 로컬 호스트(소켓/파이프)는 이미 "Local"이라고 나오므로, 분기는 "로컬 데몬이면 Local/This device" 하나로 통일할 수 있다 — 업스트림과 맞출지 아키텍트 판단 (§9 Q5).

**라벨.** 자동 등록 시 기본 라벨은 기기 모델명(`Build.MODEL`, 예: "Pixel 8"). 사용자는 기존 Appearance 섹션에서 이름·색을 바꾼다. "This device"를 라벨로 쓰지 않는 이유: 데스크톱 Paseo가 같은 데몬에 붙을 때도 이 라벨(데몬 쪽 hostname이 아니라 클라이언트 쪽 라벨이지만)과 구분되는 이름이 필요하고, 데스크톱에서 "This device"는 틀린 말이 된다.

**상태 점.** `HostStatusDot` 그대로. Linux가 멈추면 연결이 끊기므로 기존 offline 표현이 그대로 나온다.

**Danger zone.** 이 기기 호스트에서는:

- "Remove host"를 숨긴다 (지워도 다음 시작에 다시 등록되므로 의미 없음).
- "Restart daemon"(기존 `RestartDaemonCard`)은 둔다.
- "Reinstall Linux"를 추가한다:

```
Danger zone
┌──────────────────────────────────────────────┐
│ Restart daemon                    [Restart]  │ (기존)
│ Restarts the daemon process. ...             │
│──────────────────────────────────────────────│
│ Reinstall Linux                [Reinstall]   │ ← outline sm
│ Deletes Linux and installs it again          │
└──────────────────────────────────────────────┘
```

`confirmDialog({ destructive: true })`:

- title: "Reinstall Linux?"
- message: "Everything inside Linux is deleted, including projects in your home folder and installed agents. The exchange folder is kept."
- confirm: "Delete and reinstall"

확인 후 화면 A(설치)로 간다.

| key                                            | en                                                                                                                            |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `settings.host.thisDevice`                     | This device                                                                                                                   |
| `settings.host.linux.reinstall.title`          | Reinstall Linux                                                                                                               |
| `settings.host.linux.reinstall.hint`           | Deletes Linux and installs it again                                                                                           |
| `settings.host.linux.reinstall.action`         | Reinstall                                                                                                                     |
| `settings.host.linux.reinstall.confirmTitle`   | Reinstall Linux?                                                                                                              |
| `settings.host.linux.reinstall.confirmMessage` | Everything inside Linux is deleted, including projects in your home folder and installed agents. The exchange folder is kept. |
| `settings.host.linux.reinstall.confirm`        | Delete and reinstall                                                                                                          |

### 8.2 사이드바 콜아웃 — Linux 정지

Linux가 `stopped` 또는 `error`이고 사용자가 앱 안에 있을 때, 앱 어디서든 보이는 신호가 필요하다 (워크스페이스 화면은 "호스트 오프라인"만 말하고 왜인지는 모른다). 기존 `useSidebarCallouts`에 소스 하나를 등록한다 (`worktree-setup-callout-source.tsx`와 같은 모양).

```
┌ Linux is stopped ──────────────────┐
│ Agents on this phone are offline   │
│ [Start]            [Details]       │
└────────────────────────────────────┘
```

| 상태                    | title                                   | description                      | actions         |
| ----------------------- | --------------------------------------- | -------------------------------- | --------------- |
| stopped (사용자가 멈춤) | Linux is stopped                        | Agents on this phone are offline | Start / Details |
| error                   | Linux stopped unexpectedly              | Agents on this phone are offline | Start / Details |
| starting                | (콜아웃 유지, Start 버튼 "Starting...") |                                  |                 |
| running                 | 콜아웃 제거                             |                                  |                 |

Details → Settings › Host › 이 기기. 닫기(dismiss)는 지원하지 않는다 — 정지 상태가 끝나면 스스로 사라진다.

| key                                          | en                               |
| -------------------------------------------- | -------------------------------- |
| `sidebar.callouts.linuxStopped.title`        | Linux is stopped                 |
| `sidebar.callouts.linuxStopped.crashedTitle` | Linux stopped unexpectedly       |
| `sidebar.callouts.linuxStopped.description`  | Agents on this phone are offline |
| `sidebar.callouts.linuxStopped.start`        | Start                            |
| `sidebar.callouts.linuxStopped.starting`     | Starting...                      |
| `sidebar.callouts.linuxStopped.details`      | Details                          |

### 8.3 판정 하나: `isPhoneRuntime`

모든 추가 UI의 게이트. `Platform.OS === "android"`가 아니라 "이 빌드에 폰 런타임 네이티브 모듈이 있다"로 판정한다 (같은 앱 코드가 일반 Android 빌드로도 나갈 수 있으므로). `@/constants/platform`의 다른 게이트와 같은 자리에 두되, 파일 분리(`.android.tsx`)로 대부분은 런타임 분기 없이 끝나게 한다.

---

## 9. 아키텍트에게 넘기는 질문

UI가 코어 레이어에서 받아야 하는 것과, 디자인이 정할 수 없는 것.

**Q1. 데스크톱 Paseo 경로 = Remote SSH로 확정해도 되나?** 데몬을 `127.0.0.1`에만 열고 데스크톱은 기존 Remote SSH 연결로 터널링. 그러면 데몬 비밀번호·LAN 바인딩 UI가 통째로 없어진다. 확인할 것: Electron의 SSH 전송이 폰 sshd(proot 안 OpenSSH, 비표준 포트)와 붙는지, 터널이 `daemonPort` 쿼리를 그대로 쓰는지. 안 되면 Direct connection(LAN 바인딩 + 비밀번호) 경로로 Remote access 섹션을 다시 그려야 한다.

**Q2. UI가 받을 런타임 상태 모양.** 제안 (단일 구독 스냅샷, forms.md의 load-state 원칙에 맞춤):

```ts
type PhoneRuntimeState =
  | { status: "checking" }
  | {
      status: "setup";
      mode: "install" | "update";
      step: SetupStep;
      progress: SetupProgress | null;
      version?: string;
    }
  | { status: "setup-failed"; step: SetupStep; message: string }
  | {
      status: "ready";
      linux: LinuxState;
      daemon: ServiceState;
      ssh: SshState;
      keepAwake: boolean;
      batteryOptimized: boolean;
      distro: string;
      addresses: NetworkAddress[];
      exchange: ExchangeState;
    };

type SetupStep = "download" | "extract" | "configure" | "packages" | "paseo" | "start";
type SetupProgress = { done: number; total: number; unit: "bytes" | "packages" };
type LinuxState = "starting" | "running" | "stopping" | "stopped" | { error: string };
type ServiceState = "starting" | "running" | "stopped" | { error: string };
type SshState = { enabled: boolean; port: number; state: ServiceState };
type NetworkAddress = { kind: "wifi" | "tailscale"; address: string; dnsName?: string };
type ExchangeState = {
  phonePathLabel: string;
  linuxPath: string;
  status: "mounted" | "no-permission" | { error: string };
};
```

명령: `start()`, `stop()`, `retrySetup()`, `reinstall()`, `setSshEnabled(b)`, `setKeepAwake(b)`, `openBatterySettings()`, `openExchangeFolder()`, `requestStoragePermission()`, `listAuthorizedKeys()`, `addAuthorizedKey(text)`, `removeAuthorizedKey(fingerprint)`, `getLogs(kind: "daemon" | "ssh" | "setup")`.

**Q3. 설치 Retry 범위.** 실패한 단계부터인가, 처음부터인가? Kai는 실패 시 부분 rootfs를 지운다. 다운로드 받은 아카이브를 캐시로 남기면 Retry가 빠르다. 결정에 따라 `linuxSetup.failedDescription` 문구가 바뀐다.

**Q4. 실행 중 에이전트 수.** 알림 title과 Stop 확인 문구에 필요. 네이티브 서비스가 데몬에 직접 물을지(로컬 HTTP/WS), 앱이 알려줄지(앱이 죽어 있으면 못 함).

**Q5. 이 기기 판정의 본체 접점.** `useLocalDaemonServerId`를 Electron/폰 두 구현으로 나누는 방식(`.android.ts` 분리 vs 런타임 분기). 그리고 `formatActiveConnectionLabel`의 "This device" 분기를 업스트림 친화적으로 넣을 방법.

**Q6. 이 기기 호스트의 "Pair device" 숨김.** `HOST_SECTION_ITEMS`는 호스트별 필터가 없다. 숨기려면 본체 수정이 하나 더 생긴다. 대안: 데몬 설정에서 릴레이를 꺼 두고 섹션은 그대로 둔다 (사용자가 들어가면 릴레이 동의 화면이 보이지만 누르지 않으면 그만). 디자인 권장은 숨김, 비용이 크면 대안 수용.

**Q7. SSH 사용자 이름과 포트.** proot 안 `root`인가 별도 사용자인가. 포트 8022 고정인가 설정 가능인가 (설정 가능이면 Remote access에 행 하나 추가). 비밀번호 로그인은 끄는 게 전제 (키만).

**Q8. 경로 표시 문자열.** 교환 폴더의 폰 쪽 경로를 "Internal storage/Paseo"처럼 사람이 읽는 형태로 런타임이 줄 수 있나. 리눅스 쪽 마운트 지점은 `~/exchange`로 정해도 되나.

**Q9. 알림 언어.** 알림 문구를 영어 고정으로 둘지, Android 리소스로 한국어를 같이 둘지. 앱 i18n은 9개 로케일 parity 테스트가 있어서 새 키를 넣으면 모든 로케일 파일에 값이 필요하다 — 영어를 복사해 넣을지, 최소한 `ko`는 번역할지 정해야 한다.

**Q10. 알림 탭 라우팅.** `notification-open`에 새 payload kind를 넣을지, 딥링크로 Settings 호스트 경로를 열지.

**Q11. 부팅 시 자동 시작.** 기획서에 없다. 넣는다면 Linux 섹션에 스위치 한 줄 ("Start on boot")이 추가된다.

**Q12. 설치 용량.** 저장 공간 사전 검사에 쓸 필요 용량, 다운로드 크기. 화면에 실제 숫자가 나간다.

**Q13. 업데이트 판정.** "번들 데몬 버전 ≠ rootfs 안 데몬 버전"이면 시작 시 화면 A 업데이트 모드. Debian 패키지 업그레이드(`apt upgrade`)는 앱이 하지 않고 사용자가 터미널에서 한다 — 이 전제로 맞나.

---

## 10. 다음 사이클 메모 (디자인 담당용)

- 1차 결정 근거: 데스크톱 내장 데몬 UI가 같은 문제를 이미 풀었다 → 같은 자리에 같은 짜임으로. 새 화면 1, 새 시트 1, 새 섹션 3, 콜아웃 소스 1, 알림 1.
- 열려 있는 선택지: Q1(SSH 경로)이 뒤집히면 §5 전체를 Direct connection 기준으로 다시 그린다 (주소 + 포트 6767 + 비밀번호 행 + 비밀번호 설정 시트). 그 경우에도 섹션 자리와 행 짜임은 같다.
- 키 등록: 1차는 붙여넣기만. 실제로 써 보고 불편하면 "교환 폴더의 `.pub` 파일 가져오기"를 시트에 버튼 하나로 추가 검토.
- 검증 필요: Host 페이지가 섹션 6개(Appearance, Linux, Remote access, Exchange folder, Danger zone + 배지)로 길어진다. 실제 기기에서 스크롤 길이를 보고, 길면 Remote access를 호스트 섹션 목록의 별도 항목으로 빼는 안을 검토 (`HOST_SECTION_ITEMS`에 이 기기 전용 항목 — 본체 수정이 늘어나는 비용과 비교).
- 미검토: 태블릿/폴더블 넓은 레이아웃에서 화면 A (스플래시 틀이 가운데 정렬이라 문제없을 것으로 봄), 다크/라이트 양쪽 대비 (기존 토큰만 쓰므로 위험 낮음).
