package me.seasonyuu.displaylauncher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsRestoreSearchAndPersistBounds() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        compose.waitUntil(15000) { !model.state.value.loadingApps }
        val repo = AppRepository.get(compose.activity)
        runBlocking { repo.recent.setCount(3) }
        compose.waitUntil { model.state.value.recentCount == 3 }
        val app = model.state.value.apps.first { it.packageName == "me.seasonyuu.displaylauncher.fixture" }
        runBlocking { repo.recent.record(app, "confirmed") }
        compose.waitUntil { model.state.value.recentApps.isNotEmpty() }
        compose.onNodeWithTag("apps-list").performScrollToNode(hasText("最近使用"))
        compose.onNodeWithText("最近使用").assertIsDisplayed()
        compose.onNodeWithText("搜索应用或包名").performTextInput("fixture")
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithTag("apps-list").performScrollToNode(hasText("设置"))
        val selected = model.state.value.selected
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("最近使用数量").performClick()
        repeat(3) { index ->
            compose.onNodeWithContentDescription("减少").performClick()
            compose.waitUntil { model.state.value.recentCount == 2 - index }
        }
        compose.onNodeWithContentDescription("减少").assertIsNotEnabled()
        repeat(20) { index ->
            compose.onNodeWithContentDescription("增加").performClick()
            compose.waitUntil { model.state.value.recentCount == index + 1 }
        }
        compose.onNodeWithContentDescription("增加").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNodeWithText("20").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("显示 20 个应用").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("fixture").assertExists()
        assertEquals(selected, model.state.value.selected)
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithTag("apps-list").performScrollToNode(hasText("清空"))
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithTag("apps-list").performScrollToNode(hasText("最近使用"))
        compose.onNodeWithText("最近使用").assertIsDisplayed()
        compose.onNodeWithText("搜索应用或包名").performTextInput("no_such_app_123")
        compose.onNodeWithTag("apps-list").performScrollToNode(hasText("未找到匹配的应用"))
        compose.onNodeWithText("未找到匹配的应用").assertIsDisplayed()
        runBlocking { repo.recent.setCount(3) }
    }

    @Test fun recentLaunchUsesCurrentlySelectedDisplay() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.loadingApps }
        val repo = AppRepository.get(compose.activity)
        val app = model.state.value.apps.first { it.component.endsWith("/.StandardActivity") }
        runBlocking { repo.recent.setCount(3); repo.recent.record(app, "confirmed") }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previous = shell("settings get global overlay_display_devices")
        org.junit.Assume.assumeTrue(previous == "null" || previous.isEmpty())
        val before = model.state.value.displays.map { it.id }.toSet()
        try {
            shell("settings put global overlay_display_devices 800x600/160")
            compose.waitUntil(5000) { model.state.value.displays.any { it.id !in before } }
            val display = model.state.value.displays.first { it.id !in before }.id
            compose.runOnUiThread { model.select(display) }
            compose.waitUntil { model.state.value.recentApps.any { it.component == app.component } }
            compose.onNodeWithTag("apps-list").performScrollToNode(hasTestTag("recent-${app.component}"))
            compose.onNodeWithTag("recent-${app.component}").performClick()
            compose.waitUntil(20000) { !model.state.value.busy && model.state.value.message?.contains("已确认") == true }
            assertEquals("已确认应用位于屏幕 $display", model.state.value.message)
            assertEquals(app.component, model.state.value.recentApps.first().component)
        } finally {
            if (previous == "null") shell("settings delete global overlay_display_devices")
            else shell("settings put global overlay_display_devices ''")
            compose.runOnUiThread { model.select(0) }
        }
    }

    @Test fun searchAndLaunchFromUi() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.loadingApps }
        compose.onNodeWithText("搜索应用或包名").performTextInput("fixture")
        compose.onNodeWithText("Display Test Target").assertIsDisplayed()
        compose.onNodeWithText("Chrome").assertDoesNotExist()
        compose.onNodeWithText("Display Test Target").performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.message != null }
        assertEquals("已确认应用位于屏幕 0", model.state.value.message)
    }
}
