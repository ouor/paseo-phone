package io.github.ouor.paseophone.runtime

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Persistent part of the runtime state: `filesDir/phone-runtime/state.json`, written atomically. */
class RuntimeStore(private val file: File) {
  data class Data(
    val installed: Boolean = false,
    val desired: String = "running", // "running" | "stopped"; a fresh install starts on first launch
    val sshEnabled: Boolean = true,
    val keepAwake: Boolean = true,
    val daemonVersion: String? = null,
    val nodeVersion: String? = null,
    val failedStep: String? = null, // last failed setup step, so a relaunch resumes there
  )

  fun load(): Data =
    try {
      if (!file.isFile) {
        Data()
      } else {
        val json = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        Data(
          installed = json.optBoolean("installed", false),
          desired = json.optString("desired", "running"),
          sshEnabled = json.optBoolean("sshEnabled", true),
          keepAwake = json.optBoolean("keepAwake", true),
          daemonVersion = json.optString("daemonVersion").ifEmpty { null },
          nodeVersion = json.optString("nodeVersion").ifEmpty { null },
          failedStep = json.optString("failedStep").ifEmpty { null },
        )
      }
    } catch (_: Exception) {
      Data()
    }

  fun save(data: Data) {
    val json =
      JSONObject()
        .put("installed", data.installed)
        .put("desired", data.desired)
        .put("sshEnabled", data.sshEnabled)
        .put("keepAwake", data.keepAwake)
        .put("daemonVersion", data.daemonVersion ?: "")
        .put("nodeVersion", data.nodeVersion ?: "")
        .put("failedStep", data.failedStep ?: "")
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
}
