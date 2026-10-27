package dev.fabik.bluetoothhid.web

import dev.fabik.bluetoothhid.utils.WebSubmitFormat
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

    @Test
    fun `buildPayload with standard format generates correct json`() {
        val json = WebSubmitManager.buildPayload(
            format = WebSubmitFormat.STANDARD,
            standardFieldsJson = "",
            tencentTemplateJson = "",
            code = "TEST_BARCODE_123",
            barcodeFormat = "CODE_128",
            timestamp = 1735660800000L,
            scannerId = "DeviceModelX"
        )

        val obj = JSONObject(json)
        assertEquals("TEST_BARCODE_123", obj.getString("code"))
        assertEquals("Default_Project", obj.getString("project"))
        assertEquals("Station_01", obj.getString("station"))
        assertEquals(1735660800000L, obj.getLong("timestamp"))
    }

    @Test
    fun `buildPayload with tencent docs format generates schema and records correctly`() {
        val testBarcode = "BARCODE_ABC_999"
        val testTimestamp = 1735660800000L
        val json = WebSubmitManager.buildPayload(
            format = WebSubmitFormat.TENCENT_DOCS,
            standardFieldsJson = "",
            tencentTemplateJson = WebSubmitManager.DEFAULT_TENCENT_TEMPLATE,
            code = testBarcode,
            barcodeFormat = "QR_CODE",
            timestamp = testTimestamp,
            scannerId = "Scanner001"
        )

        val root = JSONObject(json)
        assertTrue(root.has("schema"))
        assertTrue(root.has("add_records"))

        // Verify schema definitions
        val schema = root.getJSONObject("schema")
        assertEquals("code", schema.getJSONObject("f04Gwj").getString("title"))
        assertEquals("project", schema.getJSONObject("fpMiuf").getString("title"))
        assertEquals("station", schema.getJSONObject("feZaVE").getString("title"))
        assertEquals("single_select", schema.getJSONObject("feZaVE").getString("type"))
        assertEquals("timestamp", schema.getJSONObject("f0mDed").getString("title"))

        // Verify add_records
        val records = root.getJSONArray("add_records")
        assertEquals(1, records.length())
        val values = records.getJSONObject(0).getJSONObject("values")

        assertEquals(testBarcode, values.getString("f04Gwj"))
        assertEquals("测试文本", values.getString("fpMiuf"))
        assertEquals(testTimestamp.toString(), values.getString("f0mDed"))

        val stationArray = values.getJSONArray("feZaVE")
        assertEquals(1, stationArray.length())
        assertEquals("测试选项", stationArray.getJSONObject(0).getString("text"))
    }

    @Test
    fun `buildPayload with invalid tencent template falls back to default safely`() {
        val json = WebSubmitManager.buildPayload(
            format = WebSubmitFormat.TENCENT_DOCS,
            standardFieldsJson = "",
            tencentTemplateJson = "{ broken json !!",
            code = "FALLBACK_CODE",
            barcodeFormat = "EAN_13",
            timestamp = 1735660800000L
        )

        val root = JSONObject(json)
        assertTrue(root.has("schema"))
        val values = root.getJSONArray("add_records").getJSONObject(0).getJSONObject("values")
        assertEquals("FALLBACK_CODE", values.getString("f04Gwj"))
    }

    @Test
    fun `parseTencentFields and buildTencentTemplate adapt to sample 2 with 7 fields correctly`() {
        val sample2 = """
        {
          "schema": {
            "fdgkIT": { "title": "文本", "type": "text" },
            "fVc9yb": { "title": "单选", "type": "single_select", "enum": [] },
            "fK59d9": { "title": "人员", "type": "user" },
            "fbkVfq": { "title": "数字", "type": "number" },
            "fuonhj": { "title": "日期", "type": "date_time" },
            "fdMCrH": { "title": "文本 2", "type": "text" },
            "fxNE5l": { "title": "文本 3", "type": "text" }
          },
          "add_records": [
            {
              "values": {
                "fdgkIT": "测试文本",
                "fVc9yb": [{ "text": "测试选项" }],
                "fK59d9": [{ "user_id": "" }],
                "fbkVfq": 1,
                "fuonhj": "1735660800000",
                "fdMCrH": "测试文本",
                "fxNE5l": "测试文本"
              }
            }
          ]
        }
        """.trimIndent()

        val fields = WebSubmitManager.parseTencentFields(sample2)
        assertEquals(7, fields.size)

        // Verify auto-mapping
        val textField = fields.first { it.id == "fdgkIT" }
        assertEquals("文本", textField.title)
        assertEquals("text", textField.type)
        assertEquals("{CODE}", textField.mappedValue)

        val dateField = fields.first { it.id == "fuonhj" }
        assertEquals("日期", dateField.title)
        assertEquals("date_time", dateField.type)
        assertEquals("{TIMESTAMP}", dateField.mappedValue)

        val selectField = fields.first { it.id == "fVc9yb" }
        assertEquals("单选", selectField.title)
        assertEquals("single_select", selectField.type)
        assertEquals("测试选项", selectField.mappedValue)

        val userField = fields.first { it.id == "fK59d9" }
        assertEquals("人员", userField.title)
        assertEquals("user", userField.type)

        // User customizes mapping values
        userField.mappedValue = "USER_1001"
        selectField.mappedValue = "工位A"
        val numberField = fields.first { it.id == "fbkVfq" }
        numberField.mappedValue = "42"

        // Rebuild template
        val updatedTemplate = WebSubmitManager.buildTencentTemplate(sample2, fields)
        val payload = WebSubmitManager.buildPayload(
            format = WebSubmitFormat.TENCENT_DOCS,
            standardFieldsJson = "",
            tencentTemplateJson = updatedTemplate,
            code = "BARCODE_777",
            barcodeFormat = "QR_CODE",
            timestamp = 1735660800000L
        )

        val root = JSONObject(payload)
        val values = root.getJSONArray("add_records").getJSONObject(0).getJSONObject("values")

        // Assert 101240 compliant structures
        assertEquals("BARCODE_777", values.getString("fdgkIT"))
        assertEquals(1735660800000L.toString(), values.getString("fuonhj"))
        assertEquals("工位A", values.getJSONArray("fVc9yb").getJSONObject(0).getString("text"))
        assertEquals("USER_1001", values.getJSONArray("fK59d9").getJSONObject(0).getString("user_id"))
        assertEquals(42, values.getInt("fbkVfq"))
    }
}
