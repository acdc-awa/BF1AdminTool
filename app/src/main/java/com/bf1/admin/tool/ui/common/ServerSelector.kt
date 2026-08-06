package com.bf1.admin.tool.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity

/**
 * 服务器选择器（下拉），「上下管理」与「卡行动」共用同一样式。
 * [enabled] 为 false 时禁用（如卡行动运行中），默认开启。
 */
@Composable
fun ServerSelector(
    servers: List<ServerEntity>,
    activeServer: ServerEntity?,
    activeAccount: AccountEntity?,
    onServerSelected: (ServerEntity) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var buttonWidth by remember { mutableIntStateOf(0) }
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
            enabled = enabled,
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
