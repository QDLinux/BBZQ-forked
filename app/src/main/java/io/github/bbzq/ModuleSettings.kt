package io.github.bbzq

import android.content.SharedPreferences

object ModuleSettings {
    const val PREFS_NAME = "bbzq_settings"
    const val KEY_MINI_PROGRAM_ENABLED = "mini_program"
    const val KEY_PURIFY_SHARE_ENABLED = "purify_share"
    const val KEY_SKIP_REWARD_AD_ENABLED = "skip_reward_ad"
    const val KEY_SKIP_SPLASH_AD_ENABLED = "skip_splash_ad_enabled"
    const val KEY_SKIP_VIDEO_AD_ENABLED = "skip_video_ad_enabled"
    const val KEY_BLOCK_VIDEO_DETAIL_BANNER_AD_ENABLED = "block_video_detail_banner_ad_enabled"
    const val KEY_UNLOCK_VIDEO_FEATURES_ENABLED = "unlock_video_features_enabled"
    const val KEY_AUTO_LIKE_VIDEO_DETAIL_ENABLED = "auto_like_video_detail_enabled"
    const val KEY_FIX_LIVE_QUALITY_URL_ENABLED = "fix_live_quality_url_enabled"
    const val KEY_PURIFY_HOME_RECOMMEND_AD_ENABLED = "purify_home_recommend_ad_enabled"
    const val KEY_PURIFY_STORY_VIDEO_AD_ENABLED = "purify_story_video_ad_enabled"
    const val KEY_PURIFY_STORY_VIDEO_AD_TAGS = "purify_story_video_ad_tags"
    const val KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT = "purify_story_video_ad_blocked_count"
    const val KEY_SKIP_MINI_GAME_REWARD_AD_ENABLED = "skip_mini_game_reward_ad_enabled"
    const val KEY_BLOCK_LIVE_RESERVATION_ENABLED = "block_live_reservation_enabled"
    const val KEY_BLOCK_LIVE_ROOM_QOE_POPUP_ENABLED = "block_live_room_qoe_popup_enabled"
    const val KEY_DISABLE_LONG_PRESS_COPY_ENABLED = "disable_long_press_copy_enabled"
    const val KEY_ENHANCE_LONG_PRESS_COPY_ENABLED = "enhance_long_press_copy_enabled"
    const val KEY_CUSTOM_BOTTOM_BAR_ENABLED = "custom_bottom_bar_enabled"
    const val KEY_HIDDEN_BOTTOM_BAR_ITEMS = "hidden_bottom_bar_items"
    const val KEY_KNOWN_BOTTOM_BAR_ITEMS = "known_bottom_bar_items"
    const val KEY_FULL_NUMBER_FORMAT_ENABLED = "full_number_format_enabled"
    const val KEY_UNLOCK_COMMENT_GIF_ENABLED = "unlock_comment_gif_enabled"
    const val KEY_LAST_ACCESS_KEY = "last_access_key"

    const val KEY_TARGET_APP_VERSION = "target_app_version"
    const val CACHE_BILI_SETTINGS_ACTIVITY = "cache_settings_activity"

    val defaultStoryVideoAdTags = setOf("ad")

    val storyVideoAdTags = listOf(
        StoryVideoAdTag("ad", "广告", null),
        StoryVideoAdTag("short", "短剧", "短剧"),
        StoryVideoAdTag("shopping", "购物", "购物"),
        StoryVideoAdTag("tv", "电视剧", "电视剧"),
        StoryVideoAdTag("doc", "纪录片", "纪录片"),
        StoryVideoAdTag("ent", "娱乐", "娱乐"),
        StoryVideoAdTag("movie", "电影", "电影"),
        StoryVideoAdTag("music", "音乐", "音乐"),
        StoryVideoAdTag("topic", "话题", "话题"),
    )

    fun isSkipSplashAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKIP_SPLASH_AD_ENABLED, true)

    fun isUnlockVideoFeaturesEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_UNLOCK_VIDEO_FEATURES_ENABLED, true)

    fun isSkipVideoAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKIP_VIDEO_AD_ENABLED, false)

    fun isBlockVideoDetailBannerAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_VIDEO_DETAIL_BANNER_AD_ENABLED, false)

    fun isAutoLikeVideoDetailEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_AUTO_LIKE_VIDEO_DETAIL_ENABLED, false)

    fun isFixLiveQualityUrlEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FIX_LIVE_QUALITY_URL_ENABLED, false)

    fun isPurifyHomeRecommendAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PURIFY_HOME_RECOMMEND_AD_ENABLED, false)

    fun isPurifyStoryVideoAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PURIFY_STORY_VIDEO_AD_ENABLED, false)

    fun getPurifyStoryVideoAdTags(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_PURIFY_STORY_VIDEO_AD_TAGS, defaultStoryVideoAdTags)
            ?: defaultStoryVideoAdTags

    fun isSkipMiniGameRewardAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKIP_MINI_GAME_REWARD_AD_ENABLED, true)

    fun isSkipRewardAdEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKIP_REWARD_AD_ENABLED, false)

    fun isBlockLiveReservationEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_LIVE_RESERVATION_ENABLED, false)

    fun isBlockLiveRoomQoePopupEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_LIVE_ROOM_QOE_POPUP_ENABLED, false)

    fun isDisableLongPressCopyEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DISABLE_LONG_PRESS_COPY_ENABLED, false)

    fun isEnhanceLongPressCopyEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_ENHANCE_LONG_PRESS_COPY_ENABLED, false)

    fun isPurifyShareEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PURIFY_SHARE_ENABLED, false)

    fun isCustomBottomBarEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CUSTOM_BOTTOM_BAR_ENABLED, false)

    fun getHiddenBottomBarItems(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_BOTTOM_BAR_ITEMS, emptySet()) ?: emptySet()

    fun getKnownBottomBarItems(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_KNOWN_BOTTOM_BAR_ITEMS, emptySet()) ?: emptySet()

    fun isFullNumberFormatEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FULL_NUMBER_FORMAT_ENABLED, false)

    fun isUnlockCommentGifEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_UNLOCK_COMMENT_GIF_ENABLED, false)
}

data class StoryVideoAdTag(
    val key: String,
    val label: String,
    val cartText: String?,
)
