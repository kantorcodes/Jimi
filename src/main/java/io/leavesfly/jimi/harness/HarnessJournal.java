package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * harness 变更审计日志
 * <p>
 * append-only 的 JSONL 日志，记录对 harness 状态（prompt / memory / skill / subagent）
 * 的每一次变更及其触发原因，并支持按 ID 回滚。
 * <p>
 * 这是开放自我改进能力之前的必要护栏：没有审计与回滚，自我修改就是不可控的漂移。
 * 回滚采用「追加逆向记录」而非删除原记录，历史永不被改写。
 */
@Slf4j
@Component
public class HarnessJournal {

    /** 日志文件相对工作目录的路径 */
    private static final String JOURNAL_RELATIVE_PATH = ".jimi/harness/refine-log.jsonl";

    private final ObjectMapper objectMapper;

    /** 组件读写委托，由 P2 的 HarnessStore 提供；缺省时仅记录审计、不支持回滚落地 */
    private HarnessTarget target;

    /**
     * harness 状态自上次快照注入以来是否发生变更
     * <p>
     * 所有 harness 写入都会经过本类记录，因此这里是天然的变更信号源，
     * 无需在各个工具里分散维护标志。
     */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    @Autowired
    public HarnessJournal(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 设置组件读写目标（运行时注入）
     */
    public void setTarget(HarnessTarget target) {
        this.target = target;
    }

    /**
     * 读取并清除变更标志
     *
     * @return 自上次调用以来是否发生过 harness 变更
     */
    public boolean consumeDirty() {
        return dirty.getAndSet(false);
    }

    /**
     * 主动标记 harness 状态已变更（用于不经由本类的外部变更）
     */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 记录一次 harness 变更
     *
     * @param workDirPath 工作目录绝对路径
     * @param trigger     触发原因，必填；缺省视为 {@code unknown}
     * @param kind        组件类型
     * @param op          操作类型
     * @param targetId    目标标识
     * @param before      变更前内容，create 时可为 null
     * @param after       变更后内容，delete 时可为 null
     * @return 写入的记录，写入失败时返回 {@link Optional#empty()}
     */
    public Optional<HarnessChange> record(String workDirPath, String trigger,
                                          HarnessChange.Kind kind, HarnessChange.Op op,
                                          String targetId, String before, String after) {
        HarnessChange change = HarnessChange.builder()
                .id(nextId(workDirPath))
                .ts(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .trigger(trigger != null && !trigger.isBlank() ? trigger : "unknown")
                .kind(kind)
                .op(op)
                .targetId(targetId)
                .before(before)
                .after(after)
                .build();

        return append(workDirPath, change) ? Optional.of(change) : Optional.empty();
    }

    /**
     * 回填某条记录的效果
     * <p>
     * 效果在变更产生时未知，需由后续验证结果回填。为保持 append-only 语义，
     * 回填以追加一条同 ID 的补充记录实现，读取时以最后一条为准。
     *
     * @param workDirPath 工作目录绝对路径
     * @param id          目标记录 ID
     * @param outcome     效果描述
     * @return 是否成功写入
     */
    public boolean recordOutcome(String workDirPath, long id, String outcome) {
        Optional<HarnessChange> existing = get(workDirPath, id);
        if (existing.isEmpty()) {
            log.warn("Cannot record outcome, harness change not found: id={}", id);
            return false;
        }

        HarnessChange updated = existing.get();
        updated.setOutcome(outcome);
        return append(workDirPath, updated);
    }

    /**
     * 列出最近的变更记录（按 ID 升序）
     *
     * @param workDirPath 工作目录绝对路径
     * @param limit       最大返回条数，非正数表示不限制
     */
    public List<HarnessChange> list(String workDirPath, int limit) {
        List<HarnessChange> all = readAll(workDirPath);
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /**
     * 按 ID 读取记录（同 ID 多条时取最后一条，以承载 outcome 回填）
     */
    public Optional<HarnessChange> get(String workDirPath, long id) {
        HarnessChange found = null;
        for (HarnessChange change : readAll(workDirPath)) {
            if (change.getId() == id) {
                found = change;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * 按 ID 回滚一次变更
     * <p>
     * 回滚不删除也不改写原记录，而是把 {@code before} 写回目标并追加一条
     * {@link HarnessChange.Op#REVERT} 记录，保持历史完整可审计。
     *
     * @param workDirPath 工作目录绝对路径
     * @param id          待回滚的记录 ID
     * @return 回滚结果描述
     */
    public RevertResult revert(String workDirPath, long id) {
        Optional<HarnessChange> found = get(workDirPath, id);
        if (found.isEmpty()) {
            return new RevertResult(false, "未找到 ID 为 " + id + " 的变更记录");
        }

        HarnessChange original = found.get();
        if (original.getOp() == HarnessChange.Op.REVERT) {
            return new RevertResult(false, "记录 " + id + " 本身是回滚操作，不支持再次回滚");
        }

        if (target == null || !target.supports(original.getKind())) {
            return new RevertResult(false,
                    "当前不支持回滚 " + original.getKind() + " 类型的变更（缺少读写目标）");
        }

        String currentContent = target.read(workDirPath, original.getKind(), original.getTargetId());

        try {
            if (original.getOp() == HarnessChange.Op.CREATE) {
                // 逆向操作：删除被创建的目标
                target.delete(workDirPath, original.getKind(), original.getTargetId());
            } else {
                // UPDATE / DELETE 的逆向操作：写回变更前内容
                if (original.getBefore() == null) {
                    return new RevertResult(false, "记录 " + id + " 缺少变更前快照，无法回滚");
                }
                target.write(workDirPath, original.getKind(), original.getTargetId(), original.getBefore());
            }
        } catch (Exception e) {
            log.error("Failed to revert harness change: id={}", id, e);
            return new RevertResult(false, "回滚失败: " + e.getMessage());
        }

        record(workDirPath, "revert-of-" + id, original.getKind(), HarnessChange.Op.REVERT,
                original.getTargetId(), currentContent, original.getBefore());

        log.info("Reverted harness change: id={}, kind={}, target={}",
                id, original.getKind(), original.getTargetId());
        return new RevertResult(true, String.format("已回滚变更 %d（%s / %s）",
                id, original.getKind(), original.getTargetId()));
    }

    // ==================== 内部实现 ====================

    /**
     * 追加一条记录到日志文件
     */
    private boolean append(String workDirPath, HarnessChange change) {
        Path journalFile = journalPath(workDirPath);
        try {
            Files.createDirectories(journalFile.getParent());
            String line = objectMapper.writeValueAsString(change) + System.lineSeparator();
            Files.writeString(journalFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            dirty.set(true);
            return true;
        } catch (IOException e) {
            log.error("Failed to append harness journal entry to {}", journalFile, e);
            return false;
        }
    }

    /**
     * 读取全部记录，按文件顺序（即 ID 升序）返回
     */
    private List<HarnessChange> readAll(String workDirPath) {
        Path journalFile = journalPath(workDirPath);
        if (!Files.isRegularFile(journalFile)) {
            return Collections.emptyList();
        }

        List<HarnessChange> changes = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    changes.add(objectMapper.readValue(line, HarnessChange.class));
                } catch (Exception e) {
                    log.debug("Skipping malformed harness journal line: {}", line, e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read harness journal: {}", journalFile, e);
        }
        return changes;
    }

    /**
     * 计算下一个记录 ID（现有最大 ID + 1）
     */
    private long nextId(String workDirPath) {
        return readAll(workDirPath).stream()
                .mapToLong(HarnessChange::getId)
                .max()
                .orElse(0L) + 1;
    }

    private Path journalPath(String workDirPath) {
        return Path.of(workDirPath).resolve(JOURNAL_RELATIVE_PATH);
    }

    /**
     * 回滚结果
     *
     * @param success 是否成功
     * @param message 结果描述
     */
    public record RevertResult(boolean success, String message) {
    }
}
