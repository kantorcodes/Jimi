package io.leavesfly.jimi.tool.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.harness.HarnessChange;
import io.leavesfly.jimi.harness.HarnessStore;
import io.leavesfly.jimi.tool.SyncTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * harness 状态管理工具
 * <p>
 * 把 Continual Harness 的四个组件 {@code H=(ρ,G,K,M)} 收敛到同一套 CRUD 接口，
 * 让 Agent 能以统一方式沉淀可复用的操作模式：
 * <ul>
 *   <li>{@code prompt} - 补充指令，会即时进入后续对话的 harness 状态快照</li>
 *   <li>{@code memory} - 长期记忆（section 级写入仍建议用 Memory 工具）</li>
 *   <li>{@code skill} - 技能（install / invoke 等专属操作仍用 Skills 工具）</li>
 *   <li>{@code subagent} - 子 Agent 规范</li>
 * </ul>
 * <p>
 * 所有写操作都会经过审计日志，可用 {@code /harness log} 查看、{@code /harness revert} 回滚。
 * 基础系统提示词不可修改，本工具只能写补充层。
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HarnessTool extends SyncTool<HarnessTool.Params> {

    private static final String NAME = "Harness";
    private static final String DESCRIPTION =
            "管理 harness 状态（补充指令 / 记忆 / 技能 / 子 Agent 规范）。支持的操作：\n"
            + "- list: 列出指定类型下的所有目标（需要 kind）\n"
            + "- get: 读取指定目标的内容（需要 kind、target_id）\n"
            + "- create: 新建目标（需要 kind、target_id、content）\n"
            + "- update: 覆盖更新目标（需要 kind、target_id、content）\n"
            + "- delete: 删除目标（需要 kind、target_id）\n\n"
            + "kind 取值：prompt（补充指令）、memory（长期记忆）、skill（技能）、subagent（子 Agent 规范）。\n"
            + "当你发现某个操作模式会反复用到、或某次失败的教训值得固化时，用 prompt 或 skill 记下来。\n"
            + "注意：基础系统提示词不可修改，prompt 类型写入的是补充层，且总量有上限。";

    @Autowired
    private HarnessStore harnessStore;

    /** 工作目录绝对路径，运行时注入 */
    private String workDirPath;

    public HarnessTool() {
        super(NAME, DESCRIPTION, Params.class);
    }

    public void setWorkDirPath(String workDirPath) {
        this.workDirPath = workDirPath;
    }

    @Override
    protected ToolResult executeSync(Params params) {
        if (harnessStore == null || workDirPath == null) {
            return ToolResult.error("Harness tool not properly initialized", "初始化失败");
        }

        String action = params.getAction();
        if (action == null || action.isBlank()) {
            return ToolResult.error(
                    "action is required. Supported: list, get, create, update, delete", "缺少 action 参数");
        }

        return switch (action.toLowerCase()) {
            case "list" -> handleList(params);
            case "get" -> handleGet(params);
            case "create" -> handleWrite(params, true);
            case "update" -> handleWrite(params, false);
            case "delete" -> handleDelete(params);
            default -> ToolResult.error(
                    "Unknown action: " + action + ". Supported: list, get, create, update, delete",
                    "未知操作");
        };
    }

    private ToolResult handleList(Params params) {
        HarnessChange.Kind kind = parseKind(params.getKind());
        if (kind == null) {
            return invalidKind(params.getKind());
        }

        List<String> targets = harnessStore.list(workDirPath, kind);
        if (targets.isEmpty()) {
            return ToolResult.ok("No targets under " + kind, "无目标");
        }
        return ToolResult.ok(
                kind + " targets:\n" + String.join("\n", targets.stream().map(t -> "- " + t).toList()),
                "列出 " + kind,
                targets.size() + " 项");
    }

    private ToolResult handleGet(Params params) {
        HarnessChange.Kind kind = parseKind(params.getKind());
        if (kind == null) {
            return invalidKind(params.getKind());
        }
        if (isBlank(params.getTargetId())) {
            return ToolResult.error("target_id is required for get action", "缺少 target_id");
        }

        String content = harnessStore.read(workDirPath, kind, params.getTargetId());
        if (content == null) {
            return ToolResult.ok("Target not found: " + params.getTargetId(), "目标不存在");
        }
        return ToolResult.ok(content, "读取成功", "读取 " + params.getTargetId());
    }

    private ToolResult handleWrite(Params params, boolean requireAbsent) {
        HarnessChange.Kind kind = parseKind(params.getKind());
        if (kind == null) {
            return invalidKind(params.getKind());
        }
        if (isBlank(params.getTargetId())) {
            return ToolResult.error("target_id is required", "缺少 target_id");
        }
        if (params.getContent() == null) {
            return ToolResult.error("content is required", "缺少 content");
        }

        String existing = harnessStore.read(workDirPath, kind, params.getTargetId());
        if (requireAbsent && existing != null && !existing.isEmpty()) {
            return ToolResult.error(
                    "Target already exists: " + params.getTargetId() + ". Use action='update' instead.",
                    "目标已存在");
        }

        // 补充提示词有总量上限，防止补充层无界膨胀
        if (kind == HarnessChange.Kind.PROMPT) {
            int delta = params.getContent().length() - (existing != null ? existing.length() : 0);
            Optional<String> overBudget = harnessStore.checkPromptNoteBudget(workDirPath, delta);
            if (overBudget.isPresent()) {
                return ToolResult.error(overBudget.get(), "超出 prompt notes 上限");
            }
        }

        try {
            harnessStore.applyAndRecord(workDirPath, "manual", kind, params.getTargetId(), params.getContent());
        } catch (Exception e) {
            log.error("Failed to write harness target: {}/{}", kind, params.getTargetId(), e);
            return ToolResult.error("写入失败: " + e.getMessage(), "写入失败");
        }

        return ToolResult.ok(
                String.format("Successfully wrote %s target '%s'", kind, params.getTargetId()),
                "harness 已更新",
                "写入 " + params.getTargetId());
    }

    private ToolResult handleDelete(Params params) {
        HarnessChange.Kind kind = parseKind(params.getKind());
        if (kind == null) {
            return invalidKind(params.getKind());
        }
        if (isBlank(params.getTargetId())) {
            return ToolResult.error("target_id is required for delete action", "缺少 target_id");
        }

        try {
            harnessStore.applyAndRecord(workDirPath, "manual", kind, params.getTargetId(), null);
        } catch (Exception e) {
            log.error("Failed to delete harness target: {}/{}", kind, params.getTargetId(), e);
            return ToolResult.error("删除失败: " + e.getMessage(), "删除失败");
        }

        return ToolResult.ok(
                String.format("Successfully deleted %s target '%s'", kind, params.getTargetId()),
                "harness 已更新",
                "删除 " + params.getTargetId());
    }

    private HarnessChange.Kind parseKind(String kind) {
        if (isBlank(kind)) {
            return null;
        }
        try {
            return HarnessChange.Kind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ToolResult invalidKind(String kind) {
        return ToolResult.error(
                "Invalid kind: " + kind + ". Supported: prompt, memory, skill, subagent",
                "非法的 kind");
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    @Override
    public boolean isConcurrentSafe() {
        return false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonProperty("action")
        @JsonPropertyDescription("操作类型：list、get、create、update、delete")
        private String action;

        @JsonProperty("kind")
        @JsonPropertyDescription("harness 组件类型：prompt（补充指令）、memory（长期记忆）、skill（技能）、subagent（子 Agent 规范）")
        private String kind;

        @JsonProperty("target_id")
        @JsonPropertyDescription("目标标识：prompt 为 note 名称，skill 为技能名，subagent 为子 Agent 名，memory 为 section 名")
        private String targetId;

        @JsonProperty("content")
        @JsonPropertyDescription("写入内容，create 与 update 操作必需")
        private String content;
    }
}
