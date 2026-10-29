package io.github.ouor.paseophone.runtime

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * User intent only: `filesDir/phone-runtime/state.json` (architecture §2.3). Install state and versions
 * are read from disk, never stored here. Fields are only ever added; a changed meaning gets a new key.
 */
class RuntimeStore(private val file: File) {
  data class Data(
    val desired: String = "running", // "running" | "stopped"
    val sshEnabled: Boolean = true,
    val keepAwake: Boolean = true,
    val failedStep: String? = null, // last failed setup step, so a relaunch offers Retry from there
  )

  /** Missing or unreadable → defaults; an unreadable file is kept aside as `state.json.corrupt`. */
  fun load(): Data {
    if (!file.isFile) return Data()
    return try {
      val json = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
      Data(
        desired = json.optString("desired", "running").takeIf { it == "running" || it == "stopped" } ?: "running",
        sshEnabled = json.optBoolean("sshEnabled", true),
        keepAwake = json.optBoolean("keepAwake", true),
        failedStep = if (json.isNull("failedStep")) null else json.optString("failedStep").ifEmpty { null },
      )
    } catch (_: Exception) {
      file.renameTo(File(file.path + ".corrupt"))
      Data()
    }
  }

  fun save(data: Data) {
    val json =
      JSONObject()
        .put("version", VERSION)
        .put("desired", data.desired)
        .put("sshEnabled", data.sshEnabled)
        .put("keepAwake", data.keepAwake)
        .put("failedStep", data.failedStep ?: JSONObject.NULL)
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    try {
      out.write(json.toString(2).toByteArray())
      atomic.finishWrite(out)
    } catch (e: Exception) {
      atomic.failWrite(out)
      throw e
    }
  }

  private companion object {
    const val VERSION = 1
  }
}
