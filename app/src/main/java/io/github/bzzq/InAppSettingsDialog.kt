package io.github.bzzq

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences

object InAppSettingsDialog {
    fun show(
        activity: Activity,
        prefs: SharedPreferences = ModuleSettingsBridge.instance,
    ) {
        val contentView = SettingsContentFactory(activity, prefs).createScrollView()
        AlertDialog.Builder(activity)
            .setTitle("高级设置")
            .setView(contentView)
            .setPositiveButton("关闭", null)
            .show()
    }
}
