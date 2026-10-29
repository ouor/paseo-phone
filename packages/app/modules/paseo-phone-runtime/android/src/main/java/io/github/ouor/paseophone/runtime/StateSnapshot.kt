package io.github.ouor.paseophone.runtime

import io.github.ouor.paseophone.runtime.exchange.ExchangeFolder
import io.github.ouor.paseophone.runtime.linux.PhoneConstants
import io.github.ouor.paseophone.runtime.notify.RuntimeNotification
import io.github.ouor.paseophone.runtime.setup.SetupEvent
import io.github.ouor.paseophone.runtime.setup.SetupMode
import io.github.ouor.paseophone.runtime.system.DeviceChecks
import io.github.ouor.paseophone.runtime.system.NetworkAddresses

/** One service's state: `ServiceState` in src/state-schema.ts. */
internal data class Svc(val state: String, val code: String? = null, val message: String? = null) {
  val active get() = state == "starting" || state == "running"

  fun toMap(): Map<String, Any?> =
    if (code == null) mapOf("state" to state) else mapOf("state" to state, "code" to code, "message" to (message ?: ""))

  companion object {
    val STOPPED = Svc("stopped")
    val STARTING = Svc("starting")
    val RUNNING = Svc("running")
    val STOPPING = Svc("stopping")

    fun error(code: String, message: String) = Svc("error", code, message)
  }
}

internal sealed interface Phase {
  object Checking : Phase

  data class Setup(val mode: SetupMode, val step: String, val progress: SetupEvent.Progress?) : Phase

  data class SetupFailed(
    val mode: SetupMode,
    val step: String,
    val code: String,
    val message: String,
    val canStartPrevious: Boolean,
    val freeBytes: Long? = null,
    val neededBytes: Long? = null,
  ) : Phase

  object Ready : Phase
}

/** Values read from the device and rootfs, refreshed on foreground, network change and the health tick. */
internal data class Env(
  val serverId: String? = null,
  val daemonVersion: String = "",
  val hostKeys: List<String> = emptyList(),
  val addresses: List<NetworkAddresses.Address> = emptyList(),
  val exchange: ExchangeFolder.Status? = null,
  val device: DeviceChecks.Result? = null,
)

internal data class SnapshotInput(
  val phase: Phase,
  val linux: Svc,
  val daemon: Svc,
  val ssh: Svc,
  val store: RuntimeStore.Data,
  val env: Env,
  val exchangeBound: Boolean, // the running proot processes were started with the exchange bind
  val targetVersion: String,
  val distro: String,
)

/**
 * Pure: internal state → the `PhoneRuntimeState` map sent to JS (architecture §7.1). The shape must
 * parse with src/state-schema.ts; StateSnapshotTest checks it against src/__fixtures__/state-*.json.
 */
internal object StateSnapshot {
  fun build(input: SnapshotInput): Map<String, Any?> =
    when (val phase = input.phase) {
      Phase.Checking -> mapOf("status" to "checking")
      is Phase.Setup ->
        mapOf(
          "status" to "setup",
          "mode" to modeName(phase.mode),
          "step" to phase.step,
          "progress" to
            phase.progress?.let { mapOf("done" to it.done.toDouble(), "total" to it.total.toDouble(), "unit" to it.unit) },
          "targetVersion" to input.targetVersion,
        )
      is Phase.SetupFailed -> {
        val base =
          mapOf(
            "status" to "setup-failed",
            "mode" to modeName(phase.mode),
            "step" to phase.step,
            "code" to phase.code,
            "message" to phase.message,
          )
        val space =
          if (phase.freeBytes != null && phase.neededBytes != null) {
            mapOf("space" to mapOf("freeBytes" to phase.freeBytes.toDouble(), "neededBytes" to phase.neededBytes.toDouble()))
          } else {
            emptyMap()
          }
        base + space + mapOf("canStartPrevious" to phase.canStartPrevious)
      }
      Phase.Ready -> ready(input)
    }

  private fun ready(input: SnapshotInput): Map<String, Any?> {
    val env = input.env
    val exchange = env.exchange
    val exchangeStatus =
      when {
        exchange == null -> mapOf("status" to "no-permission")
        exchange.status == "mounted" && input.linux.active && !input.exchangeBound -> mapOf("status" to "restart-required")
        exchange.status == "error" -> mapOf("status" to "error", "message" to (exchange.message ?: ""))
        else -> mapOf("status" to exchange.status)
      }
    val device = env.device
    return mapOf(
      "status" to "ready",
      "linux" to input.linux.toMap(),
      "daemon" to
        input.daemon.toMap() +
          mapOf(
            "serverId" to env.serverId,
            "version" to env.daemonVersion,
            "listen" to PhoneConstants.DAEMON_LISTEN,
          ),
      "ssh" to
        mapOf(
          "enabled" to input.store.sshEnabled,
          "port" to PhoneConstants.SSH_PORT,
          "state" to (if (input.store.sshEnabled) input.ssh else Svc.STOPPED).toMap(),
          "hostKeyFingerprints" to env.hostKeys,
        ),
      "keepAwake" to input.store.keepAwake,
      "distro" to input.distro,
      "addresses" to env.addresses.map { mapOf("kind" to it.kind, "address" to it.address) },
      "exchange" to
        exchangeStatus +
          mapOf(
            "phonePathLabel" to (exchange?.phonePathLabel ?: ""),
            "phonePath" to (exchange?.phonePath ?: ""),
            "linuxPath" to PhoneConstants.GUEST_EXCHANGE,
          ),
      "device" to
        mapOf(
          "batteryOptimized" to (device?.batteryOptimized ?: true),
          "phantomProcessMonitor" to (device?.phantomProcessMonitor ?: "unknown"),
          "notificationsAllowed" to (device?.notificationsAllowed ?: false),
        ),
    )
  }

  /** The notification for this state, or null when the service should show nothing new (design §7). */
  fun notificationView(input: SnapshotInput): RuntimeNotification.View? =
    when (val phase = input.phase) {
      Phase.Checking -> null
      is Phase.Setup ->
        RuntimeNotification.View.Setup(
          update = phase.mode != SetupMode.INSTALL,
          step = phase.step,
          bytesDone = phase.progress?.takeIf { it.unit == "bytes" }?.done,
          bytesTotal = phase.progress?.takeIf { it.unit == "bytes" }?.total,
        )
      is Phase.SetupFailed -> RuntimeNotification.View.SetupFailed
      Phase.Ready ->
        when {
          input.linux.state == "starting" -> RuntimeNotification.View.Starting
          input.linux.state == "stopping" -> RuntimeNotification.View.Stopping
          input.linux.state != "running" -> null
          input.daemon.code == "not-responding" -> RuntimeNotification.View.NotResponding(input.env.serverId)
          else ->
            RuntimeNotification.View.Running(
              serverId = input.env.serverId,
              sshEnabled = input.store.sshEnabled,
              sshPort = PhoneConstants.SSH_PORT,
              keepAwake = input.store.keepAwake,
              addresses = input.env.addresses.map { it.kind to it.address },
            )
        }
    }

  private fun modeName(mode: SetupMode) = if (mode == SetupMode.INSTALL) "install" else "update"
}
