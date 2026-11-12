package me.seasonyuu.displaylauncher.fixture

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

open class StandardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "${this@StandardActivity.javaClass.simpleName}\nDisplay ${this@StandardActivity.display?.displayId}\n多屏启动测试"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 76, 96))
        })
    }
}
class SingleTaskActivity : StandardActivity()
class SecureActivity : StandardActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, StandardActivity::class.java))
        finish()
    }
}
