package com.bf1.admin.tool.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onNavigateToWebView: () -> Unit,
    onNavigateToManual: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加 EA 账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
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
            Text("选择登录方式", style = MaterialTheme.typography.titleMedium)

            Card(
                onClick = onNavigateToWebView,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.Web, null, Modifier.size(40.dp))
                    Column {
                        Text("网页登录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在内置浏览器中登录 EA 账号，自动获取凭证",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(
                onClick = onNavigateToManual,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.Edit, null, Modifier.size(40.dp))
                    Column {
                        Text("手动输入", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "手动粘贴 remid 和 sid cookie 值",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
