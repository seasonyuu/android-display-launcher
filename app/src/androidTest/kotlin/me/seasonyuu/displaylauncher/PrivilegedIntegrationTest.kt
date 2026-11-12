package me.seasonyuu.displaylauncher

import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires a running, authorized Shizuku and the :test-target APK. */
@RunWith(AndroidJUnit4::class)
class PrivilegedIntegrationTest {
    private fun withService(block: (IPrivilegedService) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val binderLatch = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { binderLatch.countDown() }
        Shizuku.addBinderReceivedListenerSticky(listener)
        try {
            assertTrue("Start Shizuku first", binderLatch.await(10, TimeUnit.SECONDS))
            assertEquals("Authorize Display Launcher first", 0, Shizuku.checkSelfPermission())
            val latch = CountDownLatch(1)
            var service: IPrivilegedService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IPrivilegedService.Stub.asInterface(binder); latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedService::class.java))
                .daemon(false).tag("integration-tests-${System.nanoTime()}").processNameSuffix("test_privileged").version(1)
            instrumentation.runOnMainSync { Shizuku.bindUserService(args, connection) }
            try {
                assertTrue("UserService connection timed out", latch.await(10, TimeUnit.SECONDS))
                block(requireNotNull(service))
            } finally { Shizuku.unbindUserService(args, connection, true) }
        } finally { Shizuku.removeBinderReceivedListener(listener) }
    }
    private fun request(activity: String, display: Int = 0, user: Int = android.os.Process.myUid() / 100000) = Bundle().apply {
        putString("component", "me.seasonyuu.displaylauncher.fixture/.$activity")
        putInt("display", display); putInt("user", user)
    }
    /** Read-only vivo verification: no display creation, target launches or setting changes. */
    @Test fun capturesVivoVirtualOutputsAndFallsBackForUnbackedMirror() = withService { service ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java).displays
        val names = setOf("vivo_car_screen_3", "vivo_car_screen", "vivo_car_screen_phone_mirror")
        val targets = displays.filter { it.name in names }
        assertEquals("Keep vivo car displays connected for this test", 3, targets.size)
        repeat(2) {
            for (display in targets) {
                val started = android.os.SystemClock.elapsedRealtime()
                val result = service.capturePreview(display.displayId)
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                if (display.name == "vivo_car_screen_phone_mirror") {
                    assertEquals(result.message, "unsupported", result.status)
                    assertNull(result.image)
                    println("${display.name} display=${display.displayId}: unsupported, ${elapsed}ms")
                } else {
                    assertEquals(result.message, "ready", result.status)
                    val bitmap = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(result.image)).use { BitmapFactory.decodeStream(it) }
                    assertNotNull(bitmap)
                    assertTrue(maxOf(bitmap.width, bitmap.height) <= 640)
                    println("${display.name} display=${display.displayId}: ready ${bitmap.width}x${bitmap.height}, ${elapsed}ms")
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun startsStandardAndReusesSingleTask() = withService { service ->
        for (activity in listOf("StandardActivity", "SingleTaskActivity", "SingleTaskActivity")) {
            val result = service.launch(request(activity))
            assertEquals(result.getString("details"), "confirmed", result.getString("status"))
            assertEquals(0, result.getInt("actualDisplay"))
        }
    }
    @Test fun invalidDisplayAndCrossUserFail() = withService { service ->
        assertEquals("failed", service.launch(request("StandardActivity", display = 99999)).getString("status"))
        assertEquals("failed", service.launch(request("StandardActivity", user = 999)).getString("status"))
    }
    @Test fun trampolineDoesNotFalselyConfirm() = withService { service ->
        assertEquals("unverified", service.launch(request("TrampolineActivity")).getString("status"))
    }
    @Test fun launchesOnSimulatedSecondaryDisplay() = withService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        val previous = shell("settings get global overlay_display_devices")
        org.junit.Assume.assumeTrue("Do not replace existing user overlays", previous == "null" || previous.isEmpty())
        val dm = instrumentation.targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        val before = dm.displays.map { it.displayId }.toSet()
        try {
            shell("settings put global overlay_display_devices 800x600/160")
            var secondary: android.view.Display? = null
            val deadline = System.currentTimeMillis() + 5000
            while (secondary == null && System.currentTimeMillis() < deadline) {
                secondary = dm.displays.firstOrNull { it.displayId !in before }
                if (secondary == null) Thread.sleep(100)
            }
            assertNotNull("Simulated display not created", secondary)
            val id = secondary!!.displayId
            val result = service.launch(request("StandardActivity", display = id))
            assertEquals(result.getString("details"), "confirmed", result.getString("status"))
            assertEquals(id, result.getInt("actualDisplay"))
            assertEquals("unsupported", service.capturePreview(id).status)
        } finally {
            if (previous == "null") shell("settings delete global overlay_display_devices")
            else shell("settings put global overlay_display_devices ''")
        }
    }
    @Test fun physicalPreviewIsScaledAndVirtualMappingFailsClosed() = withService { service ->
        val result = service.capturePreview(0)
        assertEquals(result.message, "ready", result.status)
        val descriptor = result.image
        assertNotNull(descriptor)
        val bitmap = ParcelFileDescriptor.AutoCloseInputStream(descriptor!!).use { BitmapFactory.decodeStream(it) }
        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 640)
        bitmap.recycle()
        assertEquals("unsupported", service.capturePreview(99999).status)
    }
}
