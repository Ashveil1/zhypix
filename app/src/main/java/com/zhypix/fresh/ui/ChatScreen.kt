package com.zhypix.fresh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(
    vm: AgentViewModel,
    onOpenProviders: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val items by vm.items.collectAsState()
    val runState by vm.runState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Zhypix") },
                actions = {
                    TextButton(onClick = onOpenAccessibilitySettings) { Text("Access") }
                    TextButton(onClick = onOpenProviders) { Text("Providers") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(items.size) { i ->
                    when (val item = items[i]) {
                        is AgentViewModel.ChatItem.User -> Bubble(item.text, isUser = true)
                        is AgentViewModel.ChatItem.Agent -> Bubble(item.text, isUser = false)
                        is AgentViewModel.ChatItem.Status -> Text(
                            item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        is AgentViewModel.ChatItem.Step -> Text(
                            "Step ${item.n}/${item.max}: ${item.action}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when (runState) {
                                is AgentViewModel.RunState.WaitingForUser -> "Reply to the agent..."
                                else -> "What should I do on your phone?"
                            }
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                if (runState is AgentViewModel.RunState.Running) {
                    Button(onClick = { vm.stop() }) { Text("Stop") }
                } else {
                    Button(onClick = {
                        vm.send(input)
                        input = ""
                    }) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
