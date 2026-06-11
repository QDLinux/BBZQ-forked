package io.github.bzzq

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class IntroActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("unknown")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F8"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(createTitle())
            addView(createCard(getString(R.string.intro_module_title), getString(R.string.intro_module_body, versionName)))
            addView(
                createCard(
                    getString(R.string.intro_desc_title),
                    getString(R.string.intro_desc_body),
                ),
            )
            addView(
                createCard(
                    getString(R.string.intro_entry_title),
                    getString(R.string.intro_entry_body),
                ),
            )
            addView(
                createCard(
                    getString(R.string.intro_launcher_title),
                    getString(R.string.intro_launcher_body),
                ),
            )
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#F6F7F8"))
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun createTitle(): TextView {
        return TextView(this).apply {
            text = getString(R.string.intro_title)
            textSize = 28f
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, 0, 0, dp(16))
        }
    }

    private fun createCard(title: String, body: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }

            addView(TextView(this@IntroActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#FB7299"))
            })
            addView(TextView(this@IntroActivity).apply {
                text = body
                textSize = 16f
                setTextColor(Color.parseColor("#222222"))
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
