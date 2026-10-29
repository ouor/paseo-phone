<p align="center">
  <img src="packages/website/public/logo.svg" width="64" height="64" alt="Paseo logo">
</p>

<h1 align="center">Paseo Phone</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ko.md">한국어</a>
</p>

<p align="center">
  <a href="https://github.com/ouor/paseo/releases">
    <img src="https://img.shields.io/github/v/release/ouor/paseo?include_prereleases&style=flat&logo=github" alt="Release">
  </a>
  <a href="https://github.com/getpaseo/paseo">
    <img src="https://img.shields.io/badge/upstream-getpaseo%2Fpaseo-555?logo=github" alt="Upstream Paseo">
  </a>
</p>

Paseo Phone은 안드로이드 폰 하나를 개발 머신으로 만듭니다. 폰 안에서 Debian, [Paseo](https://github.com/getpaseo/paseo) 데몬, 코딩 에이전트가 돌아갑니다. 폰 앱이 에이전트 UI이고, 데스크톱은 폰에 접속하는 단말입니다. Paseo의 개인용 포크이며 GitHub Releases로만 배포합니다.

구조: 앱이 proot로 Debian 13을 루트 없이 올립니다. 포그라운드 서비스가 Paseo 데몬(`127.0.0.1:6767`)과 sshd(포트 `2222`)를 계속 띄워 둡니다. 폰의 Paseo 앱은 페어링 없이 이 데몬을 "이 기기"로 봅니다. 데스크톱은 SSH로 접속합니다.

업스트림 Paseo는 데스크톱, 모바일, 웹, CLI용 오픈 소스 에이전트 개발 환경입니다. Claude Code, Codex, Copilot, OpenCode, Pi, Antigravity, Muse Code 에이전트를 한 인터페이스로 다룹니다. Paseo Phone은 Claude Code, Codex, OpenCode를 설치합니다.

## 요구 사항

- arm64 안드로이드 폰. 다른 ABI는 빌드하지 않습니다.
- 여유 공간 약 4 GB. 설치 전에 4 GB를 확인하고, 설치 중 최대 약 3.3 GB, 설치 후 약 2.4 GB를 씁니다.
- phantom process killer 끄기. 켜져 있으면 안드로이드가 데몬, sshd, proot를 죽입니다. adb가 있는 컴퓨터에서:

  ```bash
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```

- 권장: 화면이 꺼져도 작업이 이어지도록 Paseo Phone을 배터리 최적화에서 제외하세요.

앱은 두 설정을 감지해서 경고만 하고, 직접 바꾸지 않습니다.

## 설치

1. [Releases](https://github.com/ouor/paseo/releases)에서 APK를 받으세요. 태그는 `phone-v<업스트림 버전>-<n>`입니다. 첫 릴리스는 `phone-v0.14.0-1`(arm64, 프리릴리스)입니다.
2. 다른 키로 서명된 이전 빌드(초기 미서명/debug 빌드)가 있으면 먼저 삭제하세요. 그대로는 안드로이드가 업데이트를 거부합니다. 삭제하면 `/root` 아래 레포와 에이전트 로그인을 포함한 리눅스 환경이 지워집니다.
3. APK를 설치하세요. 앱 이름은 "Paseo Phone"(`io.github.ouor.paseophone`)이고, 일반 Paseo 앱과 따로 설치됩니다.

## 첫 실행

앱을 여세요. Debian, Node.js, Paseo 데몬, 에이전트 CLI를 받아 설치합니다. 약 6분 걸리고 네트워크가 필요합니다. 단계가 실패하면 화면에 오류와 설치 로그가 나오고, **다시 시도**는 실패한 단계부터 이어 갑니다.

설치가 끝나면 앱이 "이 기기" 호스트로 열립니다. 리눅스 관련 기능은 **설정 › 호스트 › 이 기기 › Host**에 있습니다.

- **Linux**: 시작/멈추기, 데몬 상태와 재시작, SSH 서버 스위치, 화면 꺼짐 유지 스위치, 로그.
- **Remote access**: 접속 주소(Wi-Fi 또는 Tailscale), `ssh` 명령, Paseo 데스크톱 URI, SSH config, 호스트 키 지문, 허용된 키.
- **Exchange folder**: 폴더 열기, 리눅스 경로 복사.
- **Reset Linux**: Debian 다시 설치. 데몬 server ID, SSH 호스트 키, 허용된 키는 유지하고 나머지 `/root`는 지웁니다.

상시 알림에 상태가 나오고, **Stop**과 로그 링크가 있습니다.

## 에이전트 로그인

에이전트 로그인은 폰의 셸에서 합니다. SSH로 접속한 뒤(다음 섹션) 실행하세요.

| 에이전트    | 명령                                         | 비고                                                              |
| ----------- | -------------------------------------------- | ----------------------------------------------------------------- |
| Claude Code | `claude` 실행 후 `/login`                    | proot에는 브라우저가 없습니다. 출력된 URL을 다른 기기에서 여세요. |
| Codex       | `codex login --device-auth`                  |                                                                   |
| OpenCode    | `opencode auth login` (TUI에서는 `/connect`) | 프로바이더별 API 키 또는 OAuth.                                   |

Codex 샌드박스는 proot에서 동작하지 않습니다. Codex 에이전트는 **Full Access**를 고르세요. `/root/.claude/CLAUDE.md`, `/root/.codex/AGENTS.md`, `/root/.config/opencode/AGENTS.md`에 쓰는 전역 에이전트 지침에도 적혀 있습니다.

## 데스크톱에서 접속

sshd는 공개키만 받고, 사용자 `root`, 포트 `2222`, IPv4만 엽니다. 같은 Wi-Fi나 Tailscale을 쓰세요.

1. **Remote access › 허용된 키**에 데스크톱의 공개키를 붙여 넣으세요.
2. 터미널에서 한 번 접속해 호스트 키를 받아들이세요. 앱에 나온 지문과 비교하세요.

   ```bash
   ssh -p 2222 root@<폰 주소>
   ```

3. Paseo 데스크톱: **Remote access**의 URI(`ssh://root@<폰 주소>:2222`)로 Remote SSH 호스트를 추가하세요. 데몬은 loopback에만 열려 있어서 데스크톱 Paseo는 항상 SSH로 붙습니다. 처음 보는 호스트 키는 거부하므로 2단계를 먼저 합니다.
4. VS Code: **Remote access**의 SSH config를 `~/.ssh/config`에 넣고 Remote-SSH로 접속하세요.

데몬에는 비밀번호도 릴레이도 없습니다. 폰에서 `127.0.0.1:6767`에 닿는 것은 다른 앱을 포함해 무엇이든 데몬을 쓸 수 있습니다.

## 교환 폴더

폰의 `/storage/emulated/0/Paseo`가 리눅스의 `/root/exchange`에 마운트됩니다. 리눅스와 다른 앱 사이에 파일을 옮길 때 쓰세요. 레포는 `/root` 아래에 두세요.

- "모든 파일 접근" 권한이 필요합니다. 이 권한은 앱 전체에 붙으므로 리눅스 안의 모든 프로그램이 공유 저장소 전체를 읽고, 쓰고, 지울 수 있습니다. 기본은 꺼져 있습니다.
- 권한을 준 뒤에는 앱의 안내에 따라 리눅스를 재시작하세요.
- 심볼릭 링크, 하드 링크, 실행 비트는 동작하지 않습니다.

## 소스에서 빌드

```bash
bash scripts/phone/build-apk.sh [--skip-tests] [--install] [--device <serial>]
```

깨끗한 작업 트리가 필요합니다. arm64 release APK를 빌드하고, 모듈 단위 테스트를 돌리고, 소유자의 release 키로 서명하고, 인증서를 검증합니다. 결과물은 `artifacts/paseo-phone-arm64.apk`, 전체 로그는 `artifacts/paseo-phone-build.log`입니다. `--install`은 서명이 다르면 삭제하지 않고 멈춥니다.

서명 파일(`signing/release.p12`, `signing/keystore.properties`)은 소유자의 것이고 레포에 없습니다. 없으면 빌드가 처음에 실패합니다. 직접 빌드하려면 자기 키를 쓰고 스크립트의 기대 인증서 해시를 바꾸세요.

## 업스트림 따라가기

이 포크는 업스트림 Paseo 릴리스를 따라가고, 업스트림으로 다시 합치지 않습니다. 새 업스트림 릴리스로 옮기려면 그 태그를 머지하고 [architecture §4.3](docs/phone-dev-machine/architecture/architecture.md)의 머지 체크리스트를 확인하세요. 포크가 업스트림 파일에 넣은 줄에는 `phone-fork:` 표시가 있습니다. `rg -n "phone-fork:" packages/app scripts`로 확인하세요.

데몬은 `packages/server/package.json` 버전의 공개 `@getpaseo/cli`이고, Node.js는 `.tool-versions`를 따릅니다. 머지 후 앱이 다음 시작 때 폰에서 둘을 업데이트합니다. 에이전트 CLI와 apt 패키지는 자동으로 업그레이드하지 않습니다.

## 제한과 하지 않는 것

- arm64만, Debian만. 앱에 리눅스 터미널 화면은 없습니다. SSH를 쓰세요.
- proot에서는 파일 작업이 훨씬 느립니다. 무거운 빌드(안드로이드 Gradle 빌드 등)는 범위 밖입니다.
- 외부 서버, 클라우드, Paseo 릴레이를 통한 접속은 없습니다. 데몬 비밀번호나 LAN에 노출된 데몬도 없습니다.
- 공유 저장소에서의 레포 작업, 양방향 동기화는 없습니다.
- Play 스토어 배포, 일반 사용자용 온보딩, CI는 없습니다.
- 부팅 시 자동 시작은 없습니다. 에이전트 CLI와 apt 패키지 업그레이드는 직접 합니다.
- 원격 푸시 알림은 없습니다(Firebase 없음).

## 문서

설계와 진행 문서는 [docs/phone-dev-machine/](docs/phone-dev-machine/)에 있습니다.

- [original-plan.md](docs/phone-dev-machine/original-plan.md): 무엇을, 왜, 하지 않을 것
- [architecture/architecture.md](docs/phone-dev-machine/architecture/architecture.md): 결정, 포트, 경로, 머지 체크리스트
- [architecture/spike-report.md](docs/phone-dev-machine/architecture/spike-report.md): proot에서의 에이전트, phantom process killer, 속도
- [core/core-rootfs.md](docs/phone-dev-machine/core/core-rootfs.md): 설치 스크립트, 빌드 스크립트, 용량
- [ui/ui.md](docs/phone-dev-machine/ui/ui.md): 앱에서 각 기능의 위치
- [qa/qa-report.md](docs/phone-dev-machine/qa/qa-report.md): QA 결과

업스트림 Paseo 문서는 [paseo.sh/docs](https://paseo.sh/docs)에 있습니다.

## 크레딧과 라이선스

- [Paseo](https://github.com/getpaseo/paseo), Mohamed Boudra: 데몬, 앱, 에이전트 연동. 이 포크는 Paseo 릴리스를 따라갑니다.
- [Kai](https://github.com/SimonSchubert/Kai), Simon Schubert: 리눅스 레이어(proot 빌드와 런타임 코드)를 Kai에서 옮겨 왔습니다.
- [termux/proot](https://github.com/termux/proot), [termux/proot-distro](https://github.com/termux/proot-distro): proot와 Debian 루트 파일시스템.

Paseo는 Apache-2.0 라이선스입니다. [LICENSE](LICENSE)를 보세요. Kai는 Apache-2.0, PRoot는 GPL-2.0, talloc은 LGPL-3.0입니다. 자세한 내용과 출처는 [THIRD_PARTY_NOTICES.md](packages/app/modules/paseo-phone-runtime/THIRD_PARTY_NOTICES.md)에 있습니다.
