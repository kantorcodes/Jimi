package io.leavesfly.jimi.harness;

import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.skill.SkillRegistry;
import io.leavesfly.jimi.skill.SkillSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * harness 状态统一存储
 * <p>
 * 把 Continual Harness 的四个组件 {@code H=(ρ,G,K,M)} 收敛到同一套 CRUD 接口：
 * <ul>
 *   <li>{@code ρ} PROMPT：补充提示词，落盘于 {@code .jimi/harness/prompt-notes/}，由快照注入承载生效</li>
 *   <li>{@code M} MEMORY：委托 {@link MemoryManager}</li>
 *   <li>{@code K} SKILL：委托 {@link SkillRegistry}</li>
 *   <li>{@code G} SUBAGENT：spec 写入 {@code .jimi/harness/subagents/}，委托 {@link AgentRegistry} 注册</li>
 * </ul>
 * <p>
 * 同时作为 {@link HarnessTarget} 供 {@link HarnessJournal} 执行回滚落地。
 * <p>
 * <b>MEMORY 的快照语义</b>：记忆是分 section 组织的单文件，逐 section 做快照会引入
 * 解析歧义，因此 {@link #read}/{@link #write} 对 MEMORY 采用<b>整份 MEMORY.md</b> 的
 * 快照与还原语义，{@code targetId} 仅用于标注是哪个 section 触发的变更。
 * 面向 Agent 的 section 级写入走 {@link MemoryManager} 自身接口。
 */
@Slf4j
@Component
public class HarnessStore implements HarnessTarget {

    /** prompt notes 相对工作目录的存放路径 */
    private static final String PROMPT_NOTES_DIR = ".jimi/harness/prompt-notes";

    /** subagent spec 相对工作目录的存放路径 */
    private static final String SUBAGENTS_DIR = ".jimi/harness/subagents";

    /** prompt notes 总量上限（字符），超出须先删除才能新增，防止补充层无界膨胀 */
    public static final int PROMPT_NOTES_MAX_TOTAL_CHARS = 4000;

    private final MemoryManager memoryManager;
    private final SkillRegistry skillRegistry;
    private final AgentRegistry agentRegistry;
    private final HarnessJournal journal;

    @Autowired
    public HarnessStore(MemoryManager memoryManager,
                        SkillRegistry skillRegistry,
                        AgentRegistry agentRegistry,
                        HarnessJournal journal) {
        this.memoryManager = memoryManager;
        this.skillRegistry = skillRegistry;
        this.agentRegistry = agentRegistry;
        this.journal = journal;
        // 自注册为审计层的读写目标，使 revert 可以落地
        journal.setTarget(this);
    }

    // ==================== HarnessTarget 实现 ====================

    @Override
    public boolean supports(HarnessChange.Kind kind) {
        return kind != null;
    }

    @Override
    public String read(String workDirPath, HarnessChange.Kind kind, String targetId) {
        return switch (kind) {
            case PROMPT -> readPromptNote(workDirPath, targetId);
            case MEMORY -> memoryManager.readMemory(workDirPath);
            case SKILL -> skillRegistry.findByName(targetId).map(SkillSpec::getContent).orElse(null);
            case SUBAGENT -> readSubagentSpec(workDirPath, targetId);
        };
    }

    @Override
    public void write(String workDirPath, HarnessChange.Kind kind, String targetId, String content) {
        switch (kind) {
            case PROMPT -> writePromptNote(workDirPath, targetId, content);
            case MEMORY -> memoryManager.overwriteMemory(workDirPath, content);
            case SKILL -> writeSkill(targetId, content);
            case SUBAGENT -> writeSubagentSpec(workDirPath, targetId, content);
        }
    }

    @Override
    public void delete(String workDirPath, HarnessChange.Kind kind, String targetId) {
        switch (kind) {
            case PROMPT -> deletePromptNote(workDirPath, targetId);
            case MEMORY -> memoryManager.overwriteMemory(workDirPath, "");
            case SKILL -> skillRegistry.uninstall(targetId);
            case SUBAGENT -> deleteSubagentSpec(workDirPath, targetId);
        }
    }

    // ==================== ρ：prompt notes ====================

    /**
     * 读取指定 prompt note
     *
     * @return note 内容，不存在时返回 {@code null}
     */
    public String readPromptNote(String workDirPath, String noteId) {
        Path notePath = promptNotePath(workDirPath, noteId);
        if (!Files.isRegularFile(notePath)) {
            return null;
        }
        try {
            return Files.readString(notePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read prompt note: {}", notePath, e);
            return null;
        }
    }

    /**
     * 写入 prompt note（不存在则创建）
     */
    public void writePromptNote(String workDirPath, String noteId, String content) {
        Path notePath = promptNotePath(workDirPath, noteId);
        try {
            Files.createDirectories(notePath.getParent());
            Files.writeString(notePath, content != null ? content : "", StandardCharsets.UTF_8);
            log.info("Wrote prompt note: {}", noteId);
        } catch (IOException e) {
            throw new IllegalStateException("写入 prompt note 失败: " + noteId, e);
        }
    }

    /**
     * 删除 prompt note
     *
     * @return 是否实际删除了文件
     */
    public boolean deletePromptNote(String workDirPath, String noteId) {
        Path notePath = promptNotePath(workDirPath, noteId);
        try {
            boolean deleted = Files.deleteIfExists(notePath);
            if (deleted) {
                log.info("Deleted prompt note: {}", noteId);
            }
            return deleted;
        } catch (IOException e) {
            throw new IllegalStateException("删除 prompt note 失败: " + noteId, e);
        }
    }

    /**
     * 列出所有 prompt note ID（按名称排序）
     */
    public List<String> listPromptNotes(String workDirPath) {
        Path dir = Path.of(workDirPath).resolve(PROMPT_NOTES_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - ".md".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list prompt notes in {}", dir, e);
            return List.of();
        }
    }

    /**
     * 拼接所有 prompt notes，供 harness 状态快照注入使用
     *
     * @return 拼接后的 Markdown 文本，无 note 时返回空串
     */
    public String renderPromptNotes(String workDirPath) {
        List<String> noteIds = listPromptNotes(workDirPath);
        if (noteIds.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String noteId : noteIds) {
            String content = readPromptNote(workDirPath, noteId);
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append("- **").append(noteId).append("**: ").append(content.strip()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 统计 prompt notes 总字符数
     */
    public int totalPromptNoteChars(String workDirPath) {
        int total = 0;
        for (String noteId : listPromptNotes(workDirPath)) {
            String content = readPromptNote(workDirPath, noteId);
            total += content != null ? content.length() : 0;
        }
        return total;
    }

    private Path promptNotePath(String workDirPath, String noteId) {
        return Path.of(workDirPath).resolve(PROMPT_NOTES_DIR).resolve(sanitize(noteId) + ".md");
    }

    // ==================== K：skills ====================

    /**
     * 写入技能：已存在则编辑，不存在则创建
     */
    private void writeSkill(String skillName, String content) {
        if (skillRegistry.hasSkill(skillName)) {
            skillRegistry.editSkill(skillName, content);
        } else {
            // 回滚重建场景下原始 description 已不可得，交由 createSkill 使用默认描述
            skillRegistry.createSkill(skillName, null, content);
        }
    }

    // ==================== G：subagent specs ====================

    /**
     * 读取 subagent spec 内容
     */
    public String readSubagentSpec(String workDirPath, String subagentName) {
        Path specPath = subagentSpecPath(workDirPath, subagentName);
        if (!Files.isRegularFile(specPath)) {
            return null;
        }
        try {
            return Files.readString(specPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read subagent spec: {}", specPath, e);
            return null;
        }
    }

    /**
     * 写入 subagent spec 并注册到 {@link AgentRegistry}
     */
    public void writeSubagentSpec(String workDirPath, String subagentName, String yamlContent) {
        Path specPath = subagentSpecPath(workDirPath, subagentName);
        try {
            Files.createDirectories(specPath.getParent());
            Files.writeString(specPath, yamlContent != null ? yamlContent : "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 subagent spec 失败: " + subagentName, e);
        }

        agentRegistry.registerAgentSpec(specPath)
                .doOnError(e -> log.warn("Failed to register subagent spec: {}", specPath, e))
                .onErrorResume(e -> Mono.empty())
                .block();
        log.info("Wrote and registered subagent spec: {}", subagentName);
    }

    /**
     * 删除 subagent spec 并反注册
     */
    public boolean deleteSubagentSpec(String workDirPath, String subagentName) {
        Path specPath = subagentSpecPath(workDirPath, subagentName);
        agentRegistry.unregisterAgentSpec(specPath);
        try {
            boolean deleted = Files.deleteIfExists(specPath);
            Path parent = specPath.getParent();
            if (deleted && parent != null && isEmptyDir(parent)) {
                Files.deleteIfExists(parent);
            }
            return deleted;
        } catch (IOException e) {
            throw new IllegalStateException("删除 subagent spec 失败: " + subagentName, e);
        }
    }

    /**
     * 列出所有 subagent spec 名称
     */
    public List<String> listSubagentSpecs(String workDirPath) {
        Path dir = Path.of(workDirPath).resolve(SUBAGENTS_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(dir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("agent.yaml")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list subagent specs in {}", dir, e);
            return List.of();
        }
    }

    private Path subagentSpecPath(String workDirPath, String subagentName) {
        return Path.of(workDirPath).resolve(SUBAGENTS_DIR).resolve(sanitize(subagentName)).resolve("agent.yaml");
    }

    private boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    // ==================== 通用 ====================

    /**
     * 列出指定组件类型下的所有目标标识
     */
    public List<String> list(String workDirPath, HarnessChange.Kind kind) {
        return switch (kind) {
            case PROMPT -> listPromptNotes(workDirPath);
            case MEMORY -> List.of("MEMORY.md");
            case SKILL -> skillRegistry.getAllSkills().stream()
                    .map(SkillSpec::getName)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            case SUBAGENT -> listSubagentSpecs(workDirPath);
        };
    }

    /**
     * 变更并记录审计：捕获变更前快照 → 执行写入 → 追加审计记录
     *
     * @param workDirPath 工作目录绝对路径
     * @param trigger     触发原因
     * @param kind        组件类型
     * @param targetId    目标标识
     * @param content     新内容，{@code null} 表示删除
     * @return 审计记录，写入失败时为空
     */
    public Optional<HarnessChange> applyAndRecord(String workDirPath, String trigger,
                                                  HarnessChange.Kind kind, String targetId,
                                                  String content) {
        String before = read(workDirPath, kind, targetId);
        HarnessChange.Op op;
        if (content == null) {
            op = HarnessChange.Op.DELETE;
            delete(workDirPath, kind, targetId);
        } else {
            op = before == null ? HarnessChange.Op.CREATE : HarnessChange.Op.UPDATE;
            write(workDirPath, kind, targetId, content);
        }

        String after = content == null ? null : read(workDirPath, kind, targetId);
        return journal.record(workDirPath, trigger, kind, op, targetId, before, after);
    }

    /**
     * 清理标识中的路径分隔符，避免越出存储目录
     */
    private String sanitize(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("harness 目标标识不能为空");
        }
        String cleaned = id.replaceAll("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]", "_");
        if (cleaned.equals(".") || cleaned.equals("..")) {
            throw new IllegalArgumentException("非法的 harness 目标标识: " + id);
        }
        return cleaned;
    }

    /**
     * 供测试与诊断使用：返回 harness 根目录
     */
    public Path harnessRoot(String workDirPath) {
        return Path.of(workDirPath).resolve(".jimi").resolve("harness");
    }

    /**
     * 供 P2 refine 使用：校验 prompt notes 是否仍有配额
     *
     * @param addingChars 计划新增的字符数
     * @return 超限说明，未超限时返回空
     */
    public Optional<String> checkPromptNoteBudget(String workDirPath, int addingChars) {
        int total = totalPromptNoteChars(workDirPath) + addingChars;
        if (total > PROMPT_NOTES_MAX_TOTAL_CHARS) {
            return Optional.of(String.format(
                    "prompt notes 总量将达 %d 字符，超过上限 %d，必须先删除已有 note 才能新增",
                    total, PROMPT_NOTES_MAX_TOTAL_CHARS));
        }
        return Optional.empty();
    }
}
