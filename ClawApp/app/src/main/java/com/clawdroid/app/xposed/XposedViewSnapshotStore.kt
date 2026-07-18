package com.clawdroid.app.xposed

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/**
 * Schema v1 shallow View hierarchy snapshots (Settings-first).
 * Separate from focus snapshots to keep focus extras small.
 */
internal object XposedViewSnapshotStore {
    const val LATEST_FILE = "view_latest.json"

    @Volatile
    internal var snapshotDirOverride: File? = null

    fun writeFromActivity(
        activity: Activity,
        adapterId: String,
        loadedAtEpochMs: Long = System.currentTimeMillis(),
        redactText: Boolean = false
    ): String? {
        return runCatching {
            val root = activity.window?.decorView ?: return@runCatching null
            val component = activity.componentName
                ?: android.content.ComponentName(activity.packageName, activity.javaClass.name)
            val tree = walkAndroidView(root, depth = 0, redactText = redactText)
            val flat = ViewHierarchyDumper.flatten(tree)
            val truncated = flat.size >= ViewHierarchyDumper.MAX_NODES ||
                flat.any { it.depth >= ViewHierarchyDumper.MAX_DEPTH && it.childCount > 0 }
            val payload = ViewHierarchyDumper.buildPayload(
                packageName = activity.packageName,
                activityClass = component.className,
                adapterId = adapterId,
                loadedAtEpochMs = loadedAtEpochMs,
                nodes = flat,
                truncated = truncated
            )
            val dir = snapshotDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            atomicWrite(File(dir, LATEST_FILE), payload)
            payload
        }.getOrNull()
    }

    fun summaryForProbe(): String = ViewHierarchyDumper.formatSummary(readRaw())

    fun readRaw(): String? {
        return runCatching {
            val file = File(snapshotDir(), LATEST_FILE)
            if (file.exists()) file.readText() else null
        }.getOrNull()
    }

    internal fun snapshotDir(): File = snapshotDirOverride ?: File(XposedPaths.DIR)

    private fun walkAndroidView(view: View, depth: Int, redactText: Boolean): ViewHierarchyDumper.NodeSpec {
        val childCount = if (view is ViewGroup) view.childCount else 0
        val children = if (view is ViewGroup && depth < ViewHierarchyDumper.MAX_DEPTH) {
            (0 until childCount).map { index ->
                walkAndroidView(view.getChildAt(index), depth + 1, redactText)
            }
        } else {
            emptyList()
        }
        val text = when {
            redactText -> ""
            view is TextView -> view.text?.toString().orEmpty()
            else -> ""
        }
        val desc = view.contentDescription?.toString().orEmpty()
        val bounds = screenBounds(view)
        val semantics = ComposeSemanticsProbe.summarize(ComposeSemanticsProbe.enrich(view))
        return ViewHierarchyDumper.NodeSpec(
            className = ViewHierarchyDumper.classSimpleName(view.javaClass.name),
            id = resolveViewId(view),
            text = text.trim(),
            contentDescription = desc.trim(),
            bounds = bounds,
            visibility = visibilityLabel(view.visibility),
            childCount = childCount,
            depth = depth,
            semantics = semantics,
            children = children
        )
    }

    /** Screen-coordinate bounds: left,top,right,bottom from getLocationOnScreen + size. */
    internal fun screenBounds(view: View): String {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + view.width
        val bottom = top + view.height
        return "$left,$top,$right,$bottom"
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

    private fun visibilityLabel(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> "unknown"
        }
    }

    private fun atomicWrite(file: File, payload: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            file.writeText(payload)
            tmp.delete()
        }
    }
}
