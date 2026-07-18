package com.clawdroid.app.xposed

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Chrome-role localization for WeChat pages (opt-in adapter).
 * Matches id / contentDescription / class heuristics — never list-cell message text.
 */
internal object WeChatControlRoles {
    private const val VALUE_MAX = 96

    /** Pages where chrome roles are collected. Payment pages are excluded. */
    val ALLOWED_PAGES: Set<String> = setOf("chat", "home", "contacts", "search", "moments")

    data class Candidate(
        val className: String,
        val id: String = "",
        val contentDescription: String = "",
        val bounds: String = ""
    )

    fun fromActivity(activity: Activity, page: String): Map<String, String> {
        if (page !in ALLOWED_PAGES) return emptyMap()
        val root = activity.window?.decorView ?: return emptyMap()
        val candidates = ArrayList<Candidate>(ViewHierarchyDumper.MAX_NODES)
        collectCandidates(root, depth = 0, out = candidates)
        return fromCandidates(page, candidates)
    }

    fun fromCandidates(page: String, candidates: List<Candidate>): Map<String, String> {
        if (page !in ALLOWED_PAGES) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (candidate in candidates) {
            val role = matchRole(page, candidate) ?: continue
            val key = "wechat_ctrl_$role"
            if (key in out) continue
            out[key] = encodeValue(candidate).take(VALUE_MAX)
        }
        return out
    }

    fun matchRole(page: String, candidate: Candidate): String? {
        val hay = buildString {
            append(candidate.id.lowercase())
            append(' ')
            append(candidate.contentDescription.lowercase())
            append(' ')
            append(candidate.className.lowercase())
        }
        val className = candidate.className
        // Prefer structural EditText / editable class as input (not message TextViews).
        if (className.contains("EditText", ignoreCase = true) ||
            (className.contains("Edit", ignoreCase = true) && hay.contains("input"))
        ) {
            if (page == "chat" || page == "search" || page == "moments") {
                return "input"
            }
        }
        return when {
            matchesAny(hay, "send", "发送") -> "send"
            matchesAny(hay, "back", "返回", "navigate_up", "up_btn") -> "back"
            matchesAny(hay, "more", "更多", "overflow") -> "more"
            matchesAny(hay, "voice", "语音", "speak") -> "voice"
            matchesAny(hay, "emoji", "表情", "smiley") -> "emoji"
            page == "home" && matchesAny(hay, "tab", "main_tab", "底部") -> "tab"
            matchesAny(hay, "input", "edit_text", "chat_footer", "文字") &&
                (page == "chat" || page == "search") -> "input"
            else -> null
        }
    }

    private fun matchesAny(hay: String, vararg needles: String): Boolean {
        return needles.any { needle -> hay.contains(needle.lowercase()) }
    }

    private fun encodeValue(candidate: Candidate): String {
        val id = candidate.id.trim()
        val bounds = candidate.bounds.trim()
        return when {
            id.isNotBlank() && bounds.isNotBlank() -> "$id|$bounds"
            id.isNotBlank() -> id
            else -> bounds
        }
    }

    private fun collectCandidates(view: View, depth: Int, out: MutableList<Candidate>) {
        if (out.size >= ViewHierarchyDumper.MAX_NODES) return
        if (depth > ViewHierarchyDumper.MAX_DEPTH) return
        // Skip list-like message containers' text children for role matching by not using text.
        val id = resolveViewId(view)
        val desc = view.contentDescription?.toString().orEmpty().trim()
        val className = ViewHierarchyDumper.classSimpleName(view.javaClass.name)
        val useful = id.isNotBlank() || desc.isNotBlank() ||
            view is EditText ||
            className.contains("EditText", ignoreCase = true)
        if (useful) {
            out += Candidate(
                className = className,
                id = id,
                contentDescription = desc.take(80),
                bounds = XposedViewSnapshotStore.screenBounds(view)
            )
        }
        if (view is ViewGroup && depth < ViewHierarchyDumper.MAX_DEPTH) {
            for (i in 0 until view.childCount) {
                if (out.size >= ViewHierarchyDumper.MAX_NODES) return
                collectCandidates(view.getChildAt(i), depth + 1, out)
            }
        }
    }

    private fun resolveViewId(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrElse {
            "0x${Integer.toHexString(id)}"
        }
    }
}
