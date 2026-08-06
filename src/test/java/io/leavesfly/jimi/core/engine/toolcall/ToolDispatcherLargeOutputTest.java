package io.leavesfly.jimi.core.engine.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.ToolOutputConfig;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolDispatcher} 大输出落盘测试
 * <p>
 * 大输出整块进上下文会形成「大输出 → 频繁压缩 → 每次只留 2 条 → 信息快速丢失」的恶性循环。
 * 这批用例守住的是：超阈值输出落盘并留下可寻址的引用，而错误信息绝不被截断。
 */
class ToolDispatcherLargeOutputTest {

    private static final String SESSION_ID = "test-session";
    private static final String TOOL_CALL_ID = "call-001";

    @TempDir
    Path workDir;

    private ObjectMapper objectMapper;
    private Context context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        context = new Context(workDir.resolve("history.jsonl"), objectMapper);
    }

    @Test
    void largeOkOutputShouldBeOffloadedToDisk() {
        String hugeOutput = buildOutput(40_000);
        ToolDispatcher dispatcher = dispatcher(new ToolOutputConfig(), okTool(hugeOutput, "已扫描完成"));

        String content = executeAndGetContent(dispatcher);

        assertTrue(content.contains("[输出过大已截断。"), "应带截断提示");
        assertTrue(content.length() < hugeOutput.length(), "上下文中的内容应显著短于原始输出");

        Path outputFile = workDir.resolve(".jimi/tool-output").resolve(SESSION_ID)
                .resolve(TOOL_CALL_ID + ".txt");
        assertTrue(Files.isRegularFile(outputFile), "完整输出应落盘到 " + outputFile);
        assertTrue(content.contains(workDir.relativize(outputFile).toString()),
                "提示中应包含可供 ReadFile 使用的相对路径");
    }

    @Test
    void offloadNoteShouldReportAccurateCharAndLineCount() throws Exception {
        // 每行 99 字符 + 换行，共 500 行
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("x".repeat(99)).append('\n');
        }
        String hugeOutput = sb.toString();
        ToolDispatcher dispatcher = dispatcher(new ToolOutputConfig(), okTool(hugeOutput, ""));

        String content = executeAndGetContent(dispatcher);

        Path outputFile = workDir.resolve(".jimi/tool-output").resolve(SESSION_ID)
                .resolve(TOOL_CALL_ID + ".txt");
        String persisted = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(content.contains(persisted.length() + " 字符"),
                "字符数应与落盘内容一致，实际提示: " + tail(content));
        assertTrue(content.contains("500 行"), "行数应准确，实际提示: " + tail(content));
    }

    @Test
    void previewShouldRetainConfiguredLeadingChars() {
        String hugeOutput = "HEAD-MARKER" + buildOutput(40_000);
        ToolOutputConfig config = new ToolOutputConfig();
        config.setPreviewChars(100);
        ToolDispatcher dispatcher = dispatcher(config, okTool(hugeOutput, "摘要行"));

        String content = executeAndGetContent(dispatcher);

        assertTrue(content.startsWith("摘要行"), "摘要应排在最前");
        assertTrue(content.contains("HEAD-MARKER"), "预览应保留输出开头");
    }

    @Test
    void errorResultShouldNeverBeTruncated() {
        String hugeError = buildOutput(40_000);
        ToolDispatcher dispatcher = dispatcher(new ToolOutputConfig(),
                errorTool(hugeError, "扫描失败"));

        String content = executeAndGetContent(dispatcher);

        assertFalse(content.contains("[输出过大已截断。"),
                "错误信息短且关键，截断反而丢掉排查依据");
        Path sessionOutputDir = workDir.resolve(".jimi/tool-output").resolve(SESSION_ID);
        assertFalse(Files.exists(sessionOutputDir), "错误结果不应落盘");
    }

    @Test
    void zeroThresholdShouldDisableOffloading() {
        String hugeOutput = buildOutput(40_000);
        ToolOutputConfig config = new ToolOutputConfig();
        config.setMaxInlineChars(0);
        ToolDispatcher dispatcher = dispatcher(config, okTool(hugeOutput, ""));

        String content = executeAndGetContent(dispatcher);

        assertFalse(content.contains("[输出过大已截断。"), "阈值为 0 时应禁用落盘");
        assertTrue(content.contains(hugeOutput), "输出应完整内联");
        assertFalse(Files.exists(workDir.resolve(".jimi/tool-output")), "禁用时不应产生文件");
    }

    @Test
    void outputBelowThresholdShouldStayInline() {
        String smallOutput = buildOutput(100);
        ToolDispatcher dispatcher = dispatcher(new ToolOutputConfig(), okTool(smallOutput, "完成"));

        String content = executeAndGetContent(dispatcher);

        assertTrue(content.contains(smallOutput));
        assertFalse(content.contains("[输出过大已截断。"));
        assertFalse(Files.exists(workDir.resolve(".jimi/tool-output")));
    }

    // ==================== 测试脚手架 ====================

    private ToolDispatcher dispatcher(ToolOutputConfig config, AbstractTool<StubParams> tool) {
        ToolRegistry registry = new ToolRegistry(objectMapper);
        registry.register(tool);
        return new ToolDispatcher(registry, workDir, new WireImpl(), new ToolErrorTracker(),
                null, config, SESSION_ID);
    }

    private String executeAndGetContent(ToolDispatcher dispatcher) {
        ToolCall toolCall = ToolCall.builder()
                .id(TOOL_CALL_ID)
                .type("function")
                .function(FunctionCall.builder().name("StubTool").arguments("{}").build())
                .build();

        Message message = dispatcher.executeToolCall(toolCall, context).block();
        assertNotNull(message, "工具调用应产生一条消息");
        String content = message.getTextContent();
        assertNotNull(content);
        return content;
    }

    private String buildOutput(int chars) {
        return "A".repeat(chars);
    }

    /**
     * 截取提示尾部用于断言失败时定位
     */
    private String tail(String content) {
        return content.substring(Math.max(0, content.length() - 200));
    }

    private AbstractTool<StubParams> okTool(String output, String message) {
        return new StubTool(ToolResult.ok(output, message));
    }

    private AbstractTool<StubParams> errorTool(String output, String message) {
        return new StubTool(ToolResult.error(output, message, "扫描失败"));
    }

    @Data
    public static class StubParams {
    }

    /**
     * 返回预设结果的桩工具
     */
    private static class StubTool extends AbstractTool<StubParams> {

        private final ToolResult result;

        StubTool(ToolResult result) {
            super("StubTool", "测试用桩工具", StubParams.class);
            this.result = result;
        }

        @Override
        public Mono<ToolResult> execute(StubParams params) {
            return Mono.just(result);
        }
    }
}
