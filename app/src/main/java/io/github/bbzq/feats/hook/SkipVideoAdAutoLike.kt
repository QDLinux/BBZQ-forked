package io.github.bbzq.feats.hook

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookAfterAllConstructors
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object SkipVideoAdAutoLike {
    private val installed = AtomicBoolean(false)
    private val candidates = CopyOnWriteArrayList<LikeCandidate>()
    private val detailStateOwnerFields = ConcurrentHashMap<Class<*>, FieldLookup>()
    private val storyOwnerFields = ConcurrentHashMap<Class<*>, FieldLookup>()
    private val storyActionOwnerTypes = ConcurrentHashMap<Class<*>, Boolean>()

    fun install(env: RoamingEnv): Int {
        if (!installed.compareAndSet(false, true)) return 0
        var count = 0
        count += hookDetailLikeComponent(env)
        count += hookStoryLikeWidget(env, STORY_LIKE_WIDGET)
        count += hookStoryLikeWidget(env, STORY_LANDSCAPE_LIKE_WIDGET)
        count += hookViewConstructors(env, GEMINI_PLAYER_LIKE_WIDGET, LikeKind.GEMINI)
        env.log("SkipVideoAd auto-like hooks installed, methods=$count")
        return count
    }

    fun likeCurrentVideo(log: (String, Throwable?) -> Unit): AutoLikeResult =
        runCatching {
            pruneCandidates()
            candidates.toList().asReversed().forEach { candidate ->
                val view = candidate.viewRef.get() ?: return@forEach
                if (!candidate.canReceiveAutoLikeAction(view)) return@forEach

                val liked = candidate.isLiked()
                if (liked == true) return AutoLikeResult.ALREADY_LIKED
                if (liked != false) return@forEach

                return if (view.performClick()) {
                    AutoLikeResult.PERFORMED
                } else {
                    AutoLikeResult.NO_CANDIDATE
                }
            }
            AutoLikeResult.NO_CANDIDATE
        }.onFailure {
            log("SkipVideoAd auto-like failed", it)
        }.getOrDefault(AutoLikeResult.NO_CANDIDATE)

    private fun hookDetailLikeComponent(env: RoamingEnv): Int {
        val type = DETAIL_LIKE_COMPONENT.from(env.classLoader) ?: return 0
        val method = type.allMethods().firstOrNull { it.isDetailLikeInflateMethod() } ?: return 0
        env.hookAfter(method) { param ->
            val root = param.result?.callMethod("getRoot") as? View ?: return@hookAfter
            val stateOwner = param.thisObject?.detailLikeStateOwner() ?: return@hookAfter
            remember(root, stateOwner, LikeKind.DETAIL)
        }
        return 1
    }

    private fun hookViewConstructors(
        env: RoamingEnv,
        className: String,
        kind: LikeKind,
    ): Int {
        val type = className.from(env.classLoader) ?: return 0
        return hookViewConstructors(env, type, kind)
    }

    private fun hookViewConstructors(
        env: RoamingEnv,
        type: Class<*>,
        kind: LikeKind,
    ): Int =
        env.hookAfterAllConstructors(type) { param ->
            val view = param.thisObject as? View ?: return@hookAfterAllConstructors
            remember(view, null, kind)
        }

    private fun hookStoryLikeWidget(env: RoamingEnv, className: String): Int {
        val type = className.from(env.classLoader) ?: return 0
        var count = hookViewConstructors(env, type, LikeKind.STORY)
        type.allMethods()
            .filter { it.isStoryBindMethod() }
            .forEach { method ->
                env.hookAfter(method) { param ->
                    val view = param.thisObject as? View ?: return@hookAfter
                    remember(view, param.args.firstOrNull(), LikeKind.STORY)
                }
                count++
            }
        return count
    }

    private fun remember(view: View, stateOwner: Any?, kind: LikeKind) {
        candidates.toList().forEach { candidate ->
            val existing = candidate.viewRef.get()
            if (existing == null || existing === view) {
                candidates.remove(candidate)
            }
        }
        candidates += LikeCandidate(
            viewRef = WeakReference(view),
            stateOwnerRef = stateOwner?.let(::WeakReference),
            kind = kind,
        )
    }

    private fun pruneCandidates() {
        candidates.toList().forEach { candidate ->
            if (candidate.viewRef.get() == null) {
                candidates.remove(candidate)
            }
        }
    }

    private fun LikeCandidate.canReceiveAutoLikeAction(view: View): Boolean {
        return when (kind) {
            LikeKind.DETAIL -> view.isAttachedToWindow && view.isShown && view.isEnabled && view.hasOnClickListeners()
            LikeKind.STORY -> view.hasOnClickListeners() && storyOwner(view)?.callMethod("isActive") == true
            LikeKind.GEMINI -> view.isAttachedToWindow && view.isEnabled && view.hasOnClickListeners()
        }
    }

    private fun Method.isDetailLikeInflateMethod(): Boolean {
        if (name != "b" || parameterCount != 3) return false
        val params = parameterTypes
        return Context::class.java.isAssignableFrom(params[0]) &&
            LayoutInflater::class.java.isAssignableFrom(params[1]) &&
            ViewGroup::class.java.isAssignableFrom(params[2])
    }

    private fun Method.isStoryBindMethod(): Boolean =
        parameterCount == 1 &&
            returnType == Void.TYPE &&
            parameterTypes[0].isStoryActionOwnerType()

    private fun Any.detailLikeStateOwner(): Any? =
        cachedField(detailStateOwnerFields, javaClass) {
            javaClass.allFields().firstOrNull { it.type.name == DETAIL_LIKE_STATE_OWNER }
        }?.let { field ->
            runCatching { field.get(this) }.getOrNull()
        }

    private fun LikeCandidate.isLiked(): Boolean? =
        when (kind) {
            LikeKind.DETAIL -> stateOwnerRef?.get()?.callMethod("c") as? Boolean
            LikeKind.STORY -> viewRef.get()?.let { view -> storyOwner(view)?.storyLikeState() }
            LikeKind.GEMINI -> viewRef.get()?.geminiLikeState()
        }

    private fun LikeCandidate.storyOwner(view: View): Any? =
        view.storyActionOwner() ?: stateOwnerRef?.get()

    private fun Any.storyLikeState(): Boolean? {
        val data = callMethod("getData") ?: return null
        val requestUser = data.callMethod("getRequestUser") ?: return null
        return requestUser.callMethod("getLike") as? Boolean
    }

    private fun View.storyActionOwner(): Any? =
        cachedField(storyOwnerFields, javaClass) {
            javaClass.allFields().firstOrNull { it.type.isStoryActionOwnerType() }
        }?.let { field ->
            runCatching { field.get(this) }.getOrNull()
        }

    private fun Class<*>.isStoryActionOwnerType(): Boolean {
        storyActionOwnerTypes[this]?.let { return it }
        val result = isStoryActionOwnerTypeUncached()
        storyActionOwnerTypes[this] = result
        return result
    }

    private fun Class<*>.isStoryActionOwnerTypeUncached(): Boolean {
        if (name == STORY_ACTION_OWNER) return true
        val methods = runCatching { allMethods().toList() }.getOrDefault(emptyList())
        return methods.any {
            it.name == "getData" && it.parameterCount == 0
        } && methods.any {
            it.name == "isActive" &&
                it.parameterCount == 0 &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType)
        }
    }

    private fun cachedField(
        cache: ConcurrentHashMap<Class<*>, FieldLookup>,
        type: Class<*>,
        finder: () -> Field?,
    ): Field? {
        cache[type]?.let { return it.field }
        val field = runCatching { finder() }.getOrNull()
        cache[type] = FieldLookup(field)
        return field
    }

    private fun View.geminiLikeState(): Boolean? =
        (callMethod("getMLikeStateFlow")?.callMethod("getValue") as? Boolean)
            ?: isSelected.takeIf { hasOnClickListeners() }

    enum class AutoLikeResult {
        PERFORMED,
        ALREADY_LIKED,
        NO_CANDIDATE,
    }

    private enum class LikeKind {
        DETAIL,
        STORY,
        GEMINI,
    }

    private data class LikeCandidate(
        val viewRef: WeakReference<View>,
        val stateOwnerRef: WeakReference<Any>?,
        val kind: LikeKind,
    )

    private data class FieldLookup(val field: Field?)

    private const val DETAIL_LIKE_COMPONENT =
        "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionComponent2\$LikeComponent"
    private const val DETAIL_LIKE_STATE_OWNER =
        "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionService\$d"
    private const val STORY_ACTION_OWNER =
        "com.bilibili.video.story.action.InterfaceC24213g"
    private const val STORY_LIKE_WIDGET =
        "com.bilibili.video.story.action.widget.StoryLikeWidget"
    private const val STORY_LANDSCAPE_LIKE_WIDGET =
        "com.bilibili.video.story.action.widget.StoryLandscapeLikeWidget"
    private const val GEMINI_PLAYER_LIKE_WIDGET =
        "com.bilibili.app.gemini.player.widget.like.GeminiPlayerLikeWidget"
}
