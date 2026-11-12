package me.seasonyuu.displaylauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: LauncherState, onBack: () -> Unit, onAdjustCount: (Int) -> Unit, modifier: Modifier = Modifier) {
    var showCount by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "返回") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            Text("应用列表", Modifier.padding(start = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = { showCount = true }).padding(vertical = 8.dp),
                    headlineContent = { Text("最近使用数量") },
                    supportingContent = { Text(if (state.recentCount == 0) "不显示最近使用" else "显示 ${state.recentCount} 个应用") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_history), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            Text("仅记录通过本应用启动的应用。调整显示数量不会删除最近使用记录。",
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.preferencesError?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (showCount) AlertDialog(
        onDismissRequest = { showCount = false },
        title = { Text("最近使用数量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("选择首页显示的应用数量，设为 0 时隐藏最近使用。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { onAdjustCount(-1) }, enabled = state.recentCount > 0,
                        modifier = Modifier.semantics { contentDescription = "减少" }) { Text("−", style = MaterialTheme.typography.headlineSmall) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.recentCount}", style = MaterialTheme.typography.displaySmall)
                        Text(if (state.recentCount == 0) "已隐藏" else "个应用", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalIconButton(onClick = { onAdjustCount(1) }, enabled = state.recentCount < 20,
                        modifier = Modifier.semantics { contentDescription = "增加" }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                }
                Text("最多 20 个 · 更改自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.preferencesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { showCount = false }) { Text("完成") } },
    )
}
