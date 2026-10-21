package com.zhypix.fresh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhypix.fresh.data.ProviderStore
import java.util.UUID

@Composable
fun ProvidersScreen(vm: AgentViewModel, onBack: () -> Unit) {
    val providers by vm.providers.collectAsState()
    val selection by vm.selection.collectAsState()
    var editing by remember { mutableStateOf<ProviderStore.Provider?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = {
                        editing = ProviderStore.Provider(UUID.randomUUID().toString(), "", "", "", emptyList())
                        showEditor = true
                    }) { Text("Add") }
                }
            )
        }
    ) { padding ->
        if (providers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No providers yet.\nAdd one with its base URL, API key and model names.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(providers, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name.ifBlank { p.baseUrl }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { editing = p; showEditor = true }) { Text("Edit") }
                                TextButton(onClick = { vm.store.deleteProvider(p.id); vm.refreshProviders() }) { Text("Delete") }
                            }
                            Text(p.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            if (p.models.isEmpty()) {
                                Text("No models listed", style = MaterialTheme.typography.bodySmall)
                            } else {
                                p.models.forEach { m ->
                                    val selected = selection?.providerId == p.id && selection?.model == m
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.store.saveSelection(ProviderStore.Selection(p.id, m))
                                                vm.refreshProviders()
                                            }
                                    ) {
                                        RadioButton(selected = selected, onClick = {
                                            vm.store.saveSelection(ProviderStore.Selection(p.id, m))
                                            vm.refreshProviders()
                                        })
                                        Text(m, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor && editing != null) {
        ProviderEditorDialog(
            initial = editing!!,
            onDismiss = { showEditor = false },
            onSave = { p ->
                vm.store.saveProvider(p)
                if (vm.selection.value?.providerId == null && p.models.isNotEmpty()) {
                    vm.store.saveSelection(ProviderStore.Selection(p.id, p.models.first()))
                }
                vm.refreshProviders()
                showEditor = false
            }
        )
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: ProviderStore.Provider,
    onDismiss: () -> Unit,
    onSave: (ProviderStore.Provider) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "Add provider" else "Edit provider") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (OpenAI-compatible)") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = models, onValueChange = { models = it },
                    label = { Text("Model names (comma separated)") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val modelList = models.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                    onSave(initial.copy(name = name, baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), models = modelList))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
