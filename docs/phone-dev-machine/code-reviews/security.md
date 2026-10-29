# 보안 코드 리뷰 (feat/phone-dev-machine)

범위: `assets/phone-runtime/**`, `runtime/ssh/**`, `runtime/exchange/**`, `runtime/notify/**`, 모듈 `AndroidManifest.xml`, `packages/app/src/phone/**`, `linux/Downloader.kt`, `linux/RootfsSpec.kt`, `setup/SetupRunner.kt`, `scripts/phone/build-apk.sh`. 읽기 전용 정적 리뷰이며 기기 확인은 하지 않았다.

기준: architecture.md §5(노출 정책), §12(수용한 위험). "다른 앱이 loopback 데몬(127.0.0.1:6767)에 붙을 수 있다"는 수용한 위험이라 결함으로 세지 않는다.

경로 약어: `M` = `packages/app/modules/paseo-phone-runtime/android/src/main`, `R` = `M/java/io/github/ouor/paseophone/runtime`.

---

## 1. [중상] 모든 파일 접근 권한이 게스트 전체로 넘어간다. 교환 폴더 하나로 끝나지 않는다

- 위치: `M/AndroidManifest.xml:10`, `R/exchange/ExchangeFolder.kt:45-56`, `R/linux/ProotCommand.kt:65`
- 문제: §5.2는 이 권한을 `/storage/emulated/0/Paseo` → `/root/exchange` 바인드용으로 설명한다. 그런데 `MANAGE_EXTERNAL_STORAGE`는 앱 uid 전체에 붙고, proot 게스트 프로세스는 같은 uid로 돈다. proot는 ptrace 경로 변환일 뿐 보안 경계가 아니다(예: 게스트에 바인드된 호스트 `/proc`의 `/proc/<proot pid>/root/storage/emulated/0/...`는 proot가 변환하지 않는 경로라 호스트 루트로 풀린다. 기기 확인 필요). 즉 권한을 주는 순간 게스트 안의 모든 코드가 공유 저장소 전체(DCIM, Download, Documents, 메신저 미디어)를 읽고 쓰고 지울 수 있다.
- 시나리오: 프롬프트 인젝션을 당한 에이전트, 또는 `npm install`의 postinstall 스크립트가 사진·문서를 외부로 보내거나 지운다. 사용자는 "교환 폴더를 켠다"고만 알고 동의했다.
- 수정: (a) UI 권한 요청 문구를 "Linux 안의 모든 프로그램이 휴대폰의 모든 파일에 접근할 수 있게 된다"로 바꾸고 기본은 꺼 둔다(소유자 결정을 §12에 추가). (b) 근본 해결이 필요하면 권한 없는 앱 전용 외부 디렉터리(`getExternalFilesDir`)를 바인드하거나, SAF 트리 URI로 `/Paseo`만 받아 앱이 사설 디렉터리와 동기화한다. 둘 다 2차 범위.
- 담당: core-runtime(+ §12 소유자 결정)

## 2. [중] `allowBackup="true"`로 SSH 호스트 개인키와 에이전트 자격 증명이 백업·이전 대상이 된다

- 위치: `packages/app/app.config.js:130-143`(android 블록에 `allowBackup` 설정 없음) → prebuild 결과 `packages/app/android/app/src/main/AndroidManifest.xml:21` `android:allowBackup="true"`. `dataExtractionRules`/`fullBackupContent` 없음.
- 문제: rootfs는 `filesDir/phone-runtime/rootfs`에 있다. 여기에 `etc/ssh/ssh_host_*_key`(호스트 개인키), `root/.ssh/authorized_keys`, `root/.claude/.credentials.json`, `root/.codex/auth.json` 같은 에이전트 토큰이 들어간다. 클라우드 자동 백업은 25 MB 한도로 대개 건너뛰지만, 기기 간 이전(D2D)에는 크기 한도가 없고, minSdk 29라 Android 10/11 기기에서는 `adb backup`도 이 앱을 포함한다.
- 시나리오: 잠금 해제된 폰을 잠깐 손에 넣은 사람이 `adb backup`으로 호스트 개인키를 뽑아 데스크톱 SSH 연결을 가로채거나(MITM, `BatchMode` 호스트 키 검증 무력화), 에이전트 API 토큰을 가져간다. 새 폰으로 옮길 때 같은 호스트 키·토큰이 복제된다.
- 수정: 폰 변형에서만 `android.allowBackup: false`를 app.config.js에 넣거나, 모듈 매니페스트에 `dataExtractionRules`/`fullBackupContent`로 `files/phone-runtime`, `cache/phone-runtime`을 제외한다.
- 담당: core-runtime(manifest)

