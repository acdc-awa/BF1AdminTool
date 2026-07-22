package com.bf1.admin.tool.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.data.remote.EAApiService

/**
 * Tab 0: 管理员管理。
 * 三段式中间区域：顶部固定(服务器选择 + 列表头) + 中间滚动(管理员列表) + 底部固定(增减管理员)。
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
            Column {
                ServerSelector(
                    servers = servers,
                    activeServer = activeServer,
                    activeAccount = activeAccount,
                    onServerSelected = onSwitchServer
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // ═══════ 管理员列表表头（固定）═══════
        val countSuffix = if (filteredAdminList.size != adminList.size)
            "${filteredAdminList.size}/${adminList.size}"
        else
            "${adminList.size}"
        Text(
            "管理员列表 ($countSuffix)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

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
            if (filteredAdminList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isRefreshingAdminList -> "正在加载..."
                            playerInput.isNotBlank() -> "无匹配的管理员"
                            else -> "暂无管理员"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAdminList, key = { it.personaId }) { admin ->
                        AdminCard(
                            admin = admin,
                            onLongPress = { onDeleteAdmin(admin) }
                        )
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = isRefreshingAdminList,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        // ═══════ 增减管理员（固定底部）═══════
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
// 服务器选择器
// ──────────────────────────────────────

@Composable
private fun ServerSelector(
    servers: List<com.bf1.admin.tool.data.local.entity.ServerEntity>,
    activeServer: com.bf1.admin.tool.data.local.entity.ServerEntity?,
    activeAccount: com.bf1.admin.tool.data.local.entity.AccountEntity?,
    onServerSelected: (com.bf1.admin.tool.data.local.entity.ServerEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var buttonWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "ArrowRotation"
    )

    Box(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
            OutlinedButton(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { buttonWidth = it.width }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Dns, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            activeServer?.serverName ?: "未选择服务器",
                            maxLines = 1
                        )
                    }
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "展开",
                        modifier = Modifier.rotate(iconRotation)
                    )
                }
            }

            val dropdownWidth = with(density) { buttonWidth.toDp() }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(dropdownWidth)
            ) {
                if (activeAccount == null) {
                    DropdownMenuItem(
                        text = { Text("请先登录账号") },
                        onClick = { expanded = false }
                    )
                } else if (servers.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无服务器，请到设置页添加") },
                        onClick = { expanded = false }
                    )
                } else {
                    servers.forEach { server ->
                        val isSelected = activeServer?.id == server.id
                        DropdownMenuItem(
                            text = {
                                Text(
                                    server.serverName,
                                    maxLines = 2,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onServerSelected(server)
                                expanded = false
                            },
                            modifier = if (isSelected) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }


// ──────────────────────────────────────
// 管理员卡片（长按删除）
// ──────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdminCard(
    admin: EAApiService.AdminInfo,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(admin.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "PID: ${admin.personaId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ──────────────────────────────────────
// 增减管理员操作栏
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
        modifier = modifier,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddAdmin,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && playerInput.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加管理员")
                    }
                }
                OutlinedButton(
                    onClick = onRemoveAdmin,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && playerInput.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("移除管理员")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = playerInput,
                onValueChange = onInputChange,
                label = { Text("搜索或输入玩家名") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
    }
}
