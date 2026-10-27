package dev.fabik.bluetoothhid.ui

import android.widget.Toast
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fabik.bluetoothhid.R
import dev.fabik.bluetoothhid.utils.PreferenceStore
import dev.fabik.bluetoothhid.utils.rememberPreference
import dev.fabik.bluetoothhid.web.WebSubmitManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSubmitFieldManageDialog(dialogState: DialogState) {
    val context = LocalContext.current
    var fieldsJson by rememberPreference(PreferenceStore.WEB_SUBMIT_FIELDS_JSON)

    val currentFields = remember { mutableStateListOf<WebSubmitManager.FieldPair>() }

    LaunchedEffect(fieldsJson, dialogState.openState) {
        if (dialogState.openState) {
            currentFields.clear()
            currentFields.addAll(WebSubmitManager.parseFields(fieldsJson))
        }
    }

    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editKey by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var isAddingNew by remember { mutableStateOf(false) }

    ConfirmDialog(
        dialogState = dialogState,
        title = stringResource(R.string.web_submit_manage_fields),
        onConfirm = {
            fieldsJson = WebSubmitManager.serializeFields(currentFields.toList())
            dialogState.close()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                stringResource(R.string.web_submit_fields_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Editing or Adding panel
            if (editIndex != null || isAddingNew) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (isAddingNew) stringResource(R.string.add_field) else stringResource(R.string.edit_field),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = editKey,
                            onValueChange = { editKey = it },
                            label = { Text(stringResource(R.string.field_key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        OutlinedTextField(
                            value = editValue,
                            onValueChange = { editValue = it },
                            label = { Text(stringResource(R.string.field_value)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        // Quick placeholder buttons
                        Text(
                            stringResource(R.string.quick_placeholders),
                            style = MaterialTheme.typography.labelSmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val placeholders = listOf("{CODE}", "{FORMAT}", "{TIMESTAMP}", "{DATETIME}", "{SCANNER_ID}")
                            for (p in placeholders) {
                                SuggestionChip(
                                    onClick = {
                                        editValue = if (editValue.isEmpty()) p else "$editValue$p"
                                    },
                                    label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(onClick = {
                                editIndex = null
                                isAddingNew = false
                            }) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = editKey.isNotBlank(),
                                onClick = {
                                    val pair = WebSubmitManager.FieldPair(editKey.trim(), editValue)
                                    if (isAddingNew) {
                                        currentFields.add(pair)
                                    } else {
                                        editIndex?.let { idx -> currentFields[idx] = pair }
                                    }
                                    editIndex = null
                                    isAddingNew = false
                                }
                            ) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.current_mappings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(onClick = {
                        editKey = ""
                        editValue = ""
                        isAddingNew = true
                    }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add_field))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(currentFields) { index, item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.key,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = item.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row {
                                IconButton(onClick = {
                                    editIndex = index
                                    editKey = item.key
                                    editValue = item.value
                                    isAddingNew = false
                                }) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.edit))
                                }
                                IconButton(onClick = {
                                    currentFields.removeAt(index)
                                }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TencentDocsTemplateDialog(dialogState: DialogState) {
    val context = LocalContext.current
    var templateJson by rememberPreference(PreferenceStore.WEB_SUBMIT_TENCENT_TEMPLATE)
    var currentText by remember { mutableStateOf("") }
    var isRawMode by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInputText by remember { mutableStateOf("") }

    val fieldsList = remember { mutableStateListOf<WebSubmitManager.TencentField>() }

    LaunchedEffect(templateJson, dialogState.openState) {
        if (dialogState.openState) {
            currentText = if (templateJson.isBlank()) {
                WebSubmitManager.DEFAULT_TENCENT_TEMPLATE
            } else {
                templateJson
            }
            fieldsList.clear()
            fieldsList.addAll(WebSubmitManager.parseTencentFields(currentText))
            isRawMode = false
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.import_tencent_json)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.import_tencent_json_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importInputText,
                        onValueChange = { importInputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        placeholder = { Text("{\n  \"schema\": { ... },\n  \"add_records\": [ ... ]\n}") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = WebSubmitManager.parseTencentFields(importInputText)
                        if (parsed.isNotEmpty()) {
                            currentText = importInputText.trim()
                            fieldsList.clear()
                            fieldsList.addAll(parsed)
                            showImportDialog = false
                            importInputText = ""
                            Toast.makeText(
                                context,
                                context.getString(R.string.import_success_count, parsed.size),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.import_failed_no_schema),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.parse_and_apply))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    ConfirmDialog(
        dialogState = dialogState,
        title = stringResource(R.string.web_submit_manage_tencent_template),
        onConfirm = {
            if (!isRawMode) {
                val updatedJson = WebSubmitManager.buildTencentTemplate(currentText, fieldsList.toList())
                templateJson = updatedJson
                dialogState.close()
            } else {
                val isValid = runCatching { JSONObject(currentText) }.isSuccess
                if (isValid) {
                    templateJson = currentText.trim()
                    dialogState.close()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.invalid_json_format),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // Action button bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1.2f),
                    onClick = {
                        importInputText = ""
                        showImportDialog = true
                    }
                ) {
                    Text(stringResource(R.string.import_tencent_json), style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isRawMode) {
                            currentText = WebSubmitManager.buildTencentTemplate(currentText, fieldsList.toList())
                        } else {
                            val parsed = WebSubmitManager.parseTencentFields(currentText)
                            if (parsed.isNotEmpty()) {
                                fieldsList.clear()
                                fieldsList.addAll(parsed)
                            }
                        }
                        isRawMode = !isRawMode
                    }
                ) {
                    Text(
                        if (isRawMode) stringResource(R.string.view_visual_mapping) else stringResource(R.string.view_raw_json),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    modifier = Modifier.weight(0.9f),
                    onClick = {
                        currentText = WebSubmitManager.DEFAULT_TENCENT_TEMPLATE
                        fieldsList.clear()
                        fieldsList.addAll(WebSubmitManager.parseTencentFields(currentText))
                    }
                ) {
                    Text(stringResource(R.string.restore_default), style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!isRawMode) {
                Text(
                    stringResource(R.string.tencent_docs_template_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(fieldsList) { index, item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${item.title} (${item.id})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = item.type,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                OutlinedTextField(
                                    value = item.mappedValue,
                                    onValueChange = { newValue ->
                                        fieldsList[index] = item.copy(mappedValue = newValue)
                                    },
                                    label = { Text(stringResource(R.string.mapped_value)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(Modifier.height(4.dp))

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    val macros = listOf("{CODE}", "{TIMESTAMP}", "{DATETIME}", "{SCANNER_ID}")
                                    for (m in macros) {
                                        SuggestionChip(
                                            onClick = {
                                                val current = fieldsList[index].mappedValue
                                                fieldsList[index] = item.copy(
                                                    mappedValue = if (current.isEmpty()) m else "$current$m"
                                                )
                                            },
                                            label = { Text("+ $m", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val formatted = JSONObject(currentText).toString(2)
                                currentText = formatted
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.invalid_json_format),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.format_json), style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = currentText,
                    onValueChange = { currentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 25
                )
            }
        }
    }
}
