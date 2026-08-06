package io.leavesfly.jimi.core.engine.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.harness.HarnessChange;
import io.leavesfly.jimi.harness.HarnessJournal;
import io.leavesfly.jimi.harness.HarnessStore;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HarnessStateSnapshot} 注入测试
 * <p>
 * 守两条线：写入 harness 后<b>当场</b>能读到（写-读回路闭合），
 * 且历史中至多一条快照（不随步数累积 token）。
 */
class HarnessStateSnapshotTest {

    @TempDir
    Path workDir;

    private HarnessStateSnapshot snapshot;
    private HarnessJournal journal;
    private HarnessStore harnessStore;
    private Context context;
    private String workDirPath;

    @BeforeEach
    void setUp() {
        workDirPath = workDir.toAbsolutePath().toString();

        ObjectMapper objectMapper = new ObjectMapper();
        journal = new HarnessJournal(objectMapper);

        MemoryConfig memoryConfig = new MemoryConfig();
        memoryConfig.setEnabled(false);
        MemoryManager memoryManager = new MemoryManager(memoryConfig);

        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.generateSkillsSummary()).thenReturn("");

        harnessStore = new HarnessStore(memoryManager, skillRegistry, mock(AgentRegistry.class), journal);
        snapshot = new HarnessStateSnapshot(journal, harnessStore, memoryManager, skillRegistry);

        context = new Context(workDir.resolve("history.jsonl"), objectMapper);
    }

    @Test
    void snapshotShouldNotBeInjectedWhenHarnessUnchanged() {
        harnessStore.writePromptNote(workDirPath, "note-a", "先写入内容但不记审计");
        // 未经 journal 记录，dirty 未置位

        boolean injected = snapshot.refreshIfDirty(context, workDirPath);

        assertFalse(injected, "harness 未变更时不应注入，避免每轮增加 token");
        assertEquals(0, countSnapshots());
    }

    @Test
    void snapshotShouldBeInjectedAfterHarnessWrite() {
        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", "改动构建配置后先跑一次编译");

        boolean injected = snapshot.refreshIfDirty(context, workDirPath);

        assertTrue(injected, "写入 harness 后应当场注入快照");
        assertEquals(1, countSnapshots());
        String text = snapshotMessages().get(0).getTextContent();
        assertTrue(text.startsWith(HarnessStateSnapshot.SNAPSHOT_PREFIX));
        assertTrue(text.contains("改动构建配置后先跑一次编译"), "快照应含刚写入的内容");
        assertTrue(text.contains("note-a"));
    }

    @Test
    void repeatedRefreshShouldKeepExactlyOneSnapshot() {
        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", "第一版规则");
        assertTrue(snapshot.refreshIfDirty(context, workDirPath));

        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-b", "第二版规则");
        assertTrue(snapshot.refreshIfDirty(context, workDirPath));

        assertEquals(1, countSnapshots(), "历史中至多一条快照，不随步数累积");
        String text = snapshotMessages().get(0).getTextContent();
        assertTrue(text.contains("第一版规则"));
        assertTrue(text.contains("第二版规则"));
    }

    @Test
    void dirtyFlagShouldBeConsumedSoNextStepDoesNotReinject() {
        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", "规则");

        assertTrue(snapshot.refreshIfDirty(context, workDirPath), "首次应注入");
        assertFalse(snapshot.refreshIfDirty(context, workDirPath), "标志已消费，下一步不应重复注入");
        assertEquals(1, countSnapshots());
    }

    @Test
    void snapshotShouldBeTransientAndNotPersisted() {
        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", "规则");
        snapshot.refreshIfDirty(context, workDirPath);

        assertEquals(1, countSnapshots(), "内存历史中应有快照");

        // 重新加载同一份持久化历史：快照是派生状态，不应出现在磁盘上
        Context reloaded = new Context(workDir.resolve("history.jsonl"), new ObjectMapper());
        reloaded.restore().block();
        long persistedSnapshots = reloaded.getHistory().stream()
                .filter(HarnessStateSnapshot::isSnapshotMessage)
                .count();
        assertEquals(0, persistedSnapshots, "快照不应写入 append-only 的 JSONL");
    }

    @Test
    void emptyHarnessShouldRenderNothing() {
        journal.markDirty();

        boolean injected = snapshot.refreshIfDirty(context, workDirPath);

        assertFalse(injected, "harness 为空时无内容可注入");
        assertEquals("", snapshot.render(workDirPath));
    }

    @Test
    void staleSnapshotShouldBeRemovedWhenHarnessBecomesEmpty() {
        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", "规则");
        assertTrue(snapshot.refreshIfDirty(context, workDirPath));

        harnessStore.applyAndRecord(workDirPath, "manual", HarnessChange.Kind.PROMPT,
                "note-a", null);

        assertFalse(snapshot.refreshIfDirty(context, workDirPath));
        assertEquals(0, countSnapshots(), "内容清空后旧快照也应被移除，避免留下过期状态");
    }

    private List<Message> snapshotMessages() {
        return context.getHistory().stream()
                .filter(HarnessStateSnapshot::isSnapshotMessage)
                .toList();
    }

    private int countSnapshots() {
        return snapshotMessages().size();
    }
}
