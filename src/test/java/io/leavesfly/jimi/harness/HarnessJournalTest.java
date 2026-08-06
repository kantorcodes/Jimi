package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarnessJournal} 审计与回滚测试
 * <p>
 * 回滚的关键语义是「追加逆向记录，绝不改写历史」——这批用例守住的正是这一点：
 * 开放自我改进之前，审计日志本身必须不可篡改。
 */
class HarnessJournalTest {

    @TempDir
    Path workDir;

    private HarnessJournal journal;
    private InMemoryTarget target;
    private String workDirPath;

    @BeforeEach
    void setUp() {
        journal = new HarnessJournal(new ObjectMapper());
        target = new InMemoryTarget();
        journal.setTarget(target);
        workDirPath = workDir.toAbsolutePath().toString();
    }

    @Test
    void recordShouldAssignIncrementalIds() {
        Optional<HarnessChange> first = journal.record(workDirPath, "manual",
                HarnessChange.Kind.PROMPT, HarnessChange.Op.CREATE, "note-a", null, "内容 A");
        Optional<HarnessChange> second = journal.record(workDirPath, "manual",
                HarnessChange.Kind.PROMPT, HarnessChange.Op.CREATE, "note-b", null, "内容 B");

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(1L, first.get().getId());
        assertEquals(2L, second.get().getId());
    }

    @Test
    void recordShouldDefaultBlankTriggerToUnknown() {
        Optional<HarnessChange> change = journal.record(workDirPath, "  ",
                HarnessChange.Kind.MEMORY, HarnessChange.Op.UPDATE, "决策", "旧", "新");

        assertTrue(change.isPresent());
        assertEquals("unknown", change.get().getTrigger(), "trigger 必填，缺省应落为 unknown");
    }

    @Test
    void revertUpdateShouldRestoreBeforeContentAndAppendRevertRecord() {
        target.write(workDirPath, HarnessChange.Kind.PROMPT, "note-a", "改后内容");
        Optional<HarnessChange> change = journal.record(workDirPath, "refine",
                HarnessChange.Kind.PROMPT, HarnessChange.Op.UPDATE, "note-a", "改前内容", "改后内容");
        assertTrue(change.isPresent());

        HarnessJournal.RevertResult result = journal.revert(workDirPath, change.get().getId());

        assertTrue(result.success(), result.message());
        assertEquals("改前内容", target.read(workDirPath, HarnessChange.Kind.PROMPT, "note-a"),
                "before 应被写回目标");

        List<HarnessChange> all = journal.list(workDirPath, 0);
        assertEquals(2, all.size(), "回滚应追加新记录而非替换");

        HarnessChange original = all.get(0);
        assertEquals(HarnessChange.Op.UPDATE, original.getOp(), "原记录不得被改写");
        assertEquals("改后内容", original.getAfter());

        HarnessChange revertRecord = all.get(1);
        assertEquals(HarnessChange.Op.REVERT, revertRecord.getOp());
        assertEquals("revert-of-" + original.getId(), revertRecord.getTrigger());
        assertEquals("改前内容", revertRecord.getAfter());
    }

    @Test
    void revertCreateShouldDeleteTarget() {
        target.write(workDirPath, HarnessChange.Kind.PROMPT, "note-new", "新建内容");
        Optional<HarnessChange> change = journal.record(workDirPath, "refine",
                HarnessChange.Kind.PROMPT, HarnessChange.Op.CREATE, "note-new", null, "新建内容");
        assertTrue(change.isPresent());

        HarnessJournal.RevertResult result = journal.revert(workDirPath, change.get().getId());

        assertTrue(result.success(), result.message());
        assertNull(target.read(workDirPath, HarnessChange.Kind.PROMPT, "note-new"),
                "CREATE 的逆向操作是删除");
    }

    @Test
    void revertShouldRejectRevertingARevertRecord() {
        target.write(workDirPath, HarnessChange.Kind.PROMPT, "note-a", "改后内容");
        long id = journal.record(workDirPath, "refine", HarnessChange.Kind.PROMPT,
                HarnessChange.Op.UPDATE, "note-a", "改前内容", "改后内容").orElseThrow().getId();
        journal.revert(workDirPath, id);

        List<HarnessChange> all = journal.list(workDirPath, 0);
        long revertId = all.get(all.size() - 1).getId();

        HarnessJournal.RevertResult result = journal.revert(workDirPath, revertId);

        assertFalse(result.success());
        assertTrue(result.message().contains("不支持再次回滚"));
    }

