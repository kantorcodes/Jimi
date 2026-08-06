package io.leavesfly.jimi.core.engine.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.memory.MemorySearcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查点回退的归档溯源测试
 * <p>
 * 上下文压缩会先 {@code revertTo(0)}，把压缩前的完整历史轮转为 {@code history.jsonl.N}。
 * 这批用例验证归档路径确实被回传到 {@link Context}（压缩摘要的溯源锚点依赖它），
 * 且归档内容能被 {@link MemorySearcher} 检索到 —— 压缩有损，但原始历史仍可寻址。
 */
class ContextArchiveTraceTest {

    @TempDir
    Path sessionsDir;

    private ObjectMapper objectMapper;
    private Path historyFile;
    private Context context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        historyFile = sessionsDir.resolve("abcdef123456.jsonl");
        context = new Context(historyFile, objectMapper);
    }

    @Test
    void lastArchivedPathShouldBeNullBeforeAnyRevert() {
        assertNull(context.getLastArchivedPath(), "未发生回退时不应有归档路径");
    }

    @Test
    void revertShouldExposeRotatedArchivePath() {
        context.checkpoint(false).block();
        context.appendMessage(Message.user("这条内容会在压缩时被归档：archived-detail")).block();
        context.checkpoint(false).block();
        context.appendMessage(Message.assistant("后续回复")).block();

        context.revertTo(1).block();

        Path archived = context.getLastArchivedPath();
        assertNotNull(archived, "回退应回传归档文件路径，压缩摘要的溯源锚点依赖它");
        assertEquals("abcdef123456.jsonl.1", archived.getFileName().toString());
        assertTrue(Files.isRegularFile(archived), "归档文件应真实存在");
    }

    @Test
    void archivedHistoryShouldStillBeSearchable() {
        context.checkpoint(false).block();
        context.appendMessage(Message.user("压缩前提到过 needle-in-archive")).block();

        // 与 ContextManager.checkAndCompact 一致：压缩回退到检查点 0
        context.revertTo(0).block();

        // 回退后活动文件已不含该内容，但归档文件仍保留
        assertTrue(context.getHistory().isEmpty(), "回退至检查点 0 后活动历史应为空");
        List<MemorySearcher.SearchResult> results =
                new MemorySearcher().search(sessionsDir, "needle-in-archive", 10);

        assertEquals(1, results.size(), "归档内容必须仍可检索 —— 存储可达即模型可达");
        assertTrue(results.get(0).archived());
        assertEquals("abcdef123456#archive1", results.get(0).sessionId());
    }

    @Test
    void repeatedRevertShouldRotateWithIncrementingSuffix() {
        // 回退会把 nextCheckpointId 重置为目标检查点，因此每轮都从 0 重新开始
        context.checkpoint(false).block();
        context.appendMessage(Message.user("第一轮")).block();
        context.revertTo(0).block();
        assertEquals("abcdef123456.jsonl.1", context.getLastArchivedPath().getFileName().toString());

        context.checkpoint(false).block();
        context.appendMessage(Message.user("第二轮")).block();
        context.revertTo(0).block();

        assertEquals("abcdef123456.jsonl.2", context.getLastArchivedPath().getFileName().toString(),
                "轮转序号应递增，旧归档不被覆盖");
        assertTrue(Files.isRegularFile(sessionsDir.resolve("abcdef123456.jsonl.1")));
        assertTrue(Files.isRegularFile(sessionsDir.resolve("abcdef123456.jsonl.2")));
    }

    @Test
    void revertShouldTruncateInMemoryHistoryAtCheckpoint() {
        context.checkpoint(false).block();
        context.appendMessage(Message.user("保留的内容")).block();
        context.checkpoint(false).block();
        context.appendMessage(Message.assistant("将被丢弃的内容")).block();
        assertEquals(2, context.getHistory().size());

        context.revertTo(1).block();

        assertEquals(1, context.getHistory().size(), "检查点之后的消息应从内存历史移除");
        assertTrue(context.getHistory().get(0).getTextContent().contains("保留的内容"));
    }
}