## 3. [중] 에이전트 CLI와 데몬 의존성이 버전 고정 없이 root로 설치된다

- 위치: `M/assets/phone-runtime/paseo.sh:39-44`
- 문제: `@anthropic-ai/claude-code`, `@openai/codex`, `opencode-ai`는 버전 없이 설치 시점의 `latest`를 받는다. `@getpaseo/cli@$PASEO_DAEMON_VERSION`은 최상위만 고정이고 전이 의존성은 lockfile 없이 범위로 풀린다(`npm install -g`는 패키지가 `npm-shrinkwrap.json`을 싣지 않으면 lock을 쓰지 않는다). 설치 스크립트는 게스트 root, 즉 앱 uid 전체 권한(1번의 저장소 권한 포함)으로 돈다.
- 시나리오: 설치·재설치 시점에 레지스트리에 올라간 악성 버전(계정 탈취, 의존성 하이재킹)이 그대로 실행된다. 같은 APK라도 설치 날짜마다 다른 코드가 깔려 재현도 안 된다.
- 수정: 에이전트 버전을 데몬처럼 빌드 시점 상수(BuildConfig 또는 `.tool-versions`)로 고정해 `pkg@x.y.z`로 설치한다. 설치 단계에 `--ignore-scripts`가 가능한 패키지는 붙인다. 데몬은 `@getpaseo/cli`가 shrinkwrap을 싣는지 확인하고, 없으면 그 사실을 문서에 수용 위험으로 적는다.
- 담당: core-rootfs

## 4. [중하] Node 압축 파일의 sha256이 고정값이 아니다

- 위치: `R/setup/SetupRunner.kt:133-135`, `R/linux/RootfsSpec.kt:28`, `R/linux/TarExtractor.kt:31-32`
- 문제: rootfs는 `RootfsSpec.SHA256`으로 고정했지만 Node는 같은 서버의 `SHASUMS256.txt`를 받아 거기서 해시를 꺼낸다. 서명(`SHASUMS256.txt.sig`) 검증도 없다. 그래서 Node의 무결성은 nodejs.org TLS에만 기댄다. `TarExtractor`는 "아카이브가 sha256으로 고정돼 있다"는 전제로 심볼릭 링크를 따라가는 쓰기를 막지 않는데, Node 아카이브에는 그 전제가 성립하지 않는다.
- 시나리오: nodejs.org 배포 경로가 오염되면(미러·CDN 사고) 변조된 `node`와 해시가 함께 내려오고, 검증을 통과해 `/usr/local`에 풀린다. 이후 모든 에이전트·데몬이 이 바이너리로 돈다.
- 수정: `.tool-versions`의 Node 버전 옆에 linux-arm64 tarball sha256을 두고 build.gradle에서 `NODE_SHA256` BuildConfig로 넘겨 `Downloader.download`에 직접 쓴다. `fetchText`/`sha256From` 경로는 지운다.
- 담당: core-linux

## 5. [중하] sshd가 에이전트 포워딩과 유닉스 소켓 포워딩을 기본값(허용)으로 둔다

- 위치: `M/assets/phone-runtime/sshd_config:1-8`
- 문제: `AllowAgentForwarding`, `AllowStreamLocalForwarding`을 지정하지 않아 Debian 기본값 `yes`가 적용된다. 게스트는 단일 root 사용자라 포워딩된 에이전트 소켓(`/tmp/ssh-*/agent.*`)을 게스트 안의 모든 프로세스가 쓸 수 있다.
- 시나리오: 데스크톱 `~/.ssh/config`에 `ForwardAgent yes`가 있는 사용자가 폰에 접속한 동안, 프롬프트 인젝션된 에이전트가 그 소켓으로 사용자의 GitHub·서버 키를 써서 다른 호스트에 인증한다.
- 수정: `phone.conf`에 `AllowAgentForwarding no`, `AllowStreamLocalForwarding no`, `PermitTunnel no`, `GatewayPorts no`를 명시한다. 데몬 터널만 필요하면 `PermitOpen 127.0.0.1:6767`도 고려한다(셸이 있으니 우회 가능한 방어선이지만 VS Code Remote-SSH가 쓰는 동적 포트까지 막으므로 QA 후 결정).
- 담당: core-rootfs

## 6. [하] 키 삭제가 열린 SSH 세션과 터널을 끊지 않는다

