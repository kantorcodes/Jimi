package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.harness.HarnessChange;
import io.leavesfly.jimi.harness.HarnessJournal;
import io.leavesfly.jimi.harness.HarnessStore;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /harness 命令处理器
 * <p>
 * 提供 harness 状态（prompt / memory / skill / subagent）的审计查看与回滚能力：
 * <ul>
 *   <li>/harness log [limit] - 查看变更审计日志</li>
 *   <li>/harness show &lt;id&gt; - 查看某条变更的详细前后内容</li>
 *   <li>/harness revert &lt;id&gt; - 回滚指定变更</li>
 *   <li>/harness list &lt;kind&gt; - 列出某类组件的所有目标</li>
 * </ul>
 * <p>
 * 回滚采用追加逆向记录的方式，原始历史永不被改写。
 */
@Slf4j
@Component
public class HarnessCommandHandler implements CommandHandler {

    /** 日志默认展示条数 */
    private static final int DEFAULT_LOG_LIMIT = 20;

    /** 详情视图中前后内容的截断长度 */
    private static final int DETAIL_SNIPPET_LIMIT = 600;

    @Autowired
    private HarnessJournal harnessJournal;

    @Autowired
    private HarnessStore harnessStore;

    @Override
    public String getName() {
        return "harness";
    }

    @Override
    public String getDescription() {
        return "查看与回滚 harness 状态变更";
    }

    @Override
    public String getUsage() {
        return "/harness [log [limit]|show <id>|revert <id>|list <prompt|memory|skill|subagent>]";
    }

    @Override
    public String getCategory() {
        return "knowledge";
    }

    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        String workDirPath = context.getEngineClient().getWorkDir().toAbsolutePath().toString();

        String subCommand = context.getArg(0);
        if (subCommand == null || subCommand.isEmpty()) {
            showLog(out, workDirPath, DEFAULT_LOG_LIMIT);
            return;
        }

        switch (subCommand.toLowerCase()) {
            case "log" -> showLog(out, workDirPath, parseLimit(context.getArg(1)));
            case "show" -> showDetail(out, workDirPath, context.getArg(1));
            case "revert" -> revert(out, workDirPath, context.getArg(1));
            case "list" -> listTargets(out, workDirPath, context.getArg(1));
            case "help" -> showHelp(out);
            default -> {
                out.printError("未知子命令: " + subCommand);
                showHelp(out);
            }
        }
    }

    /**
     * 展示变更审计日志
     */
    private void showLog(OutputFormatter out, String workDirPath, int limit) {
        List<HarnessChange> changes = harnessJournal.list(workDirPath, limit);
        if (changes.isEmpty()) {
            out.printInfo("暂无 harness 变更记录");
            return;
        }

        out.printStatus(String.format("harness 变更记录（最近 %d 条）", changes.size()));
        out.println();
        for (HarnessChange change : changes) {
            out.println(String.format("  #%d  %s  %s/%s  %s",
                    change.getId(),
                    change.getTs(),
                    change.getKind(),
                    change.getOp(),
                    change.getTargetId()));
            out.println(String.format("      trigger: %s%s",
                    change.getTrigger(),
                    change.getOutcome() != null ? "  outcome: " + change.getOutcome() : ""));
        }
        out.println();
        out.printInfo("使用 /harness show <id> 查看详情，/harness revert <id> 回滚");
    }

    /**
     * 展示单条变更的前后内容
     */
    private void showDetail(OutputFormatter out, String workDirPath, String idArg) {
        Long id = parseId(out, idArg);
        if (id == null) {
            return;
        }

        harnessJournal.get(workDirPath, id).ifPresentOrElse(change -> {
            out.printStatus(String.format("变更 #%d  %s/%s  %s",
                    change.getId(), change.getKind(), change.getOp(), change.getTargetId()));
            out.println("  时间: " + change.getTs());
            out.println("  触发: " + change.getTrigger());
            if (change.getOutcome() != null) {
                out.println("  效果: " + change.getOutcome());
            }
            out.println();
            out.printInfo("变更前:");
            out.println(truncate(change.getBefore()));
            out.println();
            out.printInfo("变更后:");
            out.println(truncate(change.getAfter()));
        }, () -> out.printError("未找到 ID 为 " + id + " 的变更记录"));
    }

    /**
     * 回滚指定变更
     */
    private void revert(OutputFormatter out, String workDirPath, String idArg) {
        Long id = parseId(out, idArg);
        if (id == null) {
            return;
        }

        HarnessJournal.RevertResult result = harnessJournal.revert(workDirPath, id);
        if (result.success()) {
            out.printSuccess(result.message());
        } else {
            out.printError(result.message());
        }
    }

    /**
     * 列出指定组件类型下的目标
     */
    private void listTargets(OutputFormatter out, String workDirPath, String kindArg) {
        if (kindArg == null || kindArg.isBlank()) {
            out.printError("请指定组件类型: prompt / memory / skill / subagent");
            return;
        }

        HarnessChange.Kind kind;
        try {
            kind = HarnessChange.Kind.valueOf(kindArg.toUpperCase());
        } catch (IllegalArgumentException e) {
            out.printError("未知组件类型: " + kindArg + "，可选: prompt / memory / skill / subagent");
            return;
        }

        List<String> targets = harnessStore.list(workDirPath, kind);
        if (targets.isEmpty()) {
            out.printInfo(kind + " 下暂无目标");
            return;
        }

        out.printStatus(kind + " 目标列表（" + targets.size() + " 项）");
        targets.forEach(target -> out.println("  - " + target));
    }

    private void showHelp(OutputFormatter out) {
        out.printStatus("harness 命令用法");
        out.println("  /harness log [limit]        查看变更审计日志（默认 " + DEFAULT_LOG_LIMIT + " 条）");
        out.println("  /harness show <id>          查看某条变更的前后内容");
        out.println("  /harness revert <id>        回滚指定变更（追加逆向记录，不改写历史）");
        out.println("  /harness list <kind>        列出组件目标，kind 取 prompt/memory/skill/subagent");
    }

    /**
     * 解析记录 ID，失败时输出错误并返回 null
     */
    private Long parseId(OutputFormatter out, String idArg) {
        if (idArg == null || idArg.isBlank()) {
            out.printError("请指定变更记录 ID");
            return null;
        }
        try {
            return Long.parseLong(idArg.trim());
        } catch (NumberFormatException e) {
            out.printError("非法的记录 ID: " + idArg);
            return null;
        }
    }

    /**
     * 解析展示条数，非法或缺失时回退到默认值
     */
    private int parseLimit(String limitArg) {
        if (limitArg == null || limitArg.isBlank()) {
            return DEFAULT_LOG_LIMIT;
        }
        try {
            int limit = Integer.parseInt(limitArg.trim());
            return limit > 0 ? limit : DEFAULT_LOG_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_LOG_LIMIT;
        }
    }

    private String truncate(String content) {
        if (content == null) {
            return "  (无)";
        }
        if (content.length() <= DETAIL_SNIPPET_LIMIT) {
            return content;
        }
        return content.substring(0, DETAIL_SNIPPET_LIMIT)
                + String.format("\n  ...（共 %d 字符，已截断）", content.length());
    }
}
