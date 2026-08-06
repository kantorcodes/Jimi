package io.leavesfly.jimi.tool.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.tool.AbstractTool;
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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 异步子 Agent 结果查询工具
 * <p>
 * 与 {@code SubAgentTool(mode=async)} 配对：后者返回句柄，这里按句柄取状态与结果。
 * <p>
 * 运行态只存在内存，进程重启即丢。但句柄同时也是子 Agent 的 sessionId，
 * 因此即使运行态丢失，轨迹本身仍可用 {@code Memory(action=search)} 检索。
 *
 * @author 山泽
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SubagentResultTool extends AbstractTool<SubagentResultTool.Params> {

    private static final String DESCRIPTION = """
            查询由 SubAgentTool(mode=async) 异步启动的子 Agent。

            可用 action：
            - `status`: 查询某个句柄的运行状态（running / completed / failed）
            - `result`: 获取某个句柄的完整结果，仍在运行中时会告知尚未完成
            - `list`: 列出本次进程内所有异步运行记录

            注意：查询不会阻塞等待。若状态仍为 running，请先去做别的事，稍后再查。
            """;

    private final SubagentRunRegistry runRegistry;

    @Autowired
    public SubagentResultTool(SubagentRunRegistry runRegistry) {
        super("SubagentResult", DESCRIPTION, Params.class);
        this.runRegistry = runRegistry;
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.fromSupplier(() -> {
            if (params == null || params.getAction() == null || params.getAction().isBlank()) {
                return ToolResult.error("Missing required parameter: action", "缺少 action 参数");
            }

            String action = params.getAction().trim().toLowerCase();
            return switch (action) {
                case "list" -> handleList();
                case "status" -> handleStatus(params.getHandle());
                case "result" -> handleResult(params.getHandle());
                default -> ToolResult.error(
                        "Unknown action: " + action + ". Supported: status, result, list",
                        "未知的 action");
            };
        });
    }

    private ToolResult handleList() {
        List<SubagentRunRegistry.SubagentRun> runs = runRegistry.list();
        if (runs.isEmpty()) {
            return ToolResult.ok("当前没有异步子 Agent 运行记录。", "无异步运行记录");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共 %d 条异步运行记录（可用并发额度 %d）：%n%n",
                runs.size(), runRegistry.availablePermits()));
        for (SubagentRunRegistry.SubagentRun run : runs) {
            sb.append(String.format("- %s | %s | %s | %ds%n",
                    run.getHandle(),
                    run.getSubagentName(),
                    run.getState().name().toLowerCase(),
                    run.elapsedSeconds()));
        }
        return ToolResult.ok(sb.toString(), "已列出异步运行记录",
                "共 " + runs.size() + " 条异步运行记录");
    }

    private ToolResult handleStatus(String handle) {
        Optional<SubagentRunRegistry.SubagentRun> found = findRun(handle);
        if (found.isEmpty()) {
            return notFound(handle);
        }

        SubagentRunRegistry.SubagentRun run = found.get();
        String output = String.format(
                "handle: %s%nsubagent: %s%nstate: %s%nelapsed: %ds%ndescription: %s",
                run.getHandle(),
                run.getSubagentName(),
                run.getState().name().toLowerCase(),
                run.elapsedSeconds(),
                run.getDescription() != null ? run.getDescription() : "(无)");

        if (run.getState() == SubagentRunRegistry.State.RUNNING) {
            output += "\n\n[仍在运行中。请先推进其他工作，稍后用 action=result 取结果。]";
        }
        return ToolResult.ok(output, "已查询子 Agent 状态",
                run.getSubagentName() + " -> " + run.getState().name().toLowerCase());
    }

    private ToolResult handleResult(String handle) {
        Optional<SubagentRunRegistry.SubagentRun> found = findRun(handle);
        if (found.isEmpty()) {
            return notFound(handle);
        }

        SubagentRunRegistry.SubagentRun run = found.get();
        return switch (run.getState()) {
            case RUNNING -> ToolResult.ok(
                    String.format("子 Agent %s 仍在运行（已耗时 %ds）。请稍后再查询，不要在此处等待。",
                            run.getHandle(), run.elapsedSeconds()),
                    "子 Agent 尚未完成", "尚未完成");
            case COMPLETED -> ToolResult.ok(
                    run.getOutput() != null ? run.getOutput() : "(子 Agent 未产生输出)",
                    "已获取子 Agent 结果", "取回 " + run.getSubagentName() + " 结果");
            case FAILED -> ToolResult.error(
                    String.format("子 Agent %s 执行失败：%s", run.getHandle(),
                            run.getError() != null ? run.getError() : "(无错误信息)"),
                    "子 Agent 执行失败", "子 Agent 执行失败");
        };
    }

    /**
     * 按句柄查找运行记录，兼容模型误传带 {@code .jsonl} 后缀的情形
     */
    private Optional<SubagentRunRegistry.SubagentRun> findRun(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String normalized = handle.trim();
        if (normalized.endsWith(".jsonl")) {
            normalized = normalized.substring(0, normalized.length() - ".jsonl".length());
        }
        return runRegistry.find(normalized);
    }

    private ToolResult notFound(String handle) {
        if (handle == null || handle.isBlank()) {
            return ToolResult.error("Missing required parameter: handle", "缺少 handle 参数");
        }
        return ToolResult.error(
                String.format("未找到句柄 %s 的运行记录。异步运行态不跨进程保留；"
                        + "若需查看其历史轨迹，请用 Memory(action=search) 检索该 sessionId。", handle),
                "句柄不存在", "句柄不存在");
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonProperty("action")
        @JsonPropertyDescription("操作类型：status（查状态）、result（取结果）、list（列出全部）")
        private String action;

        @JsonProperty("handle")
        @JsonPropertyDescription("子 Agent 句柄，即异步启动时返回的 sessionId。action=list 时可省略")
        private String handle;
    }
}