- 위치: `R/ssh/AuthorizedKeys.kt:38-43`, `R/RuntimeController.kt:261`
- 문제: sshd는 인증 시점에만 `authorized_keys`를 읽는다. 키를 지워도 그 키로 열린 셸과 `-W 127.0.0.1:6767` 터널은 살아 있다. UI는 삭제로 접근이 끊긴 것처럼 보인다.
- 시나리오: 잃어버린 노트북의 키를 지웠는데, 그 노트북에 열려 있던 데스크톱 Paseo 연결이 계속 데몬을 조종한다.
- 수정: 키 삭제 후 sshd를 재시작한다(proot `--kill-on-exit`로 자식 세션이 함께 죽는다). 또는 삭제 확인 문구에 "열린 연결은 SSH를 껐다 켜야 끊긴다"를 넣는다.
- 담당: core-runtime(+ ui 문구)

## 7. [하] authorized_keys 갱신이 직렬화되지 않아 삭제한 키가 되살아날 수 있다

- 위치: `R/ssh/AuthorizedKeys.kt:25-54`, `R/RuntimeController.kt:676-679`
- 문제: `add`/`remove`는 읽기 → 수정 → 고정 이름 `authorized_keys.tmp`에 쓰기 → rename이다. `keys {}`는 `Dispatchers.IO`에서 잠금 없이 돈다. 동시에 들어온 add와 remove가 서로의 결과를 덮는다.
- 시나리오: remove(K1)와 add(K2)가 겹치면 add가 읽은 옛 목록 [K1]에 K2를 붙여 써서 K1이 다시 허용된다. UI에서 동시에 일어날 가능성은 낮다.
- 수정: `AuthorizedKeys` 호출을 `Mutex` 하나로 감싼다.
- 담당: core-runtime

## 8. [하] 붙여넣은 키 줄의 옵션 접두어를 그대로 받는다

- 위치: `R/ssh/AuthorizedKeys.kt:30-34`, `:76`
- 문제: `parse`는 줄 어디에서든 첫 키 타입 토큰을 찾으므로 `command="..."`, `from=`, `permitopen=` 같은 옵션이나 아무 문자열이 앞에 붙은 줄도 통과한다. 개행 주입은 `lines()` + `singleOrNull()`로 막혀 있다.
- 시나리오: 웹 페이지에서 복사한 "공개키"에 `command="curl …|sh"`가 붙어 있으면 그 키로 접속할 때마다 실행된다. 옵션이 잘못된 줄은 sshd가 무시해 "등록됐는데 안 되는" 키가 된다.
- 수정: `add`에서는 `tokens[0]`이 키 타입일 때만 받는다(옵션 줄은 `invalid-key`). `parse`의 관대한 동작은 기존 줄 목록용으로 남긴다.
- 담당: core-runtime

## 9. [하] proot 바이너리가 제3자 저장소의 사전 빌드본이고, 재빌드 스크립트는 talloc을 검증 없이 받는다

- 위치: `packages/app/modules/paseo-phone-runtime/proot/PINS.md:11-21`, `proot/build-proot.sh:126`
- 문제: `jniLibs/arm64-v8a/*.so`는 Kai 저장소의 사전 빌드본이다. 해시는 PINS.md와 일치함을 확인했다(3개 모두). 하지만 그 해시는 "Kai가 올린 파일"을 고정할 뿐 소스에서 재현된 값이 아니다. 재빌드 경로의 `curl -sL "$TALLOC_URL"`은 체크섬 확인이 없다.
- 시나리오: Kai 저장소가 오염된 시점의 바이너리를 가져왔다면 앱 uid로 도는 모든 것이 영향을 받는다. 재빌드 시 talloc 미러가 바뀌면 그대로 섞인다.
- 수정: `build-proot.sh`에 talloc tarball sha256 확인을 넣는다. Linux/WSL 환경이 생기면 로컬 재빌드해 PINS.md를 재현 가능한 해시로 바꾼다.
- 담당: core-rootfs

## 10. [정보] 게스트의 에이전트는 SSH 접근을 스스로 늘릴 수 있다

- 위치: `R/ssh/AuthorizedKeys.kt`(파일은 게스트 `/root/.ssh/authorized_keys`), `M/assets/phone-runtime/configure.sh:31`
- 문제가 아니라 구조: 에이전트는 게스트 root라 `authorized_keys`, `sshd_config.d/phone.conf`, `/root/.paseo/config.json`(`listen`)을 고칠 수 있다. 프롬프트 인젝션 한 번이 영구 원격 접근이 된다. 앱은 목록을 매번 파일에서 읽으므로 UI에는 드러난다.
- 권고: 문서(§5)에 수용 위험으로 한 줄 적는다. 원하면 서비스 시작 때 `phone.conf`를 자산에서 다시 써서 설정 변조만이라도 되돌린다(`installScripts`가 이미 매번 돈다).
- 담당: core-runtime

