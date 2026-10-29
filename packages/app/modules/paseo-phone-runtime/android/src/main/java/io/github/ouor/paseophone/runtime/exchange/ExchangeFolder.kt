package io.github.ouor.paseophone.runtime.exchange

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/** Shared-storage exchange folder bound to `/root/exchange` (architecture §5.2). */
object ExchangeFolder {
  const val LINUX_PATH = "/root/exchange"
  private const val NAME = "Paseo"

  data class Status(
    val status: String, // "mounted" | "no-permission" | "error" (restart-required is decided by the caller)
    val message: String?,
    val dir: File?, // non-null when the folder can be bound
    val phonePath: String,
    val phonePathLabel: String,
  )

  fun phoneDir(): File = File(Environment.getExternalStorageDirectory(), NAME)

  fun hasPermission(): Boolean = Environment.isExternalStorageManager()

  /** Checks permission and creates the folder when missing. */
  fun status(context: Context): Status {
    val dir = phoneDir()
    val label =
      try {
        val volume = context.getSystemService(StorageManager::class.java)?.primaryStorageVolume
        "${volume?.getDescription(context) ?: dir.parent}/$NAME"
      } catch (_: Exception) {
        dir.path
      }
    if (!hasPermission()) return Status("no-permission", null, null, dir.path, label)
    if (!dir.isDirectory && !dir.mkdirs()) return Status("error", "Cannot create ${dir.path}", null, dir.path, label)
    return Status("mounted", null, dir, dir.path, label)
  }

  fun requestPermission(context: Context) {
    val intent =
      Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      context.startActivity(
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }

  /** Opens the folder in DocumentsUI, falling back to the storage root. */
  fun open(context: Context) {
    val authority = "com.android.externalstorage.documents"
    for (documentId in listOf("primary:$NAME", "primary:")) {
      val intent =
        Intent(Intent.ACTION_VIEW)
          .setDataAndType(DocumentsContract.buildDocumentUri(authority, documentId), DocumentsContract.Document.MIME_TYPE_DIR)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      try {
        context.startActivity(intent)
        return
      } catch (_: ActivityNotFoundException) {
        // try the next fallback
      }
    }
    throw ActivityNotFoundException("No app can open $authority")
  }
}
