package me.seasonyuu.displaylauncher

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

data class DisplayItem(val id: Int, val name: String, val width: Int, val height: Int, val dpi: Int,
    val hz: Float, val rotation: Int, val state: Int, val modeWidth: Int, val modeHeight: Int)
data class Preview(val bitmap: Bitmap? = null, val message: String = "正在获取快照…", val time: Long = 0)
data class LauncherState(
    val connection: String = "检查 Shizuku…", val ready: Boolean = false,
    val displays: List<DisplayItem> = emptyList(), val selected: Int? = 0,
    val apps: List<AppItem> = emptyList(), val loadingApps: Boolean = true,
    val recentApps: List<AppItem> = emptyList(), val recentCount: Int = 3,
    val refreshingApps: Boolean = false, val catalogError: String? = null, val preferencesError: String? = null,
    val previews: Map<Int, Preview> = emptyMap(), val previewRevision: Int = 0, val busy: Boolean = false,
    val message: String? = null, val diagnostic: String = "尚无启动记录"
)

class LauncherModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val context = app
    private val repository = AppRepository.get(app)
    private val mutable = MutableStateFlow(LauncherState(selected = savedState.get<Int>("selectedDisplay").let { if (it == null) 0 else it.takeIf { id -> id >= 0 } }))
    val state = mutable.asStateFlow()
    private val dm = app.getSystemService(DisplayManager::class.java)
    private var remote: IPrivilegedService? = null
    private var binding = false
    private var bindTimeout: Job? = null
    private val previewMutex = Mutex()
    @Volatile private var previewGeneration = 0
    @Volatile private var selectorOpen = false
    private val args = Shizuku.UserServiceArgs(ComponentName(app, PrivilegedService::class.java))
        .daemon(false).processNameSuffix("privileged").tag("display-launcher").version(3)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false; bindTimeout?.cancel()
            remote = IPrivilegedService.Stub.asInterface(binder)
            mutable.update { it.copy(ready = remote != null, connection = "Shizuku 已连接") }
            if (selectorOpen) refreshPreviews()
        }
        override fun onServiceDisconnected(name: ComponentName?) { disconnected() }
    }
    private val received = Shizuku.OnBinderReceivedListener { checkConnection() }
    private val dead = Shizuku.OnBinderDeadListener { disconnected() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) checkConnection()
        else mutable.update { it.copy(connection = "Shizuku 授权被拒绝", ready = false) }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refreshDisplays()
        override fun onDisplayRemoved(id: Int) = refreshDisplays()
        override fun onDisplayChanged(id: Int) = refreshDisplays()
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        viewModelScope.launch {
            combine(repository.catalog, repository.recent.state) { catalog, recent -> catalog to recent }
                .collect { (catalog, recent) -> mutable.update { it.copy(
                    apps = catalog.apps, loadingApps = catalog.loading, refreshingApps = catalog.refreshing,
                    catalogError = catalog.error, recentApps = RecentRules.visible(catalog.apps, recent),
                    recentCount = recent.count, preferencesError = recent.error,
                ) } }
        }
        refreshDisplays(); loadApps(); checkConnection()
    }
    private fun disconnected() {
        remote = null; binding = false; bindTimeout?.cancel(); previewGeneration++
        mutable.update { it.copy(ready = false, connection = "Shizuku 连接断开，请重新连接", previews = emptyMap()) }
    }
    fun checkConnection() {
        try {
            if (!Shizuku.pingBinder()) {
                previewGeneration++
                remote = null
                mutable.update { it.copy(ready = false, connection = "Shizuku 未运行", previews = emptyMap()) }
                viewModelScope.launch {
                    val installed = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0) }.isSuccess }
                    if (!Shizuku.pingBinder()) mutable.update { it.copy(connection = if (installed) "Shizuku 未运行" else "尚未安装 Shizuku") }
                }
                return
            }
            if (Shizuku.getVersion() < 13) {
                mutable.update { it.copy(ready = false, connection = "请升级 Shizuku 至 v13 或以上") }; return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                previewGeneration++
                remote = null
                mutable.update { it.copy(ready = false, connection = "等待 Shizuku 授权", previews = emptyMap()) }; return
            }
            if (remote?.asBinder()?.isBinderAlive == true || binding) return
            binding = true
            mutable.update { it.copy(connection = "正在连接特权服务…", ready = false) }
            Shizuku.bindUserService(args, connection)
            bindTimeout = viewModelScope.launch {
                delay(10000)
                if (binding) {
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    binding = false
                    mutable.update { it.copy(connection = "连接超时，请重试") }
                }
            }
        } catch (e: Exception) { binding = false; mutable.update { it.copy(ready = false, connection = "连接失败：${e.message}") } }
    }
    fun authorize() {
        viewModelScope.launch { runCatching {
            if (!Shizuku.pingBinder()) {
                val intent = withContext(Dispatchers.IO) { context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") }
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    mutable.update { it.copy(message = "请在 Shizuku 的应用管理中允许本应用使用权限") }
                } else Shizuku.requestPermission(1)
            } else checkConnection()
        }.onFailure { e -> mutable.update { it.copy(message = e.message ?: "无法打开 Shizuku") } } }
    }
    @Suppress("DEPRECATION")
    fun refreshDisplays() {
        val displays = dm.displays.mapNotNull { display -> runCatching {
            val metrics = DisplayMetrics(); display.getRealMetrics(metrics)
            val dpi = context.createDisplayContext(display).resources.configuration.densityDpi
            DisplayItem(display.displayId, display.name, metrics.widthPixels, metrics.heightPixels, dpi,
                display.refreshRate, display.rotation * 90, display.state, display.mode.physicalWidth, display.mode.physicalHeight)
        }.getOrNull() }.sortedBy { it.id }
        val old = mutable.value.displays
        mutable.update { s -> s.copy(displays = displays, selected = s.selected?.takeIf { id -> displays.any { it.id == id } }) }
        savedState["selectedDisplay"] = mutable.value.selected ?: -1
        if (old != displays) {
            previewGeneration++
            mutable.update { it.copy(previews = emptyMap()) }
            if (selectorOpen) refreshPreviews()
        }
    }
    fun loadApps() = repository.refresh()
    suspend fun loadIcon(app: AppItem): Bitmap? = repository.loadIcon(app)
    fun adjustRecentCount(delta: Int) {
        viewModelScope.launch {
            runCatching { repository.recent.adjustCount(delta) }.onFailure {
                mutable.update { it.copy(preferencesError = "最近使用数量保存失败，请重试") }
            }
        }
    }
    fun select(id: Int) { if (mutable.value.displays.any { it.id == id }) { savedState["selectedDisplay"] = id; mutable.update { it.copy(selected = id) } } }
    fun selector(open: Boolean) {
        selectorOpen = open
        previewGeneration++
        mutable.update { it.copy(previews = emptyMap()) }
        if (open) refreshPreviews()
    }
    fun refreshPreviews() {
        previewGeneration++
        mutable.update { it.copy(previews = emptyMap(), previewRevision = previewGeneration) }
    }
    fun requestPreview(display: DisplayItem) {
        val service = remote ?: return
        if (!selectorOpen || !mutable.value.ready || mutable.value.previews.containsKey(display.id)) return
        val generation = previewGeneration
        mutable.update { it.copy(previews = it.previews + (display.id to Preview())) }
        viewModelScope.launch {
            val preview = if (display.state == Display.STATE_OFF) Preview(message = "屏幕已关闭") else withContext(Dispatchers.IO) {
                previewMutex.withLock {
                    if (generation != previewGeneration || !selectorOpen) return@withLock Preview(message = "已取消")
                    runCatching {
                        val result = service.capturePreview(display.id)
                        val bitmap = result.image?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use(BitmapFactory::decodeStream) }
                        Preview(bitmap, result.message, result.timestamp)
                    }.getOrElse { Preview(message = "无法预览 · 服务不可用") }
                }
            }
            if (selectorOpen && generation == previewGeneration && mutable.value.ready) {
                mutable.update { it.copy(previews = it.previews + (display.id to preview)) }
            }
        }
    }
    fun launch(app: AppItem) {
        if (mutable.value.busy) return
        val display = mutable.value.selected ?: return
        val service = remote ?: return
        mutable.update { it.copy(busy = true, message = "正在启动 ${app.label}…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku 授权不可用" }
                check(dm.getDisplay(display)?.isValid == true) { "目标屏幕已断开" }
                val component = requireNotNull(ComponentName.unflattenFromString(app.component))
                val info = context.packageManager.getActivityInfo(component, 0)
                check(info.enabled && info.exported && info.applicationInfo.enabled) { "应用入口已不可用" }
                service.launch(Bundle().apply {
                    putString("component", app.component); putInt("display", display)
                    putInt("user", android.os.Process.myUid() / 100000)
                })
            }.getOrElse { Bundle().apply { putString("message", it.message ?: "服务连接中断"); putString("status", "failed") } } }
            val saved = runCatching { repository.recent.record(app, result.getString("status")) }.isSuccess
            mutable.update { it.copy(busy = false, message = if (saved) result.getString("message") else
                "${result.getString("message")}\n应用已启动，但最近使用未保存", diagnostic =
                "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${app.component}\n目标 display=$display\n状态=${result.getString("status")}\n${result.getString("message")}\n${result.getString("details", "")}") }
        }
    }
    override fun onCleared() {
        dm.unregisterDisplayListener(displayListener)
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
}
