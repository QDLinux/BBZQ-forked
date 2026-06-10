package io.github.bzzq.hooks

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.util.LinkedHashSet

/**
 * Injects the module entry into Bilibili's settings page.
 *
 * We keep the known activity names as a fast path and fall back to DexKit
 * so the hook survives package-level refactors and mild obfuscation.
 */
class BiliEntryHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(context: HookContext) {
        installCachedLifecycleHooks(context)

        val resolvedActivities = resolveOtherSettingsActivities(context)
        resolvedActivities.forEach { className ->
            runCatching {
                val clazz = Class.forName(className, false, context.classLoader)
                var installed = false

                clazz.findDeclaredMethod("onCreate", Bundle::class.java)?.let { method ->
                    context.xposed.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        scheduleInjection(chain.thisObject as? Activity, context.log)
                        result
                    }
                    installed = true
                }
                clazz.findDeclaredMethod("onResume")?.let { method ->
                    context.xposed.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        scheduleInjection(chain.thisObject as? Activity, context.log)
                        result
                    }
                    installed = true
                }

                if (installed) {
                    context.log("Installed settings entry hook for $className", null)
                } else {
                    context.log("No usable lifecycle methods found for $className", null)
                }
            }.onFailure {
                context.log("Unable to hook $className", it)
            }
        }

        if (resolvedActivities.isEmpty()) {
            installFallbackActivityHook(context)
        }
    }

    private fun installCachedLifecycleHooks(context: HookContext) {
        var installedAny = false
        cachedLifecycleSpecs.forEach { spec ->
            val cachedMethod = context.dexDesc(spec.cacheKey).toMethodOrNull()
            if (cachedMethod != null) {
                hookLifecycleMethod(context, cachedMethod)
                installedAny = true
                return@forEach
            }

            val searchedMethod = searchLifecycleMethod(context, spec)
            if (searchedMethod != null) {
                hookLifecycleMethod(context, searchedMethod)
                installedAny = true
            }
        }
        if (installedAny) {
            context.log("Installed cached DexKit lifecycle hook(s) for settings entry", null)
        }
    }

    private fun resolveOtherSettingsActivities(context: HookContext): List<String> {
        val resolved = LinkedHashSet<String>()

        OTHER_SETTING_ACTIVITY_NAMES.forEach { className ->
            if (runCatching { Class.forName(className, false, context.classLoader) }.isSuccess) {
                resolved += className
            }
        }
        if (resolved.isNotEmpty()) return resolved.toList()

        val cache = context.dexKitCache()
        val cachedClassName = cache.readClassCache(CACHE_KEY_SETTINGS_ACTIVITY)
        if (cachedClassName.isNotEmpty()) {
            if (runCatching { Class.forName(cachedClassName, false, context.classLoader) }.isSuccess) {
                return listOf(cachedClassName)
            }
        }

        val dexKitMatches: List<String> = context.dexKitOrNull()
            ?.findClass {
                searchPackages(OTHER_SETTING_PACKAGE)
                matcher {
                    methods {
                        add {
                            name = "onCreate"
                            returnType = "void"
                            paramTypes("android.os.Bundle")
                            usingStrings(SETTINGS_MARKERS, StringMatchType.Contains)
                        }
                        count(1..20)
                    }
                    usingStrings(SETTINGS_MARKERS, StringMatchType.Contains)
                }
            }
            ?.map { it.name }
            .orEmpty()
        
        if (dexKitMatches.isNotEmpty()) {
            val matchedName: String = dexKitMatches.first()
            cache.saveClassCache(CACHE_KEY_SETTINGS_ACTIVITY, matchedName)
            resolved.add(matchedName)
        }

        if (resolved.isEmpty()) {
            context.log("DexKit could not resolve a settings activity for ${context.packageName}", null)
        }
        return resolved.toList()
    }

    private fun searchLifecycleMethod(context: HookContext, spec: LifecycleSpec): Method? {
        val bridge = context.dexKitOrNull() ?: return null
        val methodData = runCatching {
            bridge.findMethod(
                FindMethod.create()
                    .searchPackages(OTHER_SETTING_PACKAGE)
                    .matcher(
                        MethodMatcher.create()
                            .name(spec.methodName)
                            .modifiers(java.lang.reflect.Modifier.PUBLIC)
                            .returnType("void")
                            .paramCount(spec.paramCount)
                            .apply {
                                if (spec.paramTypes.isNotEmpty()) {
                                    paramTypes(*spec.paramTypes.toTypedArray())
                                }
                                usingStrings(SETTINGS_MARKERS, StringMatchType.Contains)
                            },
                    ),
            ).singleOrNull()
        }.getOrNull() ?: return null

        return methodData.toHookMethod(context, spec.cacheKey)
    }

    private fun MethodData.toHookMethod(context: HookContext, cacheKey: String): Method? {
        return runCatching {
            val method = getMethodInstance(context.classLoader)
            method.isAccessible = true
            context.dexKitCache().saveMethodCache(cacheKey, toDexMethod().serialize())
            method
        }.getOrElse {
            context.log("Failed to materialize DexKit method for $cacheKey", it)
            null
        }
    }

    private fun hookLifecycleMethod(context: HookContext, method: Method) {
        context.xposed.hook(method).intercept { chain ->
            val result = chain.proceed()
            scheduleInjection(chain.thisObject as? Activity, context.log)
            result
        }
    }

    private fun scheduleInjection(activity: Activity?, log: (String, Throwable?) -> Unit) {
        val safeActivity = activity ?: return
        val decor = safeActivity.window?.decorView ?: return
        INJECTION_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                runCatching { injectIntoSettingsPage(safeActivity, log) }
                    .onFailure { log("Failed to inject bzzq entry", it) }
            }, delay)
        }
    }

    private fun installFallbackActivityHook(context: HookContext) {
        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            context.xposed.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@intercept result
                if (looksLikeSettingsActivity(activity)) {
                    scheduleInjection(activity, context.log)
                }
                result
            }
            context.log("Installed fallback settings activity hook", null)
        }.onFailure {
            context.log("Failed to install fallback settings activity hook", it)
        }
    }

    private fun injectIntoSettingsPage(activity: Activity, log: (String, Throwable?) -> Unit) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(ENTRY_TAG) != null) return

        val anchor = findAnchorRow(root)
        val parent = anchor?.parent as? ViewGroup ?: findBestSettingsContainer(root) ?: return
        val entry = createEntryView(activity).apply { tag = ENTRY_TAG }

        if (anchor != null) {
            val index = parent.indexOfChild(anchor)
            parent.addView(entry, index.coerceAtLeast(0))
            log("Inserted bzzq entry before anchor row in settings", null)
            return
        }

        parent.addView(entry, 0)
        log("Inserted bzzq entry at top of fallback settings container", null)
    }

    private fun createEntryView(activity: Activity): View {
        val versionName = runCatching {
            activity.packageManager.getPackageInfo("io.github.bzzq", 0).versionName
        }.getOrDefault("unknown")

        val titleLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleLayout.addView(TextView(activity).apply {
            text = "bzzq"
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
        })
        titleLayout.addView(TextView(activity).apply {
            text = "模块设置"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(activity, 4), 0, 0)
        })

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            addView(titleLayout)
            addView(TextView(activity).apply {
                text = "v$versionName"
                textSize = 13f
                setTextColor(Color.parseColor("#9E9E9E"))
            })
            setOnClickListener {
                val intent = Intent().apply {
                    setClassName("io.github.bzzq", "io.github.bzzq.SettingsActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
        }
    }

    private fun findAnchorRow(view: View): View? {
        val titleView = findTextView(view) { text ->
            ANCHOR_TEXTS.any { anchor -> text.contains(anchor) }
        } ?: return null

        var current: View? = titleView
        while (current != null) {
            val parent = current.parent as? ViewGroup ?: break
            if (parent.isVerticalContainer() && parent.indexOfChild(current) >= 0) {
                return current
            }
            current = parent
        }
        return null
    }

    private fun findBestSettingsContainer(view: View): ViewGroup? {
        if (view !is ViewGroup) return null

        if (view.isVerticalContainer() && view.childCount >= 2) {
            val textChildCount = countTextDrivenChildren(view)
            if (textChildCount >= 2) return view
        }

        for (index in 0 until view.childCount) {
            findBestSettingsContainer(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun countTextDrivenChildren(group: ViewGroup): Int {
        var count = 0
        for (index in 0 until group.childCount) {
            if (containsAnyText(group.getChildAt(index))) count++
        }
        return count
    }

    private fun containsAnyText(view: View): Boolean {
        if (view is TextView && !view.text.isNullOrBlank()) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsAnyText(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun findTextView(view: View, predicate: (String) -> Boolean): TextView? {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (predicate(text)) return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun looksLikeSettingsActivity(activity: Activity): Boolean {
        val className = activity.javaClass.name.lowercase()
        if ("setting" in className) return true
        val root = activity.window?.decorView ?: return false
        return findTextView(root) { text ->
            SETTINGS_MARKERS.any { marker -> text.contains(marker) }
        } != null
    }

    private fun ViewGroup.isVerticalContainer(): Boolean {
        return this is LinearLayout && orientation == LinearLayout.VERTICAL
    }

    private fun Class<*>.findDeclaredMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        return runCatching { getDeclaredMethod(name, *parameterTypes) }.getOrNull()
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val CACHE_KEY_SETTINGS_ACTIVITY = "settings_activity"
        private const val ENTRY_TAG = "bzzq_other_settings_entry"
        private const val OTHER_SETTING_PACKAGE = "com.bilibili.app.comm.setting"
        private val INJECTION_DELAYS_MS = longArrayOf(120L, 360L, 720L)
        private val OTHER_SETTING_ACTIVITY_NAMES = listOf(
            "com.bilibili.app.comm.setting.v2.OtherSettingActivity",
            "com.bilibili.app.comm.setting.OtherSettingActivity",
        )
        private val SETTINGS_MARKERS = listOf(
            "关于",
            "清理缓存",
            "推荐设置",
            "隐私权限设置",
        )
        private val ANCHOR_TEXTS = listOf(
            "关于哔哩哔哩",
            "关于",
            "隐私权限设置",
            "青少年守护",
            "清理缓存",
            "推荐设置",
        )

        private val cachedLifecycleSpecs = listOf(
            LifecycleSpec("settings_on_create", "onCreate", listOf("android.os.Bundle")),
            LifecycleSpec("settings_on_resume", "onResume", emptyList()),
        )
    }

    private data class LifecycleSpec(
        val cacheKey: String,
        val methodName: String,
        val paramTypes: List<String>,
    ) {
        val paramCount: Int
            get() = paramTypes.size
    }
}
