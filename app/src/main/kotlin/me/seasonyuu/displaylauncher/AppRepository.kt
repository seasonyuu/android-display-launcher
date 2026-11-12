package me.seasonyuu.displaylauncher

import android.content.*
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.text.Transliterator
import android.os.Build
import android.util.AtomicFile
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** One instance per application process. The privileged service never accesses these stores. */
class AppRepository internal constructor(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    cacheRoot: File? = null,
    private val queryOverride: (() -> List<AppItem>)? = null,
    registerEvents: Boolean = true,
) {
    private val context = context.applicationContext
    private val root by lazy { cacheRoot ?: File(this.context.cacheDir, "app_catalog") }
    data class Catalog(val apps: List<AppItem> = emptyList(), val loading: Boolean = true,
        val refreshing: Boolean = false, val error: String? = null)
    private val mutable = MutableStateFlow(Catalog())
    val catalog = mutable.asStateFlow()
    val recent = RecentStore(context, scope)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val generation = AtomicLong()
    private val iconMutex = Mutex()
    private val icons = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val user = android.os.Process.myUid() / 100000
    private val snapshot get() = AtomicFile(File(root, "catalog.json"))
    private val iconDir get() = File(root, "icons")
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                intent.data?.schemeSpecificPart?.let { pkg -> scope.launch {
                    runCatching { recent.removePackage(user, pkg) }.onFailure {
                        mutable.update { it.copy(error = "卸载记录清理失败，请重试") }
                    }
                } }
            }
            refresh()
        }
    }
    init {
        if (registerEvents) {
            val packages = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            register(packages)
            register(IntentFilter().apply { addAction(Intent.ACTION_LOCALE_CHANGED); addAction(Intent.ACTION_CONFIGURATION_CHANGED) })
        }
        scope.launch {
            runCatching { readSnapshot() }.getOrNull()?.let { mutable.value = Catalog(it, loading = false) }
            for (ignored in requests) {
                // A single worker, one trailing refresh and a quiet period merge foreground/broadcast bursts.
                delay(250)
                while (requests.tryReceive().isSuccess) { delay(250) }
                val ticket = generation.get()
                mutable.update { it.copy(refreshing = true, error = null) }
                try {
                    val apps = queryOverride?.invoke() ?: queryApps()
                    if (ticket != generation.get()) continue
                    val saved = runCatching { writeSnapshot(apps) }.isSuccess
                    if (ticket != generation.get()) continue
                    mutable.value = Catalog(apps, loading = false, error = if (saved) null else "目录缓存保存失败，可重试")
                    iconMutex.withLock { cleanIcons(apps.map { it.iconKey }.toSet()) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(loading = false, refreshing = false, error = "应用目录刷新失败，请重试") }
                }
            }
        }
        refresh()
    }
    private fun register(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    }
    fun refresh() { generation.incrementAndGet(); requests.trySend(Unit) }
    private fun configurationKey(): String = Configuration(context.resources.configuration).let {
        "${it.locales.toLanguageTags()}|${it.densityDpi}|${it.uiMode}|${it.orientation}|${it.screenLayout}|" +
            "${it.screenWidthDp}|${it.screenHeightDp}|${it.smallestScreenWidthDp}|${it.colorMode}|${it.fontScale}"
    }
    private fun key(component: String, updated: Long, config: String): String = MessageDigest.getInstance("SHA-256")
        .digest("v1|$user|$component|$updated|$config|192".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun queryApps(): List<AppItem> {
        val config = configurationKey()
        val pm = context.packageManager
        val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        val updates = mutableMapOf<String, Long>()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .map { info ->
                val pkg = info.activityInfo.packageName
                val component = ComponentName(pkg, info.activityInfo.name).flattenToShortString()
                val updated = updates.getOrPut(pkg) { pm.getPackageInfo(pkg, 0).lastUpdateTime }
                val label = info.loadLabel(pm).toString()
                AppItem(component, label, pkg, key(component, updated, config),
                    AppSearch.fields(label, pkg, transliterator.transliterate(label)), user)
            }.distinctBy { it.component }.sortedWith(compareBy({ AppSearch.normalize(it.label) }, { it.component }))
    }
    suspend fun loadIcon(app: AppItem): Bitmap? = withContext(Dispatchers.IO) {
        iconMutex.withLock {
            icons.get(app.iconKey)?.let { return@withLock it }
            // Ignore obsolete composed rows after a directory refresh.
            if (mutable.value.apps.none { it.iconKey == app.iconKey }) return@withLock null
            runCatching {
                val file = File(iconDir, "${app.iconKey}.png")
                val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                val bitmap = cached?.takeIf { it.width == 192 && it.height == 192 } ?: run {
                    val pm = context.packageManager
                    val updated = pm.getPackageInfo(app.packageName, 0).lastUpdateTime
                    if (key(app.component, updated, configurationKey()) != app.iconKey) { refresh(); return@withLock null }
                    val component = requireNotNull(ComponentName.unflattenFromString(app.component))
                    val loaded = pm.getActivityIcon(component).toBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    // A disappearing cache directory or a full disk must not prevent rendering an icon.
                    runCatching {
                        iconDir.mkdirs()
                        val atomic = AtomicFile(file)
                        val stream = atomic.startWrite()
                        try { check(loaded.compress(Bitmap.CompressFormat.PNG, 100, stream)); atomic.finishWrite(stream) }
                        catch (e: Exception) { atomic.failWrite(stream); throw e }
                    }
                    loaded
                }
                file.setLastModified(System.currentTimeMillis())
                icons.put(app.iconKey, bitmap)
                trimDisk()
                bitmap
            }.getOrNull()
        }
    }
    private fun readSnapshot(): List<AppItem> {
        val obj = JSONObject(snapshot.openRead().bufferedReader().use { it.readText() })
        require(obj.getInt("version") == 1 && obj.getInt("user") == user)
        val array = obj.getJSONArray("apps")
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            val component = it.getString("component")
            require(ComponentName.unflattenFromString(component) != null)
            val iconKey = it.getString("icon")
            require(iconKey.matches(Regex("[0-9a-f]{64}")))
            val fields = it.getJSONArray("search")
            AppItem(component, it.getString("label"), it.getString("package"), iconKey,
                (0 until fields.length()).map(fields::getString), user)
        } }
    }
    private fun writeSnapshot(apps: List<AppItem>) {
        root.mkdirs()
        val data = JSONObject().put("version", 1).put("user", user).put("apps", JSONArray().apply {
            apps.forEach { put(JSONObject().put("component", it.component).put("label", it.label)
                .put("package", it.packageName).put("icon", it.iconKey).put("search", JSONArray(it.searchIndex))) }
        }).toString().toByteArray()
        val atomic = snapshot
        val stream = atomic.startWrite()
        try { stream.write(data); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun cleanIcons(valid: Set<String>) {
        icons.snapshot().keys.filterNot { it in valid }.forEach { icons.remove(it) }
        iconDir.listFiles()?.filterNot { it.nameWithoutExtension in valid }?.forEach { it.delete() }
        trimDisk()
    }
    private fun trimDisk() {
        val files = iconDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }
    companion object {
        @SuppressLint("StaticFieldLeak") // Only the application context is retained.
        @Volatile private var instance: AppRepository? = null
        fun get(context: Context): AppRepository = instance ?: synchronized(this) {
            instance ?: AppRepository(context.applicationContext).also { instance = it }
        }
    }
}
