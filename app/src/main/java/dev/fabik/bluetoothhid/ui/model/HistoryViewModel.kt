package dev.fabik.bluetoothhid.ui.model

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.TableView
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import dev.fabik.bluetoothhid.R
import dev.fabik.bluetoothhid.utils.PreferenceStore
import dev.fabik.bluetoothhid.utils.Serializer
import dev.fabik.bluetoothhid.utils.getPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HistoryViewModel : ViewModel() {
    private var selectedHistory: SnapshotStateList<Int> = mutableStateListOf<Int>()

    val selectionSize by derivedStateOf { selectedHistory.size }
    val isSelecting by derivedStateOf { selectedHistory.isNotEmpty() }

    var isSearching by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    var filteredTypes = mutableStateListOf<Int>()
    var filterDateStart by mutableStateOf<Long?>(null)
    var filterDateEnd by mutableStateOf<Long?>(null)

    val filteredHistory by derivedStateOf {
        historyEntries.fastFilter { (barcode, timestamp, type) ->
            barcode.contains(searchQuery, ignoreCase = true)
                    && (filteredTypes.isEmpty() || filteredTypes.contains(type))
                    && (filterDateStart == null || timestamp > filterDateStart!!)
                    && (filterDateEnd == null || timestamp < filterDateEnd!!)
        }
    }

    companion object {
        private const val TAG = "History"

        private const val HISTORY_FILE = "history.csv"
        private const val HISTORY_FILE_TMP = "history.csv.tmp"
        private var historyFileLoaded = false

        private var applicationContext: Context? = null
        private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        var historyEntries: SnapshotStateList<HistoryEntry> = mutableStateListOf<HistoryEntry>()

        fun initContext(context: Context) {
            applicationContext = context.applicationContext
        }

        fun autoSave(context: Context? = null) {
            val ctx = context?.applicationContext ?: applicationContext ?: return
            saveScope.launch {
                val persist = runCatching {
                    ctx.getPreference(PreferenceStore.PERSIST_HISTORY).first()
                }.getOrDefault(true)

                if (persist) {
                    saveHistory(ctx)
                }
            }
        }

        @Synchronized
        fun saveHistory(context: Context) {
            runCatching {
                applicationContext = context.applicationContext
                val file = context.filesDir.resolve(HISTORY_FILE)
                val tmpFile = context.filesDir.resolve(HISTORY_FILE_TMP)

                // Cleanup file if history empty
                if (historyEntries.isEmpty()) {
                    context.deleteFile(file.name)
                    context.deleteFile(tmpFile.name)
                    return
                }

                Log.d(TAG, "Saving history to: $file")

                // Atomic write via temp file
                tmpFile.bufferedWriter().use {
                    // Internal format: numeric format for efficiency
                    val entries = historyEntries.map {
                        Serializer.BarcodeEntry(it.value, it.timestamp, it.format.toString())
                    }
                    it.write(Serializer.toCsv(entries))
                }

                if (tmpFile.exists()) {
                    if (file.exists()) file.delete()
                    tmpFile.renameTo(file)
                }
            }.onFailure {
                Log.e(TAG, "Failed to store history:", it)
            }
        }

        @Synchronized
        fun restoreHistory(context: Context) {
            applicationContext = context.applicationContext

            // guard for only loading once
            if (historyFileLoaded) {
                return
            }

            historyFileLoaded = true

            runCatching {
                val file = context.filesDir.resolve(HISTORY_FILE)

                if (!file.exists()) {
                    Log.d(TAG, "No history file exists: $file")
                    return
                }

                Log.d(TAG, "Loading history from: $file")

                val csvContent = file.readText()
                val parsedEntries = Serializer.fromCsv(csvContent)

                historyEntries.clear()
                parsedEntries.forEach { entry ->
                    val formatIndex = entry.format.toIntOrNull() ?: -1
                    historyEntries.add(HistoryEntry(entry.text, entry.timestamp, formatIndex))
                }
            }.onFailure {
                Log.e(TAG, "Error loading history:", it)
            }
        }

        fun addHistoryItem(value: String, format: Int, context: Context? = null) {
            context?.let { applicationContext = it.applicationContext }
            val currentTime = System.currentTimeMillis()
            historyEntries.add(HistoryEntry(value, currentTime, format))
            autoSave(context)
        }

        fun clearHistory(context: Context? = null) {
            context?.let { applicationContext = it.applicationContext }
            historyEntries.clear()
            autoSave(context)
        }

        fun exportEntries(
            dataToExport: List<HistoryEntry>,
            exportType: ExportType,
            formatNames: Array<String>
        ): String {
            val entries = dataToExport.map { entry ->
                Serializer.BarcodeEntry(
                    text = entry.value,
                    timestamp = entry.timestamp,
                    format = formatNames.getOrNull(entry.format) ?: "UNKNOWN"
                )
            }

            return when (exportType) {
                ExportType.LINES -> Serializer.toLines(entries)
                ExportType.CSV -> Serializer.toCsv(entries)
                ExportType.JSON -> Serializer.toJson(entries)
                ExportType.XML -> Serializer.toXml(entries)
            }
        }

        /**
         * Imports barcode entries from various formats.
         *
         * @param content File content to parse
         * @param format Import format (CSV, JSON, or XML)
         * @param formatNames Array mapping format names to indices
         * @return Number of successfully imported entries
         */
        fun importHistory(
            content: String,
            format: ImportFormat,
            formatNames: Array<String>,
            context: Context? = null
        ): Int {
            context?.let { applicationContext = it.applicationContext }
            val parsedEntries = when (format) {
                ImportFormat.CSV -> Serializer.fromCsv(content)
                ImportFormat.JSON -> Serializer.fromJson(content)
                ImportFormat.XML -> Serializer.fromXml(content)
            }

            // Convert Serializer.BarcodeEntry to HistoryEntry
            parsedEntries.forEach { entry ->
                // Parse format: either numeric string or name string
                val formatIndex = entry.format.toIntOrNull()
                    ?: formatNames.indexOf(entry.format).takeIf { it >= 0 }
                    ?: -1

                historyEntries.add(HistoryEntry(entry.text, entry.timestamp, formatIndex))
            }

            autoSave(context)
            return parsedEntries.size
        }
    }

    fun deleteSelectedItems(context: Context? = null) {
        historyEntries.removeIf {
            selectedHistory.contains(it.hashCode())
        }
        clearSelection()
        autoSave(context)
    }

    fun clearSelection() {
        selectedHistory.clear()
    }

    fun isItemSelected(item: HistoryEntry): Boolean {
        return selectedHistory.contains(item.hashCode())
    }

    fun setItemSelected(item: HistoryEntry, selected: Boolean) {
        if (selected) {
            selectedHistory.add(item.hashCode())
        } else {
            selectedHistory.remove(item.hashCode())
        }
    }

    fun exportHistory(
        exportType: ExportType,
        deduplicate: Boolean,
        formatNames: Array<String>
    ): String {
        var history = filteredHistory
        if (isSelecting) {
            history = history.fastFilter {
                selectedHistory.contains(it.hashCode())
            }
        }
        if (deduplicate) {
            history = history.fastDistinctBy { it.value }
        }
        return exportEntries(history, exportType, formatNames)
    }

    data class HistoryEntry(val value: String, val timestamp: Long, val format: Int)

    enum class ExportType(
        @StringRes val label: Int,
        @StringRes val description: Int,
        val icon: ImageVector
    ) {
        CSV(R.string.export_csv, R.string.export_fields, Icons.Default.TableView),
        JSON(R.string.export_json, R.string.export_fields, Icons.Default.DataObject),
        XML(R.string.export_xml, R.string.export_fields, Icons.Filled.Code),
        LINES(R.string.export_lines, R.string.export_lines_description, Icons.Default.TableRows)
    }

    enum class ImportFormat(
        @StringRes val label: Int,
        @StringRes val description: Int,
        val icon: ImageVector,
        val baseMime: String,                 // was: mimeType: String
        val extraMimeTypes: Array<String>? = null
    ) {
        CSV(
            R.string.export_csv,
            R.string.export_fields,
            Icons.Default.TableView,
            baseMime = "*/*",
            extraMimeTypes = arrayOf(
                "text/csv",
                "application/csv",
                "text/comma-separated-values",
                "application/vnd.ms-excel",
                "application/vnd.msexcel"
            )
        ),
        JSON(
            R.string.export_json,
            R.string.export_fields,
            Icons.Default.DataObject,
            baseMime = "application/json"
        ),
        XML(
            R.string.export_xml,
            R.string.export_fields,
            Icons.Filled.Code,
            baseMime = "text/xml",
            extraMimeTypes = arrayOf(
                "text/xml",
                "application/xml"
            )
        )
    }
}