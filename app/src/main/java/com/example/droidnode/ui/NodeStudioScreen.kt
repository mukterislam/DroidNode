package com.example.droidnode.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.droidnode.service.NodeServerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeStudioScreen(
    viewModel: NodeRuntimeViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isServerRunning by NodeServerService.isRunning.collectAsState()
    val serverLogs by NodeServerService.serverLogs.collectAsState()

    var packageNameInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll log console when new lines arrive
    LaunchedEffect(serverLogs.size, uiState.installLogs.size) {
        val total = serverLogs.size + uiState.installLogs.size
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DroidNode Engine Studio") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // 1. Server Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Node.js HTTP Server",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isServerRunning) "Listening on http://127.0.0.1:8080" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isServerRunning) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(context, NodeServerService::class.java).apply {
                                action = if (isServerRunning) NodeServerService.ACTION_STOP else NodeServerService.ACTION_START
                            }
                            context.startService(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServerRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isServerRunning) "Stop Server" else "Start Server")
                    }
                }
            }

            // 2. Dynamic NPM Package Installer Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Dynamic NPM Package Installer",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = packageNameInput,
                            onValueChange = { packageNameInput = it },
                            placeholder = { Text("e.g. express, ws, cors, lodash") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !uiState.isInstalling
                        )

                        Button(
                            onClick = {
                                if (packageNameInput.isNotBlank()) {
                                    viewModel.installPackage(packageNameInput.trim())
                                }
                            },
                            enabled = !uiState.isInstalling && packageNameInput.isNotBlank()
                        ) {
                            if (uiState.isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text("Install")
                            }
                        }
                    }
                }
            }

            // 3. Terminal Log Output Console
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Console Output",
                        style = MaterialTheme.typography.titleSmall
                    )

                    TextButton(onClick = {
                        NodeServerService.clearLogs()
                        viewModel.clearInstallLogs()
                    }) {
                        Text("Clear Logs")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    val allLogs = (serverLogs + uiState.installLogs)

                    if (allLogs.isEmpty()) {
                        Text(
                            text = "Terminal ready. Start server or install a package to view real-time logs...",
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(allLogs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = when {
                                        logLine.contains("ERR", ignoreCase = true) || logLine.contains("error", ignoreCase = true) -> Color(0xFFEF4444)
                                        logLine.contains("npm", ignoreCase = true) -> Color(0xFF38BDF8)
                                        logLine.contains("listen", ignoreCase = true) -> Color(0xFF4ADE80)
                                        else -> Color(0xFFE2E8F0)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
