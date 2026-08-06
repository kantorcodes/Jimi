package io.leavesfly.jimi.config.info;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 自我改进（refine）配置
 * <p>
 * refine 会读取执行轨迹并对 harness 补充层做最小增量修改。这是一个有实证风险的能力：
 * Prime Intellect 在 Factorio 环境中实测到「同一 refine 循环从构建合法技能转向构建
 * 高效作弊技能」，即使有显式禁止提示。因此默认关闭，需显式开启。
 */
@Data
public class RefineConfig {

    /**
     * 是否启用自我改进
     * <p>
     * 默认关闭：开启前应确认审计日志（{@code /harness log}）与回滚
     * （{@code /harness revert}）可用，否则自我修改无法收敛。
     */
    @JsonProperty("enabled")
    private boolean enabled = false;

    /**
     * 目标验证连续失败达到该次数时触发 refine
     */
    @JsonProperty("trigger_on_verify_failures")
    private int triggerOnVerifyFailures = 2;

    /**
     * 是否在任务成功后触发 refine（沉淀可复用战术）
     */
    @JsonProperty("trigger_on_success")
    private boolean triggerOnSuccess = false;

    /**
     * refine 分析时读取的最近历史消息条数
     */
    @JsonProperty("trajectory_window")
    private int trajectoryWindow = 40;

    /**
     * refine 使用的模型名称，为空则回退到默认模型
     */
    @JsonProperty("model")
    private String model = "";
}
