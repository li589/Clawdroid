package com.clawdroid.app.tools.handlers

import com.clawdroid.app.skills.ClawAgentCatalog
import com.clawdroid.app.skills.ClawAgentRunner
import com.clawdroid.app.skills.ClawSkillCatalog
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ToolHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

fun agentToolHandlers(agentRunner: ClawAgentRunner): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.LIST_SKILLS to ToolHandler { _, _ ->
        ClawToolCallResult(
            success = true,
            output = ClawSkillCatalog.all().joinToString("\n") { skill ->
                "- ${skill.id}: ${skill.name} — ${skill.description}"
            }
        )
    },
    ClawTool.GET_SKILL to ToolHandler { _, arguments ->
        val skillId = arguments.string("skill_id", "id", "name")
        val skill = ClawSkillCatalog.byId(skillId)
            ?: return@ToolHandler ClawToolCallResult(
                success = false,
                output = "未知 Skill: $skillId",
                error = "unknown_skill"
            )
        ClawToolCallResult(
            success = true,
            output = ClawSkillCatalog.toSkillMd(skill)
        )
    },
    ClawTool.LIST_AGENTS to ToolHandler { _, _ ->
        ClawToolCallResult(
            success = true,
            output = ClawAgentCatalog.all().joinToString("\n") { agent ->
                "- ${agent.id}: ${agent.name} — ${agent.description} [${agent.steps.joinToString(" -> ")}]"
            }
        )
    },
    ClawTool.RUN_AGENT to ToolHandler { _, arguments ->
        val agentId = arguments.string("agent_id", "agent", "id", "name")
        if (agentId.isBlank()) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: agent_id 不能为空",
                error = "missing_agent_id"
            )
        }
        agentRunner.run(agentId, arguments)
    },
    ClawTool.RUN_AGENTS_PARALLEL to ToolHandler { _, arguments ->
        val rawIds = arguments.string("agent_ids", "agents", "agent_id", "agent")
        val ids = rawIds
            .split(',', ';', '|', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (ids.isEmpty()) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: agent_ids 不能为空",
                error = "missing_agent_ids"
            )
        }
        coroutineScope {
            val parts = ids.map { id ->
                async {
                    id to agentRunner.run(id, arguments)
                }
            }.map { it.await() }
            val allOk = parts.all { it.second.success }
            ClawToolCallResult(
                success = allOk,
                output = buildString {
                    appendLine("并行 Agent 结果（${parts.size}）：")
                    parts.forEach { (id, result) ->
                        appendLine("--- $id (${if (result.success) "ok" else "fail"}) ---")
                        appendLine(result.output)
                    }
                },
                error = if (allOk) null else "parallel_agent_partial_failure"
            )
        }
    }
)
