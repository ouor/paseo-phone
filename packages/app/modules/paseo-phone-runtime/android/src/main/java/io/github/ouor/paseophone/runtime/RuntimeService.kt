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
import io.github.ouor.paseophone.runtime.notify.RuntimeNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (`specialUse`) that keeps the app process alive while Linux runs or setup works
 * (architecture §8.1). It holds the notification and the wake/wifi locks. It has no state machine and
 * owns no processes: RuntimeController drives it from its confined thread and hears back through
 * [Listener]. [onDestroy] never touches locks or processes itself.
 */
class RuntimeService : Service() {
  interface Listener {
    fun onNotificationAction(action: String)

    /** Only for the current instance, i.e. a destroy we did not ask for. */
    fun onServiceDestroyed(service: RuntimeService)
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private val destroyed = CompletableDeferred<Unit>()

  // Last notification and whether it could actually be shown; Android drops it while POST_NOTIFICATIONS is denied.
  private var lastNotification: Notification? = null
  private var lastShown = false
  private var foreground = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action != null && action != ACTION_START) {
      listener?.onNotificationAction(action)
      if (instance !== this) stopSelf() // a stale notification action created us; nothing to own
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
    val wasCurrent = instance === this
    if (wasCurrent) instance = null
    destroyed.complete(Unit)
    if (wasCurrent) listener?.onServiceDestroyed(this)
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

  /** [wake]: PARTIAL_WAKE_LOCK (keepAwake, and always during setup). [wifi]: low-latency WifiLock (keepAwake). */
  fun setLocks(wake: Boolean, wifi: Boolean) {
    if (wake && wakeLock?.isHeld != true) {
      wakeLock =
        getSystemService(PowerManager::class.java)
          ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaseoPhone:runtime")
          ?.apply {
            setReferenceCounted(false)
            acquire()
          }
    } else if (!wake) {
      wakeLock?.takeIf { it.isHeld }?.release()
      wakeLock = null
    }
    if (wifi && wifiLock?.isHeld != true) {
      wifiLock =
        applicationContext
          .getSystemService(WifiManager::class.java)
          ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PaseoPhone:runtime")
          ?.apply {
            setReferenceCounted(false)
            acquire()
          }
    } else if (!wifi) {
      wifiLock?.takeIf { it.isHeld }?.release()
      wifiLock = null
    }
  }

  /**
   * Leaves the foreground and stops. From this call on the instance is no longer current, so its
   * [onDestroy] is not reported as unexpected. [keepNotification] leaves the last notification posted.
   */
  fun shutdown(keepNotification: Boolean) {
    if (instance === this) instance = null
    pendingDestroy = destroyed
    foreground = false
    setLocks(wake = false, wifi = false)
    ServiceCompat.stopForeground(this, if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  companion object {
    private const val ACTION_START = "io.github.ouor.paseophone.runtime.START"
    private const val EXTRA_NOTIFICATION = "notification"
    private const val START_TIMEOUT_MS = 10_000L

    @Volatile var listener: Listener? = null

    /** The current instance: started and not shutting down. */
    @Volatile var instance: RuntimeService? = null
      private set

    @Volatile private var started = CompletableDeferred<RuntimeService>()

    // Destroy signal of the instance we last shut down; a new start waits for it (architecture §8.2).
    @Volatile private var pendingDestroy: CompletableDeferred<Unit>? = null

    /**
     * Returns the current instance, starting one if needed. Waits for a stopping instance's onDestroy
     * first. Must be called while the app is in the foreground (Android 12+ FGS start rule); null on failure.
     */
    suspend fun ensure(context: Context, notification: Notification): RuntimeService? {
      instance?.let { return it }
      pendingDestroy?.let { withTimeoutOrNull(START_TIMEOUT_MS) { it.await() } }
      pendingDestroy = null
      val waiting = CompletableDeferred<RuntimeService>()
      started = waiting
      val intent =
        Intent(context, RuntimeService::class.java).setAction(ACTION_START).putExtra(EXTRA_NOTIFICATION, notification)
      ContextCompat.startForegroundService(context, intent)
      return withTimeoutOrNull(START_TIMEOUT_MS) { waiting.await() }
    }

    fun actionIntent(context: Context, action: String): PendingIntent =
      PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, RuntimeService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
  }
}
