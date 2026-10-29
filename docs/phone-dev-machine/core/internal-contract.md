# 코어 내부 계약 (코어 개발자 3명 공통)

[architecture.md](../architecture/architecture.md)가 기준이다. 이 문서는 세 코어 개발자가 동시에 작업할 수 있게 서로 맞물리는 지점만 고정한다. 바꿔야 하면 팀장에게 보고하고 이 문서를 먼저 고친다. 1차 리뷰 수정 배정은 [fix-plan.md](../code-reviews/fix-plan.md).

## 소유

| 담당         | 파일 (모두 `packages/app/modules/paseo-phone-runtime/` 기준, 아니면 레포 경로)                                                                                                                                                                                                                                                                         |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| core-linux   | `proot/**`, `THIRD_PARTY_NOTICES.md`, `android/src/main/jniLibs/**`, `android/src/main/java/io/github/ouor/paseophone/runtime/linux/**`, `.../runtime/setup/**`, `android/src/test/**/linux/**`, `android/src/test/**/setup/**`                                                                                                                        |
| core-runtime | `package.json`, `expo-module.config.json`, `index.ts`, `src/**`(`types.ts`, `state-schema.ts`, 테스트, `__fixtures__`), `android/build.gradle`, `android/node-sha256.txt`, `android/src/main/AndroidManifest.xml`, `android/src/main/res/**`, `.../runtime/*.kt`(최상위), `.../runtime/{notify,ssh,exchange,system}/**`, `android/src/test/**`(나머지) |
| core-rootfs  | `android/src/main/assets/phone-runtime/**`, `packages/app/app.config.js`의 `phone` 변형, `scripts/phone/**`                                                                                                                                                                                                                                            |
| ui           | `packages/app/src/phone/**`, `_layout.tsx`·`host-page.tsx`의 `phone-fork:` 줄                                                                                                                                                                                                                                                                          |

남의 파일은 고치지 않는다. 커밋은 자기 경로만: `git commit -m "..." -- <paths>`.

## Kotlin 내부 API (core-linux가 제공, core-runtime이 사용)

패키지 `io.github.ouor.paseophone.runtime.linux` / `.setup`. 아래 시그니처를 그대로 지킨다. 내부 구현은 자유.

```kotlin
object PhoneConstants {                       // architecture §7.5. Kotlin에서 이 값들의 유일한 출처
  const val DAEMON_HOST = "127.0.0.1"
  const val DAEMON_PORT = 6767
  const val SSH_PORT = 2222
  const val GUEST_PASEO_HOME = "/root/.paseo"
  const val GUEST_EXCHANGE = "/root/exchange"
  const val SCRIPT_DIR = "/opt/paseo-phone"
  const val APPLICATION_ID = "io.github.ouor.paseophone"   // app.config.js variants.phone과 같게
  const val URL_SCHEME = "paseo-phone"                      // 같은 곳
  // env 이름: ENV_DAEMON_VERSION, ENV_NODE_VERSION, ENV_SSH_PORT, ENV_DAEMON_LISTEN, ENV_NODENAME
}

enum class RootfsState { ABSENT, EXTRACTED, INSTALLED }  // architecture §2.3

class LinuxPaths(context: Context) {
  val root: File          // filesDir/phone-runtime
  val rootfs: File        // root/rootfs
  val rootfsStaging: File // root/rootfs.staging
  val identity: File      // root/identity
  val tmp: File; val sysdata: File; val logs: File
  val downloads: File     // cacheDir/phone-runtime/downloads
  val stateFile: File     // root/state.json
  fun logFile(kind: String): File   // "daemon" | "ssh" | "setup"
  fun rootfsState(): RootfsState    // 디스크만 본다. state.json을 읽지 않는다
}

object Identity {                            // architecture §2.3 D16
  fun save(paths: LinuxPaths)                // rootfs → identity/ (server-id, ssh_host_*, authorized_keys). 없는 파일은 건너뜀
  fun restoreIfPresent(paths: LinuxPaths)    // identity/ → rootfs, 모드 보존(키 0600), 끝나면 identity/ 삭제
}

data class ProotSpec(
  val command: List<String>,                  // rootfs 안 argv
  val env: Map<String, String> = emptyMap(),  // rootfs 안 env에 추가 (PASEO_PHONE_NODENAME은 자동)
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
  fun stop(graceMs: Long)       // architecture §8.3 정지 순서. 블로킹. 끝나면 로그 핸들 닫힘
  fun killTree()                // 스냅샷한 자손 + proot에 SIGKILL (onDestroy 최후 수단)
  fun waitFor(timeoutMs: Long): Boolean
  fun onExit(listener: (ExitInfo) -> Unit)   // 한 번 호출, 백그라운드 스레드. 우리가 stop/killTree한 경우도 호출
}

data class ExecResult(val exitCode: Int, val timedOut: Boolean, val lastErrorLine: String?)

object ProotExec {
  suspend fun run(                            // 타임아웃·취소 시 ProotProcess.stop(5_000)과 같은 순서로 정리
    context: Context, paths: LinuxPaths, spec: ProotSpec, logFile: File,
    timeoutMs: Long, onLine: (String) -> Unit = {},
  ): ExecResult
}

enum class SetupMode { INSTALL, UPDATE_DAEMON, UPDATE_NODE }

sealed interface SetupEvent {
  data class Step(val step: String) : SetupEvent                                       // "download" | "extract" | "configure" | "packages" | "paseo"
  data class Progress(val done: Long, val total: Long, val unit: String) : SetupEvent  // "bytes" | "packages"
}

sealed interface SetupResult {
  object Success : SetupResult
  data class Failed(
    val step: String,
    val code: String,                 // types.ts SetupErrorCode
    val message: String,
    val freeBytes: Long? = null,      // code == "no-space"일 때 둘 다 채움
    val neededBytes: Long? = null,
  ) : SetupResult
}

class SetupRunner(context: Context, paths: LinuxPaths) {
  suspend fun run(mode: SetupMode, fromStep: String?, onEvent: (SetupEvent) -> Unit): SetupResult
  fun installedDaemonVersion(): String?   // rootfs/usr/local/lib/node_modules/@getpaseo/cli/package.json
  fun installedNodeVersion(): String?

  // rootfs 안 스크립트 실행의 유일한 입구 (architecture §7.5)
  fun installScripts()                                      // assets/phone-runtime/* → rootfs/opt/paseo-phone/ (덮어쓰기)
  fun scriptEnv(): Map<String, String>                      // 공통 env 전부
  fun scriptSpec(name: String, exchangeDir: File? = null): ProotSpec   // /bin/bash /opt/paseo-phone/<name> + scriptEnv()
  suspend fun runScript(name: String, logFile: File, timeoutMs: Long): ExecResult
}
```

