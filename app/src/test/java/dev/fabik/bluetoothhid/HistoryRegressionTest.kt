package dev.fabik.bluetoothhid

import dev.fabik.bluetoothhid.ui.model.HistoryViewModel
import dev.fabik.bluetoothhid.utils.Serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistoryRegressionTest {

    @Test
    fun `history entry unique id incrementation ensures unique keys for duplicate barcodes`() {
        val entry1 = HistoryViewModel.HistoryEntry("SAME_CODE", 1000L, 0)
        val entry2 = HistoryViewModel.HistoryEntry("SAME_CODE", 1000L, 0)
        val entry3 = HistoryViewModel.HistoryEntry("SAME_CODE", 1000L, 0)

        assertEquals("SAME_CODE", entry1.value)
        assertEquals("SAME_CODE", entry2.value)
        assertEquals("SAME_CODE", entry3.value)

        // IDs must be distinct to prevent Jetpack Compose LazyColumn crash
        assertNotEquals(entry1.id, entry2.id)
        assertNotEquals(entry2.id, entry3.id)
        assertNotEquals(entry1.id, entry3.id)
    }

    @Test
    fun `csv serializer round trip preserves barcode content and special characters`() {
        val entries = listOf(
            Serializer.BarcodeEntry("ABC,123", 100000L, "QR_CODE"),
            Serializer.BarcodeEntry("SIMPLE-SN", 200000L, "CODE_128"),
            Serializer.BarcodeEntry("CODE\"WITH\"QUOTES", 300000L, "DATA_MATRIX")
        )

        val csv = Serializer.toCsv(entries)
        assertTrue(csv.isNotBlank())

        val restored = Serializer.fromCsv(csv)
        assertEquals(3, restored.size)
        assertEquals("ABC,123", restored[0].text)
        assertEquals(100000L, restored[0].timestamp)
        assertEquals("SIMPLE-SN", restored[1].text)
        assertEquals(200000L, restored[1].timestamp)
        assertEquals("CODE\"WITH\"QUOTES", restored[2].text)
        assertEquals(300000L, restored[2].timestamp)
    }

    @Test
    fun `anti wipe guard protects history file when not loaded`() {
        val context = RuntimeEnvironment.getApplication()
        val historyFile = context.filesDir.resolve("history.csv")
        historyFile.writeText("sample_code,12345678,QR_CODE\n")
        assertTrue(historyFile.exists())

        // Explicitly set historyFileLoaded to false via testing helper
        HistoryViewModel.setHistoryFileLoadedForTesting(false)

        // Clear in-memory entries
        HistoryViewModel.historyEntries.clear()

        // Calling saveHistory when not loaded MUST be blocked by guard and NOT delete the physical file
        HistoryViewModel.saveHistory(context)

        assertTrue("history.csv must not be deleted when historyFileLoaded is false!", historyFile.exists())
    }
}
