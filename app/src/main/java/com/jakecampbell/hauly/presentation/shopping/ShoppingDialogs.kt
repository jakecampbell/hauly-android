package com.jakecampbell.hauly.presentation.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.util.titleCaseWhileTyping

/**
 * Type-ahead add dialog. Typing narrows a suggestion list of existing items
 * (from the cache and, when online, Notion itself). Selecting a suggestion
 * pre-fills the quantity; confirming an unknown name creates a new item
 * tagged with the store view it was added from.
 */
@Composable
fun AddItemDialog(
    state: AddItemUiState,
    currentStore: String?,
    onQueryChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onSelectSuggestion: (ShoppingItem) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("haul") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onQueryChange(it.lowercase()) },
                    label = { Text("Name") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.isSearchingRemote) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    supportingText = {
                        when {
                            state.selected != null ->
                                Text("Existing item selected")

                            state.query.isNotBlank() && state.suggestions.isEmpty() &&
                                !state.isSearchingRemote ->
                                Text("New item — will be created in Notion")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.suggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        state.suggestions.forEach { suggestion ->
                            SuggestionRow(
                                suggestion = suggestion,
                                isSelected = state.selected?.name == suggestion.name,
                                onClick = { onSelectSuggestion(suggestion) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.quantityText,
                    onValueChange = onQuantityChange,
                    label = { Text("Quantity (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (currentStore != null) {
                    Text(
                        text = "Will be tagged for $currentStore",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canConfirm) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SuggestionRow(
    suggestion: ShoppingItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            val detail = buildList {
                add(if (suggestion.shopped) "shopped before" else "on the list")
                if (suggestion.stores.isNotEmpty()) add(suggestion.stores.joinToString(", "))
            }.joinToString(" · ")
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        suggestion.quantity?.let {
            Text(
                text = "× ${formatQuantity(it)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Long-press edit dialog for an item's properties: name, stores, and quantity.
 * Shared by the shopping list and the recipe ingredient list. An empty
 * quantity clears the Qty in Notion. Delete removes the item from the Notion
 * database (a second tap confirms).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditItemDialog(
    item: ShoppingItem,
    storeOptions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, stores: List<String>, quantity: Double?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(item.localId) { mutableStateOf(item.name) }
    var quantityText by remember(item.localId) {
        mutableStateOf(item.quantity?.let(::formatQuantity) ?: "")
    }
    val selected = remember(item.localId) { item.stores.toMutableStateList() }
    var newStore by remember(item.localId) { mutableStateOf("") }
    var confirmDelete by remember(item.localId) { mutableStateOf(false) }
    // The item may carry stores that aren't in the schema options yet.
    val options = (storeOptions + item.stores).distinctBy { it.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase() },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Stores",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { store ->
                        FilterChip(
                            selected = selected.any { it.equals(store, ignoreCase = true) },
                            onClick = {
                                val existing = selected.firstOrNull { it.equals(store, ignoreCase = true) }
                                if (existing != null) selected.remove(existing) else selected.add(store)
                            },
                            label = { Text(store) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = newStore,
                    onValueChange = { newStore = titleCaseWhileTyping(it) },
                    label = { Text("New store (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val stores = (selected + newStore.trim().takeIf { it.isNotEmpty() })
                        .filterNotNull()
                        .distinctBy { it.lowercase() }
                    onConfirm(name.trim(), stores, quantityText.trim().toDoubleOrNull())
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                // Destructive: the first tap arms the button, the second deletes.
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(
                        text = if (confirmDelete) "Really delete?" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorePickerDialog(
    item: ShoppingItem,
    options: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember(item.localId) { item.stores.toMutableStateList() }
    var newStore by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stores for ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { store ->
                        FilterChip(
                            selected = store in selected,
                            onClick = {
                                if (store in selected) selected.remove(store) else selected.add(store)
                            },
                            label = { Text(store) },
                        )
                    }
                }
                OutlinedTextField(
                    value = newStore,
                    onValueChange = { newStore = titleCaseWhileTyping(it) },
                    label = { Text("New store (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val stores = (selected + newStore.trim().takeIf { it.isNotEmpty() })
                        .filterNotNull()
                        .distinctBy { it.lowercase() }
                    onConfirm(stores)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
