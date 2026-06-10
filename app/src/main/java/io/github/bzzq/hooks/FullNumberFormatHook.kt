package io.github.bzzq.hooks

import android.view.View
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Mirrors BiliRoamingX's "number format" feature for mine and space headers.
 */
class FullNumberFormatHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val prefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
        val classLoader = packageReady.getClassLoader()

        hookMineHeader(xposed, classLoader, prefs, log)
        hookSpaceHeader(xposed, classLoader, prefs, log)
    }

    private fun hookMineHeader(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: android.content.SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        val accountMineClass = runCatching {
            Class.forName(ACCOUNT_MINE_CLASS_NAME, false, classLoader)
        }.getOrElse {
            log("AccountMine class not found for full-number hook", it)
            return
        }

        MINE_FRAGMENT_CLASS_NAMES.forEach { className ->
            runCatching {
                val clazz = Class.forName(className, false, classLoader)
                val methods = clazz.declaredMethods.filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == accountMineClass &&
                        method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }
                methods.forEach { method ->
                    method.isAccessible = true
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                                return@intercept result
                            }

                            val accountMine = chain.getArg(0) ?: return@intercept result
                            runCatching {
                                val rootView = getView(chain.thisObject) ?: return@runCatching
                                setText(rootView, "following_count", getLongField(accountMine, "dynamic"))
                                setText(rootView, "attention_count", getLongField(accountMine, "following"))
                                setText(rootView, "fans_count", getLongField(accountMine, "follower"))
                            }.onFailure { log("Failed to apply full-number mine header", it) }
                            result
                        }
                }
                if (methods.isNotEmpty()) {
                    log("Installed full-number mine hook for $className", null)
                }
            }.onFailure {
                log("Failed to install full-number mine hook for $className", it)
            }
        }
    }

    private fun hookSpaceHeader(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: android.content.SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        runCatching {
            val clazz = Class.forName(SPACE_HEADER_FRAGMENT_CLASS_NAME, false, classLoader)
            val memberCardClass = Class.forName(BILI_MEMBER_CARD_CLASS_NAME, false, classLoader)
            val methods = clazz.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(memberCardClass))
            }
            methods.forEach { method ->
                method.isAccessible = true
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                            return@intercept result
                        }

                        val memberCard = chain.getArg(0) ?: return@intercept result
                        runCatching {
                            val rootView = getView(chain.thisObject) ?: return@runCatching
                            setText(rootView, "fans", getLongField(memberCard, "mFollowers"))
                            setText(rootView, "attentions", getLongField(memberCard, "mFollowings"))
                            setText(rootView, "likes", getNestedLongField(memberCard, "likes", "likeNum"))
                        }.onFailure { log("Failed to apply full-number space header", it) }
                        result
                    }
            }
            if (methods.isNotEmpty()) {
                log("Installed full-number space hook", null)
            }
        }.onFailure {
            log("Failed to install full-number space hook", it)
        }
    }

    private fun getView(target: Any?): View? {
        if (target == null) return null
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 0 }
                ?.invoke(target) as? View
        }.getOrNull()
    }

    private fun setText(rootView: View, idName: String, value: Long?) {
        if (value == null) return
        val context = rootView.context ?: return
        val viewId = context.resources.getIdentifier(idName, "id", context.packageName)
        if (viewId == 0) return
        val textView = rootView.findViewById<View>(viewId) as? TextView ?: return
        textView.text = value.toString()
    }

    private fun getLongField(target: Any, fieldName: String): Long? {
        val value = getFieldValue(target, fieldName) ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun getNestedLongField(target: Any, fieldName: String, nestedFieldName: String): Long? {
        val nested = getFieldValue(target, fieldName) ?: return null
        return getLongField(nested, nestedFieldName)
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = target.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    private companion object {
        private const val ACCOUNT_MINE_CLASS_NAME = "tv.danmaku.bili.ui.main2.api.AccountMine"
        private const val BILI_MEMBER_CARD_CLASS_NAME = "com.bilibili.app.authorspace.api.BiliMemberCard"
        private const val SPACE_HEADER_FRAGMENT_CLASS_NAME = "com.bilibili.app.authorspace.ui.SpaceHeaderFragment2"
        private val MINE_FRAGMENT_CLASS_NAMES = listOf(
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
            "tv.danmaku.bilibilihd.ui.main.mine.HdHomeUserCenterFragment",
        )
    }
}
