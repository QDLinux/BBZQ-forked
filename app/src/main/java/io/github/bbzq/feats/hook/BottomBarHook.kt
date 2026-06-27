package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore

class BottomBarHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        ModuleSettings.refreshKnownBottomBarItemsCache(prefs)
        val symbols = env.symbols?.bottomBar?.restore(classLoader)
        val tabHostSetTabsMethods = symbols?.tabHostSetTabsMethods.orEmpty()
        val tabHostGetTabsMethods = symbols?.tabHostGetTabsMethods.orEmpty()
        val baseOnViewCreatedMethods = symbols?.baseOnViewCreatedMethods.orEmpty()

        tabHostSetTabsMethods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    val tabs = param.args.getOrNull(0) as? List<*> ?: return@runCatching
                    dispatch(tabs)?.let { updated ->
                        if (updated !== tabs) {
                            param.args[0] = updated
                        }
                    }
                }.onFailure {
                    log("Bottom bar TabHost processor failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }

        val tabHostClass = tabHostSetTabsMethods.firstOrNull()?.declaringClass
        baseOnViewCreatedMethods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching {
                    val host = param.thisObject?.findTabHost(tabHostClass) ?: return@runCatching
                    val getTabs = tabHostGetTabsMethods.firstOrNull { it.declaringClass.isInstance(host) }
                        ?: return@runCatching
                    val setTabs = tabHostSetTabsMethods.firstOrNull { it.declaringClass.isInstance(host) }
                        ?: return@runCatching
                    val tabs = getTabs.invoke(host) as? List<*> ?: return@runCatching
                    val updated = tabs.toMutableList()
                    val originalSize = updated.size
                    dispatch(updated)
                    if (updated.size != originalSize) {
                        setTabs.invoke(host, updated)
                    }
                }.onFailure {
                    log("Bottom bar onViewCreated processor failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }

        val totalMethods = tabHostSetTabsMethods.size + baseOnViewCreatedMethods.size
        if (totalMethods == 0) {
            log("startHook: BottomBar, no hook point found")
        } else {
            log("startHook: BottomBar, methods=$totalMethods")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(tabs: List<*>): List<*>? {
        val bottom = tabs as? MutableList<Any?> ?: tabs.toMutableList() as MutableList<Any?>
        val hiddenIds = ModuleSettings.getHiddenBottomBarItems(prefs)
        val enabled = ModuleSettings.isCustomBottomBarEnabled(prefs)
        val knownItems = linkedSetOf<String>()

        val changed = bottom.removeAll { item ->
            val entry = item?.extractBottomEntry() ?: return@removeAll false
            knownItems += encodeBottomItem(
                order = knownItems.size,
                id = entry.id,
                name = entry.name,
                uri = entry.uri,
            )
            enabled && entry.id in hiddenIds
        }

        saveKnownItems(knownItems)
        return if (changed) bottom else null
    }

    private fun Any.extractBottomEntry(): BottomBarEntry? {
        val strings = javaClass.allFields()
            .mapNotNull { field ->
                runCatching { field.get(this) as? String }.getOrNull()?.trim()
            }
            .filter { it.isNotEmpty() }
            .distinct()

        val guessedUri = strings.firstOrNull(::looksLikeUri)
        val guessedName = strings.firstOrNull(::looksLikeDisplayName)
            ?: strings.firstOrNull { it != guessedUri && it.length > 1 }
        val guessedId = strings.firstOrNull { it != guessedUri && it != guessedName && looksLikeAsciiId(it) }
            ?: strings.firstOrNull { it != guessedUri && it != guessedName && looksLikeBottomBarId(it) }
            ?: strings.firstOrNull { it != guessedUri && it != guessedName }

        if (guessedId == null && guessedName == null && guessedUri == null) return null
        val resolvedId = guessedId ?: guessedName ?: guessedUri ?: return null
        val resolvedName = guessedName ?: guessedId ?: guessedUri ?: resolvedId
        return BottomBarEntry(resolvedId, resolvedName, guessedUri.orEmpty())
    }

    private fun Any.findTabHost(tabHostClass: Class<*>?): Any? {
        if (tabHostClass == null) return null
        if (tabHostClass.isInstance(this)) return this
        return javaClass.allFields()
            .firstNotNullOfOrNull { field ->
                runCatching { field.get(this) }
                    .getOrNull()
                    ?.takeIf(tabHostClass::isInstance)
            }
    }

    private fun looksLikeUri(value: String): Boolean =
        "://" in value || value.startsWith("bilibili://", ignoreCase = true) ||
            value.startsWith("activity://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun looksLikeDisplayName(value: String): Boolean {
        if (looksLikeUri(value)) return false
        if (value.length < 2) return false
        return value.any { it.isLetter() || it.code in 0x4E00..0x9FFF }
    }

    private fun looksLikeBottomBarId(value: String): Boolean {
        if (looksLikeUri(value)) return false
        if (value.length !in 1..48) return false
        if (value.any(Char::isWhitespace)) return false
        return value.any { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun looksLikeAsciiId(value: String): Boolean {
        if (!looksLikeBottomBarId(value)) return false
        return value.all { it.code in 0x21..0x7E }
    }

    private fun saveKnownItems(items: Set<String>) {
        if (items.isEmpty()) return
        val oldItems = ModuleSettings.getKnownBottomBarItems(prefs)
        if (oldItems == items) return
        ModuleSettings.cacheKnownBottomBarItems(items)
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_BOTTOM_BAR_ITEMS, items.toMutableSet())
            .apply()
    }

    private fun encodeBottomItem(order: Int, id: String, name: String, uri: String): String =
        listOf(order.toString(), id, name, uri)
            .joinToString(ITEM_SEPARATOR) { it.sanitizeItemPart() }

    private fun String.sanitizeItemPart(): String =
        replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private data class BottomBarEntry(
        val id: String,
        val name: String,
        val uri: String,
    )

    private companion object {
        private const val ITEM_SEPARATOR = "\t"
    }
}
