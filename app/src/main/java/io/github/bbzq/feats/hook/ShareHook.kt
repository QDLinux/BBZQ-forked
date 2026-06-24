package io.github.bbzq.feats.hook

import android.content.ClipData
import android.content.ClipboardManager
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.hookBeforeAllConstructors
import io.github.bbzq.feats.setObjectField
import io.github.bbzq.feats.symbol.RestoredShareSymbols
import java.lang.reflect.Method

class ShareHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!isShareTransformEnabled(isMiniProgramEnabled())) return

        val symbols = env.symbols?.share?.restore(classLoader)
        if (symbols == null) {
            log("startHook: Share skipped because symbols are unavailable")
            return
        }
        var count = 0
        count += hookLegacyShareClickResult(symbols)
        count += hookModernShareContent(symbols)
        count += hookModernCopyContent(symbols)
        count += hookCopyToClipboardUtility(symbols)
        count += hookClipboardFallback()

        log("startHook: Share, methods=$count")
    }

    private fun hookLegacyShareClickResult(symbols: RestoredShareSymbols): Int {
        var count = 0
        symbols.legacyGetLink?.let { method ->
            env.hookAfter(method) { param ->
                val link = param.result as? String ?: return@hookAfter
                val transformAv = isMiniProgramEnabled()
                if (!isShareTransformEnabled(transformAv)) return@hookAfter

                val purified = purifyLink(link, transformAv)
                if (purified == link) return@hookAfter
                param.thisObject?.setObjectField("link", purified)
                param.result = purified
            }
            count++
        }
        symbols.legacyGetContent?.let { method ->
            env.hookAfter(method) { param ->
                val content = param.result as? String ?: return@hookAfter
                val transformAv = isMiniProgramEnabled()
                if (!isShareTransformEnabled(transformAv)) return@hookAfter

                val transformed = purifyText(content, transformAv)
                if (transformed == content) return@hookAfter
                param.thisObject?.setObjectField("content", transformed)
                param.result = transformed
            }
            count++
        }
        symbols.legacyGetShareMode?.let { method ->
            env.hookAfter(method) { param ->
                if (!isMiniProgramEnabled()) return@hookAfter
                if (param.result != 6 && param.result != 7) return@hookAfter
                param.result = 0
                val target = param.thisObject ?: return@hookAfter
                if (target.getObjectField("title") == BILI_TITLE) {
                    target.setObjectField("title", target.getObjectField("content"))
                    target.setObjectField("content", BBZQ_SHARE_TEXT)
                }
                (target.getObjectField("content") as? String)
                    ?.takeIf { it.startsWith(WATCHED_PREFIX) }
                    ?.let { target.setObjectField("content", "$it\n$BBZQ_SHARE_TEXT") }
            }
            count++
        }
        return count
    }

    private fun hookModernShareContent(symbols: RestoredShareSymbols): Int {
        var count = 0
        symbols.shareContentClass?.let { type ->
            count += env.hookBeforeAllConstructors(type) { param ->
                rewriteShareContentArgs(param.args)
            }
            count += hookCopyMethods(symbols.shareContentCopyMethods, ::rewriteShareContentArgs)
            count += hookPurifiedStringGetter(symbols.shareContentGetLink, ::purifyLink)
            count += hookPurifiedStringGetter(symbols.shareContentGetContent, ::purifyText)
            symbols.shareContentGetMode?.let { method ->
                env.hookAfter(method) { param ->
                    if (!isMiniProgramEnabled()) return@hookAfter
                    param.result = normalizeShareMode(param.result)
                }
                count++
            }
        }
        symbols.shareBiliContentClass?.let { type ->
            count += env.hookBeforeAllConstructors(type) { param ->
                rewriteShareBiliContentArgs(param.args)
            }
            count += hookCopyMethods(symbols.shareBiliContentCopyMethods, ::rewriteShareBiliContentArgs)
            count += hookPurifiedStringGetter(symbols.shareBiliContentGetDescription, ::purifyText)
            count += hookPurifiedStringGetter(symbols.shareBiliContentGetContentUrl, ::purifyLink)
        }
        return count
    }

    private fun hookModernCopyContent(symbols: RestoredShareSymbols): Int {
        val type = symbols.copyContentClass ?: return 0
        var count = env.hookBeforeAllConstructors(type) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookBeforeAllConstructors
            val content = param.args.firstOrNull() as? String ?: return@hookBeforeAllConstructors
            param.args[0] = purifyText(content, transformAv)
        }
        symbols.copyContentGetters.forEach { method ->
            count += hookPurifiedStringGetter(method, ::purifyText)
        }
        return count
    }

    private fun hookCopyToClipboardUtility(symbols: RestoredShareSymbols): Int {
        symbols.copyUtilityMethods.forEach { method ->
            env.hookBefore(method) { param ->
                val transformAv = isMiniProgramEnabled()
                if (!isShareTransformEnabled(transformAv)) return@hookBefore
                val content = param.args.firstOrNull() as? String ?: return@hookBefore
                param.args[0] = purifyText(content, transformAv)
            }
        }
        return symbols.copyUtilityMethods.size
    }

    private fun hookCopyMethods(methods: List<Method>, rewriter: (MutableList<Any?>) -> Unit): Int {
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                rewriter(param.args)
            }
        }
        return methods.size
    }

    private fun hookPurifiedStringGetter(
        method: Method?,
        purifier: (String, Boolean) -> String,
    ): Int {
        if (method == null) return 0
        env.hookAfter(method) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookAfter
            val value = param.result as? String ?: return@hookAfter
            val purified = purifier(value, transformAv)
            if (purified != value) param.result = purified
        }
        return 1
    }

    private fun hookClipboardFallback(): Int {
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        env.hookBefore(method) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookBefore

            val clip = param.args.firstOrNull() as? ClipData ?: return@hookBefore
            val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?: return@hookBefore
            val purified = purifyText(text, transformAv)
            if (purified != text) {
                param.args[0] = ClipData.newPlainText(clip.description.label, purified)
            }
        }
        return 1
    }

    private fun rewriteShareContentArgs(args: MutableList<Any?>) {
        val transformAv = isMiniProgramEnabled()
        if (!isShareTransformEnabled(transformAv)) return
        if (args.isNotEmpty()) args[0] = normalizeShareMode(args[0])
        rewriteStringArg(args, 2, transformAv, ::purifyText)
        rewriteStringArg(args, 3, transformAv, ::purifyLink)

        if (transformAv && args.size > 2) {
            if (args.getOrNull(1) == BILI_TITLE) {
                args[1] = args[2]
                args[2] = BBZQ_SHARE_TEXT
            }
            (args.getOrNull(2) as? String)
                ?.takeIf { it.startsWith(WATCHED_PREFIX) && !it.contains(BBZQ_SHARE_TEXT) }
                ?.let { args[2] = "$it\n$BBZQ_SHARE_TEXT" }
        }
    }

    private fun rewriteShareBiliContentArgs(args: MutableList<Any?>) {
        val transformAv = isMiniProgramEnabled()
        if (!isShareTransformEnabled(transformAv)) return
        rewriteStringArg(args, 1, transformAv, ::purifyText)
        rewriteStringArg(args, 3, transformAv, ::purifyLink)
    }

    private fun rewriteStringArg(
        args: MutableList<Any?>,
        index: Int,
        transformAv: Boolean,
        purifier: (String, Boolean) -> String,
    ) {
        val value = args.getOrNull(index) as? String ?: return
        args[index] = purifier(value, transformAv)
    }

    private fun normalizeShareMode(mode: Any?): Any? {
        if (!isMiniProgramEnabled() || mode == null) return mode
        if (!mode.toString().contains("MiniProgram", ignoreCase = true)) return mode
        return mode.javaClass.enumConstants
            ?.firstOrNull { it.toString() == "Link" }
            ?: mode
    }

    private fun purifyText(text: String, transformAv: Boolean): String =
        ShareLinkPurifier.purifyText(text, transformAv)

    private fun purifyLink(url: String, transformAv: Boolean): String =
        ShareLinkPurifier.purifyLink(url, transformAv)

    private fun isMiniProgramEnabled(): Boolean =
        prefs.getBoolean(ModuleSettings.KEY_MINI_PROGRAM_ENABLED, false)

    private fun isPurifyShareEnabled(): Boolean =
        ModuleSettings.isPurifyShareEnabled(prefs)

    private fun isShareTransformEnabled(transformAv: Boolean): Boolean =
        isPurifyShareEnabled() || transformAv

    private companion object {
        private const val BILI_TITLE = "\u54d4\u54e9\u54d4\u54e9"
        private const val BBZQ_SHARE_TEXT = "\u7531 BBZQ \u5206\u4eab"
        private const val WATCHED_PREFIX = "\u5df2\u89c2\u770b"
    }
}

