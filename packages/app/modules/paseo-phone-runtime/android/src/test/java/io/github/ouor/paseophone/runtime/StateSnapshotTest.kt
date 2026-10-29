package io.github.ouor.paseophone.runtime

import io.github.ouor.paseophone.runtime.exchange.ExchangeFolder
import io.github.ouor.paseophone.runtime.setup.SetupEvent
import io.github.ouor.paseophone.runtime.setup.SetupMode
import io.github.ouor.paseophone.runtime.system.DeviceChecks
import io.github.ouor.paseophone.runtime.system.NetworkAddresses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden snapshots shared with src/state-schema.test.ts (architecture §7.1). A mismatch means Kotlin and
 * the zod schema drifted. Rewrite with `./gradlew :paseo-phone-runtime:testReleaseUnitTest -PupdateFixtures`.
 */
class StateSnapshotTest {
  private val fixturesDir = File(System.getProperty("paseo.fixturesDir") ?: "../src/__fixtures__")
  private val update = System.getProperty("paseo.updateFixtures") == "true"

  private val env =
    Env(
      serverId = "srv_test",
      daemonVersion = "0.14.0",
      hostKeys = listOf("ssh-ed25519 SHA256:8U6KLuWB+vF3OoAwYwDpYUr8pJ0EOpASSW+rGVeTHMQ"),
      addresses = listOf(NetworkAddresses.Address("wifi", "192.168.1.23"), NetworkAddresses.Address("tailscale", "100.101.102.103")),
      exchange = ExchangeFolder.Status("mounted", null, File("/storage/emulated/0/Paseo"), "/storage/emulated/0/Paseo", "Internal storage/Paseo"),
      device = DeviceChecks.Result(batteryOptimized = true, phantomProcessMonitor = "disabled", notificationsAllowed = true),
    )

  private fun input(phase: Phase, linux: Svc = Svc.STOPPED, daemon: Svc = Svc.STOPPED, ssh: Svc = Svc.STOPPED) =
    SnapshotInput(phase, linux, daemon, ssh, RuntimeStore.Data(), env, exchangeBound = true, targetVersion = "0.14.0", distro = "Debian 13")

  @Test
  fun checking() = golden("checking", input(Phase.Checking))

  @Test
  fun setupDownloading() =
    golden(
      "setup-download",
      input(Phase.Setup(SetupMode.INSTALL, "download", SetupEvent.Progress(74_000_000, 182_000_000, "bytes"))),
    )

  @Test
  fun setupFailedNoSpace() =
    golden(
      "setup-failed-no-space",
      input(
        Phase.SetupFailed(SetupMode.INSTALL, "download", "no-space", "Not enough storage", false, 1_000_000_000, 4_000_000_000),
      ),
    )

  @Test
  fun setupFailedStart() =
    golden(
      "setup-failed-start",
      input(Phase.SetupFailed(SetupMode.UPDATE_DAEMON, "start", "command-failed", "Error: listen EADDRINUSE", true)),
    )

  @Test
  fun readyRunning() = golden("ready-running", input(Phase.Ready, Svc.RUNNING, Svc.RUNNING, Svc.RUNNING))

  @Test
  fun readyError() =
    golden(
      "ready-error",
      input(Phase.Ready, Svc.error("restart-limit", "Stopped after 5 failed restarts in 5 minutes."), Svc.STOPPED, Svc.STOPPED),
    )

  @Test
  fun exchangeNeedsRestartWhenProcessesStartedWithoutTheBind() {
    val state = StateSnapshot.build(input(Phase.Ready, Svc.RUNNING, Svc.RUNNING, Svc.RUNNING).copy(exchangeBound = false))
    assertEquals("restart-required", (state["exchange"] as Map<*, *>)["status"])
  }

  private fun golden(name: String, input: SnapshotInput) {
    val actual = toJson(StateSnapshot.build(input), "") + "\n"
    val file = File(fixturesDir, "state-$name.json")
    if (update) {
      file.parentFile.mkdirs()
      file.writeText(actual)
      return
    }
    assertTrue("missing ${file.path}; run with -PupdateFixtures", file.isFile)
    // Whitespace-insensitive so the repo formatter may reflow the fixture files.
    assertEquals("${file.name} drifted; run with -PupdateFixtures and check the TS schema test", canonical(file.readText()), canonical(actual))
  }

  companion object {
    fun toJson(value: Any?, indent: String): String =
      when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        is Map<*, *> ->
          if (value.isEmpty()) "{}"
          else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$indent  ${quote(k as String)}: ${toJson(v, "$indent  ")}" }
        is List<*> ->
          if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${toJson(it, "$indent  ")}" }
        else -> error("unsupported ${value::class}")
      }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Drops whitespace outside string literals. */
    fun canonical(json: String): String {
      val out = StringBuilder()
      var inString = false
      var escaped = false
      for (c in json) {
        if (inString) {
          out.append(c)
          if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else if (c == '"') {
          inString = true
          out.append(c)
        } else if (!c.isWhitespace()) {
          out.append(c)
        }
      }
      return out.toString()
    }
  }
}
