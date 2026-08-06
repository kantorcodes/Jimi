package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * harness 变更审计记录
 * <p>
 * 自我改进机制必须可追溯、可回滚，否则就是不可控的漂移。每条记录同时回答三个问题：
 * <ul>
 *   <li>为什么产生这次变更（{@code trigger}）</li>
 *   <li>改了什么、改前改后是什么（{@code kind}/{@code op}/{@code before}/{@code after}）</li>
 *   <li>产生了什么效果（{@code outcome}，可延后回填）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarnessChange {

    /** 记录 ID，单调递增，用作回滚的定位依据 */
    private long id;

    /** 记录时间（ISO 8601） */
    private String ts;

    /** 触发原因，例如 {@code manual}、{@code goal-verify-failed} */
    private String trigger;

    /** 变更的 harness 组件类型 */
    private Kind kind;

    /** 变更操作 */
    private Op op;

    /** 变更目标标识，如 memory 的 section 名、skill 名、prompt note ID */
    private String targetId;

    /** 变更前内容，{@code create} 时为 null */
    private String before;

    /** 变更后内容，{@code delete} 时为 null */
    private String after;

    /** 变更效果，产生时未知，由后续验证结果回填 */
    private String outcome;

    /**
     * harness 组件类型，对应 Continual Harness 的 H=(ρ,G,K,M)
     */
    public enum Kind {
        /** ρ：补充提示词 */
        PROMPT,
        /** M：长期记忆 */
        MEMORY,
        /** K：技能 */
        SKILL,
        /** G：子 Agent 规范 */
        SUBAGENT
    }

    /**
     * 变更操作类型
     */
    public enum Op {
        CREATE,
        UPDATE,
        DELETE,
        /** 回滚操作，由 {@link HarnessJournal#revert(long)} 产生 */
        REVERT
    }
}
