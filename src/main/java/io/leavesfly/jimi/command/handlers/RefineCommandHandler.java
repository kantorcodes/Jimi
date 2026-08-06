package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.config.info.RefineConfig;
import io.leavesfly.jimi.harness.RefineEngine;
import io.leavesfly.jimi.harness.TrajectoryReader;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * /refine 命令处理器
 * <p>
 * 手动触发一次自我改进：读取最近执行轨迹，对 harness 补充层施加最小增量修改。
 * <p>
 * 变更受四条护栏约束（最小编辑、基础提示词不可变、证据留痕、补充层配额），
 * 结果可用 {@code /harness log} 查看、{@code /harness revert} 回滚。
 */
@Slf4j
@Component
public class RefineCommandHandler implements CommandHandler {

    @Autowired
    private RefineEngine refineEngine;

    @Autowired
    private RefineConfig refineConfig;

    @Autowired
    private TrajectoryReader trajectoryReader;

    @Override
    public String getName() {
        return "refine";
    }

    @Override
    public String getDescription() {
        return "从执行轨迹中提炼改进并写入 harness 补充层";
    }

    @Override
    public String getUsage() {
        return "/refine [关注点]";
    }

    @Override
    public String getCategory() {
        return "knowledge";
    }

    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (!refineConfig.isEnabled()) {
            out.printWarning("自我改进未启用。请在 config.yml 中设置 refine.enabled: true");
            out.printInfo("开启前请确认 /harness log 与 /harness revert 可用 —— "
                    + "自我修改若没有审计与回滚，会退化为不可控漂移。");
            return;
        }

        String workDirPath = context.getEngineClient().getWorkDir().toAbsolutePath().toString();
        String focus = context.getArgsAsString();

        String trajectory = trajectoryReader.readRecent(workDirPath, refineConfig.getTrajectoryWindow());
        if (trajectory.isBlank()) {
            out.printWarning("未找到可分析的执行轨迹");
            return;
        }

        out.printStatus("正在分析执行轨迹并提炼改进...");
        RefineEngine.RefineResult result = refineEngine
                .run(workDirPath, "manual-refine", trajectory, focus)
                .block();

        if (result == null) {
            out.printError("refine 未返回结果");
            return;
        }

        switch (result.status()) {
            case APPLIED -> {
                out.printSuccess(String.format("已应用改进 #%d（%s / %s）",
                        result.change().getId(),
                        result.change().getKind(),
                        result.change().getTargetId()));
                if (result.message() != null && !result.message().isBlank()) {
                    out.println("  理由: " + result.message());
                }
                out.printInfo("使用 /harness revert " + result.change().getId() + " 可回滚此次改动");
            }
            case SKIPPED -> out.printInfo("未产生改动: " + result.message());
            case REJECTED -> out.printWarning("提案被护栏拒绝: " + result.message());
            case FAILED -> out.printError("refine 失败: " + result.message());
        }
    }
}