- `SetupRunner`는 `download`~`paseo`까지만 한다. `start`(데몬·sshd 기동과 준비 대기)는 core-runtime의 컨트롤러가 한다. 모르는 `fromStep`은 `Failed(step = fromStep, code = "unknown")`.
- `extract`는 `rootfs.staging/`에 풀고 rename한다. `rootfs/`가 이미 있으면 `Failed(code = "unknown", message = "rootfs exists")`. 성공하면 다운로드 캐시를 지우고 `Identity.restoreIfPresent`를 부른다. `rootfs/`를 지우는 코드는 core-linux에 없다.
- `UPDATE_NODE`의 `extract`는 Node tarball만 `rootfs/usr/local`에 다시 푼다(rootfs는 건드리지 않음).
- Node 다운로드의 기대 sha256은 `BuildConfig.NODE_SHA256`. `SHASUMS256.txt`는 받지 않는다.
- 취소(`CancellationException`)는 일반 실패로 바꾸지 않고 그대로 던진다. 컨트롤러가 `cancelled`로 바꾼다.
- `state.json`(`desired`, `sshEnabled`, `keepAwake`, `failedStep`, `version`)은 core-runtime의 `RuntimeStore`만 쓴다. 설치 여부와 버전은 기록하지 않는다.
- `rootfs/` 삭제는 core-runtime의 `reinstall()` 한 곳: 정지 → `Identity.save` → 삭제 → `SetupRunner.run(INSTALL, null)`.
- proot 바이너리는 Kai 산출물, 출처 커밋과 sha256은 `PINS.md`.

## 셸 스크립트 계약 (core-rootfs가 제공, core-linux·core-runtime이 실행)

- `SetupRunner.installScripts()`가 `extract` 뒤에, 그리고 서비스 시작 때마다 `assets/phone-runtime/`을 rootfs `/opt/paseo-phone/`에 덮어쓴다. 실행은 항상 `SetupRunner.scriptSpec`/`runScript`로, 즉 `/bin/bash /opt/paseo-phone/<script>`.
- 공통 env (`scriptEnv()`가 넣는다): `PASEO_DAEMON_VERSION`, `NODE_VERSION`, `PASEO_PHONE_SSH_PORT`, `PASEO_PHONE_DAEMON_LISTEN`(`127.0.0.1:6767`). `PASEO_PHONE_NODENAME`은 `ProotProcess`/`ProotExec`가 넣는다.
- 스크립트에 포트·주소 리터럴을 두지 않는다. 위 env를 쓴다.
- 모든 스크립트는 비대화형이다(`DEBIAN_FRONTEND=noninteractive`, `-y`, stdin 미사용). 성공 0, 실패는 0이 아닌 값과 마지막 stderr 줄에 원인.

| 파일                    | 누가 실행                                         | 하는 일                                                                                                                                                            |
| ----------------------- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `configure.sh`          | SetupRunner `configure`                           | architecture §2.5 configure(`policy-rc.d` 포함). 멱등. 호스트 키는 만들지 않는다                                                                                   |
| `packages.sh`           | SetupRunner `packages`                            | apt 설치. 시작 시 `PASEO_PHONE_TOTAL=<n>` 한 줄, apt의 `Setting up` 줄은 그대로                                                                                    |
| `paseo.sh`              | SetupRunner `paseo`                               | config.json(없을 때만, listen은 env), 데몬은 버전이 다를 때만, 에이전트는 없을 때만 `versions.env` 버전으로, 설치했으면 `npm cache clean --force`, 전역 지침. 멱등 |
| `versions.env`          | `paseo.sh`가 source                               | `CLAUDE_CODE_VERSION`, `CODEX_VERSION`, `OPENCODE_VERSION`                                                                                                         |
| `agent-instructions.sh` | `paseo.sh`, 그리고 core-runtime이 서비스 시작마다 | 세 지침 파일의 관리 블록만 갱신 (§5.3). 출력은 `setup.log`                                                                                                         |
| `run-daemon.sh`         | core-runtime, 장기 실행                           | `cd /root; exec paseo daemon run` (foreground)                                                                                                                     |
| `run-sshd.sh`           | core-runtime, 장기 실행                           | `sshd_config`를 `/etc/ssh/sshd_config.d/phone.conf`로 덮어쓰기 → 없는 호스트 키만 생성 → `exec /usr/sbin/sshd -D -e -p $PASEO_PHONE_SSH_PORT`                      |
| `sshd_config`           | `run-sshd.sh`가 복사                              | architecture §5.1 블록 그대로. `Port` 줄 없음                                                                                                                      |
