package com.bf1.admin.tool.ui.cardtool

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.cardtool.CardToolConfig
import com.bf1.admin.tool.cardtool.JoinStyle
import com.bf1.admin.tool.cardtool.MODE_PRETTY_NAMES
import com.bf1.admin.tool.cardtool.MODES
import com.bf1.admin.tool.ui.common.ServerSelector

/**
 * 卡行动页：服务器选择（与上下管理一致） + 可折叠配置卡 + 操作按钮 + 日志卡片，
 * 整页随内容滚动；服务器未保存 GameID 时提示去设置页重新添加（跨版本更新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardToolScreen(
    modifier: Modifier = Modifier,
    viewModel: CardToolViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val activeAccount by viewModel.activeAccount.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    // ── 配置状态 ──
    var selectedMode by rememberSaveable { mutableIntStateOf(0x2) }
    var player by rememberSaveable { mutableIntStateOf(0x40) }
    var minMap by rememberSaveable { mutableStateOf("24") }
    var joinStyle by rememberSaveable { mutableStateOf(JoinStyle.DIRECT) }
    var primeGids by rememberSaveable { mutableStateOf("") }
    var primeRounds by rememberSaveable { mutableStateOf("2") }
    var primeStay by rememberSaveable { mutableStateOf("5") }
    var showConfirm by remember { mutableStateOf(false) }
    var configExpanded by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    if (accounts.isEmpty()) {
        EmptyHint("请先在「设置」中添加并选择 EA 账号")
        return
    }
    if (servers.isEmpty()) {
        EmptyHint("请先在「设置」中添加服务器")
        return
    }

    // 新版本按 GameID 添加服务器，旧服务器没有 GameID 时提示重新添加
    val gameId = activeServer?.gameId
    val gameIdValid = gameId?.length == 14

    // 整页滚动，新日志到达时滚到底部
    val scrollState = rememberScrollState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ServerSelector(
            servers = servers,
            activeServer = activeServer,
            activeAccount = activeAccount,
            onServerSelected = { viewModel.switchServer(it) }
        )

        // 未保存 GameID 的提示（跨版本更新：去设置重新添加）
        if (!gameIdValid) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "该服务器未保存 GameID",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "旧版服务器缺少 GameID，请在「设置」中删除后重新添加服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ConfigCard(
            expanded = configExpanded,
            onExpandedChange = { configExpanded = it },
            selectedMode = selectedMode,
            onModeSelected = { selectedMode = it },
            player = player,
            onPlayerSelected = { player = it },
            minMap = minMap,
            onMinMapChange = { if (it.length <= 3) minMap = it.filter(Char::isDigit) },
            joinStyle = joinStyle,
            onJoinStyleSelected = { joinStyle = it },
            showAdvanced = showAdvanced,
            onShowAdvancedChange = { showAdvanced = it },
            primeGids = primeGids,
            onPrimeGidsChange = { if (it.length <= 200) primeGids = it },
            primeRounds = primeRounds,
            onPrimeRoundsChange = { if (it.length <= 2) primeRounds = it.filter(Char::isDigit) },
            primeStay = primeStay,
            onPrimeStayChange = { if (it.length <= 2) primeStay = it.filter(Char::isDigit) }
        )

        // ═══════ 操作按钮 ═══════
        if (isRunning) {
            OutlinedButton(
                onClick = { viewModel.stop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("■ 停止")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        viewModel.startDiagnostic(buildConfig(gameId.orEmpty(), selectedMode, player, minMap, joinStyle, primeGids, primeRounds, primeStay))
                    },
                    enabled = gameIdValid,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("只读诊断")
                }
                Button(
                    onClick = { showConfirm = true },
                    enabled = gameIdValid,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("开始卡行动")
                }
            }
        }

        // 运行日志卡片（与配置卡一样随页面滚动）
        LogSection(
            logs = logs,
            phase = phase,
            isRunning = isRunning,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 开始卡行动确认
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("开始卡行动") },
            text = { Text("将修改服务器轮换并占位进服，确认开始？") },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        viewModel.startCard(buildConfig(gameId.orEmpty(), selectedMode, player, minMap, joinStyle, primeGids, primeRounds, primeStay))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════════════════════════════════════════════
// 卡行动配置（可折叠卡片，默认收起）
// ═══════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    player: Int,
    onPlayerSelected: (Int) -> Unit,
    minMap: String,
    onMinMapChange: (String) -> Unit,
    joinStyle: JoinStyle,
    onJoinStyleSelected: (JoinStyle) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    primeGids: String,
    onPrimeGidsChange: (String) -> Unit,
    primeRounds: String,
    onPrimeRoundsChange: (String) -> Unit,
    primeStay: String,
    onPrimeStayChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            // 头部：标题 + 折叠箭头（整行可点击）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    "卡行动配置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起配置" else "展开配置"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()

                    // 游戏模式下拉
                    var modeMenu by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = it }) {
                        OutlinedTextField(
                            value = MODE_PRETTY_NAMES[MODES[selectedMode]] ?: "选择模式",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("游戏模式") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            MODES.toSortedMap().forEach { (mode, name) ->
                                DropdownMenuItem(
                                    text = { Text(MODE_PRETTY_NAMES[name] ?: name) },
                                    onClick = {
                                        onModeSelected(mode)
                                        modeMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // 人数
                    Text("人数", style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(0x40, 0x28, 0x20, 0x18)
                        options.forEachIndexed { index, value ->
                            SegmentedButton(
                                selected = player == value,
                                onClick = { onPlayerSelected(value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text("$value")
                            }
                        }
                    }

                    // 最少地图数
                    OutlinedTextField(
                        value = minMap,
                        onValueChange = onMinMapChange,
                        label = { Text("最少地图数") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 进服方式
                    Text("进服方式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(JoinStyle.DIRECT to "直连（推荐）", JoinStyle.CARDTOOL to "观战占位").forEach { (style, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onJoinStyleSelected(style) }
                            ) {
                                RadioButton(selected = joinStyle == style, onClick = { onJoinStyleSelected(style) })
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // 高级选项（预热）
                    TextButton(onClick = { onShowAdvancedChange(!showAdvanced) }) {
                        Text(if (showAdvanced) "收起高级选项" else "高级选项（预热）")
                        Icon(
                            if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = showAdvanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = primeGids,
                                onValueChange = onPrimeGidsChange,
                                label = { Text("暖服 GameID（逗号分隔，可多个）") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = primeRounds,
                                onValueChange = onPrimeRoundsChange,
                                label = { Text("每服进出次数") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = primeStay,
                                onValueChange = onPrimeStayChange,
                                label = { Text("每次停留秒数") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════
// 运行日志卡片（随页面滚动，不固定）
// ═══════════════════════════════════════════════════

@Composable
private fun LogSection(
    logs: List<CardToolViewModel.LogLine>,
    phase: String?,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phase ?: "运行日志",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (isRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                val clipboard = LocalClipboardManager.current
                val context = LocalContext.current
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    },
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制日志",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (logs.isEmpty()) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                logs.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            if (line.isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp).padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════
// 工具
// ═══════════════════════════════════════════════════

private fun buildConfig(
    gameId: String,
    mode: Int,
    player: Int,
    minMap: String,
    joinStyle: JoinStyle,
    primeGids: String,
    primeRounds: String,
    primeStay: String
): CardToolConfig {
    return CardToolConfig(
        gameId = gameId,
        mode = mode,
        player = player,
        minMap = minMap.toIntOrNull() ?: 1,
        joinStyle = joinStyle,
        primeGids = primeGids.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() },
        primeRounds = primeRounds.toIntOrNull() ?: 2,
        primeStaySeconds = primeStay.toIntOrNull() ?: 5
    )
}
