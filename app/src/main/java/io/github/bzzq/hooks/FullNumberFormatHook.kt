package io.github.bzzq.hooks

import android.annotation.SuppressLint
import android.view.View
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class FullNumberFormatHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var hookCount = 0
        hookCount += hookMineCountBindings()
        hookCount += hookSpaceCountBindings()
        log("Installed full-number UI hooks: $hookCount")
    }

    private fun hookMineCountBindings(): Int {
        val fragmentClass = HostAccess.findClass(classLoader, *MINE_FRAGMENT_CLASSES) ?: return 0
        val accountMineClass = HostAccess.findClass(classLoader, *ACCOUNT_MINE_CLASSES) ?: return 0
        val methods = HostAccess.methods(fragmentClass)
            .filter { it.returnType == Void.TYPE }
            .filter { method -> method.parameterTypes.any(accountMineClass::isAssignableFrom) }
            .distinctBy(Method::toGenericString)
            .toList()

        methods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                        return@intercept result
                    }
                    runCatching {
                        applyMineCountTexts(
                            fragment = resolveReceiver(chain.thisObject, chain.args, fragmentClass),
                            accountMine = chain.args.firstOrNull(accountMineClass::isInstance),
                        )
                    }.onFailure {
                        log("Failed to patch mine count text after ${method.name}", it)
                    }
                    result
                }
        }
        return methods.size
    }

    private fun hookSpaceCountBindings(): Int {
        val fragmentClass = HostAccess.findClass(classLoader, *SPACE_FRAGMENT_CLASSES) ?: return 0
        val memberCardClass = HostAccess.findClass(classLoader, *MEMBER_CARD_CLASSES) ?: return 0
        val methods = HostAccess.methods(fragmentClass)
            .filter { it.returnType == Void.TYPE }
            .filter { method -> method.parameterTypes.any(memberCardClass::isAssignableFrom) }
            .distinctBy(Method::toGenericString)
            .toList()

        methods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                        return@intercept result
                    }
                    runCatching {
                        applySpaceCountTexts(
                            fragment = resolveReceiver(chain.thisObject, chain.args, fragmentClass),
                            memberCard = chain.args.firstOrNull(memberCardClass::isInstance),
                        )
                    }.onFailure {
                        log("Failed to patch space count text after ${method.name}", it)
                    }
                    result
                }
        }
        return methods.size
    }

    @SuppressLint("SetTextI18n")
    private fun applyMineCountTexts(fragment: Any?, accountMine: Any?) {
        val root = fragmentView(fragment) ?: return
        accountMine ?: return

        HostAccess.getLong(accountMine, "dynamic")?.let { value ->
            root.findTextView("following_count")?.text = value.toString()
        }
        HostAccess.getLong(accountMine, "following")?.let { value ->
            root.findTextView("attention_count")?.text = value.toString()
        }
        HostAccess.getLong(accountMine, "follower")?.let { value ->
            root.findTextView("fans_count")?.text = value.toString()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applySpaceCountTexts(fragment: Any?, memberCard: Any?) {
        val root = fragmentView(fragment) ?: return
        memberCard ?: return
        val likes = HostAccess.get(memberCard, "likes")

        HostAccess.getLong(memberCard, "mFollowers")?.let { value ->
            root.findTextView("fans")?.text = value.toString()
        }
        HostAccess.getLong(memberCard, "mFollowings")?.let { value ->
            root.findTextView("attentions")?.text = value.toString()
        }
        root.findTextView("likes")?.text = likes?.let {
            HostAccess.getLong(it, "likeNum")?.toString()
        } ?: "-"
    }

    private fun fragmentView(fragment: Any?): View? {
        if (fragment is View) return fragment
        fragment ?: return null
        return HostAccess.get(fragment, "view", "mView") as? View
            ?: HostAccess.invoke(fragment, "getView") as? View
    }

    private fun resolveReceiver(
        thisObject: Any?,
        args: List<Any?>,
        fragmentClass: Class<*>,
    ): Any? {
        if (thisObject != null && fragmentClass.isInstance(thisObject)) {
            return thisObject
        }
        return args.firstOrNull(fragmentClass::isInstance)
    }

    private fun View.findTextView(name: String): TextView? {
        val id = resources.getIdentifier(name, "id", context.packageName)
        if (id == 0) return null
        return findViewById(id) as? TextView
    }

    private companion object {
        private val MINE_FRAGMENT_CLASSES = arrayOf(
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.HomeUserCenterFragment",
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
        )

        private val SPACE_FRAGMENT_CLASSES = arrayOf(
            "com.bilibili.p4439app.authorspace.p4444ui.SpaceHeaderFragment2",
            "com.bilibili.app.authorspace.ui.SpaceHeaderFragment2",
        )

        private val ACCOUNT_MINE_CLASSES = arrayOf(
            "tv.danmaku.p9138bili.p9228ui.main2.p9245api.AccountMine",
            "tv.danmaku.bili.ui.main2.api.AccountMine",
        )

        private val MEMBER_CARD_CLASSES = arrayOf(
            "com.bilibili.p4439app.authorspace.p4440api.BiliMemberCard",
            "com.bilibili.app.authorspace.api.BiliMemberCard",
        )
    }
}
