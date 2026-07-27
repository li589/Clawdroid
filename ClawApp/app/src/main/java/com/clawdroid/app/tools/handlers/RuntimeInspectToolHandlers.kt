package com.clawdroid.app.tools.handlers

import android.content.Context
import com.clawdroid.app.tools.CapabilityProbe
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.tools.ClawToolDefinitions
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.ToolHandler
import com.clawdroid.app.tools.ToolPermissionTier

fun runtimeInspectToolHandlers(
    executor: ClawToolExecutor,
    capabilityProbe: CapabilityProbe,
    appContext: Context?
): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.RUNTIME_PING to ToolHandler { _, _ -> executor.ping() },
    ClawTool.GET_VERSION to ToolHandler { _, _ -> executor.getVersion() },
    ClawTool.GET_HEALTH to ToolHandler { _, _ -> executor.getHealth() },
    ClawTool.GET_RUNTIME_STATUS to ToolHandler { _, _ -> executor.getRuntimeStatus() },
    ClawTool.GET_LAST_ERROR to ToolHandler { _, _ -> executor.getLastError() },
    ClawTool.PROBE_SESSION to ToolHandler { _, _ -> executor.probeSession() },
    ClawTool.GET_CAPABILITIES to ToolHandler { _, _ -> executor.getCapabilities() },
    ClawTool.LIST_TOOLS to ToolHandler { _, arguments ->
        runCatching { capabilityProbe.refreshIfStale() }
        val tag = arguments.string("tag")
        val tierName = arguments.string("tier")
        val tier = ToolPermissionTier.entries.firstOrNull {
            it.name.equals(tierName, ignoreCase = true)
        }
        val idPrefix = arguments.string("id_prefix")
        val includePlanned = arguments.bool("include_planned", default = false)
        val specs = ClawToolDefinitions.find(
            tag = tag.ifBlank { null },
            tier = tier,
            idPrefix = idPrefix.ifBlank { null },
            includePlanned = includePlanned
        ).filter {
            ClawAssetPromptStore.isToolEnabled(appContext, it.id, default = true)
        }
        val lines = buildList {
            specs.forEach { s ->
                val availability = ClawToolCatalog.availabilityFor(s)
                val flag = if (availability.available) "ok" else "unavailable"
                add("- ${s.id} [$flag/${s.tier.name}/${s.risk.name}] ${s.summary}")
            }
            if (includePlanned) {
                ClawAssetPromptStore.plannedBlueprints(appContext)
                    .filter { bp ->
                        (tag.isBlank() || bp.domain.equals(tag, true) ||
                            bp.id.contains(tag, true)) &&
                            (idPrefix.isBlank() || bp.id.startsWith(idPrefix, true)) &&
                            (tierName.isBlank() || bp.tier.equals(tierName, true))
                    }
                    .forEach { bp ->
                        add("- ${bp.id} [planned/${bp.tier}/${bp.domain}] ${bp.summary}")
                    }
            }
        }
        ClawToolCallResult(
            success = true,
            output = lines.joinToString("\n").ifBlank { "(empty)" }
        )
    },
    ClawTool.GET_TOOL to ToolHandler { _, arguments ->
        val toolId = arguments.string("tool_id", "id", "name")
        val json = ClawToolCatalog.describeTool(toolId, appContext)
            ?: return@ToolHandler ClawToolCallResult(false, "未知工具: $toolId", error = "unknown_tool")
        ClawToolCallResult(success = true, output = json.toString(2))
    }
)