    @Test
    void revertShouldFailForUnknownId() {
        HarnessJournal.RevertResult result = journal.revert(workDirPath, 999L);

        assertFalse(result.success());
        assertTrue(result.message().contains("未找到"));
    }

    @Test
    void revertShouldFailWhenTargetKindUnsupported() {
        target.supportedKinds.clear();
        long id = journal.record(workDirPath, "refine", HarnessChange.Kind.SKILL,
                HarnessChange.Op.UPDATE, "some-skill", "旧", "新").orElseThrow().getId();

        HarnessJournal.RevertResult result = journal.revert(workDirPath, id);

        assertFalse(result.success());
        assertTrue(result.message().contains("缺少读写目标"));
    }

    @Test
    void recordOutcomeShouldBeReadBackAsLatestEntry() {
        long id = journal.record(workDirPath, "refine", HarnessChange.Kind.PROMPT,
                HarnessChange.Op.CREATE, "note-a", null, "内容").orElseThrow().getId();

        assertTrue(journal.recordOutcome(workDirPath, id, "下一轮验证通过"));

        assertEquals("下一轮验证通过", journal.get(workDirPath, id).orElseThrow().getOutcome());
        assertEquals(2, journal.list(workDirPath, 0).size(),
                "outcome 回填也走追加，不改写原行");
    }

    @Test
    void listShouldRespectLimit() {
        for (int i = 0; i < 5; i++) {
            journal.record(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                    HarnessChange.Op.CREATE, "note-" + i, null, "内容");
        }

        List<HarnessChange> recent = journal.list(workDirPath, 2);

        assertEquals(2, recent.size());
        assertEquals(4L, recent.get(0).getId(), "limit 应保留最近的记录");
        assertEquals(5L, recent.get(1).getId());
    }

    @Test
    void journalShouldBeAppendOnlyOnDisk() throws Exception {
        journal.record(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                HarnessChange.Op.CREATE, "note-a", null, "内容");
        journal.record(workDirPath, "manual", HarnessChange.Kind.MEMORY,
                HarnessChange.Op.UPDATE, "决策", "旧", "新");

        Path journalFile = workDir.resolve(".jimi/harness/refine-log.jsonl");
        assertTrue(Files.isRegularFile(journalFile));
        List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "每次记录追加一行");
    }

    @Test
    void writeShouldSetDirtyFlagAndConsumeShouldClearIt() {
        assertFalse(journal.consumeDirty(), "初始状态无变更");

        journal.record(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                HarnessChange.Op.CREATE, "note-a", null, "内容");

        assertTrue(journal.consumeDirty(), "写入应置位变更标志");
        assertFalse(journal.consumeDirty(), "消费后应复位");
    }

    /**
     * 测试用的内存读写目标，避开真实的 MEMORY.md / SKILL.md 存储细节
     */
    private static class InMemoryTarget implements HarnessTarget {

        private final Map<String, String> store = new HashMap<>();
        private final java.util.Set<HarnessChange.Kind> supportedKinds =
                new java.util.HashSet<>(List.of(HarnessChange.Kind.PROMPT, HarnessChange.Kind.MEMORY));

        @Override
        public boolean supports(HarnessChange.Kind kind) {
            return supportedKinds.contains(kind);
        }

        @Override
        public String read(String workDirPath, HarnessChange.Kind kind, String targetId) {
            return store.get(key(kind, targetId));
        }

        @Override
        public void write(String workDirPath, HarnessChange.Kind kind, String targetId, String content) {
            store.put(key(kind, targetId), content);
        }

        @Override
        public void delete(String workDirPath, HarnessChange.Kind kind, String targetId) {
            store.remove(key(kind, targetId));
        }

        private String key(HarnessChange.Kind kind, String targetId) {
            return kind + ":" + targetId;
        }
    }
}
