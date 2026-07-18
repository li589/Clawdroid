package com.clawdroid.app.xposed

import android.view.View

/**
 * Best-effort Compose/accessibility semantics extraction without compile-time Compose deps.
 * Uses AccessibilityNodeInfo first, then optional reflection into Compose semantics APIs.
 */
internal object ComposeSemanticsProbe {
    private const val MAX_CHARS = 120
    private const val MAX_SEM_ENTRIES = 6

    fun enrich(view: View): Map<String, String> {
        if (!ViewHierarchyDumper.isComposeClass(view.javaClass.name) &&
            !ViewHierarchyDumper.isComposeClass(view.javaClass.simpleName)
        ) {
            // Still try a11y for unlabeled containers that host Compose.
            if (!looksLikeComposeHost(view)) {
                return emptyMap()
            }
        }
        val out = linkedMapOf<String, String>()
        out.putAll(fromAccessibility(view))
        out.putAll(fromReflection(view))
        return out
    }

    fun summarize(semantics: Map<String, String>): String {
        if (semantics.isEmpty()) return ""
        return semantics.entries.take(MAX_SEM_ENTRIES).joinToString(";") { (k, v) ->
            "${k.take(24)}=${v.take(40)}"
        }.take(MAX_CHARS)
    }

    /** Pure helper for unit tests. */
    fun mergeUnique(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
        if (extra.isEmpty()) return base
        val out = linkedMapOf<String, String>()
        out.putAll(base)
        for ((k, v) in extra) {
            if (k.isBlank() || v.isBlank()) continue
            if (out.size >= MAX_SEM_ENTRIES) break
            out.putIfAbsent(k.take(32), v.take(MAX_CHARS))
        }
        return out
    }

    private fun looksLikeComposeHost(view: View): Boolean {
        val name = view.javaClass.name.lowercase()
        return name.contains("androidx.compose") || name.contains("compose")
    }

    private fun fromAccessibility(view: View): Map<String, String> {
        return runCatching {
            val info = view.createAccessibilityNodeInfo() ?: return emptyMap()
            try {
                val out = linkedMapOf<String, String>()
                val text = info.text?.toString()?.trim().orEmpty()
                val desc = info.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (android.os.Build.VERSION.SDK_INT >= 26) {
                    info.hintText?.toString()?.trim().orEmpty()
                } else {
                    ""
                }
                val pane = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.paneTitle?.toString()?.trim().orEmpty()
                } else {
                    ""
                }
                if (text.isNotBlank()) out["a11y_text"] = text.take(MAX_CHARS)
                if (desc.isNotBlank()) out["a11y_desc"] = desc.take(MAX_CHARS)
                if (hint.isNotBlank()) out["a11y_hint"] = hint.take(MAX_CHARS)
                if (pane.isNotBlank()) out["a11y_pane"] = pane.take(MAX_CHARS)
                if (info.isEditable) out["a11y_editable"] = "true"
                if (info.isClickable) out["a11y_clickable"] = "true"
                if (info.childCount > 0) out["a11y_children"] = info.childCount.toString()
                out
            } finally {
                info.recycle()
            }
        }.getOrDefault(emptyMap())
    }

    private fun fromReflection(view: View): Map<String, String> {
        return runCatching {
            val out = linkedMapOf<String, String>()
            // AndroidComposeView / AbstractComposeView often expose getSemanticsOwner()
            val owner = invokeNoArg(view, "getSemanticsOwner") ?: return emptyMap()
            val unmerged = invokeNoArg(owner, "getUnmergedRootSemanticsNode")
                ?: invokeNoArg(owner, "getRootSemanticsNode")
                ?: return emptyMap()
            collectSemanticsNode(unmerged, out, depth = 0)
            out
        }.getOrDefault(emptyMap())
    }

    private fun collectSemanticsNode(node: Any, out: MutableMap<String, String>, depth: Int) {
        if (out.size >= MAX_SEM_ENTRIES || depth > 2) return
        val config = invokeNoArg(node, "getConfig") ?: invokeNoArg(node, "getUnmergedConfig")
        if (config != null) {
            extractConfigStrings(config, out)
        }
        val children = invokeNoArg(node, "getChildren") as? Collection<*>
        if (children != null) {
            for (child in children.take(4)) {
                if (child == null || out.size >= MAX_SEM_ENTRIES) break
                collectSemanticsNode(child, out, depth + 1)
            }
        }
    }

    private fun extractConfigStrings(config: Any, out: MutableMap<String, String>) {
        // SemanticsConfiguration is Iterable<Map.Entry<SemanticsPropertyKey<*>, Any?>> in many versions.
        when (config) {
            is Iterable<*> -> {
                for (entry in config) {
                    if (out.size >= MAX_SEM_ENTRIES) return
                    val key = invokeNoArg(entry ?: continue, "getKey")
                        ?: entry.javaClass.methods.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
                            ?.invoke(entry)
                    val value = invokeNoArg(entry, "getValue")
                        ?: entry.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                            ?.invoke(entry)
                    val keyName = key?.toString()?.substringAfterLast('.')?.take(32).orEmpty()
                    val valueText = value?.toString()?.trim().orEmpty()
                    if (keyName.isBlank() || valueText.isBlank()) continue
                    if (keyName.contains("Text", ignoreCase = true) ||
                        keyName.contains("ContentDescription", ignoreCase = true) ||
                        keyName.contains("TestTag", ignoreCase = true) ||
                        keyName.contains("Role", ignoreCase = true) ||
                        keyName.contains("StateDescription", ignoreCase = true)
                    ) {
                        out.putIfAbsent("sem_$keyName", valueText.take(MAX_CHARS))
                    }
                }
            }
            else -> {
                val asString = config.toString().trim()
                if (asString.isNotBlank() && asString.length <= MAX_CHARS) {
                    out.putIfAbsent("sem_config", asString)
                }
            }
        }
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }
}
