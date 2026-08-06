package io.leavesfly.jimi.tool.core.file;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.SyncTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolResultBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Grep 工具 - 使用正则表达式搜索文件内容
 * <p>
 * 提供两条搜索后端，输出格式完全一致：
 * <ul>
 *   <li>ripgrep（若 PATH 中存在 {@code rg}）：外部进程搜索，大仓库上快 1~2 个数量级</li>
 *   <li>Java 回退：并行遍历 + 内置正则，无外部依赖</li>
 * </ul>
 * ripgrep 不可用、或其正则引擎拒绝该模式（如 Java 特有的逆序环视、反向引用）时，
 * 自动回退到 Java 实现，调用方无感知。
 * <p>
 * 继承 SyncTool 基类，只需实现 executeSync() 方法，无需关心 Reactor 的 Mono 包装。
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Grep extends SyncTool<Grep.Params> {
    
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int BINARY_CHECK_SIZE = 8192; // 检查前8KB判断是否为二进制
    private static final int SEARCH_TIMEOUT_SECONDS = 60;
    private static final List<String> BINARY_EXTENSIONS = Arrays.asList(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".ico", ".svg",
        ".pdf", ".zip", ".tar", ".gz", ".7z", ".rar",
        ".exe", ".dll", ".so", ".dylib",
        ".class", ".jar", ".war",
        ".mp3", ".mp4", ".avi", ".mov",
        ".DS_Store"
    );
    
    /**
     * 构建产物目录：占仓库文件数的大头且几乎从不是搜索目标。
     * ripgrep 通过 .gitignore 天然跳过它们，此处让 Java 回退路径保持一致。
     */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "target", "build", "node_modules", "dist", "out", "vendor", "__pycache__"
    );
    
    /**
     * ripgrep 可用性探测结果，进程级缓存（Grep 是 prototype 作用域，不能按实例探测）
     */
    private static final AtomicReference<Boolean> RIPGREP_AVAILABLE = new AtomicReference<>();
    
    private Path workDir;
    
    /**
     * 参数模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 正则表达式模式
         */
        @JsonPropertyDescription("用于搜索的正则表达式模式（支持 Java 正则语法）")
        private String pattern;
        
        /**
         * 搜索路径（文件或目录）
         */
        @JsonPropertyDescription("要搜索的文件或目录路径。可以是相对路径或绝对路径。默认为 '.' （当前目录）")
        @Builder.Default
        private String path = ".";
        
        /**
         * Glob 模式过滤文件
         */
        @JsonPropertyDescription("用于过滤文件名的 Glob 模式（例如：*.java）。默认为 null（不过滤）")
        @Builder.Default
        private String glob = null;
        
        /**
         * 输出模式：content, files_with_matches, count_matches
         */
        @JsonPropertyDescription("输出模式：'content'（显示匹配的行）、'files_with_matches'（显示包含匹配的文件）、'count_matches'（显示每个文件的匹配数）。默认为 'files_with_matches'")
        @Builder.Default
        private String outputMode = "files_with_matches";
        
        /**
         * 显示行号（仅 content 模式）
         */
        @JsonPropertyDescription("在 content 模式下是否显示行号。默认为 false")
        @Builder.Default
        private boolean lineNumber = false;
        
        /**
         * 忽略大小写
         */
        @JsonPropertyDescription("是否在匹配时忽略大小写。默认为 false")
        @Builder.Default
        private boolean ignoreCase = false;
        
        /**
         * 限制输出行数
         */
        @JsonPropertyDescription("限制输出的最大行数。默认为 null（不限制）")
        @Builder.Default
        private Integer headLimit = null;
    }
    
    public Grep() {
        super(
            "Grep",
            "使用正则表达式搜索文件内容。自动跳过隐藏文件、二进制文件和构建产物目录（target/build/node_modules 等）。",
            Params.class
        );
    }
    
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    @Override
    protected ToolResult executeSync(Params params) {
        try {
            // 验证参数
            if (params.pattern == null || params.pattern.trim().isEmpty()) {
                return ToolResult.error(
                    "Pattern is required. Please provide a valid regex pattern.",
                    "Missing pattern"
                );
            }
            
            // 先校验输出模式，避免白跑一次搜索
            if (!isValidOutputMode(params.outputMode)) {
                return ToolResult.error("Invalid output mode: " + params.outputMode, "Invalid mode");
            }
            
            // 编译正则表达式
            int flags = params.ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
            Pattern pattern;
            try {
                pattern = Pattern.compile(params.pattern, flags);
            } catch (PatternSyntaxException e) {
                return ToolResult.error(
                    String.format("Invalid regex pattern: %s", e.getMessage()),
                    "Invalid pattern"
                );
            }
            
            // 确定搜索路径
            Path requestedPath = ".".equals(params.path) ? workDir : Path.of(params.path);
            final Path searchPath = requestedPath.isAbsolute()
                    ? requestedPath
                    : workDir.resolve(requestedPath);
            
            if (!Files.exists(searchPath)) {
                return ToolResult.error(
                    String.format("Path does not exist: %s", params.path),
                    "Path not found"
                );
            }
            
            // 优先走 ripgrep，不可用或执行失败时回退到 Java 实现
            SearchResult result = searchWithRipgrep(searchPath, params)
                    .orElseGet(() -> performSearch(searchPath, pattern, params));
            
            // 生成输出
            return formatResult(result, params);
            
        } catch (Exception e) {
            log.error("Failed to grep: {}", params.pattern, e);
            return ToolResult.error(
                String.format("Failed to grep. Error: %s", e.getMessage()),
                "Failed to grep"
            );
        }
    }
    
    private boolean isValidOutputMode(String outputMode) {
        return "content".equals(outputMode)
                || "files_with_matches".equals(outputMode)
                || "count_matches".equals(outputMode);
    }
    
    // ==================== ripgrep 快路径 ====================
    
    /**
     * 探测 PATH 中是否存在可用的 ripgrep，结果进程级缓存。
     * <p>并发首次调用最多重复探测几次，无副作用，因此不加锁。
     */
    private static boolean isRipgrepAvailable() {
        Boolean cached = RIPGREP_AVAILABLE.get();
        if (cached != null) {
            return cached;
        }
        
        boolean available = false;
        try {
            Process process = new ProcessBuilder("rg", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            available = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            log.debug("ripgrep not found on PATH, falling back to Java search");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        RIPGREP_AVAILABLE.compareAndSet(null, available);
        log.info("Grep backend: {}", RIPGREP_AVAILABLE.get() ? "ripgrep" : "java");
        return RIPGREP_AVAILABLE.get();
    }
    
    /**
     * 用 ripgrep 执行搜索
     *
     * @return 搜索结果；ripgrep 不可用或执行失败时返回 empty，表示应回退到 Java 实现
     */
    Optional<SearchResult> searchWithRipgrep(Path searchPath, Params params) {
        if (!isRipgrepAvailable()) {
            return Optional.empty();
        }
        
        List<String> command = buildRipgrepCommand(searchPath, params);
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            
            SearchResult result = new SearchResult();
            List<String> lines = collectOutputLines(result, params);
            boolean truncated = false;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // headLimit 已满时提前终止进程，避免大仓库跑完全量搜索
                    if (params.headLimit != null && lines.size() >= params.headLimit) {
                        truncated = true;
                        break;
                    }
                    lines.add(line);
                }
            }
            
            if (truncated) {
                process.destroyForcibly();
                return Optional.of(result);
            }
            
            if (!process.waitFor(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ripgrep timed out after {}s, falling back to Java search", SEARCH_TIMEOUT_SECONDS);
                return Optional.empty();
            }
            
            // 0 = 有匹配, 1 = 无匹配, 其余为错误（如正则引擎不支持该语法）
            int exitCode = process.exitValue();
            if (exitCode == 0 || exitCode == 1) {
                return Optional.of(result);
            }
            
            log.debug("ripgrep exited with {} for pattern '{}', falling back to Java search",
                    exitCode, params.pattern);
            return Optional.empty();
            
        } catch (IOException e) {
            log.debug("ripgrep execution failed, falling back to Java search", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
    
    /**
     * 构造 ripgrep 命令行，输出格式与 Java 实现逐字节对齐
     */
    List<String> buildRipgrepCommand(Path searchPath, Params params) {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--no-messages");             // 抑制二进制/权限告警
        command.add("--color");
        command.add("never");
        command.add("--max-filesize");
        command.add(String.valueOf(MAX_FILE_SIZE));
        
        if (params.ignoreCase) {
            command.add("--ignore-case");
        }
        
        switch (params.outputMode) {
            case "content" -> {
                command.add("--no-heading");
                command.add("--with-filename");
                command.add(params.lineNumber ? "--line-number" : "--no-line-number");
            }
            case "files_with_matches" -> command.add("--files-with-matches");
            case "count_matches" -> command.add("--count");
            default -> throw new IllegalStateException("Unexpected output mode: " + params.outputMode);
        }
        
        if (params.glob != null) {
            command.add("--glob");
            command.add(params.glob);
        }
        
        // -e 保证以 '-' 开头的模式不会被当成选项
        command.add("-e");
        command.add(params.pattern);
        command.add(searchPath.toString());
        
        return command;
    }
    
    /**
     * 取出当前输出模式对应的结果列表，ripgrep 直接把原始输出行写入其中
     */
    private List<String> collectOutputLines(SearchResult result, Params params) {
        return switch (params.outputMode) {
            case "content" -> result.contentLines;
            case "files_with_matches" -> result.filesWithMatches;
            case "count_matches" -> result.matchCounts;
            default -> throw new IllegalStateException("Unexpected output mode: " + params.outputMode);
        };
    }
    
    // ==================== Java 回退路径 ====================
    
    /**
     * 执行搜索：先廉价地收集候选文件（只读元数据），再并行读取内容匹配
     */
    SearchResult performSearch(Path searchPath, Pattern pattern, Params params) {
        SearchResult result = new SearchResult();
        
        List<Path> candidates;
        if (Files.isRegularFile(searchPath)) {
            candidates = List.of(searchPath);
        } else {
            candidates = collectCandidates(searchPath, params);
        }
        
        // parallelStream().collect(toList()) 保持 encounter order，输出顺序与串行版本一致
        List<FileMatches> matches = candidates.parallelStream()
                .map(file -> searchFile(file, pattern, params))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        for (FileMatches fileMatches : matches) {
            result.filesWithMatches.add(fileMatches.path());
            result.matchCounts.add(String.format("%s:%d", fileMatches.path(), fileMatches.count()));
            result.contentLines.addAll(fileMatches.contentLines());
        }
        
        return result;
    }
    
    /**
     * 遍历目录收集候选文件，只依赖文件名和元数据，不读取内容
     */
    private List<Path> collectCandidates(Path searchPath, Params params) {
        List<Path> candidates = new ArrayList<>();
        PathMatcher globMatcher = params.glob == null ? null : compileGlob(params.glob);
        
        try {
            Files.walkFileTree(searchPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    
                    // 跳过隐藏文件
                    if (fileName.startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // 检查 glob 过滤
                    if (globMatcher != null && !globMatcher.matches(Path.of(fileName))) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // 跳过大文件
                    if (attrs.size() > MAX_FILE_SIZE) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // 跳过已知的二进制文件类型
                    if (isBinaryFileByExtension(fileName)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    candidates.add(file);
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dir.equals(searchPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 跳过隐藏目录和构建产物目录
                    if (dirName.startsWith(".") || EXCLUDED_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    log.debug("Skipped unreadable path: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk {}: {}", searchPath, e.getMessage());
        }
        
        return candidates;
    }
    
    /**
     * 搜索单个文件
     * <p>
     * 二进制探测与内容读取共用同一个流，每个文件只打开一次。
     *
     * @return 匹配结果；未命中、二进制或读取失败时返回 null
     */
    private FileMatches searchFile(Path file, Pattern pattern, Params params) {
        boolean collectContent = "content".equals(params.outputMode);
        List<String> contentLines = collectContent ? new ArrayList<>() : List.of();
        int matchCount = 0;
        int lineNumber = 0;
        
        // UTF-8 严格解码：遇到非法字节抛 CharacterCodingException，据此判定为二进制
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BINARY_CHECK_SIZE * 2)) {
            if (isBinaryContent(in)) {
                return null;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, decoder));
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = pattern.matcher(line);
                
                if (matcher.find()) {
                    matchCount++;
                    
                    if (collectContent) {
                        String prefix = params.lineNumber ? String.format("%d:", lineNumber) : "";
                        contentLines.add(String.format("%s:%s%s", file, prefix, line));
                    }
                }
            }
        } catch (CharacterCodingException e) {
            // 遇到编码错误，认为是二进制文件，静默跳过
            log.debug("Skipped binary file: {}", file);
            return null;
        } catch (IOException e) {
            log.debug("Skipped unreadable file: {}", file);
            return null;
        }
        
        if (matchCount == 0) {
            return null;
        }
        
        return new FileMatches(file.toString(), matchCount, contentLines);
    }
    
    /**
     * 使用 Java PathMatcher 编译 Glob 模式
     */
    private PathMatcher compileGlob(String glob) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (Exception e) {
            log.warn("Invalid glob pattern: {}", glob, e);
            // 非法 glob 与旧行为保持一致：不匹配任何文件
            return path -> false;
        }
    }
    
    /**
     * 根据文件扩展名判断是否为二进制文件
     */
    private boolean isBinaryFileByExtension(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        return BINARY_EXTENSIONS.stream()
            .anyMatch(lowerFileName::endsWith);
    }
    
    /**
     * 检测流开头是否像二进制内容，检测后将流重置回原位
     * 通过读取文件前几个字节判断是否包含非文本字符
     */
    private boolean isBinaryContent(InputStream in) throws IOException {
        in.mark(BINARY_CHECK_SIZE + 1);
        byte[] bytes = new byte[BINARY_CHECK_SIZE];
        int bytesRead = in.readNBytes(bytes, 0, BINARY_CHECK_SIZE);
        in.reset();
        
        if (bytesRead <= 0) {
            return false;
        }
        
        // 检查是否包含 NUL 字符或大量非ASCII字符
        int nonAsciiCount = 0;
        for (int i = 0; i < bytesRead; i++) {
            byte b = bytes[i];
            if (b == 0) {
                // 包含 NUL 字符，很可能是二进制文件
                return true;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                // 控制字符（除了 tab, LF, CR）
                nonAsciiCount++;
            }
        }
        
        // 如果超过30%是非文本字符，认为是二进制文件
        return (double) nonAsciiCount / bytesRead > 0.3;
    }
    
    /**
     * 格式化结果
     */
    private ToolResult formatResult(SearchResult result, Params params) {
        ToolResultBuilder builder = new ToolResultBuilder();
        List<String> output = new ArrayList<>();
        
        switch (params.outputMode) {
            case "content":
                output = result.contentLines;
                break;
            case "files_with_matches":
                output = result.filesWithMatches;
                break;
            case "count_matches":
                output = result.matchCounts;
                break;
            default:
                return ToolResult.error("Invalid output mode: " + params.outputMode, "Invalid mode");
        }
        
        // 应用 headLimit
        if (params.headLimit != null && output.size() > params.headLimit) {
            output = output.subList(0, params.headLimit);
            builder.write(String.join("\n", output));
            builder.write(String.format("\n... (results truncated to %d lines)", params.headLimit));
        } else {
            builder.write(String.join("\n", output));
        }
        
        if (output.isEmpty()) {
            return builder.ok("No matches found");
        }
        
        return builder.ok("");
    }
    
    /**
     * 搜索结果
     */
    static class SearchResult {
        List<String> contentLines = new ArrayList<>();
        List<String> filesWithMatches = new ArrayList<>();
        List<String> matchCounts = new ArrayList<>();
    }
    
    /**
     * 单个文件的匹配结果（Java 回退路径并行搜索的中间产物）
     */
    private record FileMatches(String path, int count, List<String> contentLines) {
    }
}
