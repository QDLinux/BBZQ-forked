package io.github.bbzq

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val toolbar = createToolbar()
        val content = SettingsContentFactory(this, prefs).createScrollView()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F8"))
            addView(toolbar)
            addView(
                content,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        setContentView(root)
        applyWindowInsets(root, toolbar, content)
    }

    private fun createToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(2).toFloat()

            addView(TextView(this@SettingsActivity).apply {
                text = "BBZQ 设置"
                textSize = 20f
                setTextColor(Color.parseColor("#18191C"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@SettingsActivity).apply {
                text = "完成"
                textSize = 15f
                setTextColor(Color.parseColor("#FB7299"))
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
        }
    }

    private fun applyWindowInsets(
        root: LinearLayout,
        toolbar: LinearLayout,
        content: ScrollView,
    ) {
        val toolbarLeft = toolbar.paddingLeft
        val toolbarTop = toolbar.paddingTop
        val toolbarRight = toolbar.paddingRight
        val toolbarBottom = toolbar.paddingBottom
        val contentLeft = content.paddingLeft
        val contentTop = content.paddingTop
        val contentRight = content.paddingRight
        val contentBottom = content.paddingBottom

        root.setOnApplyWindowInsetsListener { _, insets ->
            val safeInsets =
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            toolbar.setPadding(
                toolbarLeft,
                toolbarTop + safeInsets.top,
                toolbarRight,
                toolbarBottom,
            )
            content.setPadding(
                contentLeft,
                contentTop,
                contentRight,
                contentBottom + safeInsets.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
