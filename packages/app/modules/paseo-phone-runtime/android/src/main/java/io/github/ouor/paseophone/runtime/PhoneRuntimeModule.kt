package io.github.ouor.paseophone.runtime

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import expo.modules.interfaces.permissions.PermissionsResponseListener
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** Expo bridge only: forwards to RuntimeController and maps RuntimeError to coded JS errors (architecture §7.2). */
class PhoneRuntimeModule : Module() {
  private val stateListener: (Map<String, Any?>) -> Unit = { state -> sendEvent("onStateChange", mapOf("state" to state)) }

  private val context: Context
    get() = appContext.reactContext?.applicationContext ?: throw CodedException("io-error", "React context is gone", null)

  override fun definition() = ModuleDefinition {
    Name("PaseoPhoneRuntime")
    Events("onStateChange")

    OnCreate {
      RuntimeController.init(context)
      RuntimeController.addListener(stateListener)
    }
    OnDestroy { RuntimeController.removeListener(stateListener) }
    // Returning from system settings or permission dialogs changes device checks and exchange status.
    OnActivityEntersForeground { RuntimeController.refreshEnvironment() }

    Function("getState") { -> RuntimeController.state() }

    AsyncFunction("start") Coroutine { -> bridge { RuntimeController.start() } }
    AsyncFunction("stop") Coroutine { -> bridge { RuntimeController.stop() } }
    AsyncFunction("restartLinux") Coroutine { -> bridge { RuntimeController.restartLinux() } }
    AsyncFunction("restartDaemon") Coroutine { -> bridge { RuntimeController.restartDaemon() } }
    AsyncFunction("retrySetup") Coroutine { -> bridge { RuntimeController.retrySetup() } }
    AsyncFunction("startPrevious") Coroutine { -> bridge { RuntimeController.startPrevious() } }
    AsyncFunction("reinstall") Coroutine { -> bridge { RuntimeController.reinstall() } }
    AsyncFunction("setSshEnabled") Coroutine { enabled: Boolean -> bridge { RuntimeController.setSshEnabled(enabled) } }
    AsyncFunction("setKeepAwake") Coroutine { enabled: Boolean -> bridge { RuntimeController.setKeepAwake(enabled) } }

    AsyncFunction("openBatterySettings") { -> bridgeSync { RuntimeController.openBatterySettings() } }
    AsyncFunction("requestStoragePermission") { -> bridgeSync { RuntimeController.requestStoragePermission() } }
    AsyncFunction("openExchangeFolder") { -> bridgeSync { RuntimeController.openExchangeFolder() } }

    AsyncFunction("requestNotificationPermission") { promise: Promise ->
      val permissions = appContext.permissions
      if (Build.VERSION.SDK_INT < 33 || permissions == null) {
        promise.resolve(NotificationManagerCompat.from(context).areNotificationsEnabled())
        return@AsyncFunction
      }
      permissions.askForPermissions(
        PermissionsResponseListener {
          promise.resolve(NotificationManagerCompat.from(context).areNotificationsEnabled())
          RuntimeController.refreshEnvironment()
        },
        Manifest.permission.POST_NOTIFICATIONS,
      )
    }

    AsyncFunction("listAuthorizedKeys") Coroutine { -> bridge { RuntimeController.listAuthorizedKeys() } }
    AsyncFunction("addAuthorizedKey") Coroutine { text: String -> bridge { RuntimeController.addAuthorizedKey(text) } }
    AsyncFunction("removeAuthorizedKey") Coroutine { fingerprint: String ->
      bridge { RuntimeController.removeAuthorizedKey(fingerprint) }
    }
    AsyncFunction("getLogs") Coroutine { kind: String, maxBytes: Int? -> bridge { RuntimeController.getLogs(kind, maxBytes) } }
  }

  private inline fun <T> bridgeSync(block: () -> T): T =
    try {
      block()
    } catch (e: RuntimeError) {
      throw CodedException(e.code, e.message, e)
    }

  private suspend fun <T> bridge(block: suspend () -> T): T =
    try {
      block()
    } catch (e: RuntimeError) {
      throw CodedException(e.code, e.message, e)
    }
}
