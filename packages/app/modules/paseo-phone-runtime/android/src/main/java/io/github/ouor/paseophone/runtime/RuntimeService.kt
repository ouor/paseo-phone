package io.github.ouor.paseophone.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.ouor.paseophone.runtime.linux.ProotProcess
import io.github.ouor.paseophone.runtime.notify.RuntimeNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (`specialUse`) that keeps the app process alive while Linux runs or setup works
 * (architecture §8.1). It holds the notification, wake/wifi locks and the proot handles so they die
 * with it. It has no state machine: RuntimeController drives it and receives events through [Listener].
 */
class RuntimeService : Service() {
  interface Listener {
    fun onNotificationAction(action: String)
    fun onServiceDestroyed()
  }

  private val processes = mutableMapOf<String, ProotProcess>()
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  // Last notification and whether it could actually be shown; Android drops it while POST_NOTIFICATIONS is denied.
  private var lastNotification: Notification? = null
  private var lastShown = false
  private var foreground = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action != null && action != ACTION_START) {
      listener?.onNotificationAction(action)
      if (instance == null) stopSelf() // a stale notification action created us; nothing to own
      return START_NOT_STICKY
    }
    val notification =
      intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java) }
    if (notification == null) {
      // Restarted without our intent (no START_STICKY, so unexpected): nothing to own.
      stopSelf()
      return START_NOT_STICKY
    }
    enterForeground(notification)
    instance = this
    started.complete(this)
    // Not sticky: after the process dies the next app launch restarts from state.json (`desired`).
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    if (instance === this) {
      instance = null
      started = CompletableDeferred()
    }
    processes.values.forEach { runCatching { it.destroyForcibly() } }
    processes.clear()
    setKeepAwake(false)
    listener?.onServiceDestroyed()
    super.onDestroy()
  }

  fun show(notification: Notification) {
    lastNotification = notification
    lastShown = notificationsAllowed()
    getSystemService(NotificationManager::class.java)?.notify(RuntimeNotification.NOTIFICATION_ID, notification)
  }

  /** Re-posts the current notification once notifications become allowed after it was dropped. Cheap when nothing changed. */
  fun repostIfAllowed() {
    val notification = lastNotification ?: return
    if (!foreground || lastShown || !notificationsAllowed()) return
    enterForeground(notification)
  }

  private fun enterForeground(notification: Notification) {
    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    ServiceCompat.startForeground(this, RuntimeNotification.NOTIFICATION_ID, notification, type)
    foreground = true
    lastNotification = notification
    lastShown = notificationsAllowed()
  }

  private fun notificationsAllowed() = NotificationManagerCompat.from(this).areNotificationsEnabled()

  fun track(key: String, process: ProotProcess) {
    processes[key] = process
  }

  fun untrack(key: String, process: ProotProcess) {
    if (processes[key] === process) processes.remove(key)
  }

  fun setKeepAwake(enabled: Boolean) {
    if (enabled) {
      if (wakeLock?.isHeld != true) {
        wakeLock =
          getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaseoPhone:runtime")
            ?.apply { setReferenceCounted(false); acquire() }
      }
      if (wifiLock?.isHeld != true) {
        wifiLock =
          applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PaseoPhone:runtime")
            ?.apply { setReferenceCounted(false); acquire() }
      }
    } else {
      wakeLock?.takeIf { it.isHeld }?.release()
      wifiLock?.takeIf { it.isHeld }?.release()
      wakeLock = null
      wifiLock = null
    }
  }

  /** Leaves the foreground and stops. [keepNotification] leaves the last notification posted (setup failed). */
  fun shutdown(keepNotification: Boolean) {
    val mode = if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE
    foreground = false
    ServiceCompat.stopForeground(this, mode)
    stopSelf()
  }

  companion object {
    private const val ACTION_START = "io.github.ouor.paseophone.runtime.START"
    private const val EXTRA_NOTIFICATION = "notification"

    @Volatile var listener: Listener? = null

    @Volatile var instance: RuntimeService? = null
      private set

    @Volatile private var started = CompletableDeferred<RuntimeService>()

    /** Must be called while the app is in the foreground (Android 12+ FGS start rule). */
    fun start(context: Context, notification: Notification) {
      val intent =
        Intent(context, RuntimeService::class.java).setAction(ACTION_START).putExtra(EXTRA_NOTIFICATION, notification)
      ContextCompat.startForegroundService(context, intent)
    }

    suspend fun awaitStarted(timeoutMs: Long): RuntimeService? = instance ?: withTimeoutOrNull(timeoutMs) { started.await() }

    fun actionIntent(context: Context, action: String): PendingIntent =
      PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, RuntimeService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
  }
}
