package io.leavesfly.jimi.config.info;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 工具输出配置
 * <p>
 * 控制工具大输出的落盘策略。工具输出直接整块进入上下文时，会加速触发有损压缩
 * （压缩仅保留最近 2 条消息），形成「大输出 → 频繁压缩 → 信息快速丢失」的循环。
 * 超过阈值的输出改为落盘 + 回摘要与文件路径，让数据留在外部、指针留在上下文。
 */
@Data
public class ToolOutputConfig {

    /**
     * 工具输出内联到上下文的最大字符数，超出则落盘
     * <p>
     * 使用字符数而非 token 数，避免为此引入 tokenizer 依赖。
     * 设为 {@code 0} 表示禁用落盘、恢复全量内联的旧行为。
     */
    @JsonProperty("max_inline_chars")
    private int maxInlineChars = 32000;

    /**
     * 落盘后仍保留在上下文中的预览字符数
     */
    @JsonProperty("preview_chars")
    private int previewChars = 2000;
}
