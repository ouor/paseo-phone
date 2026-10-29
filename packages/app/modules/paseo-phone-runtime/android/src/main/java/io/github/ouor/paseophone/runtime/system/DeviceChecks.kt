package io.github.ouor.paseophone.runtime.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Device settings the app reads and reports but never changes (architecture §8.4). */
object DeviceChecks {
  data class Result(
    val batteryOptimized: Boolean,
    val phantomProcessMonitor: String, // "disabled" | "enabled" | "unknown"
    val notificationsAllowed: Boolean,
  )

  fun read(context: Context): Result {
    val power = context.getSystemService(PowerManager::class.java)
    val phantom =
      try {
        if (Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs") == "false") {
          "disabled"
        } else {
          "enabled"
        }
      } catch (_: Exception) {
        "unknown"
      }
    return Result(
      batteryOptimized = power?.isIgnoringBatteryOptimizations(context.packageName) != true,
      phantomProcessMonitor = phantom,
      notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
  }

  fun openBatterySettings(context: Context) {
    val request =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
      context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }
}
