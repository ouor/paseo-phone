# 코어 내부 계약 (코어 개발자 3명 공통)

[architecture.md](../architecture/architecture.md)가 기준이다. 이 문서는 세 코어 개발자가 동시에 작업할 수 있게 서로 맞물리는 지점만 고정한다. 바꿔야 하면 팀장에게 보고하고 이 문서를 먼저 고친다.

## 소유

| 담당         | 파일 (모두 `packages/app/modules/paseo-phone-runtime/` 기준, 아니면 레포 경로)                                                                                                                                                              |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| core-linux   | `proot/**`, `THIRD_PARTY_NOTICES.md`, `android/src/main/jniLibs/**`, `android/src/main/java/io/github/ouor/paseophone/runtime/linux/**`, `.../runtime/setup/**`                                                                             |
| core-runtime | `package.json`, `expo-module.config.json`, `index.ts`, `src/types.ts`, `android/build.gradle`, `android/src/main/AndroidManifest.xml`, `android/src/main/res/**`, `.../runtime/*.kt`(최상위), `.../runtime/{notify,ssh,exchange,system}/**` |
| core-rootfs  | `android/src/main/assets/phone-runtime/**`, `packages/app/app.config.js`의 `phone` 변형, `scripts/phone/**`                                                                                                                                 |

남의 파일은 고치지 않는다. 커밋은 자기 경로만: `git commit -m "..." -- <paths>`.

## Kotlin 내부 API (core-linux가 제공, core-runtime이 사용)

패키지 `io.github.ouor.paseophone.runtime.linux` / `.setup`. 아래 시그니처를 그대로 지킨다. 내부 구현은 자유.

```kotlin
class LinuxPaths(context: Context) {
  val root: File        // filesDir/phone-runtime
  val rootfs: File      // root/rootfs
  val tmp: File; val sysdata: File; val logs: File
  val downloads: File   // cacheDir/phone-runtime/downloads
  val stateFile: File   // root/state.json
  fun logFile(kind: String): File   // "daemon" | "sshd" | "setup"
  fun isRootfsPresent(): Boolean
}

data class ProotSpec(
  val command: List<String>,                  // rootfs 안에서 실행할 argv, 예: listOf("/bin/bash", "/opt/paseo-phone/run-daemon.sh")
  val env: Map<String, String> = emptyMap(),  // rootfs 안 프로세스 env에 추가
  val exchangeDir: File? = null,              // null이 아니면 /root/exchange로 bind
  val cwd: String = "/root",
)

data class ExitInfo(val exitCode: Int, val signal: Int?)   // SIGKILL이면 signal = 9 (또는 exitCode 137)

class ProotProcess private constructor(...) {
  companion object {
    fun start(context: Context, paths: LinuxPaths, spec: ProotSpec, logFile: File): ProotProcess
  }
  val pid: Int
  fun isAlive(): Boolean
  fun destroy()                 // SIGTERM
  fun destroyForcibly()         // SIGKILL
  fun waitFor(timeoutMs: Long): Boolean
  fun onExit(listener: (ExitInfo) -> Unit)   // 한 번 호출, 백그라운드 스레드
}

data class ExecResult(val exitCode: Int, val timedOut: Boolean, val lastErrorLine: String?)

object ProotExec {
  suspend fun run(
    context: Context, paths: LinuxPaths, spec: ProotSpec, logFile: File,
    timeoutMs: Long, onLine: (String) -> Unit = {},
  ): ExecResult
}

enum class SetupMode { INSTALL, UPDATE_DAEMON, UPDATE_NODE }

sealed interface SetupEvent {
  data class Step(val step: String) : SetupEvent                                   // "download" | "extract" | "configure" | "packages" | "paseo"
  data class Progress(val done: Long, val total: Long, val unit: String) : SetupEvent  // "bytes" | "packages"
}

sealed interface SetupResult {
  object Success : SetupResult
  data class Failed(val step: String, val code: String, val message: String) : SetupResult  // code: types.ts SetupErrorCode
}

class SetupRunner(context: Context, paths: LinuxPaths) {
  suspend fun run(mode: SetupMode, fromStep: String?, onEvent: (SetupEvent) -> Unit): SetupResult
  fun installedDaemonVersion(): String?   // rootfs/usr/local/lib/node_modules/@getpaseo/cli/package.json
  fun installedNodeVersion(): String?
}
```

- `SetupRunner`는 `download`~`paseo`까지만 한다. `start` 단계(데몬·sshd 기동과 준비 대기)는 core-runtime의 컨트롤러가 한다.
- 설치 마커와 버전 기록(`state.json`)은 core-runtime의 `RuntimeStore`가 쓴다. `SetupRunner`는 결과만 돌려준다.
- proot 바이너리: 1차에서 NDK/WSL 재빌드가 막히면 Kai의 arm64 산출물을 그대로 쓰고 `PINS.md`에 출처 커밋과 sha256을 적는다.

## 셸 스크립트 계약 (core-rootfs가 제공, core-linux·core-runtime이 실행)

- APK `assets/phone-runtime/`의 파일은 `SetupRunner`가 `extract` 뒤에, 그리고 서비스 시작 때마다 rootfs의 `/opt/paseo-phone/`로 복사한다(덮어쓰기). 실행은 항상 `/bin/bash /opt/paseo-phone/<script>`.
- 공통 env (호출하는 쪽이 넣는다): `PASEO_DAEMON_VERSION`, `NODE_VERSION`, `PASEO_PHONE_SSH_PORT=2222`, `PASEO_PHONE_NODENAME`.
- 모든 스크립트는 비대화형이다. 입력을 기다리지 않는다(`DEBIAN_FRONTEND=noninteractive`, `-y`, stdin 미사용). 성공 0, 실패는 0이 아닌 값과 마지막 stderr 줄에 원인.

| 스크립트                | 누가 실행                                         | 하는 일                                                                                                        |
| ----------------------- | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `configure.sh`          | SetupRunner `configure`                           | architecture §2.5 configure. 멱등                                                                              |
| `packages.sh`           | SetupRunner `packages`                            | apt 설치. 진행률용으로 시작 시 `PASEO_PHONE_TOTAL=<n>` 한 줄을 출력하고, apt의 `Setting up` 줄은 그대로 흘린다 |
| `paseo.sh`              | SetupRunner `paseo`                               | config.json(없을 때만), `npm i -g` 데몬과 세 에이전트, 전역 지침. 멱등                                         |
| `agent-instructions.sh` | `paseo.sh`, 그리고 core-runtime이 서비스 시작마다 | 세 지침 파일의 관리 블록만 갱신 (§5.3). architecture의 `exchange/AgentInstructions.kt`를 대신한다              |
| `run-daemon.sh`         | core-runtime, 장기 실행                           | `exec paseo daemon run` (foreground)                                                                           |
| `run-sshd.sh`           | core-runtime, 장기 실행                           | `exec /usr/sbin/sshd -D -e -p $PASEO_PHONE_SSH_PORT` (foreground, 키 로그인만)                                 |
