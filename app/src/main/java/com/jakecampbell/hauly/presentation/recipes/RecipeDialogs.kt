package com.jakecampbell.hauly.presentation.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Create a new recipe: a name plus optional ingredient and instruction text
 * (one item/step per line). Confirming is online-first (handled upstream).
 * The initial values prefill the fields when reviewing a clipboard extraction;
 * the remembers are keyed on them so a different extraction doesn't reuse
 * another's edited field state.
 */
@Composable
fun RecipeCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, ingredients: String, instructions: String, url: String) -> Unit,
    initialName: String = "",
    initialIngredients: String = "",
    initialInstructions: String = "",
    initialUrl: String = "",
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var ingredients by remember(initialIngredients) { mutableStateOf(initialIngredients) }
    var instructions by remember(initialInstructions) { mutableStateOf(initialInstructions) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("new recipe") },
        text = {
            // The multi-line fields grow to fit all of their text (no inner
            // scrolling — important when reviewing a long extraction), and the
            // whole panel scrolls once it outgrows the dialog.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ingredients,
                    onValueChange = { ingredients = it },
                    label = { Text("Ingredients (one per line)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions (one per line)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Link (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, ingredients, instructions, url) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A max-sized dialog with one large text area for typing (or pasting) a
 * free-text recipe (R8.15). Confirming sends the text to the backend's magic
 * extractor via the same flow as the clipboard paste — a SUBMITTING row appears
 * and the dialog closes; all further feedback lives on that extraction row.
 */
@Composable
fun RecipeFreeTextDialog(
    onDismiss: () -> Unit,
    onCreate: (text: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        // Fill the screen rather than the platform's narrow dialog width — the
        // whole point is a roomy text area for a long recipe.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding()) {
                Text(
                    text = "free-text recipe",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                // Grows to fill the dialog and scrolls internally past that.
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            "Paste or type your recipe - a description, list of ingredients, " +
                                "half-remembered steps. The recipe service will make sense of it.",
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onCreate(text) },
                        enabled = text.isNotBlank(),
                    ) { Text("Create") }
                }
            }
        }
    }
}

/** Edit (or clear) the recipe's source link. */
@Composable
fun RecipeUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("link") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Link (leave empty to clear)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Rename an existing recipe. */
@Composable
fun RecipeRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
