package me.seasonyuu.displaylauncher

import android.icu.text.Transliterator
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CatalogIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun ready(repo: AppRepository) = withTimeout(15000) {
        repo.catalog.first { !it.loading && !it.refreshing }
    }
    @Test fun systemIcuGeneratesPinyinAndInitials() {
        val latin = Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower").transliterate("微信")
        val app = AppItem("p/.A", "微信", "com.tencent.mm", "", AppSearch.fields("微信", "com.tencent.mm", latin), 0)
        for (query in listOf("微信", "weixin", "wx", "ＷＸ", "WeiXin mm", "  wx  ")) {
            assertTrue("$query: $latin", AppSearch.matches(app, AppSearch.tokens(query)))
        }
    }
    @Test fun coldDiskCorruptionFailureAndConflatedRefresh() = runBlocking<Unit> {
        val root = File(context.cacheDir, "catalog-test-${System.nanoTime()}")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val user = android.os.Process.myUid() / 100000
        val app = AppItem("p/.A", "微信", "p", "a".repeat(64), listOf("微信", "weixin", "wx"), user)
        try {
            val first = AppRepository(context, scope, root, { assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); listOf(app) }, false)
            assertEquals(listOf(app), ready(first).apps)
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val calls = AtomicInteger()
            val second = AppRepository(context, scope, root, { calls.incrementAndGet(); error("query failed") }, false)
            withTimeout(5000) { second.catalog.first { it.apps.isNotEmpty() } }
            assertTrue(AppSearch.matches(second.catalog.value.apps.single(), AppSearch.tokens("wx")))
            withTimeout(5000) { second.catalog.first { it.error != null } }
            assertEquals(listOf(app), second.catalog.value.apps)
            repeat(30) { second.refresh() }
            delay(1000)
            assertEquals(2, calls.get())
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            File(root, "catalog.json").writeText("broken")
            assertEquals(listOf(app), ready(AppRepository(context, scope, root, { listOf(app) }, false)).apps)
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            root.deleteRecursively()
            assertEquals(listOf(app), ready(AppRepository(context, scope, root, { listOf(app) }, false)).apps)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun invalidatedQueryCannotPublishAndNextRefreshWins() = runBlocking<Unit> {
        val root = File(context.cacheDir, "catalog-race-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val app = AppItem("p/.A", "latest", "p", "b".repeat(64), listOf("latest"), 0)
        try {
            val repo = AppRepository(context, scope, root, {
                if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); listOf(app.copy(label = "old")) }
                else listOf(app)
            }, false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(20) { repo.refresh() }; release.countDown()
            assertEquals("latest", ready(repo).apps.single().label)
            assertEquals(2, calls.get())
        } finally { release.countDown(); scope.cancel(); root.deleteRecursively() }
    }
    @Test fun realIconsUseMemoryAndDiskAndSurviveCacheDeletion() = runBlocking<Unit> {
        val root = File(context.cacheDir, "catalog-icons-${System.nanoTime()}")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = AppRepository(context, scope, root, registerEvents = false)
            val app = ready(repo).apps.first { it.packageName == context.packageName }
            val icon = repo.loadIcon(app)!!
            assertEquals(192, icon.width); assertEquals(192, icon.height)
            assertSame(icon, repo.loadIcon(app))
            repo.refresh(); delay(1000)
            assertSame(icon, repo.loadIcon(app))
            assertTrue(File(root, "icons/${app.iconKey}.png").exists())
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val fresh = AppRepository(context, scope, root, registerEvents = false)
            withTimeout(5000) { fresh.catalog.first { it.apps.isNotEmpty() } }
            assertTrue(icon.sameAs(fresh.loadIcon(app)))
            root.deleteRecursively()
            assertSame(icon, repo.loadIcon(app))
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun configurationInvalidatesIconsAndDiskBudgetIsEnforced() = runBlocking<Unit> {
        val root = File(context.cacheDir, "catalog-config-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = context.applicationContext.resources
        val original = android.content.res.Configuration(resources.configuration)
        try {
            val repo = AppRepository(context, scope, root, registerEvents = false)
            val before = ready(repo).apps.first { it.packageName == context.packageName }
            assertNotNull(repo.loadIcon(before))
            val changed = android.content.res.Configuration(original).apply {
                setLocales(android.os.LocaleList(java.util.Locale.SIMPLIFIED_CHINESE)); densityDpi += 20
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(changed, resources.displayMetrics)
            repo.refresh()
            val after = withTimeout(5000) { repo.catalog.first { state -> state.apps.any { it.component == before.component && it.iconKey != before.iconKey } } }
                .apps.first { it.component == before.component }
            assertNotEquals(before.iconKey, after.iconKey)
            assertNotNull(repo.loadIcon(after))
            assertFalse(File(root, "icons/${before.iconKey}.png").exists())
            val obsolete = File(root, "icons/obsolete.png").apply { writeText("unused") }
            repo.refresh(); delay(1000)
            assertFalse(obsolete.exists())
            val file = File(root, "icons/${after.iconKey}.png")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(65L * 1024 * 1024) }
            repo.refresh(); delay(1000)
            assertTrue(File(root, "icons").listFiles()!!.sumOf { it.length() } <= 64L * 1024 * 1024)
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
            scope.cancel(); root.deleteRecursively()
        }
    }

    @Test fun packageUpdateDisableAndUninstallBroadcasts() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val apk = "/data/local/tmp/display-launcher-fixture.apk"
        org.junit.Assume.assumeTrue("Stage fixture APK first", shell("ls $apk") == apk)
        val repo = AppRepository.get(context)
        repo.refresh()
        val pkg = "me.seasonyuu.displaylauncher.fixture"
        val component = "$pkg/.StandardActivity"
        val original = withTimeout(10000) { repo.catalog.first { state -> state.apps.any { it.component == component } } }
            .apps.first { it.component == component }
        var stage = "record"
        try {
            repo.recent.record(original, "confirmed")
            stage = "update"
            assertTrue(shell("pm install -r $apk").contains("Success"))
            withTimeout(10000) { repo.catalog.first { state -> state.apps.any { it.component == component && it.iconKey != original.iconKey } } }
            assertTrue(repo.recent.state.value.entries.any { it.component == component })
            stage = "disable"
            assertTrue(shell("pm disable-user --user 0 $pkg").contains("disabled-user"))
            withTimeout(10000) { repo.catalog.first { state -> state.apps.none { it.component == component } } }
            assertTrue(repo.recent.state.value.entries.any { it.component == component })
            assertTrue(RecentRules.visible(repo.catalog.value.apps, repo.recent.state.value).none { it.packageName == pkg })
            stage = "enable"
            assertTrue(shell("pm enable --user 0 $pkg").contains("enabled"))
            withTimeout(10000) { repo.catalog.first { state -> state.apps.any { it.component == component } } }
            stage = "uninstall"
            assertTrue(shell("pm uninstall $pkg").contains("Success"))
            withTimeout(10000) { repo.recent.state.first { it.entries.none { entry -> entry.packageName == pkg } } }
            withTimeout(10000) { repo.catalog.first { state -> state.apps.none { it.packageName == pkg } } }
        } catch (e: Exception) { throw AssertionError("Package event stage: $stage; error=${repo.catalog.value.error}", e)
        } finally {
            shell("pm install -r $apk"); assertTrue(shell("pm enable --user 0 $pkg").contains("enabled"))
        }
    }

    @Test fun preferencesPersistDeduplicateAndKeepHiddenHistory() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = RecentStore(context, scope)
        val original = withTimeout(5000) { store.state.first { it.error == null } }.count
        val prefix = "catalog.test."
        try {
            for (i in 0..21) {
                val app = AppItem("$prefix$i/.One", "test", "$prefix$i", "", emptyList(), 0)
                store.record(app, "confirmed")
            }
            val last = AppItem("${prefix}21/.Two", "test", "${prefix}21", "", emptyList(), 0)
            store.record(last, "mismatch"); store.record(last.copy(component = "${prefix}21/.Failed"), "failed")
            store.setCount(0)
            withTimeout(5000) { store.state.first { it.count == 0 && it.entries.firstOrNull()?.component == last.component } }
            store.setCount(20)
            val reopened = RecentStore(context, scope)
            val result = withTimeout(5000) { reopened.state.first { it.count == 20 } }
            assertEquals(22, result.entries.count { it.packageName.startsWith(prefix) })
            assertEquals(last.component, result.entries.first().component)
            store.removePackage(0, last.packageName)
            withTimeout(5000) { store.state.first { it.entries.none { entry -> entry.packageName == last.packageName } } }
        } finally {
            for (i in 0..21) store.removePackage(0, "$prefix$i")
            store.setCount(original); scope.cancel()
        }
    }
}
