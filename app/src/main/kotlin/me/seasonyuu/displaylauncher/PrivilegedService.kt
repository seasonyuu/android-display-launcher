package me.seasonyuu.displaylauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt

@Keep
class PrivilegedService @Keep constructor(context: Context) : IPrivilegedService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val ownerUser = ownerUid / 100000
    private val workers = Executors.newCachedThreadPool()
    private val captureLock = Any()
    private val pipeTimeouts = Executors.newSingleThreadScheduledExecutor()

    private fun checkCaller() { check(Binder.getCallingUid() == ownerUid) { "Unauthorized caller" } }
    private data class Output(val code: Int, val bytes: ByteArray, val errors: String) {
        val text get() = bytes.toString(Charsets.UTF_8) + "\n" + errors
    }
    private fun run(args: List<String>, timeout: Long = 3000, limit: Int = 2 * 1024 * 1024): Output {
        val process = ProcessBuilder(args).start()
        fun drain(input: java.io.InputStream, cap: Int) = workers.submit<ByteArray> {
            input.use {
                val result = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (result.size() + n > cap) { process.destroyForcibly(); error("Output too large") }
                    result.write(buf, 0, n)
                }
                result.toByteArray()
            }
        }
        val out = drain(process.inputStream, limit)
        val err = drain(process.errorStream, 65536)
        try {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) throw TimeoutException("操作超时")
            return Output(process.exitValue(), out.get(1, TimeUnit.SECONDS), err.get(1, TimeUnit.SECONDS).toString(Charsets.UTF_8))
        } finally {
            process.destroyForcibly()
            out.cancel(true); err.cancel(true)
        }
    }
    private fun result(status: String, message: String) = Bundle().apply {
        putString("status", status); putString("message", message)
    }

    override fun launch(request: Bundle): Bundle {
        checkCaller()
        return try {
            val user = request.getInt("user", -1)
            val display = request.getInt("display", -1)
            val component = requireNotNull(request.getString("component"))
            require(user == ownerUser) { "不支持跨用户启动" }
            val args = ShellProtocol.launchArgs(component, user, display)
            val output = run(args, 15000)
            if (!ShellProtocol.launchAccepted(output.code, output.text)) {
                result("failed", "启动失败，请查看诊断信息").apply { putString("details", output.text.take(6000)) }
            } else {
                var verification = ""
                val actual = runCatching {
                    val dump = run(listOf("/system/bin/dumpsys", "activity", "activities"))
                    verification = dump.text.lineSequence().filter {
                        it.contains("Display #") || it.contains(component.substringBefore('/')) && it.contains("Resumed") ||
                            it.contains("Permission Denial") || it.contains("Exception")
                    }.take(12).joinToString("\n")
                    ShellProtocol.actualDisplay(dump.text, component, user)
                }.onFailure { verification = it.message ?: "无法读取任务状态" }.getOrNull()
                val status = when (actual) { display -> "confirmed"; null -> "unverified"; else -> "mismatch" }
                val message = when (status) {
                    "confirmed" -> "已确认应用位于屏幕 $display"
                    "mismatch" -> "应用位于屏幕 $actual，与目标屏幕 $display 不同"
                    else -> "启动请求完成，屏幕位置未确认"
                }
                result(status, message).apply {
                    putInt("actualDisplay", actual ?: -1)
                    putString("details", "shell uid=${android.os.Process.myUid()}\n${output.text.take(6000)}\n核验：$verification")
                }
            }
        } catch (e: TimeoutException) { result("timeout", "启动超时，请确认应用状态后手动重试")
        } catch (e: Exception) { result("failed", e.message ?: "启动失败") }
    }

    override fun capturePreview(displayId: Int): PreviewResult {
        checkCaller()
        require(displayId >= 0)
        return synchronized(captureLock) {
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                fun remaining(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also {
                    if (it <= 0) throw TimeoutException("截图超时")
                }
                val displayDump = run(listOf("/system/bin/dumpsys", "display"), remaining()).text
                val surfaces = run(listOf("/system/bin/dumpsys", "SurfaceFlinger", "--display-id"), remaining()).text
                val id = ShellProtocol.physicalId(displayDump, displayId, surfaces) ?: run {
                    val composition = run(listOf("/system/bin/dumpsys", "SurfaceFlinger"), remaining()).text
                    val candidate = ShellProtocol.virtualId(displayDump, displayId, composition, surfaces)
                        ?: return@synchronized PreviewResult("unsupported", "无法预览 · 无可用输出或无法确认屏幕映射")
                    // Car displays can be recreated while querying. Recheck the logical identity and live output ID.
                    val latestDisplays = run(listOf("/system/bin/dumpsys", "display"), remaining()).text
                    val latestOutputs = run(listOf("/system/bin/dumpsys", "SurfaceFlinger", "--display-id"), remaining()).text
                    if (ShellProtocol.displayIdentity(displayDump, displayId) != ShellProtocol.displayIdentity(latestDisplays, displayId) ||
                        ShellProtocol.virtualId(latestDisplays, displayId, composition, latestOutputs) != candidate) {
                        return@synchronized PreviewResult("unsupported", "无法预览 · 屏幕已变化，请刷新快照")
                    }
                    candidate
                }
                val output = run(listOf("/system/bin/screencap", "-p", "-d", id), remaining(), limit = 24 * 1024 * 1024)
                check(output.code == 0) { "系统未允许获取屏幕快照" }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size, options)
                check(options.outWidth > 0 && options.outHeight > 0) { "无法解码快照" }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2
                val source = requireNotNull(BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size, options))
                val scale = minOf(1f, 640f / maxOf(source.width, source.height))
                val thumb = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1), (source.height * scale).roundToInt().coerceAtLeast(1), true)
                val bytes = ByteArrayOutputStream().apply { thumb.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                if (thumb !== source) thumb.recycle()
                source.recycle()
                val pipe = ParcelFileDescriptor.createPipe()
                val closeDeadline = pipeTimeouts.schedule({ runCatching { pipe[1].close() } }, 3, TimeUnit.SECONDS)
                workers.execute {
                    try { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } } }
                    finally { closeDeadline.cancel(false) }
                }
                PreviewResult("ready", "快照", System.currentTimeMillis(), pipe[0])
            } catch (e: TimeoutException) { PreviewResult("timeout", "无法预览 · 截图超时")
            } catch (e: Exception) { PreviewResult("failed", "无法预览 · ${e.message ?: "系统限制"}") }
        }
    }

    override fun destroy() {
        // Shizuku itself invokes the reserved destroy transaction on upgrades/unbind.
        val uid = Binder.getCallingUid()
        check(uid == ownerUid || uid == 0 || uid == 2000)
        workers.shutdownNow(); pipeTimeouts.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
