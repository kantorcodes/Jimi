package io.leavesfly.jimi.tool.core;

import io.leavesfly.jimi.config.info.SubagentConfig;
import io.leavesfly.jimi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步子 Agent 运行态与结果查询测试
 * <p>
 * 覆盖 {@link SubagentRunRegistry} 的并发闸门与状态机，
 * 以及 {@link SubagentResultTool} 的按句柄查询语义。
 */
class SubagentRunRegistryTest {

    private SubagentConfig config;
    private SubagentRunRegistry registry;
    private SubagentResultTool tool;

    @BeforeEach
    void setUp() {
        config = new SubagentConfig();
        config.setMaxConcurrent(2);
        registry = new SubagentRunRegistry(config);
        tool = new SubagentResultTool(registry);
    }

    // ==================== 并发闸门 ====================

    @Test
    void concurrencyGateShouldCapAtConfiguredMax() {
        assertTrue(registry.tryAcquire());
        assertTrue(registry.tryAcquire());
        assertFalse(registry.tryAcquire(), "超出 max_concurrent 后应获取失败（调用方降级为同步）");

        registry.release();
        assertTrue(registry.tryAcquire(), "释放后额度应可再次获取");
    }

    @Test
    void gateShouldHaveAtLeastOnePermitEvenForInvalidConfig() {
        config.setMaxConcurrent(0);
        SubagentRunRegistry lenient = new SubagentRunRegistry(config);

        assertEquals(1, lenient.availablePermits(), "非法配置应兜底为 1，而不是彻底禁用异步");
    }

    // ==================== 状态机 ====================

    @Test
    void runShouldStartAsRunningAndTransitionToCompleted() {
        registry.start("handle-1", "Code-Agent", "修一个编译错误");

        SubagentRunRegistry.SubagentRun run = registry.find("handle-1").orElseThrow();
        assertEquals(SubagentRunRegistry.State.RUNNING, run.getState());
        assertFalse(run.isFinished());

        registry.complete("handle-1", "已修复");

        assertEquals(SubagentRunRegistry.State.COMPLETED, run.getState());
        assertTrue(run.isFinished());
        assertEquals("已修复", run.getOutput());
    }

    @Test
    void failedRunShouldRetainErrorMessage() {
        registry.start("handle-1", "Code-Agent", "任务");
        registry.fail("handle-1", "工具链崩了");

        SubagentRunRegistry.SubagentRun run = registry.find("handle-1").orElseThrow();
        assertEquals(SubagentRunRegistry.State.FAILED, run.getState());
        assertEquals("工具链崩了", run.getError());
    }

    @Test
    void completeOnUnknownHandleShouldBeNoOp() {
        registry.complete("nope", "结果");
        registry.fail("nope", "错误");

        assertTrue(registry.find("nope").isEmpty());
    }

    @Test
    void listShouldReturnMostRecentFirst() throws InterruptedException {
        registry.start("handle-1", "A", "任务 1");
        Thread.sleep(5);
        registry.start("handle-2", "B", "任务 2");

        List<SubagentRunRegistry.SubagentRun> runs = registry.list();

        assertEquals(2, runs.size());
        assertEquals("handle-2", runs.get(0).getHandle(), "最近启动的排在前面");
    }

    @Test
    void finishedRunsShouldBeEvictedBeyondRetentionLimit() {
        config.setMaxRetainedRuns(8);
        SubagentRunRegistry bounded = new SubagentRunRegistry(config);

        for (int i = 0; i < 12; i++) {
            bounded.start("handle-" + i, "A", "任务");
            bounded.complete("handle-" + i, "结果");
        }

        assertTrue(bounded.list().size() <= 8,
                "已结束记录应被清理，避免长会话下内存无界增长，实际 " + bounded.list().size());
    }

    @Test
    void runningRunsShouldNotBeEvicted() {
        config.setMaxRetainedRuns(8);
        SubagentRunRegistry bounded = new SubagentRunRegistry(config);

        for (int i = 0; i < 12; i++) {
            bounded.start("handle-" + i, "A", "任务");
        }

        assertEquals(12, bounded.list().size(), "运行中的记录不得被清理，否则结果无处可查");
    }

    // ==================== SubagentResult 工具 ====================

    @Test
    void statusActionShouldHintToKeepWorkingWhileRunning() {
        registry.start("handle-1", "Code-Agent", "修一个编译错误");

        ToolResult result = execute("status", "handle-1");

        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("state: running"));
        assertTrue(result.getOutput().contains("仍在运行中"), "运行中应提示先推进其他工作");
    }

    @Test
    void resultActionShouldReturnOutputOnceCompleted() {
        registry.start("handle-1", "Code-Agent", "任务");
        registry.complete("handle-1", "完整的子 Agent 输出");

        ToolResult result = execute("result", "handle-1");

        assertTrue(result.isOk());
        assertEquals("完整的子 Agent 输出", result.getOutput());
    }

    @Test
    void resultActionShouldSurfaceFailure() {
        registry.start("handle-1", "Code-Agent", "任务");
        registry.fail("handle-1", "子 Agent 抛异常");

        ToolResult result = execute("result", "handle-1");

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("子 Agent 抛异常"));
    }

    @Test
    void resultActionShouldNotBlockWhileStillRunning() {
        registry.start("handle-1", "Code-Agent", "任务");

        ToolResult result = execute("result", "handle-1");

        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("仍在运行"));
        assertTrue(result.getOutput().contains("不要在此处等待"));
    }

    @Test
    void handleWithJsonlSuffixShouldStillResolve() {
        registry.start("subagent-abc-Code-Agent-1234abcd", "Code-Agent", "任务");
        registry.complete("subagent-abc-Code-Agent-1234abcd", "输出");

        ToolResult result = execute("result", "subagent-abc-Code-Agent-1234abcd.jsonl");

        assertTrue(result.isOk());
        assertEquals("输出", result.getOutput());
    }

    @Test
    void unknownHandleShouldPointToTrajectorySearch() {
        ToolResult result = execute("status", "handle-missing");

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Memory(action=search)"),
                "运行态可能已丢，但轨迹仍可寻址，应指向检索路径");
    }

    @Test
    void listActionShouldReportAllRuns() {
        registry.start("handle-1", "A", "任务 1");
        registry.start("handle-2", "B", "任务 2");
        registry.complete("handle-2", "结果");

        ToolResult result = execute("list", null);

        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("handle-1"));
        assertTrue(result.getOutput().contains("handle-2"));
        assertTrue(result.getOutput().contains("running"));
        assertTrue(result.getOutput().contains("completed"));
    }

    @Test
    void listActionShouldHandleEmptyRegistry() {
        ToolResult result = execute("list", null);

        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("没有异步子 Agent 运行记录"));
    }

    @Test
    void missingActionShouldBeRejected() {
        ToolResult result = tool.execute(SubagentResultTool.Params.builder().build()).block();

        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void unknownActionShouldBeRejected() {
        ToolResult result = execute("wait", "handle-1");

        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Supported: status, result, list"),
                "错误消息应列出可用 action，引导模型自行纠正");
    }

    @Test
    void statusWithoutHandleShouldBeRejected() {
        ToolResult result = execute("status", null);

        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("handle"));
    }

    private ToolResult execute(String action, String handle) {
        ToolResult result = tool.execute(SubagentResultTool.Params.builder()
                .action(action)
                .handle(handle)
                .build()).block();
        assertNotNull(result, "工具应始终返回结果");
        return result;
    }
}
