package me.seasonyuu.displaylauncher

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.seasonyuu.displaylauncher.ui.theme.DisplayLauncherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingBrowserShowsSnackbar() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisplayLauncherTheme { AboutScreen(onBack = {}) }
            }
        }
        compose.onNodeWithText("项目主页").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.about_link_unavailable)).assertIsDisplayed()
    }
}
