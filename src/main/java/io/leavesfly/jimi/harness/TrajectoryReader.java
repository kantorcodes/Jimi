package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 执行轨迹读取器
 * <p>
 * 为 {@link RefineEngine} 提供「做过什么、结果如何」的原始素材。
 * <p>
 * 会话历史不足时会自动回溯到上下文压缩产生的归档文件（{@code *.jsonl.N}） ——
 * 压缩虽是有损的，但原始历史被轮转保留，因此轨迹依然可寻址。
 */
@Slf4j
@Component
public class TrajectoryReader {

    /** 会话文件名模式：活动文件或压缩归档文件 */
    private static final Pattern SESSION_FILE_PATTERN = Pattern.compile(".*\\.jsonl(\\.\\d+)?$");

    /** 单条消息纳入轨迹的文本上限，避免个别超长消息挤占分析窗口 */
    private static final int MAX_MESSAGE_CHARS = 1200;

    private final ObjectMapper objectMapper;

    @Autowired
    public TrajectoryReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取最近的执行轨迹
     *
     * @param workDirPath  工作目录绝对路径
     * @param maxMessages  最多纳入的消息条数
     * @return 轨迹文本，无可用历史时返回空串
     */
    public String readRecent(String workDirPath, int maxMessages) {
        int limit = maxMessages > 0 ? maxMessages : 40;
        Path sessionsDir = resolveSessionsDir(workDirPath);
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return "";
        }

        List<Path> sessionFiles = listSessionFilesNewestFirst(sessionsDir);
        if (sessionFiles.isEmpty()) {
            return "";
        }

        // 从最新文件向旧文件回溯，直到收集够消息数
        List<String> collected = new ArrayList<>();
        for (Path sessionFile : sessionFiles) {
            if (collected.size() >= limit) {
                break;
            }
            List<String> fromFile = readMessages(sessionFile, limit - collected.size());
            // 越旧的内容应排在越前面
            collected.addAll(0, fromFile);
        }

        if (collected.isEmpty()) {
            return "";
        }
        return String.join("\n", collected);
    }

    /**
     * 定位当前工作目录对应的会话目录
     */
    private Path resolveSessionsDir(String workDirPath) {
        if (workDirPath == null || workDirPath.isBlank()) {
            return null;
        }
        String dirHash = Integer.toHexString(workDirPath.hashCode());
        return Paths.get(System.getProperty("user.home"), ".jimi", "sessions", dirHash);
    }

    /**
     * 列出会话文件，按修改时间降序（归档文件天然靠后）
     */
    private List<Path> listSessionFilesNewestFirst(Path sessionsDir) {
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                    .filter(p -> SESSION_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list session files in {}", sessionsDir, e);
            return Collections.emptyList();
        }
    }

    /**
     * 从单个会话文件读取尾部若干条消息
     *
     * @return 按时间顺序排列的消息文本
     */
    private List<String> readMessages(Path sessionFile, int limit) {
        List<String> messages = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            // 从尾部向前扫描，取最近的 limit 条
            for (int i = lines.size() - 1; i >= 0 && messages.size() < limit; i--) {
                String formatted = formatLine(lines.get(i));
                if (formatted != null) {
                    messages.add(0, formatted);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read session file: {}", sessionFile, e);
        }
        return messages;
    }

    /**
     * 把一行 JSONL 格式化为轨迹条目
     *
     * @return 格式化文本，非消息行（如 {@code _checkpoint}、{@code _usage}）返回 null
     */
    private String formatLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            String role = node.path("role").asText("");
            if (role.isEmpty() || role.startsWith("_")) {
                return null;
            }

            String text = extractText(node.get("content"));
            if (text == null || text.isBlank()) {
                return null;
            }
            if (text.length() > MAX_MESSAGE_CHARS) {
                text = text.substring(0, MAX_MESSAGE_CHARS) + "...(截断)";
            }
            return "[" + role + "] " + text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 content 节点提取文本，兼容字符串与数组两种形态
     */
    private String extractText(JsonNode contentNode) {
        if (contentNode == null) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                JsonNode textNode = part.get("text");
                if (textNode != null && textNode.isTextual()) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append(textNode.asText());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }
}
