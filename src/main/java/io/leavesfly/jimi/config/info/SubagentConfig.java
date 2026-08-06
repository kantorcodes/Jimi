package io.leavesfly.jimi.config.info;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 子 Agent 编排配置
 */
@Data
public class SubagentConfig {

    /**
     * 异步子 Agent 的最大并发数
     * <p>
     * 与 {@code ToolDispatcher.MAX_PARALLEL_CONCURRENCY} 解耦：后者约束的是单批工具调用的
     * 并发度，而异步子 Agent 在工具返回后仍在后台运行，需要独立的闸门防止无限堆积。
     */
    @JsonProperty("max_concurrent")
    private int maxConcurrent = 4;

    /**
     * 已完成的异步运行记录保留数量上限
     * <p>
     * 运行态仅存内存，进程重启即丢；此上限防止长会话中记录无界增长。
     */
    @JsonProperty("max_retained_runs")
    private int maxRetainedRuns = 64;
}
