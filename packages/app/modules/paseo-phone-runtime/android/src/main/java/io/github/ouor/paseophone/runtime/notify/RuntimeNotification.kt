package io.github.ouor.paseophone.runtime.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.github.ouor.paseophone.runtime.R
import io.github.ouor.paseophone.runtime.linux.PhoneConstants

/** The one foreground-service notification on channel `linux_runtime` (architecture §7.4, design §7). */
object RuntimeNotification {
  const val CHANNEL_ID = "linux_runtime"
  const val NOTIFICATION_ID = 6767

  sealed interface View {
    data class Setup(val update: Boolean, val step: String, val bytesDone: Long?, val bytesTotal: Long?) : View
    object SetupFailed : View
    object Starting : View
    object Stopping : View
    data class NotResponding(val serverId: String?) : View
    data class Running(
      val serverId: String?,
      val sshEnabled: Boolean,
      val sshPort: Int,
      val keepAwake: Boolean,
      val addresses: List<Pair<String, String>>, // (kind "wifi"|"tailscale", address), preferred first
    ) : View
  }

  fun ensureChannel(context: Context) {
    val channel =
      NotificationChannel(CHANNEL_ID, context.getString(R.string.linux_notif_channel_name), NotificationManager.IMPORTANCE_LOW)
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
  }

  /** [actionIntent] builds the PendingIntent for a service action (e.g. restart the daemon). */
  fun build(context: Context, view: View, actionIntent: (String) -> PendingIntent): Notification {
    ensureChannel(context)
    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_phone_runtime_notification)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(deepLink(context, hostPath(null, null), 0))
    when (view) {
      is View.Setup -> {
        val title = if (view.update) R.string.linux_notif_updating else R.string.linux_notif_setting_up
        val stepLabel = context.getString(stepLabel(view.step))
        val total = view.bytesTotal
        builder.setContentTitle(context.getString(title))
        if (!view.update && view.bytesDone != null && total != null && total > 0) {
          val percent = (view.bytesDone * 100 / total).toInt().coerceIn(0, 100)
          builder.setContentText(context.getString(R.string.linux_notif_step_percent, stepLabel, percent))
          builder.setProgress(100, percent, false)
        } else {
          builder.setContentText(stepLabel).setProgress(0, 0, true)
        }
      }
      View.SetupFailed ->
        builder
          .setContentTitle(context.getString(R.string.linux_notif_setup_failed))
          .setContentText(context.getString(R.string.linux_notif_setup_failed_text))
          .setOngoing(false)
          .setAutoCancel(true)
      View.Starting -> builder.setContentTitle(context.getString(R.string.linux_notif_starting)).setProgress(0, 0, true)
      View.Stopping -> builder.setContentTitle(context.getString(R.string.linux_notif_stopping)).setProgress(0, 0, true)
      is View.NotResponding ->
        builder
          .setContentTitle(context.getString(R.string.linux_notif_not_responding))
          .setContentText(context.getString(R.string.linux_notif_not_responding_text))
          .setContentIntent(deepLink(context, hostPath(view.serverId, "$QUERY_LOGS=daemon"), 1))
          .setOngoing(false)
          .addAction(0, context.getString(R.string.linux_notif_action_restart), actionIntent(ACTION_RESTART_DAEMON))
      is View.Running -> {
        val awake = if (view.keepAwake) context.getString(R.string.linux_notif_awake_suffix) else ""
        val lines =
          view.addresses.map { (kind, address) ->
            val label = context.getString(if (kind == "wifi") R.string.linux_notif_wifi else R.string.linux_notif_tailscale)
            context.getString(R.string.linux_notif_ssh_address_labeled, address, view.sshPort, label)
          }
        val text =
          when {
            !view.sshEnabled -> context.getString(R.string.linux_notif_ssh_off)
            view.addresses.isEmpty() -> context.getString(R.string.linux_notif_no_network)
            else -> context.getString(R.string.linux_notif_ssh_address, view.addresses.first().second, view.sshPort)
          } + awake
        builder
          .setContentTitle(context.getString(R.string.linux_notif_running))
          .setContentText(text)
          .setContentIntent(deepLink(context, hostPath(view.serverId, null), 0))
        // Without a serverId there is no host page to confirm on, so no Stop action.
        if (view.serverId != null) {
          builder.addAction(
            0,
            context.getString(R.string.linux_notif_action_stop),
            deepLink(context, hostPath(view.serverId, "$QUERY_CONFIRM_STOP=1"), 2),
          )
        }
        if (view.sshEnabled && lines.size > 1) {
          builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n") + awake))
        }
      }
    }
    return builder.build()
  }

  const val ACTION_RESTART_DAEMON = "io.github.ouor.paseophone.runtime.RESTART_DAEMON"

  // Query keys: keep equal to PHONE_NOTIFICATION_QUERY in modules/paseo-phone-runtime/src/types.ts.
  private const val QUERY_CONFIRM_STOP = "confirmStop"
  private const val QUERY_LOGS = "logs"

  // Same path as buildSettingsHostSectionRoute(serverId, "host") in packages/app/src/utils/host-routes.ts.
  // `settings/hosts/<id>` alone redirects to Connections and drops the query. Without a serverId the link only opens the app.
  private fun hostPath(serverId: String?, query: String?): String =
    if (serverId == null) "" else "settings/hosts/${Uri.encode(serverId)}/host" + (query?.let { "?$it" } ?: "")

  private fun deepLink(context: Context, path: String, requestCode: Int): PendingIntent {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse("${PhoneConstants.URL_SCHEME}://$path"))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun stepLabel(step: String): Int =
    when (step) {
      "download" -> R.string.linux_step_download
      "extract" -> R.string.linux_step_extract
      "configure" -> R.string.linux_step_configure
      "packages" -> R.string.linux_step_packages
      "paseo" -> R.string.linux_step_paseo
      else -> R.string.linux_step_start
    }
}
