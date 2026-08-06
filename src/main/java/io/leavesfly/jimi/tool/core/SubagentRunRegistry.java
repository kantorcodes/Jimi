package io.leavesfly.jimi.tool.core;

import io.leavesfly.jimi.config.info.SubagentConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 异步子 Agent 运行态注册表
 * <p>
 * 异步模式下 {@code SubAgentTool} 立即返回句柄，真正的执行在后台继续。父 Agent 需要一个
 * 稳定的地方查询「跑完了没、结果是什么」，这里就是那个地方。
 * <p>
 * 句柄即子 Agent 的 sessionId —— 与 P0-3 保留下来的轨迹文件同名，因此即使进程重启、
 * 内存记录丢失，轨迹本身仍可通过 {@code Memory(action=search)} 寻址。
 * <p>
 * <b>明确不做持久化</b>：运行态只在内存，进程重启即丢。持久化运行态会引入
 * 「重启后如何恢复一个已死的后台任务」这类无解问题，收益不足。
 */
@Slf4j
@Component
public class SubagentRunRegistry {

    private final Map<String, SubagentRun> runs = new ConcurrentHashMap<>();

    private final SubagentConfig subagentConfig;

    /**
     * 异步并发闸门，容量即 {@code subagent.max_concurrent}
     */
    private final Semaphore concurrencyGate;

    @Autowired
    public SubagentRunRegistry(SubagentConfig subagentConfig) {
        this.subagentConfig = subagentConfig;
        int permits = Math.max(1, subagentConfig.getMaxConcurrent());
        this.concurrencyGate = new Semaphore(permits);
    }

    /**
     * 尝试占用一个并发额度
     *
     * @return 是否获取成功；失败时调用方应降级为同步执行
     */
    public boolean tryAcquire() {
        return concurrencyGate.tryAcquire();
    }

    /**
     * 释放并发额度
     */
    public void release() {
        concurrencyGate.release();
    }

    /**
     * 当前可用的并发额度
     */
    public int availablePermits() {
        return concurrencyGate.availablePermits();
    }

    /**
     * 登记一个新启动的异步运行
     *
     * @param handle       句柄（子 Agent sessionId）
     * @param subagentName 子 Agent 名称
     * @param description  任务描述
     * @return 运行记录
     */
    public SubagentRun start(String handle, String subagentName, String description) {
        SubagentRun run = new SubagentRun(handle, subagentName, description);
        runs.put(handle, run);
        evictOldFinished();
        return run;
    }

    /**
     * 标记运行成功完成
     */
    public void complete(String handle, String output) {
        SubagentRun run = runs.get(handle);
        if (run != null) {
            run.markCompleted(output);
        }
    }

    /**
     * 标记运行失败
     */
    public void fail(String handle, String error) {
        SubagentRun run = runs.get(handle);
        if (run != null) {
            run.markFailed(error);
        }
    }

    public Optional<SubagentRun> find(String handle) {
        return Optional.ofNullable(runs.get(handle));
    }

    /**
     * 列出所有运行记录，最近启动的排在前面
     */
    public List<SubagentRun> list() {
        List<SubagentRun> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing(SubagentRun::getStartedAt).reversed());
        return all;
    }

    /**
     * 清理超量的已结束记录，避免长会话下内存无界增长
     */
    private void evictOldFinished() {
        int limit = Math.max(8, subagentConfig.getMaxRetainedRuns());
        if (runs.size() <= limit) {
            return;
        }
        runs.values().stream()
                .filter(SubagentRun::isFinished)
                .sorted(Comparator.comparing(SubagentRun::getStartedAt))
                .limit(runs.size() - limit)
                .forEach(run -> runs.remove(run.getHandle()));
    }

    /**
     * 运行状态
     */
    public enum State {
        /** 后台执行中 */
        RUNNING,
        /** 已成功结束 */
        COMPLETED,
        /** 执行失败 */
        FAILED
    }

    /**
     * 单次异步子 Agent 运行的状态快照
     */
    @Getter
    public static class SubagentRun {

        private final String handle;
        private final String subagentName;
        private final String description;
        private final Instant startedAt;

        private volatile State state = State.RUNNING;
        private volatile String output;
        private volatile String error;
        private volatile Instant finishedAt;

        SubagentRun(String handle, String subagentName, String description) {
            this.handle = handle;
            this.subagentName = subagentName;
            this.description = description;
            this.startedAt = Instant.now();
        }

        void markCompleted(String output) {
            this.output = output;
            this.state = State.COMPLETED;
            this.finishedAt = Instant.now();
        }

        void markFailed(String error) {
            this.error = error;
            this.state = State.FAILED;
            this.finishedAt = Instant.now();
        }

        public boolean isFinished() {
            return state != State.RUNNING;
        }

        /**
         * 已运行 / 已耗时的秒数
         */
        public long elapsedSeconds() {
            Instant end = finishedAt != null ? finishedAt : Instant.now();
            return Duration.between(startedAt, end).toSeconds();
        }
    }
}
