package io.github.bzzq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsContentFactory(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val tagCheckBoxes = mutableMapOf<String, CheckBox>()
    private lateinit var disableLongPressCopySwitch: Switch
    private lateinit var enhanceLongPressCopySwitch: Switch
    private lateinit var storyVideoAdSwitch: Switch
    private lateinit var blockedCountView: TextView
    private var refreshing = false

    fun createScrollView(): ScrollView {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        content.addView(createSectionTitle("账号工具"))
        accountActionSpecs.forEach { spec ->
            content.addView(createClickableItem(spec.title, spec.summary, spec.onClick))
        }

        content.addView(createSectionTitle("通用功能"))
        generalToggleSpecs.forEach { spec ->
            content.addView(createFeatureSwitch(spec).also { layout ->
                val switchView = layout.getChildAt(1) as Switch
                when (spec.key) {
                    ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED -> disableLongPressCopySwitch = switchView
                    ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED -> enhanceLongPressCopySwitch = switchView
                }
            })
        }

        content.addView(createSectionTitle("竖屏视频净化"))
        content.addView(createFeatureSwitch(storyToggleSpec).also { layout ->
            storyVideoAdSwitch = layout.getChildAt(1) as Switch
        })

        val tagsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        storyTagSpecs.forEach { tag ->
            val checkBox = CheckBox(context).apply {
                text = tag.label
                textSize = 15f
                setTextColor(Color.parseColor("#212121"))
                setOnCheckedChangeListener { _, _ ->
                    if (!refreshing) saveSelectedTags()
                }
            }
            tagCheckBoxes[tag.key] = checkBox
            tagsLayout.addView(checkBox)
        }
        content.addView(tagsLayout)

        blockedCountView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(blockedCountView)

        return ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#F6F7F8"))
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }.also { refresh() }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#FB7299"))
            setPadding(0, dp(16), 0, dp(8))
        }
    }

    private fun createFeatureSwitch(spec: ToggleSpec): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(16), 0)
        }

        textLayout.addView(TextView(context).apply {
            text = spec.title
            textSize = 17f
            setTextColor(Color.parseColor("#212121"))
        })
        textLayout.addView(TextView(context).apply {
            text = spec.summary
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(4), 0, 0)
        })
        textLayout.addView(TextView(context).apply {
            text = if (spec.defaultValue) "默认：开启" else "默认：关闭"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(6), 0, 0)
        })

        val switchView = Switch(context).apply {
            isChecked = prefs.getBoolean(spec.key, spec.defaultValue)
            setOnCheckedChangeListener { _, isChecked ->
                if (!refreshing) {
                    prefs.edit().putBoolean(spec.key, isChecked).apply()
                    if (
                        spec.key == ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED ||
                        spec.key == ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED
                    ) {
                        refresh()
                    }
                }
            }
        }

        layout.addView(textLayout)
        layout.addView(switchView)
        return layout
    }

    private fun createClickableItem(
        title: String,
        summary: String,
        onClick: SettingsContentFactory.() -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { this@SettingsContentFactory.onClick() }

            addView(TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(Color.parseColor("#212121"))
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun refresh() {
        refreshing = true
        val enabled = ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        val disableLongPressCopy = ModuleSettings.isDisableLongPressCopyEnabled(prefs)

        storyVideoAdSwitch.isChecked = enabled
        disableLongPressCopySwitch.isChecked = disableLongPressCopy
        enhanceLongPressCopySwitch.isEnabled = disableLongPressCopy
        if (!disableLongPressCopy && enhanceLongPressCopySwitch.isChecked) {
            prefs.edit().putBoolean(ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED, false).apply()
        }
        enhanceLongPressCopySwitch.isChecked =
            disableLongPressCopy && ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)
        tagCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = enabled
            checkBox.isChecked = key in selectedTags
        }
        blockedCountView.text = "累计拦截：${prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)}"
        refreshing = false
    }

    private fun saveSelectedTags() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_TAGS, selectedTagKeys().toMutableSet())
            .apply()
    }

    private fun selectedTagKeys(): Set<String> =
        tagCheckBoxes.filterValues { it.isChecked }.keys.toSet()

    private fun copyAccessKey() {
        val token = prefs.getString(ModuleSettings.KEY_LAST_ACCESS_KEY, null)
        if (token.isNullOrEmpty()) {
            Toast.makeText(context, "未找到 access_key，请先登录 Bilibili 并触发一次账号相关请求。", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("access_key", token))
        Toast.makeText(context, "已复制 access_key 到剪贴板。", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private data class ToggleSpec(
        val key: String,
        val title: String,
        val summary: String,
        val defaultValue: Boolean,
    )

    private data class ActionSpec(
        val title: String,
        val summary: String,
        val onClick: SettingsContentFactory.() -> Unit,
    )

    private data class StoryTagSpec(
        val key: String,
        val label: String,
    )

    private companion object {
        private val accountActionSpecs = listOf(
            ActionSpec(
                title = "复制 access_key",
                summary = "复制当前登录账号最近一次捕获到的 access_key。",
                onClick = SettingsContentFactory::copyAccessKey,
            ),
        )

        private val generalToggleSpecs = listOf(
            ToggleSpec(ModuleSettings.KEY_SKIP_SPLASH_AD_ENABLED, "跳过开屏广告", "移除启动时的开屏广告，加快进入速度。", true),
            ToggleSpec(ModuleSettings.KEY_UNLOCK_VIDEO_FEATURES_ENABLED, "解锁视频功能", "尝试解锁部分试看限制与会员画质相关能力。", true),
            ToggleSpec(ModuleSettings.KEY_AUTO_LIKE_VIDEO_DETAIL_ENABLED, "视频详情页自动点赞", "进入视频详情页时自动点击点赞按钮。", false),
            ToggleSpec(ModuleSettings.KEY_FIX_LIVE_QUALITY_URL_ENABLED, "直播画质 URL 修复", "修复部分版本直播画质切换异常或 URL 错误的问题。", false),
            ToggleSpec(ModuleSettings.KEY_SKIP_MINI_GAME_REWARD_AD_ENABLED, "跳过小游戏激励广告", "自动快进或关闭小游戏中的激励视频广告。", true),
            ToggleSpec(ModuleSettings.KEY_BLOCK_LIVE_RESERVATION_ENABLED, "屏蔽直播预约", "移除视频详情页中的直播预约悬浮卡片。", false),
            ToggleSpec(ModuleSettings.KEY_BLOCK_LIVE_ROOM_QOE_POPUP_ENABLED, "屏蔽直播间效果弹窗", "移除直播间里的效果打分或体验调研弹窗。", false),
            ToggleSpec(ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED, "去除长按复制", "禁用评论、动态、视频简介等场景里长按后直接复制到剪贴板的行为，减少误触。", false),
            ToggleSpec(ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED, "长按自由复制", "需先开启“去除长按复制”。覆盖评论、动态、视频简介和部分私信场景，弹出可自由选择的文本窗口。", false),
            ToggleSpec(ModuleSettings.KEY_PURIFY_SHARE_ENABLED, "分享净化", "净化复制链接和分享文本中的追踪参数，尽量保留分页、评论定位等必要信息。", false),
            ToggleSpec(ModuleSettings.KEY_FULL_NUMBER_FORMAT_ENABLED, "完整数字显示", "在“我的”和空间页显示完整统计数字，不再缩写成“万”“亿”。", false),
            ToggleSpec(ModuleSettings.KEY_UNLOCK_COMMENT_GIF_ENABLED, "评论区 GIF 解锁", "恢复评论区 GIF 缩略图播放，并移除图片右上角的大会员专属角标。", false),
        )

        private val storyToggleSpec = ToggleSpec(
            ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED,
            "净化竖屏视频广告",
            "按标签过滤竖屏视频流中的广告、购物和推广内容。",
            false,
        )

        private val storyTagSpecs = listOf(
            StoryTagSpec("ad", "广告"),
            StoryTagSpec("short", "短剧"),
            StoryTagSpec("shopping", "购物"),
            StoryTagSpec("tv", "电视剧"),
            StoryTagSpec("doc", "纪录片"),
            StoryTagSpec("ent", "娱乐"),
            StoryTagSpec("movie", "电影"),
            StoryTagSpec("music", "音乐"),
            StoryTagSpec("topic", "话题"),
        )
    }
}
