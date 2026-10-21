package dev.fabik.bluetoothhid.ui.model

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.MainScope
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
    private var selectedHistory: SnapshotStateList<Long> = mutableStateListOf<Long>()

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
        }.asReversed()
    }

    companion object {
        private const val TAG = "History"
        private val idCounter = java.util.concurrent.atomic.AtomicLong(1L)
        fun nextEntryId(): Long = idCounter.getAndIncrement()

        private const val HISTORY_FILE = "history.csv"
        private const val HISTORY_FILE_TMP = "history.csv.tmp"
        private var historyFileLoaded = false

        @androidx.annotation.VisibleForTesting
        internal fun setHistoryFileLoadedForTesting(loaded: Boolean) {
            historyFileLoaded = loaded
        }

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

                // Guard: Do not save (and never delete the file!) if history has not been loaded into memory yet
                if (!historyFileLoaded) {
                    Log.w(TAG, "saveHistory skipped: history has not been loaded yet!")
                    return
                }

                val file = context.filesDir.resolve(HISTORY_FILE)
                val tmpFile = context.filesDir.resolve(HISTORY_FILE_TMP)

                // Cleanup file if history is genuinely empty
                if (historyEntries.isEmpty()) {
                    context.deleteFile(file.name)
                    context.deleteFile(tmpFile.name)
                    Log.d(TAG, "History is empty, removed file: $file")
                    return
                }

                Log.d(TAG, "Saving history to: $file (count: ${historyEntries.size})")

                val entriesSnapshot = historyEntries.toList()
                tmpFile.bufferedWriter().use { writer ->
                    val entries = entriesSnapshot.map {
                        Serializer.BarcodeEntry(it.value, it.timestamp, it.format.toString())
                    }
                    writer.write(Serializer.toCsv(entries))
                    writer.flush()
                }

                if (tmpFile.exists()) {
                    val renamed = if (file.exists()) {
                        file.delete() && tmpFile.renameTo(file)
                    } else {
                        tmpFile.renameTo(file)
                    }
                    if (!renamed && tmpFile.exists()) {
                        tmpFile.copyTo(file, overwrite = true)
                        tmpFile.delete()
                    }
                }
            }.onFailure {
                Log.e(TAG, "Failed to store history:", it)
            }
        }

        @Synchronized
        fun restoreHistory(context: Context) {
            applicationContext = context.applicationContext

            if (historyFileLoaded) {
                return
            }

            runCatching {
                val file = context.filesDir.resolve(HISTORY_FILE)

                if (!file.exists()) {
                    historyFileLoaded = true
                    Log.d(TAG, "No history file exists: $file")
                    return
                }

                Log.d(TAG, "Loading history from: $file")

                val csvContent = file.readText()
                val parsedEntries = Serializer.fromCsv(csvContent)

                val loadedItems = parsedEntries.map { entry ->
                    val formatIndex = entry.format.toIntOrNull() ?: -1
                    HistoryEntry(entry.text, entry.timestamp, formatIndex)
                }

                historyFileLoaded = true

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    historyEntries.clear()
                    historyEntries.addAll(loadedItems)
                } else {
                    MainScope().launch {
                        historyEntries.clear()
                        historyEntries.addAll(loadedItems)
                    }
                }
                Log.d(TAG, "Restored ${loadedItems.size} entries from file successfully")
            }.onFailure {
                historyFileLoaded = true
                Log.e(TAG, "Error loading history:", it)
            }
        }

        fun addHistoryItem(value: String, format: Int, context: Context? = null) {
            context?.let { applicationContext = it.applicationContext }
            historyFileLoaded = true
            val currentTime = System.currentTimeMillis()
            val entry = HistoryEntry(value, currentTime, format)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                historyEntries.add(entry)
            } else {
                MainScope().launch {
                    historyEntries.add(entry)
                }
            }
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
            selectedHistory.contains(it.id)
        }
        clearSelection()
        autoSave(context)
    }

    fun clearSelection() {
        selectedHistory.clear()
    }

    fun isItemSelected(item: HistoryEntry): Boolean {
        return selectedHistory.contains(item.id)
    }

    fun setItemSelected(item: HistoryEntry, selected: Boolean) {
        if (selected) {
            selectedHistory.add(item.id)
        } else {
            selectedHistory.remove(item.id)
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
                selectedHistory.contains(it.id)
            }
        }
        if (deduplicate) {
            history = history.fastDistinctBy { it.value }
        }
        return exportEntries(history, exportType, formatNames)
    }

    data class HistoryEntry(val value: String, val timestamp: Long, val format: Int, val id: Long = nextEntryId())

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