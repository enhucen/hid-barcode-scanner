package dev.fabik.bluetoothhid.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class WebSubmitManagerTest {

    @Test
    fun `parseFields with invalid json returns default fields`() {
        val result = WebSubmitManager.parseFields("invalid json")
        assertEquals(4, result.size)
        assertEquals("code", result[0].key)
        assertEquals("{CODE}", result[0].value)
        assertEquals("project", result[1].key)
        assertEquals("station", result[2].key)
        assertEquals("timestamp", result[3].key)
    }

    @Test
    fun `parseFields with empty json returns default fields`() {
        val result = WebSubmitManager.parseFields("")
        assertEquals(4, result.size)
    }

    @Test
    fun `parseFields and serializeFields round-trip preserves custom pairs`() {
        val original = listOf(
            WebSubmitManager.FieldPair("barcode_data", "{CODE}"),
            WebSubmitManager.FieldPair("scanner", "{SCANNER_ID}"),
            WebSubmitManager.FieldPair("my_station", "ST-99")
        )
        val json = WebSubmitManager.serializeFields(original)
        assertNotNull(json)

        val restored = WebSubmitManager.parseFields(json)
        assertEquals(3, restored.size)
        assertEquals("barcode_data", restored[0].key)
        assertEquals("{CODE}", restored[0].value)
        assertEquals("scanner", restored[1].key)
        assertEquals("{SCANNER_ID}", restored[1].value)
        assertEquals("my_station", restored[2].key)
        assertEquals("ST-99", restored[2].value)
    }

    @Test
    fun `macro placeholder replacement replaces case-insensitively`() {
        val fields = listOf(
            WebSubmitManager.FieldPair("code_upper", "{CODE}"),
            WebSubmitManager.FieldPair("code_lower", "{code}"),
            WebSubmitManager.FieldPair("format_tag", "{format}"),
            WebSubmitManager.FieldPair("time_ms", "{timestamp}"),
            WebSubmitManager.FieldPair("time_str", "{DATETIME}"),
            WebSubmitManager.FieldPair("device", "{SCANNER_ID}")
        )

        val testCode = "SN-2026-X01"
        val testFormat = "QR_CODE"
        val testTimestamp = 1729510000000L
        val testScannerId = "TEST_PHONE"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val expectedDateTime = dateFormat.format(Date(testTimestamp))

        val payload = JSONObject()
        for (pair in fields) {
            val replaced = pair.value
                .replace(Regex("(?i)\\{CODE\\}"), testCode)
                .replace(Regex("(?i)\\{FORMAT\\}"), testFormat)
                .replace(Regex("(?i)\\{TIMESTAMP\\}"), testTimestamp.toString())
                .replace(Regex("(?i)\\{DATETIME\\}"), expectedDateTime)
                .replace(Regex("(?i)\\{SCANNER_ID\\}"), testScannerId)

            if (pair.value.equals("{TIMESTAMP}", ignoreCase = true)) {
                payload.put(pair.key, testTimestamp)
            } else {
                payload.put(pair.key, replaced)
            }
        }

        assertEquals(testCode, payload.getString("code_upper"))
        assertEquals(testCode, payload.getString("code_lower"))
        assertEquals(testFormat, payload.getString("format_tag"))
        assertEquals(testTimestamp, payload.getLong("time_ms"))
        assertEquals(expectedDateTime, payload.getString("time_str"))
        assertEquals(testScannerId, payload.getString("device"))
    }
}