## 11. [정보] sshd가 모든 인터페이스(셀룰러 포함)에서 열린다

- 위치: `M/assets/phone-runtime/run-sshd.sh:8`, `sshd_config`(ListenAddress 없음)
- §5.1이 `0.0.0.0`을 의도로 정했다. 다만 IPv6 기본값 `::`까지 포함되고, 일부 통신사는 셀룰러에 공인 IPv6를 준다. 그러면 2222가 인터넷에 직접 노출된다. 키 인증만이라 무차별 대입은 막히지만 사전 인증 취약점 노출면이 생긴다.
- 권고: 문서에 셀룰러/IPv6 노출을 명시하거나, `MaxStartups 3:50:10`, `LoginGraceTime 20` 정도를 추가한다.
- 담당: core-rootfs

## 12. [정보, 업스트림] 다른 앱이 `paseo-phone://…#connect=` 링크로 호스트 추가를 시킬 수 있다

- 위치: `packages/app/src/app/_layout.tsx:612-623`, 생성 매니페스트 `MainActivity` 인텐트 필터(`android:scheme="paseo-phone"`)
- 업스트림 `OfferLinkListener`는 `#offer=`/`#connect=`가 든 URL을 확인 없이 `importConnectionLink`한다. 포크가 새 스킴을 열면서 이 경로도 그대로 노출된다. 로컬 데몬을 노출하지는 않으므로 포크 범위의 결함은 아니다. 업스트림 동작 그대로임을 기록해 둔다.
- 담당: ui(참고)

---

## 확인했고 문제없는 것

- `RuntimeService`는 `exported="false"`이고, 알림 액션은 명시적 `PendingIntent.getService` + `FLAG_IMMUTABLE`이다(`R/RuntimeService.kt:159-165`). 다른 앱이 데몬 재시작·중지를 직접 보낼 수 없다.
- 알림 딥링크 `?confirmStop=1`은 `handleStop`의 `confirmDialog`를 거친다(`packages/app/src/phone/linux-section.android.tsx:103-118, 149-153`). 다른 앱이 같은 링크를 보내도 확인 창만 뜬다. `?logs=`는 `getLogs`가 세 종류 외에는 거부한다. 재설치는 버튼 + 확인 창으로만 실행되고 딥링크 경로가 없다.
- 데몬 설정은 `127.0.0.1:6767`, relay 꺼짐(`paseo.sh:22`). 브라우저발 DNS 리바인딩은 업스트림 Host/Origin 검사(SECURITY.md)가 맡는다.
- sshd: `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin prohibit-password`, `X11Forwarding no`, 원격 포워딩 차단(`AllowTcpForwarding local`). Debian 기본 `sshd_config`의 맨 앞 `Include`로 `phone.conf`가 우선한다.
- 호스트 키는 rootfs 설치 후 기기에서 생성(`ssh-keygen -A`)되며, 고정 해시 rootfs에 들어 있지 않다. UI가 지문을 보여 준다(`R/ssh/HostKeys.kt`).
- 개인키 붙여넣기는 거부되고 입력이 비워지며, 붙여넣은 텍스트는 로그·상태·에러 메시지에 남지 않는다(`AuthorizedKeys.kt:26-28`, `authorized-keys-sheet.android.tsx:61-84`). `authorized_keys`는 0600, `.ssh`는 0700.
- 셸 스크립트: 모두 `set -eu(o pipefail)`, 변수는 인용됨. 외부에서 들어오는 값은 `PASEO_PHONE_NODENAME`뿐이고 `ProotCommand.nodename`이 `[a-z0-9-]`로 정리한다. `PASEO_DAEMON_VERSION`은 빌드 상수.
- rootfs 다운로드: URL·sha256·크기 고정(`RootfsSpec.kt:12-15`), 검증 실패 시 `.part` 삭제, 캐시 재사용 시에도 재검증. `HttpURLConnection`은 https→http 리다이렉트를 따르지 않는다. apt는 Debian 서명 저장소.
- `TarExtractor`는 `..` 경로를 버리고, 일반 파일은 기존 심볼릭 링크를 지운 뒤 쓴다. `LinuxPaths.deleteTree`는 링크를 따라가지 않아 재설치가 교환 폴더를 지우지 않는다.
- 교환 폴더(FUSE)는 심볼릭 링크를 만들 수 없어 링크 기반 공격이 성립하지 않는다.
