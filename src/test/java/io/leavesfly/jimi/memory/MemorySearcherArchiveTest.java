package io.leavesfly.jimi.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemorySearcher} 归档文件可检索性测试
 * <p>
 * 上下文压缩会把原始历史轮转为 {@code xxx.jsonl.N}，数据一直在磁盘上。
 * 这批用例守住的是「存储可达即模型可达」这条线。
 */
class MemorySearcherArchiveTest {

    @TempDir
    Path sessionsDir;

    private MemorySearcher searcher;

    @BeforeEach
    void setUp() {
        searcher = new MemorySearcher();
    }

    @Test
    void searchShouldCoverRotatedArchiveFiles() throws IOException {
        writeSession("abcdef123456.jsonl", "user", "活动会话里提到了 widget 配置");
        writeSession("abcdef123456.jsonl.1", "assistant", "归档会话里也讨论过 widget 配置");

        List<MemorySearcher.SearchResult> results = searcher.search(sessionsDir, "widget", 10);

        assertEquals(2, results.size(), "活动文件与归档文件都应命中");
        assertTrue(results.stream().anyMatch(MemorySearcher.SearchResult::archived),
                "归档命中应标记 archived=true");
        assertTrue(results.stream().anyMatch(r -> !r.archived()),
                "活动文件命中应标记 archived=false");
    }

    @Test
    void archivedResultShouldCarryRotationSuffix() throws IOException {
        writeSession("abcdef123456.jsonl.3", "user", "第三次压缩前的历史里有 marker-token");

        List<MemorySearcher.SearchResult> results = searcher.search(sessionsDir, "marker-token", 10);

        assertEquals(1, results.size());
        MemorySearcher.SearchResult result = results.get(0);
        assertEquals("abcdef123456#archive3", result.sessionId(),
                "归档来源应通过 #archiveN 后缀区分");
        assertTrue(result.archived());
        assertTrue(result.format().contains("[已归档]"), "格式化输出应带归档标记");
        assertTrue(result.format().contains("#archive3"), "缩短会话标识时应保留归档序号");
    }

    @Test
    void activeSessionResultShouldNotBeMarkedArchived() throws IOException {
        writeSession("abcdef123456.jsonl", "user", "只有活动会话提到 unique-phrase");

        List<MemorySearcher.SearchResult> results = searcher.search(sessionsDir, "unique-phrase", 10);

        assertEquals(1, results.size());
        assertEquals("abcdef123456", results.get(0).sessionId());
        assertFalse(results.get(0).archived());
        assertFalse(results.get(0).format().contains("[已归档]"));
    }

    @Test
    void nonSessionFilesShouldBeIgnored() throws IOException {
        writeSession("abcdef123456.jsonl", "user", "会话中提到 shared-keyword");
        // 相近但不合法的文件名：既非 .jsonl 也非 .jsonl.<digits>
        Files.writeString(sessionsDir.resolve("notes.jsonl.bak"),
                jsonLine("user", "备份文件里也有 shared-keyword"), StandardCharsets.UTF_8);
        Files.writeString(sessionsDir.resolve("abcdef123456_todos.json"),
                jsonLine("user", "Todo 文件里也有 shared-keyword"), StandardCharsets.UTF_8);

        List<MemorySearcher.SearchResult> results = searcher.search(sessionsDir, "shared-keyword", 10);

        assertEquals(1, results.size(), "只有会话文件与其轮转归档参与检索");
    }

    @Test
    void regexSearchShouldCoverArchiveFilesToo() throws IOException {
        writeSession("abcdef123456.jsonl.2", "assistant", "错误码 E-4041 出现在归档历史中");

        List<MemorySearcher.SearchResult> results =
                searcher.searchRegex(sessionsDir, "E-\\d{4}", 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).archived());
        assertEquals("abcdef123456#archive2", results.get(0).sessionId());
    }

    private void writeSession(String fileName, String role, String text) throws IOException {
        Files.writeString(sessionsDir.resolve(fileName), jsonLine(role, text), StandardCharsets.UTF_8);
    }

    private String jsonLine(String role, String text) {
        return String.format("{\"role\":\"%s\",\"content\":[{\"type\":\"text\",\"text\":\"%s\"}]}%n",
                role, text);
    }
}
