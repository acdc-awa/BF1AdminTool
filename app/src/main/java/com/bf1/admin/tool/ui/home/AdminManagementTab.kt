package com.bf1.admin.tool.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.ui.admin.AdminViewModel
import com.bf1.admin.tool.ui.common.ServerSelector

/**
 * Tab 0: 管理员管理 (Material 3 规范顶级质感)。
 * 三段式布局：顶部固定(服务器选择器 + 精致表头) + 中间滚动(卡片列表) + 底部固定(增减操作栏)。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AdminManagementTab(
    modifier: Modifier = Modifier,
    servers: List<com.bf1.admin.tool.data.local.entity.ServerEntity>,
    activeServer: com.bf1.admin.tool.data.local.entity.ServerEntity?,
    activeAccount: com.bf1.admin.tool.data.local.entity.AccountEntity?,
    adminList: List<EAApiService.AdminInfo>,
    isRefreshingAdminList: Boolean,
    isLoading: Boolean,
    uiState: AdminViewModel.AdminListUiState = AdminViewModel.AdminListUiState.Idle,
    onNavigateToLogin: () -> Unit = {},
    onSwitchServer: (com.bf1.admin.tool.data.local.entity.ServerEntity) -> Unit,
    onRefreshAdminList: () -> Unit,
    onAddAdmin: (String) -> Unit,
    onRemoveAdmin: (String) -> Unit,
    onDeleteAdmin: (EAApiService.AdminInfo) -> Unit,
    playerInput: String,
    onPlayerInputChange: (String) -> Unit
) {
    // ═══════ 搜索过滤 ═══════
    val filteredAdminList = remember(adminList, playerInput) {
        if (playerInput.isBlank()) {
            adminList
        } else {
            val keyword = playerInput.trim().lowercase()
            adminList.filter { admin ->
                admin.displayName.lowercase().contains(keyword)
                        || admin.personaId.toString().contains(keyword)
            }
        }
    }

    // 键盘检测
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible by remember(imeBottom) { derivedStateOf { imeBottom > 0 } }

    Column(modifier = modifier.fillMaxSize().animateContentSize()) {
        // ═══════ 服务器选择器（键盘弹起时收起）═══════
        AnimatedVisibility(
            visible = !isKeyboardVisible,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            ServerSelector(
                servers = servers,
                activeServer = activeServer,
                activeAccount = activeAccount,
                onServerSelected = onSwitchServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ═══════ 管理员列表表头（M3 芯片风格）═══════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "管理员列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    val countText = if (filteredAdminList.size != adminList.size) {
                        "${filteredAdminList.size} / ${adminList.size}"
                    } else {
                        "${adminList.size}"
                    }
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            IconButton(
                onClick = onRefreshAdminList,
                enabled = !isRefreshingAdminList,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ═══════ 管理员列表（可滚动 + 下拉刷新）═══════
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshingAdminList,
            onRefresh = onRefreshAdminList
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pullRefresh(pullRefreshState)
        ) {
            when {
                uiState is AdminViewModel.AdminListUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "正在同步管理员列表",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "正在连接 EA 网关验证凭据并获取数据...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                uiState is AdminViewModel.AdminListUiState.NetworkError -> {
                    val state = uiState
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "网络连接异常",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = state.message.ifBlank { "无法连接至 EA 战地1 服务端网关，请检查网络连接。" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(18.dp))
                                Button(
                                    onClick = onRefreshAdminList,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新加载")
                                }
                            }
                        }
                    }
                }
                uiState is AdminViewModel.AdminListUiState.AuthExpired -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.KeyOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "账号凭证已失效",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "EA 账号登录凭证已过期，请重新登录账号以恢复管理员管理权限。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(18.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = onRefreshAdminList,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("重试")
                                    }
                                    Button(
                                        onClick = onNavigateToLogin,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("重新登录")
                                    }
                                }
                            }
                        }
                    }
                }
                uiState is AdminViewModel.AdminListUiState.NoServer -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Dns,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "未选择服务器",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "请在上方切换选择要管理的服务器，或前往「设置」添加服务器。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
                uiState is AdminViewModel.AdminListUiState.Error -> {
                    val state = uiState
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "加载管理员失败",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(18.dp))
                                Button(
                                    onClick = onRefreshAdminList,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新尝试")
                                }
                            }
                        }
                    }
                }
                filteredAdminList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (playerInput.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = if (playerInput.isNotBlank()) "没有找到匹配的管理员" else "当前服务器暂无管理员",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredAdminList, key = { it.personaId }) { admin ->
                            AdminCard(
                                admin = admin,
                                onDelete = { onDeleteAdmin(admin) }
                            )
                        }
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = isRefreshingAdminList,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        // ═══════ 增减管理员操作栏（固定底部）═══════
        AdminActionBar(
            modifier = Modifier.imePadding(),
            playerInput = playerInput,
            onInputChange = onPlayerInputChange,
            isLoading = isLoading,
            onAddAdmin = { onAddAdmin(playerInput) },
            onRemoveAdmin = { onRemoveAdmin(playerInput) }
        )
    }
}

// ──────────────────────────────────────
// 管理员卡片 (Material 3 容器风格)
// ──────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdminCard(
    admin: EAApiService.AdminInfo,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像徽章
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = admin.displayName.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // 名字与 PID
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = admin.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = "PID: ${admin.personaId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // 复制 PID 按钮
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(admin.personaId.toString()))
                    Toast.makeText(context, "已复制 PID: ${admin.personaId}", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制 PID",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = "移除管理员",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ──────────────────────────────────────
// 增减管理员操作栏 (Material 3 高架底栏)
// ──────────────────────────────────────

@Composable
private fun AdminActionBar(
    modifier: Modifier = Modifier,
    playerInput: String,
    onInputChange: (String) -> Unit,
    isLoading: Boolean,
    onAddAdmin: () -> Unit,
    onRemoveAdmin: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 输入搜索框
            OutlinedTextField(
                value = playerInput,
                onValueChange = { onInputChange(it.replace("\n", "")) },
                placeholder = {
                    Text(
                        "搜索或输入玩家名称 / PID",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (playerInput.isNotEmpty()) {
                        IconButton(
                            onClick = { onInputChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空输入",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )

            Spacer(Modifier.height(10.dp))

            // 动作按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAddAdmin,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && playerInput.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加管理", fontWeight = FontWeight.SemiBold)
                    }
                }

                FilledTonalButton(
                    onClick = onRemoveAdmin,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && playerInput.isNotBlank(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("移除管理", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
