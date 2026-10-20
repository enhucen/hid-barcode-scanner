package dev.fabik.bluetoothhid.web

import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import dev.fabik.bluetoothhid.R
import dev.fabik.bluetoothhid.utils.PreferenceStore
import dev.fabik.bluetoothhid.utils.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebSubmitManager {
    private const val TAG = "WebSubmitManager"

    data class FieldPair(val key: String, val value: String)

    fun parseFields(json: String): List<FieldPair> {
        return runCatching {
            val list = mutableListOf<FieldPair>()
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val k = obj.optString("key", "")
                val v = obj.optString("value", "")
                if (k.isNotBlank()) {
                    list.add(FieldPair(k, v))
                }
            }
            list
        }.getOrElse {
            listOf(
                FieldPair("code", "{CODE}"),
                FieldPair("project", "Default_Project"),
                FieldPair("station", "Station_01"),
                FieldPair("timestamp", "{TIMESTAMP}")
            )
        }
    }

    fun serializeFields(fields: List<FieldPair>): String {
        val array = JSONArray()
        for (pair in fields) {
            val obj = JSONObject()
            obj.put("key", pair.key)
            obj.put("value", pair.value)
            array.put(obj)
        }
        return array.toString()
    }

    suspend fun submitScan(
        context: Context,
        code: String,
        format: String,
        timestamp: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getPreferences(
                PreferenceStore.WEB_SUBMIT_ENABLED,
                PreferenceStore.WEB_SUBMIT_URL,
                PreferenceStore.WEB_SUBMIT_USERNAME,
                PreferenceStore.WEB_SUBMIT_PASSWORD,
                PreferenceStore.WEB_SUBMIT_FIELDS_JSON
            ).first()

            val enabled = PreferenceStore.WEB_SUBMIT_ENABLED.extract(prefs)
            if (!enabled) return@runCatching

                        val rawUrl = PreferenceStore.WEB_SUBMIT_URL.extract(prefs).trim()
            if (rawUrl.isBlank()) {
                Log.w(TAG, "Web submit enabled but URL is empty")
                return@runCatching
            }
            val targetUrl = if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
                "http://$rawUrl"
            } else {
                rawUrl
            }

            val username = PreferenceStore.WEB_SUBMIT_USERNAME.extract(prefs).trim()
            val password = PreferenceStore.WEB_SUBMIT_PASSWORD.extract(prefs).trim()
            val fieldsJson = PreferenceStore.WEB_SUBMIT_FIELDS_JSON.extract(prefs)
            val scannerId = android.os.Build.MODEL ?: "Android"

            val fieldPairs = parseFields(fieldsJson)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateTimeStr = dateFormat.format(Date(timestamp))

            val payloadJson = JSONObject()
            for (pair in fieldPairs) {
                val replacedValue = pair.value
                    .replace(Regex("(?i)\\{CODE\\}"), code)
                    .replace(Regex("(?i)\\{FORMAT\\}"), format)
                    .replace(Regex("(?i)\\{TIMESTAMP\\}"), timestamp.toString())
                    .replace(Regex("(?i)\\{DATETIME\\}"), dateTimeStr)
                    .replace(Regex("(?i)\\{SCANNER_ID\\}"), scannerId)

                // If the placeholder was purely {TIMESTAMP}, serialize as long
                if (pair.value.equals("{TIMESTAMP}", ignoreCase = true)) {
                    payloadJson.put(pair.key, timestamp)
                } else {
                    payloadJson.put(pair.key, replacedValue)
                }
            }

            val jsonBody = payloadJson.toString()
            Log.d(TAG, "Submitting to $targetUrl with payload: $jsonBody")

            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json, text/plain, */*")

                if (username.isNotEmpty() || password.isNotEmpty()) {
                    val credentials = "$username:$password"
                    val basicAuth = "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", basicAuth)
                }
            }

            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                val isSuccess = responseCode in 200..299

                runCatching {
                    val stream = if (isSuccess) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() }
                }

                MainScope().launch {
                    val msg = if (isSuccess) {
                        context.getString(R.string.web_submit_success, responseCode)
                    } else {
                        context.getString(R.string.web_submit_failed, responseCode)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }

                Log.d(TAG, "Web submit finished with code: $responseCode")
            } finally {
                conn.disconnect()
            }
        }.onFailure { err ->
            Log.e(TAG, "Web submit request failed", err)
            MainScope().launch {
                Toast.makeText(
                    context,
                    context.getString(R.string.web_submit_error, err.message ?: "Unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
