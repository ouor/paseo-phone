# 업스트림 릴리스 따라가기

이 포크(Paseo Phone)에 새 업스트림 Paseo 릴리스를 들여오는 절차다. 업스트림은 자주 바뀌므로 이것이 주된 유지보수 작업이다. 포크가 업스트림에 기대는 지점의 목록은 [architecture §4.3](architecture/architecture.md#43-paseo-본체-수정과-머지-체크리스트)이 기준이고, 이 문서는 순서만 정한다.

## 원칙

- **업스트림 릴리스 태그(`vX.Y.Z`)만 머지한다. 업스트림 `main`은 머지하지 않는다.** 업스트림은 릴리스를 별도 작업 공간에서 만들기 때문에 릴리스 태그가 업스트림 `main`의 조상이 아니다. `main`을 섞으면 다음 태그 머지가 엉킨다.
- 포크의 `main`은 "업스트림 태그 + Paseo Phone"이다. 업스트림으로 다시 합치지 않는다.
- 포크가 업스트림 파일을 고친 곳은 `phone-fork:` 주석이 붙은 줄과 `README.md`·`README.ko.md`(전부 다시 씀)뿐이다. 나머지 포크 코드는 업스트림에 없는 경로에 있다(`packages/app/modules/paseo-phone-runtime/`, `packages/app/src/phone/`, `scripts/phone/`, `signing/`, `docs/phone-dev-machine/`).
- 릴리스 태그는 `phone-v<업스트림 버전>-<n>`. `v*` 태그는 만들지 않는다. 포크에서 업스트림 릴리스 워크플로가 돈다([architecture §9](architecture/architecture.md#9-빌드-노트-core-개발자)).

## 준비 (클론마다 한 번)

```bash
git remote add upstream https://github.com/getpaseo/paseo.git
git remote set-url --push upstream DISABLED
```

`upstream`은 fetch 전용이다. push URL을 막아 실수로 업스트림에 push하지 않게 한다.

## 절차

예시는 업스트림 `v0.15.0`, 포크의 첫 빌드 `phone-v0.15.0-1`.

1. **태그 가져오기.**
   ```bash
   git fetch upstream tag v0.15.0 --no-tags
   git log --oneline -1 v0.15.0
   ```
2. **동기화 브랜치.** 포크 `main`에서 만든다.
   ```bash
   git switch main && git pull --ff-only origin main
   git switch -c sync/v0.15.0
   ```
3. **태그 머지.**
   ```bash
   git merge --no-ff v0.15.0 -m "merge upstream v0.15.0"
   ```
4. **충돌을 파일별로 푼다.**

   | 파일                                                                 | 방법                                                                                                                                               |
   | -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `README.md`, `README.ko.md`                                          | 우리 것을 남긴다(`git checkout --ours`). 업스트림 README의 변경 중 포크 사용자에게도 해당하는 것(새 에이전트, 요구 사항, 라이선스)만 손으로 옮긴다 |
   | `app.config.js`, `_layout.tsx`, `host-page.tsx`                      | 업스트림 버전을 받고 `phone-fork:` 줄을 다시 넣는다. 줄 수는 §4.3 표                                                                               |
   | `scripts/phone/build-apk.sh`와 업스트림 `scripts/build-apk.mjs` 차이 | 포크 파일이라 충돌은 없다. 대신 `git diff v0.14.0 v0.15.0 -- scripts/build-apk.mjs`를 보고 단계·env가 바뀌었으면 `build-apk.sh`에 옮긴다           |
   | 그 밖의 업스트림 파일                                                | 포크가 고치지 않았으므로 충돌하면 무언가 잘못 들어온 것이다. 업스트림 것을 받는다(`git checkout --theirs`)                                         |

5. **포크 접점 확인.**
   ```bash
   rg -n "phone-fork:" packages/app scripts
   ```
   결과가 [§4.3](architecture/architecture.md#43-paseo-본체-수정과-머지-체크리스트) 표의 수와 같아야 한다. 그다음 §4.3 "암묵적 계약" 표를 위에서부터 훑는다. 각 행의 위치 파일이 이번 릴리스에서 바뀌었는지는 `git diff v0.14.0 v0.15.0 --stat -- <경로>`로 본다. 바뀐 행만 내용을 읽는다.
6. **버전과 고정값.**
   - 데몬: `npm view @getpaseo/cli@$(node -p "require('./packages/server/package.json').version") version`이 그 버전을 출력해야 한다.
   - Node: `.tool-versions`의 `nodejs`가 바뀌었으면 `packages/app/modules/paseo-phone-runtime/android/node-sha256.txt`에 새 줄을 넣는다. nodejs.org의 `SHASUMS256.txt`와 서명으로 `node-v<버전>-linux-arm64.tar.xz` 값을 확인한다. 줄이 없으면 빌드가 실패한다([§3.1](architecture/architecture.md#31-설치-경로와-버전)).
   - 에이전트 CLI: `assets/phone-runtime/versions.env`는 올려도 되고 그대로 둬도 된다. 올리면 새 설치에만 적용된다.
7. **설치와 검사.**
   ```bash
   npm install
   npm run typecheck
   npm run lint
   npx vitest run packages/app/modules/paseo-phone-runtime/src packages/app/src/phone --bail=1
   ```
   타입 오류가 다른 워크스페이스 쪽이면 `npm run build:server`로 선언 파일을 먼저 다시 만든다. 커밋하고 트리를 깨끗하게 둔다.
8. **빌드.** Kotlin 단위 테스트와 서명이 함께 돈다.
   ```bash
   bash scripts/phone/build-apk.sh --install
   ```
   같은 서명 키를 쓴다([signing/SIGNING.md](../../signing/SIGNING.md)). 사용법과 단계는 [core/core-rootfs.md](core/core-rootfs.md).
9. **기기 QA.** 셋 다 통과해야 한다. 이전 결과는 [qa/qa-report.md](qa/qa-report.md).
   - **업데이트 경로:** 이전 릴리스 APK로 설치·실행 중인 폰에 새 APK를 덮어 설치한다. 앱이 버전 차이를 감지해 업데이트를 돌리고(§2.5), `/root`의 레포·에이전트 로그인, 호스트 등록, SSH 키가 그대로인지 본다.
   - **새 설치:** 앱 데이터를 지우거나 Reset Linux 후 처음부터 설치한다(약 6분).
   - **스모크:** 이 기기 호스트 연결, 에이전트 하나 실행, 데스크톱 Remote SSH 연결, 교환 폴더, Stop/Start, 알림 탭.
10. **main에 합치기.** 확인한 커밋을 그대로 `main`에 fast-forward로 올린다.
    ```bash
    git switch main && git merge --ff-only sync/v0.15.0 && git push origin main
    ```
11. **태그와 릴리스.** `phone-v0.15.0-1` 태그를 달고 GitHub pre-release로 APK를 올린다. QA를 통과하면 pre-release를 해제한다. 같은 업스트림 버전에서 포크만 고친 다음 빌드는 `-2`, `-3`.

## 깨질 때

| 증상                                                          | 원인과 조치                                                                                                                                                 |
| ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm view @getpaseo/cli@<버전>`이 비어 있음                   | 업스트림이 아직 npm에 게시하지 않았다. 게시될 때까지 기다린다. 빌드해서 내보내면 폰의 업데이트가 `paseo` 단계에서 실패한다(이전 데몬으로 시작은 됨)         |
| 빌드가 Node sha256 줄이 없다며 실패                           | 6단계의 `node-sha256.txt`를 빠뜨렸다                                                                                                                        |
| 폰 업데이트가 `download`에서 `checksum`으로 실패              | `node-sha256.txt`의 값이 틀렸다. 값을 다시 확인하고 새 빌드를 낸다                                                                                          |
| `rg "phone-fork:"` 수가 다름, 또는 import 경로 typecheck 오류 | 업스트림이 포크가 고친 파일을 옮기거나 이름을 바꿨다. 새 위치에 같은 줄을 넣고 §4.3 표의 경로를 고친다                                                      |
| 설치 후 Welcome 화면, 호스트가 안 보임, 알림 탭이 엉뚱한 곳   | §4.3 암묵적 계약(부트스트랩, 시작 타이머, 라우트)이 바뀌었다. 해당 행의 위치를 읽고 고친다                                                                  |
| `build-apk.sh`가 dirty tree로 거부                            | 커밋하지 않은 파일이 있다. 빌드 중에 트리를 바꾸면 빌드가 실패한다. 빌드하는 동안 같은 체크아웃에서 다른 작업을 하지 않는다                                 |
| `react-native-enriched-markdown` 관련 네이티브 빌드 실패      | npm 11이 install script를 건너뛰었다. 빌드 스크립트가 복구하며, Windows에서는 `C:\Windows\System32\tar.exe`가 필요하다. `package.json`을 고쳐 피하지 않는다 |
| `prebuild` 또는 Gradle 단계 실패                              | 업스트림이 Expo/RN 버전을 올렸다. `artifacts/paseo-phone-build.log`를 보고, 모듈의 `build.gradle`과 Expo 모듈 API를 맞춘다                                  |

## 업스트림이 포크가 기대는 것을 바꿨을 때

1. **[architecture.md](architecture/architecture.md)를 먼저 고친다.** 무엇이 바뀌었고 포크가 어떻게 대응하는지 해당 절(§4.3 행, §3.3 config 키, §7 계약 등)에 쓴다.
2. 코어 사이 시그니처가 바뀌면 [core/internal-contract.md](core/internal-contract.md)도 고친다.
3. 그다음 코드를 문서에 맞춘다. 업스트림 파일을 새로 고쳐야 하면 줄마다 `phone-fork:`를 달고 §4.3 표의 줄 수를 올린다.
4. 업스트림 파일 수정은 마지막 수단이다. 포크 경로(`src/phone/`, 모듈) 안에서 해결할 수 있으면 그렇게 한다.
