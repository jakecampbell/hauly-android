package com.jakecampbell.hauly.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.BuildConfig
import com.jakecampbell.hauly.presentation.common.OfflineBanner

@Composable
fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.syncRequested) {
        if (state.syncRequested) {
            snackbarHostState.showSnackbar("Sync scheduled — it runs as soon as you're online.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        if (!state.isOnline) {
            OfflineBanner(pendingEdits = 0)
        }

        LabeledValue("Shopping List database", state.shoppingDatabaseId.ifEmpty { "—" })
        LabeledValue("Recipe database", state.recipeDatabaseId.ifEmpty { "—" })
        LabeledValue("Last synced", state.lastSyncLabel ?: "Never")

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Button(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth()) {
            Text("Sync now")
        }

        Text(
            "To change the Notion token or databases, clear the app's data and run setup again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BetaTokenSection(
            tokenSet = state.betaTokenSet,
            onSave = { token ->
                viewModel.saveBetaToken(token)
                snackbarMessage = if (token.isBlank()) "Beta token cleared." else "Beta token saved."
            },
        )

        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }
}

/**
 * Enter (or replace/clear) the hauly-backend beta token that unlocks the
 * clipboard recipe-extraction flow. Unlike the Notion values above, it can be
 * changed any time without re-running setup. Saved without validation — a bad
 * token surfaces as a clear error when an extraction is submitted.
 */
@Composable
private fun BetaTokenSection(tokenSet: Boolean, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }

    Text("Recipe extraction (beta)", style = MaterialTheme.typography.titleMedium)
    Text(
        if (tokenSet) {
            "A beta token is saved. Enter a new one to replace it, or save empty to clear."
        } else {
            "Paste your Hauly beta token to extract recipes from copied text."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Hauly beta token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                onSave(token)
                token = ""
            },
            enabled = token.isNotBlank() || tokenSet,
        ) { Text("Save") }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
