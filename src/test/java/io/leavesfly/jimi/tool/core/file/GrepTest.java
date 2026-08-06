package io.leavesfly.jimi.tool.core.file;

import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Grep} 双后端测试
 * <p>
 * Grep 有 ripgrep 和 Java 两条搜索后端，这批用例守住两条线：
 * 一是 Java 回退路径的过滤规则与输出格式，二是 ripgrep 命令行必须构造出同样的输出格式。
 * 机器上装了 rg 时，额外跑一轮两条后端的逐行比对。
 */
class GrepTest {

    @TempDir
    Path repo;

    private Grep grep;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repo.resolve("src/sub"));
        Files.createDirectories(repo.resolve("target/classes"));
        Files.createDirectories(repo.resolve(".hidden"));

        write("src/Alpha.java", "class Alpha {", "  // TODO refactor", "  void todo() {}");
        write("src/sub/Beta.java", "class Beta {", "  // TODO later");
        write("src/Notes.md", "# TODO in markdown");

        // 构建产物与隐藏目录：都不应出现在结果中
        write("target/classes/Generated.java", "// TODO generated");
        write(".hidden/Secret.java", "// TODO hidden");

        // 二进制内容（含 NUL 字节）：应被内容探测跳过
        byte[] binary = new byte[]{'T', 'O', 'D', 'O', 0, 1, 2, 3, 0, 4};
        Files.write(repo.resolve("blob.dat"), binary);

        grep = new Grep();
        grep.setBuiltinArgs(BuiltinSystemPromptArgs.builder().jimiWorkDir(repo).build());
    }

    // ==================== Java 回退路径：过滤规则 ====================

    @Test
    void shouldSkipBuildOutputAndHiddenDirectories() {
        List<String> files = lines(run(params("TODO").build()));

        assertLinesMatch(List.of(
                abs("src/Alpha.java"),
                abs("src/Notes.md"),
                abs("src/sub/Beta.java")
        ), files.stream().sorted().toList());
    }

    @Test
    void shouldSkipBinaryContentFiles() {
        List<String> files = lines(run(params("TODO").build()));

        assertFalse(files.contains(abs("blob.dat")), "含 NUL 字节的文件应被跳过");
    }

    @Test
    void shouldFilterByGlob() {
        List<String> files = lines(run(params("TODO").glob("*.java").build()));

        assertLinesMatch(List.of(
                abs("src/Alpha.java"),
                abs("src/sub/Beta.java")
        ), files.stream().sorted().toList());
    }

    @Test
    void shouldHonorIgnoreCase() {
        assertTrue(lines(run(params("todo").build())).contains(abs("src/Alpha.java")),
                "小写 todo() 方法名本身就能命中");

        List<String> caseInsensitive = lines(run(params("todo").ignoreCase(true).build()));
        assertTrue(caseInsensitive.contains(abs("src/sub/Beta.java")),
                "Beta.java 只有大写 TODO，需要 ignoreCase 才能命中");
    }

    // ==================== Java 回退路径：输出格式 ====================

    @Test
    void contentModeShouldPrefixFileAndLineNumber() {
        List<String> output = lines(run(params("TODO")
                .path("src/Alpha.java")
                .outputMode("content")
                .lineNumber(true)
                .build()));

        assertEquals(List.of(abs("src/Alpha.java") + ":2:  // TODO refactor"), output);
    }

    @Test
    void contentModeWithoutLineNumberShouldPrefixFileOnly() {
        List<String> output = lines(run(params("TODO")
                .path("src/Alpha.java")
                .outputMode("content")
                .build()));

        assertEquals(List.of(abs("src/Alpha.java") + ":  // TODO refactor"), output);
    }

    @Test
    void countMatchesShouldReportMatchingLinesPerFile() {
        List<String> output = lines(run(params("TODO")
                .path("src/Alpha.java")
                .outputMode("count_matches")
                .build()));

        assertEquals(List.of(abs("src/Alpha.java") + ":1"), output);
    }

    @Test
    void headLimitShouldTruncateOutput() {
        ToolResult result = run(params("TODO").headLimit(1).build());

        assertTrue(result.isOk());

        // 3 个文件命中，headLimit=1 后只留 1 条结果 + 1 行截断提示
        List<String> output = lines(result);
        assertEquals(2, output.size());
        assertTrue(output.get(0).endsWith(".java") || output.get(0).endsWith(".md"));
        assertTrue(output.get(1).contains("results truncated to 1 lines"));
    }

    @Test
    void shouldReportNoMatchesWhenNothingFound() {
        ToolResult result = run(params("ThisPatternMatchesNothing").build());

        assertTrue(result.isOk());
        // ToolResultBuilder.ok() 会给非空 message 补句点
        assertEquals("No matches found.", result.getMessage());
    }

    // ==================== 参数校验 ====================

    @Test
    void shouldRejectInvalidOutputMode() {
        ToolResult result = run(params("TODO").outputMode("bogus").build());

        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Invalid output mode"));
    }

    @Test
    void shouldRejectInvalidRegex() {
        ToolResult result = run(params("[unclosed").build());

        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Invalid regex pattern"));
    }

    @Test
    void shouldRejectMissingPath() {
        ToolResult result = run(params("TODO").path("no/such/dir").build());

        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Path does not exist"));
    }

    // ==================== ripgrep 命令行构造 ====================

    @Test
    void ripgrepCommandShouldRequestFileAndLineNumberPrefix() {
        List<String> command = grep.buildRipgrepCommand(repo, params("TODO")
                .outputMode("content")
                .lineNumber(true)
                .build());

        // content 模式的 path:line:text 前缀依赖这三个开关
        assertTrue(command.containsAll(List.of("--no-heading", "--with-filename", "--line-number")));
        // 模式必须走 -e，否则以 '-' 开头的正则会被当成选项
        assertEquals("-e", command.get(command.size() - 3));
        assertEquals("TODO", command.get(command.size() - 2));
        assertEquals(repo.toString(), command.get(command.size() - 1));
    }

    @Test
    void ripgrepCommandShouldDisableLineNumberWhenNotRequested() {
        List<String> command = grep.buildRipgrepCommand(repo, params("TODO")
                .outputMode("content")
                .build());

        // 依赖 rg 默认值不安全：TTY 下 rg 会自动开启行号
        assertTrue(command.contains("--no-line-number"));
    }

    @Test
    void ripgrepCommandShouldMapOutputModesAndFilters() {
        assertTrue(grep.buildRipgrepCommand(repo, params("TODO")
                .outputMode("files_with_matches").build()).contains("--files-with-matches"));

        assertTrue(grep.buildRipgrepCommand(repo, params("TODO")
                .outputMode("count_matches").build()).contains("--count"));

        List<String> withFilters = grep.buildRipgrepCommand(repo, params("TODO")
                .glob("*.java").ignoreCase(true).build());
        assertTrue(withFilters.containsAll(List.of("--glob", "*.java", "--ignore-case")));

        // 文件大小上限必须与 Java 实现一致，否则两条后端结果不同
        int maxFileSize = 10 * 1024 * 1024;
        assertTrue(withFilters.containsAll(List.of("--max-filesize", String.valueOf(maxFileSize))));
    }

    // ==================== 双后端一致性（仅在装有 rg 时执行）====================

    @Test
    void ripgrepAndJavaBackendsShouldProduceIdenticalOutput() {
        assumeTrue(ripgrepInstalled(), "未安装 ripgrep，跳过双后端比对");

        for (String mode : List.of("files_with_matches", "count_matches", "content")) {
            Grep.Params params = params("TODO").outputMode(mode).lineNumber(true).build();

            Grep.SearchResult javaResult = grep.performSearch(repo, Pattern.compile("TODO"), params);
            Grep.SearchResult rgResult = grep.searchWithRipgrep(repo, params)
                    .orElseThrow(() -> new AssertionError("ripgrep 已安装但执行失败, mode=" + mode));

            assertEquals(sorted(javaResult.filesWithMatches), sorted(rgResult.filesWithMatches),
                    "files_with_matches 不一致, mode=" + mode);
            assertEquals(sorted(javaResult.matchCounts), sorted(rgResult.matchCounts),
                    "count_matches 不一致, mode=" + mode);
            assertEquals(sorted(javaResult.contentLines), sorted(rgResult.contentLines),
                    "content 不一致, mode=" + mode);
        }
    }

    // ==================== 辅助方法 ====================

    private static boolean ripgrepInstalled() {
        try {
            Process process = new ProcessBuilder("rg", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Grep.Params.ParamsBuilder params(String pattern) {
        return Grep.Params.builder().pattern(pattern);
    }

    private ToolResult run(Grep.Params params) {
        return grep.execute(params).block();
    }

    private List<String> lines(ToolResult result) {
        String output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        return List.of(output.split("\n"));
    }

    private List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private String abs(String relativePath) {
        return repo.resolve(relativePath).toString();
    }

    private void write(String relativePath, String... lines) throws IOException {
        Files.writeString(repo.resolve(relativePath),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
