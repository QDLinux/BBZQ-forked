package io.github.bzzq

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F8"))
        }

        mainLayout.addView(createToolbar())
        mainLayout.addView(
            SettingsContentFactory(this, prefs).createScrollView(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(mainLayout)
    }

    private fun createToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(2).toFloat()
            addView(TextView(this@SettingsActivity).apply {
                text = "高级设置"
                textSize = 20f
                setTextColor(Color.BLACK)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "设置保存在应用内，并直接作用于 Bilibili。"
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
