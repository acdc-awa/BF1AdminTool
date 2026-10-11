package com.bf1.admin.tool.ui.cardtool

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.cardtool.CardToolConfig
import com.bf1.admin.tool.cardtool.MODE_PRETTY_NAMES
import com.bf1.admin.tool.cardtool.MODES
import com.bf1.admin.tool.ui.common.ServerSelector

/**
 * 卡行动页 (Material 3 顶级质感控制台)：
 * 服务器选择 + 状态指示卡 + 可折叠配置卡 + 控制动作组 + 高级终端日志区。
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
    val lastResult by viewModel.lastResult.collectAsState()

    // ── 配置状态 ──
    var selectedMode by rememberSaveable { mutableIntStateOf(0x2) }
    var player by rememberSaveable { mutableIntStateOf(0x40) }
    var minMap by rememberSaveable { mutableStateOf("24") }
    var primeGid by rememberSaveable { mutableStateOf("") }
    var primeRounds by rememberSaveable { mutableStateOf("2") }
    var primeStay by rememberSaveable { mutableStateOf("5") }
    var debugLogs by rememberSaveable { mutableStateOf(false) }
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

    fun currentConfig() = buildConfig(
        gameId = gameId.orEmpty(),
        mode = selectedMode,
        player = player,
        minMap = minMap,
        primeGid = primeGid,
        primeRounds = primeRounds,
        primeStay = primeStay,
        debugLogs = debugLogs
    )

    // 整页滚动，新日志到达时滚到底部；用户手动上滑翻阅时暂停跟随
    val scrollState = rememberScrollState()
    val followThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && scrollState.value >= scrollState.maxValue - followThresholdPx) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 服务器选择器
        ServerSelector(
            servers = servers,
            activeServer = activeServer,
            activeAccount = activeAccount,
            onServerSelected = { viewModel.switchServer(it) },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )

        // 运行状态横幅
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (isRunning) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRunning) "卡行动服务运行中" else "控制台待命",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRunning) (phase ?: "执行长连接保活中...") else "配置完毕后点击下方按钮启动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 未保存 GameID 的警告卡片
        if (!gameIdValid) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "该服务器未保存 GameID",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "旧版服务器缺少 GameID，请在「设置」中删除后重新添加服务器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // 配置卡片
        ConfigCard(
            enabled = !isRunning,
            expanded = configExpanded,
            onExpandedChange = { configExpanded = it },
            selectedMode = selectedMode,
            onModeSelected = { selectedMode = it },
            player = player,
            onPlayerSelected = { player = it },
            minMap = minMap,
            onMinMapChange = { if (it.length <= 3) minMap = it.filter(Char::isDigit) },
            showAdvanced = showAdvanced,
            onShowAdvancedChange = { showAdvanced = it },
            primeGid = primeGid,
            onPrimeGidChange = { if (it.length <= 14) primeGid = it.filter(Char::isDigit) },
            primeRounds = primeRounds,
            onPrimeRoundsChange = { if (it.length <= 2) primeRounds = it.filter(Char::isDigit) },
            primeStay = primeStay,
            onPrimeStayChange = { if (it.length <= 2) primeStay = it.filter(Char::isDigit) },
            debugLogs = debugLogs,
            onDebugLogsChange = { debugLogs = it }
        )

        // ═══════ 操作按钮 ═══════
        if (isRunning) {
            Button(
                onClick = { viewModel.stop() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("停止卡行动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.startDiagnostic(currentConfig()) },
                    enabled = gameIdValid,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("验证账号", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showConfirm = true },
                    enabled = gameIdValid,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("开始卡行动", fontWeight = FontWeight.SemiBold)
                }
            }

            // 自动锚定失败后的补救
            FilledTonalButton(
                onClick = { viewModel.startManualAnchor(currentConfig()) },
                enabled = gameIdValid,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("手动锚定当前轮换（先进服）", fontWeight = FontWeight.Medium)
            }
        }

        // 运行日志终端卡片
        LogSection(
            logs = logs,
            phase = phase,
            isRunning = isRunning,
            lastResult = lastResult,
            onClear = { viewModel.clearLogs() },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 开始卡行动确认
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RocketLaunch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("启动卡行动", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "即将对当前服务器开启长时连接保活与占位任务，确认立即启动？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        viewModel.startCard(currentConfig())
                    }
                ) { Text("确认启动") }
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
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════
// 卡行动配置（可折叠 M3 卡片）
// ═══════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    player: Int,
    onPlayerSelected: (Int) -> Unit,
    minMap: String,
    onMinMapChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    primeGid: String,
    onPrimeGidChange: (String) -> Unit,
    primeRounds: String,
    onPrimeRoundsChange: (String) -> Unit,
    primeStay: String,
    onPrimeStayChange: (String) -> Unit,
    debugLogs: Boolean,
    onDebugLogsChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            // 头部：标题 + 图标 + 折叠箭头
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "卡行动参数配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${MODE_PRETTY_NAMES[MODES[selectedMode]] ?: "标准模式"} · $player 人",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起配置" else "展开配置",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // 游戏模式下拉
                    var modeMenu by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = it }) {
                        OutlinedTextField(
                            value = MODE_PRETTY_NAMES[MODES[selectedMode]] ?: "选择模式",
                            onValueChange = {},
                            readOnly = true,
                            enabled = enabled,
                            label = { Text("游戏模式") },
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = modeMenu,
                            onDismissRequest = { modeMenu = false },
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(12.dp)
                            )
                        ) {
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

                    // 人数分段选择器
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "服务器人数规模",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(0x40, 0x28, 0x20, 0x18)
                            options.forEachIndexed { index, value ->
                                SegmentedButton(
                                    selected = player == value,
                                    onClick = { onPlayerSelected(value) },
                                    enabled = enabled,
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                                ) {
                                    Text("$value 人", fontWeight = if (player == value) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    // 最少地图数
                    OutlinedTextField(
                        value = minMap,
                        onValueChange = onMinMapChange,
                        enabled = enabled,
                        label = { Text("最少地图数量") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 高级选项（预热）
                    TextButton(
                        onClick = { onShowAdvancedChange(!showAdvanced) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (showAdvanced) "收起预热高级设置" else "展开预热高级设置")
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(
                                value = primeGid,
                                onValueChange = onPrimeGidChange,
                                enabled = enabled,
                                label = { Text("预设暖服 GameID（选填，单个）") },
                                placeholder = { Text("例如：11295245190321") },
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = primeRounds,
                                    onValueChange = onPrimeRoundsChange,
                                    enabled = enabled,
                                    label = { Text("每服进出轮数") },
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = primeStay,
                                    onValueChange = onPrimeStayChange,
                                    enabled = enabled,
                                    label = { Text("停留秒数") },
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "详细 Debug 日志",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "默认关闭（仅展示核心阶段与进度）；开启后输出完整底层协议报文与调试信息",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = debugLogs,
                                    onCheckedChange = onDebugLogsChange,
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════
// 运行日志终端卡片 (M3 Terminal Console 风格)
// ═══════════════════════════════════════════════════

@Composable
private fun LogSection(
    logs: List<CardToolViewModel.LogLine>,
    phase: String?,
    isRunning: Boolean,
    lastResult: CardToolViewModel.ResultSummary?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            // 工具栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "控制台日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (phase != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = phase,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                logs.joinToString("\n") { "${timeFmt.format(Date(it.timestamp))} ${it.text}" }
                            )
                        )
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制日志",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onClear,
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "清空日志",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 结果高亮横幅
            lastResult?.let { r ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (r.success) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (r.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = (if (r.success) "完成: " else "失败: ") + r.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (r.success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )

            // 日志内容输出流
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无控制台运行日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.forEach { line ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = timeFmt.format(Date(line.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.padding(top = 1.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (line.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
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
    primeGid: String,
    primeRounds: String,
    primeStay: String,
    debugLogs: Boolean
): CardToolConfig {
    return CardToolConfig(
        gameId = gameId,
        mode = mode,
        player = player,
        minMap = minMap.toIntOrNull() ?: 1,
        primeGid = primeGid.trim(),
        primeRounds = primeRounds.toIntOrNull() ?: 2,
        primeStaySeconds = primeStay.toIntOrNull() ?: 5,
        debugLogs = debugLogs
    )
}
