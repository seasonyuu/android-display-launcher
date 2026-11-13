package me.seasonyuu.displaylauncher

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.seasonyuu.displaylauncher.ui.theme.DisplayLauncherTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: LauncherModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DisplayLauncherTheme { LauncherScreen(model) } }
    }
    override fun onResume() { super.onResume(); model.checkConnection(); model.refreshDisplays(); model.loadApps() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(model: LauncherModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showDisplays by rememberSaveable { mutableStateOf(false) }
    var showDiagnostic by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val homeList = rememberLazyListState()
    val searchList = rememberLazyListState()
    val recentList = rememberLazyListState()
    BackHandler(settings) { settings = false }
    val target = state.displays.find { it.id == state.selected }
    val tokens = remember(query) { AppSearch.tokens(query) }
    val searching = tokens.isNotEmpty()
    val filtered = remember(state.apps, tokens) { state.apps.filter { AppSearch.matches(it, tokens) } }
    val context = LocalContext.current
    val packageCounts = remember(state.apps) { state.apps.groupingBy { it.packageName }.eachCount() }
    LaunchedEffect(showDisplays) { model.selector(showDisplays) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (settings) {
            SettingsScreen(state, onBack = { settings = false }, onAdjustCount = model::adjustRecentCount, modifier = Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("apps-list"), state = if (searching) searchList else homeList, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DISPLAY / LAUNCHER", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { settings = true }) { Text("设置") }
                }
                Spacer(Modifier.height(8.dp))
                Text("应用，去你想去的屏幕。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("选择一个屏幕，然后打开应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (state.ready) Color(0xFF2C8B65) else MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.connection, fontWeight = FontWeight.Medium)
                            Text(if (state.ready) "通过 Shizuku 执行跨屏启动" else "授权后即可启动应用和预览屏幕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!state.ready) TextButton(onClick = model::authorize) { Text("连接 / 授权") }
                    }
                }
            }
            item {
                Card(onClick = { showDisplays = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("目标屏幕", style = MaterialTheme.typography.labelLarge)
                            Text("选择屏幕 →", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(target?.let { "${if (it.id == 0) "主屏" else "屏幕 ${it.id}"} · ${it.name}" } ?: "请选择屏幕", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(target?.let { "${it.width} × ${it.height} px   ·   ${it.dpi} dpi   ·   Display ${it.id}" } ?: "目标屏幕可能已断开", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.message != null) item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp)) }
                        Text(state.message!!)
                        if (!state.busy) TextButton(onClick = { showDiagnostic = true }) { Text("查看诊断") }
                    }
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索应用或包名") }, supportingText = { Text("支持拼音和首字母") }, trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") }
                }, singleLine = true, shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(16.dp))
            }
            if (!searching && state.recentApps.isNotEmpty()) item {
                Text("最近使用", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                LazyRow(state = recentList, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recentApps, key = { it.component }) { app ->
                        Card(modifier = Modifier.testTag("recent-${app.component}"), onClick = { model.launch(app) }, enabled = state.ready && !state.busy) {
                            Column(Modifier.width(92.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(model, app)
                                Spacer(Modifier.height(8.dp))
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            item {
                Text("${if (searching) "搜索结果" else "全部应用"} · ${filtered.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.refreshingApps && state.apps.isNotEmpty()) Text("正在后台校验…", style = MaterialTheme.typography.bodySmall)
                state.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::loadApps) { Text("重试") } }
                state.preferencesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (state.loadingApps) item { CircularProgressIndicator() }
            if (!state.loadingApps && filtered.isEmpty()) item { Text("未找到匹配的应用") }
            items(filtered, key = { it.component }) { app ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = state.ready && !state.busy, role = Role.Button) { model.launch(app) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(model, app)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if ((packageCounts[app.packageName] ?: 0) > 1) Text(app.component.substringAfter('/'), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("↗", style = MaterialTheme.typography.headlineSmall, color = if (state.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    if (showDisplays) ModalBottomSheet(onDismissRequest = { showDisplays = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择屏幕", style = MaterialTheme.typography.headlineSmall)
                        Text("快照仅用于辨认屏幕，不会持续录制", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = model::refreshPreviews, enabled = state.ready) { Text("刷新快照") }
                }
            }
            items(state.displays, key = { it.id }) { display ->
                LaunchedEffect(display, state.previewRevision, state.ready) { model.requestPreview(display) }
                DisplayCard(display, state.previews[display.id], display.id == state.selected, state.ready) {
                    model.select(display.id); showDisplays = false
                }
            }
            if (state.displays.isEmpty()) item { Text("未检测到可用屏幕") }
        }
    }
    if (state.displayUnavailable) AlertDialog(
        onDismissRequest = model::dismissDisplayAlert,
        title = { Text("目标屏幕不可用") },
        text = { Text("目标屏幕已断开或失效，无法在该屏幕打开应用。请重新选择一个可用屏幕后再试。") },
        confirmButton = { TextButton(onClick = {
            model.dismissDisplayAlert()
            model.refreshDisplays()
            showDisplays = true
        }) { Text("选择屏幕") } },
        dismissButton = { TextButton(onClick = model::dismissDisplayAlert) { Text("取消") } },
    )
    if (showDiagnostic) AlertDialog(onDismissRequest = { showDiagnostic = false }, title = { Text("启动诊断") }, text = {
        LazyColumn { item { Text(state.diagnostic, style = MaterialTheme.typography.bodySmall) } }
    }, confirmButton = { TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("启动诊断", state.diagnostic))
        showDiagnostic = false
    }) { Text("复制并关闭") } }, dismissButton = { TextButton(onClick = { showDiagnostic = false }) { Text("关闭") } })
}

@Composable
private fun DisplayCard(display: DisplayItem, preview: Preview?, isSelected: Boolean, ready: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(Modifier.fillMaxWidth().semantics { selected = isSelected }.clip(shape)
        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(role = Role.RadioButton, onClick = onClick), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                if (preview?.bitmap != null) Image(preview.bitmap.asImageBitmap(), "屏幕 ${display.id} 快照", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else DisplaySchematic(display, Modifier.fillMaxSize().padding(16.dp))
            }
            if (preview?.bitmap == null) {
                Spacer(Modifier.height(8.dp))
                Text("屏幕比例示意 · ${if (!ready) "连接 Shizuku 后可尝试预览" else preview?.message ?: "等待预览…"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text("${if (isSelected) "●  " else ""}${display.name} · Display ${display.id}", style = MaterialTheme.typography.titleMedium)
            Text("${display.width} × ${display.height} px  ·  逻辑密度 ${display.dpi} dpi", style = MaterialTheme.typography.bodyMedium)
            Text("${String.format(Locale.ROOT, "%.1f", display.hz)} Hz  ·  旋转 ${display.rotation}°  ·  ${if (display.state == android.view.Display.STATE_ON) "已开启" else if (display.state == android.view.Display.STATE_OFF) "已关闭" else "状态 ${display.state}"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (display.modeWidth != display.width || display.modeHeight != display.height) Text("物理模式 ${display.modeWidth} × ${display.modeHeight} px", style = MaterialTheme.typography.bodySmall)
            if (preview?.time != null && preview.time > 0) Text("快照 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(preview.time))} · 受保护内容可能为空白", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AppIcon(model: LauncherModel, app: AppItem) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, app.iconKey) {
        value = null
        value = model.loadIcon(app)
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            ?: Text(app.label.take(1), style = MaterialTheme.typography.titleLarge)
    }
}

/** Fit the logical pixel dimensions inside the preview, without distorting portrait/ultrawide displays. */
@Composable
private fun DisplaySchematic(display: DisplayItem, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val outline = MaterialTheme.colorScheme.primary
    if (display.width <= 0 || display.height <= 0) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("屏幕尺寸不可用", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Canvas(modifier.semantics {
        contentDescription = "屏幕 ${display.id} 比例示意图，${display.width} × ${display.height} 像素，非实际快照"
    }) {
        val scale = minOf(size.width / display.width, size.height / display.height)
        val width = display.width * scale
        val height = display.height * scale
        val origin = Offset((size.width - width) / 2f, (size.height - height) / 2f)
        val dimensions = Size(width, height)
        val radius = CornerRadius(minOf(4.dp.toPx(), width / 8f, height / 8f))
        val stroke = minOf(2.dp.toPx(), width / 8f, height / 8f)
        drawRoundRect(fill, origin, dimensions, radius)
        for (division in 1..2) {
            val fraction = division / 3f
            drawLine(outline.copy(alpha = 0.15f), origin + Offset(width * fraction, stroke),
                origin + Offset(width * fraction, height - stroke), strokeWidth = stroke / 2)
            drawLine(outline.copy(alpha = 0.15f), origin + Offset(stroke, height * fraction),
                origin + Offset(width - stroke, height * fraction), strokeWidth = stroke / 2)
        }
        drawRoundRect(outline, origin + Offset(stroke / 2, stroke / 2),
            Size(width - stroke, height - stroke), radius, style = Stroke(stroke))
    }
}
