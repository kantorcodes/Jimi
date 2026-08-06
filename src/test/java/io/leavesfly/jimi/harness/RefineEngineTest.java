package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.config.info.RefineConfig;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.ChatProvider;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.LLMFactory;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefineEngine} 护栏测试
 * <p>
 * 自我改进是有实证风险的能力：Prime Intellect 在 Factorio 环境中观察到同一 refine 循环
 * 从「构建合法技能」漂移到「构建高效作弊技能」。这批用例逐条验证四条护栏，
 * 以及默认关闭状态下三个触发点都不生效。
 */
class RefineEngineTest {

    @TempDir
    Path workDir;

    private RefineEngine engine;
    private RefineConfig refineConfig;
    private HarnessStore harnessStore;
    private HarnessJournal journal;
    private ChatProvider chatProvider;
    private String workDirPath;

    @BeforeEach
    void setUp() {
        workDirPath = workDir.toAbsolutePath().toString();

        ObjectMapper objectMapper = new ObjectMapper();
        journal = new HarnessJournal(objectMapper);
        harnessStore = new HarnessStore(
                new MemoryManager(new MemoryConfig()),
                mock(SkillRegistry.class),
                mock(AgentRegistry.class),
                journal);

        chatProvider = mock(ChatProvider.class);
        LLM llm = LLM.builder().chatProvider(chatProvider).maxContextSize(100_000).build();
        LLMFactory llmFactory = mock(LLMFactory.class);
        when(llmFactory.getOrCreateLLM(any())).thenReturn(llm);

        refineConfig = new RefineConfig();
        refineConfig.setEnabled(true);

        engine = new RefineEngine();
        ReflectionTestUtils.setField(engine, "llmFactory", llmFactory);
        ReflectionTestUtils.setField(engine, "refineConfig", refineConfig);
        ReflectionTestUtils.setField(engine, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(engine, "harnessStore", harnessStore);
        ReflectionTestUtils.setField(engine, "trajectoryReader", new TrajectoryReader(objectMapper));
    }

    // ==================== 护栏 1：最小编辑 ====================

    @Test
    void jsonArrayProposalShouldBeRejectedAsMultipleOperations() {
        stubResponse("""
                [{"action":"upsert","kind":"prompt","target_id":"a","content":"内容 A"},
                 {"action":"upsert","kind":"prompt","target_id":"b","content":"内容 B"}]
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("多个操作"));
        assertNoChangeApplied();
    }

    @Test
    void actionsArrayFieldShouldBeRejectedAsMultipleOperations() {
        stubResponse("""
                {"actions":[{"action":"upsert","kind":"prompt","target_id":"a","content":"内容"}]}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("多个操作"));
        assertNoChangeApplied();
    }

    // ==================== 护栏 2：基础提示词不可变 ====================

    @Test
    void subagentKindShouldBeRejected() {
        stubResponse("""
                {"action":"upsert","kind":"subagent","target_id":"new-agent","content":"name: x"}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("subagent"));
        assertNoChangeApplied();
    }

    @Test
    void unknownKindShouldBeRejected() {
        stubResponse("""
                {"action":"upsert","kind":"system_prompt","target_id":"base","content":"覆盖基础提示词"}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("未知的 kind"));
        assertNoChangeApplied();
    }

    // ==================== 护栏 3：证据留痕 ====================

    @Test
    void appliedChangeShouldCarryTriggerInJournal() {
        stubResponse("""
                {"action":"upsert","kind":"prompt","target_id":"build-first",
                 "content":"改动构建配置后先跑一次编译再继续","reason":"上轮因未编译而返工"}
                """);

        RefineEngine.RefineResult result = engine
                .run(workDirPath, "goal-verify-failed-x2", "一段执行轨迹", "验证连续失败")
                .block();

        assertNotNull(result);
        assertEquals(RefineEngine.RefineResult.Status.APPLIED, result.status(), result.message());
        assertEquals("上轮因未编译而返工", result.message());

        List<HarnessChange> changes = journal.list(workDirPath, 0);
        assertEquals(1, changes.size());
        assertEquals("goal-verify-failed-x2", changes.get(0).getTrigger(),
                "每条记录必须带触发原因");
        assertEquals(HarnessChange.Kind.PROMPT, changes.get(0).getKind());
        assertEquals("改动构建配置后先跑一次编译再继续",
                harnessStore.readPromptNote(workDirPath, "build-first"));
    }

    // ==================== 护栏 4：补充层配额 ====================

    @Test
    void singleNoteExceedingPerNoteLimitShouldBeRejected() {
        stubResponse(String.format("""
                {"action":"upsert","kind":"prompt","target_id":"huge","content":"%s"}
                """, "x".repeat(1001)));

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("超过上限"));
        assertNull(harnessStore.readPromptNote(workDirPath, "huge"));
    }

    @Test
    void totalPromptNoteBudgetExceededShouldBeRejected() {
        // 预先占满 4000 字符配额（4 条 * 1000 字符）
        for (int i = 0; i < 4; i++) {
            harnessStore.writePromptNote(workDirPath, "existing-" + i, "y".repeat(1000));
        }
        assertEquals(HarnessStore.PROMPT_NOTES_MAX_TOTAL_CHARS,
                harnessStore.totalPromptNoteChars(workDirPath));

        stubResponse("""
                {"action":"upsert","kind":"prompt","target_id":"one-more","content":"再加一条"}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("必须先删除已有 note"));
        assertNull(harnessStore.readPromptNote(workDirPath, "one-more"));
    }

    @Test
    void updatingExistingNoteShouldCountOnlyTheDelta() {
        // 占满配额后覆盖其中一条为更短内容：净增为负，不应被配额拦住
        for (int i = 0; i < 4; i++) {
            harnessStore.writePromptNote(workDirPath, "existing-" + i, "y".repeat(1000));
        }

        stubResponse("""
                {"action":"upsert","kind":"prompt","target_id":"existing-0","content":"精简后的规则"}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.APPLIED, result.status(), result.message());
        assertEquals("精简后的规则", harnessStore.readPromptNote(workDirPath, "existing-0"));
    }

    // ==================== 默认关闭 ====================

    @Test
    void disabledEngineShouldNotCallLlmAtAll() {
        refineConfig.setEnabled(false);

        RefineEngine.RefineResult direct = run();
        RefineEngine.RefineResult viaTrajectory = engine
                .runOnRecentTrajectory(workDirPath, "task-success", null).block();

        assertEquals(RefineEngine.RefineResult.Status.SKIPPED, direct.status());
        assertNotNull(viaTrajectory);
        assertEquals(RefineEngine.RefineResult.Status.SKIPPED, viaTrajectory.status());
        verify(chatProvider, never()).generate(anyString(), anyList(), anyList());
        assertNoChangeApplied();
    }

    @Test
    void disabledEngineShouldReportTriggersAsOff() {
        refineConfig.setEnabled(false);

        assertTrue(!engine.isEnabled(), "enabled=false 时手动触发点应关闭");
        assertTrue(!engine.isTriggerOnSuccess(), "enabled=false 时成功触发点应关闭");
    }

    @Test
    void triggerOnSuccessRequiresBothFlags() {
        refineConfig.setEnabled(true);
        refineConfig.setTriggerOnSuccess(false);
        assertTrue(!engine.isTriggerOnSuccess(), "仅 enabled=true 不足以开启成功触发点");

        refineConfig.setTriggerOnSuccess(true);
        assertTrue(engine.isTriggerOnSuccess());
    }

    // ==================== 其他 ====================

    @Test
    void blankTrajectoryShouldBeSkippedWithoutCallingLlm() {
        RefineEngine.RefineResult result = engine.run(workDirPath, "manual", "   ", null).block();

        assertNotNull(result);
        assertEquals(RefineEngine.RefineResult.Status.SKIPPED, result.status());
        verify(chatProvider, never()).generate(anyString(), anyList(), anyList());
    }

    @Test
    void actionNoneShouldProduceNoChange() {
        stubResponse("""
                {"action":"none","reason":"轨迹中没有值得固化的教训"}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.SKIPPED, result.status());
        assertNoChangeApplied();
    }

    @Test
    void markdownFencedJsonShouldBeParsed() {
        stubResponse("""
                这是我的分析结论：
                ```json
                {"action":"upsert","kind":"prompt","target_id":"fenced","content":"围栏内的规则"}
                ```
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.APPLIED, result.status(), result.message());
        assertEquals("围栏内的规则", harnessStore.readPromptNote(workDirPath, "fenced"));
    }

    @Test
    void proposalMissingContentShouldBeRejected() {
        stubResponse("""
                {"action":"upsert","kind":"prompt","target_id":"empty","content":""}
                """);

        RefineEngine.RefineResult result = run();

        assertEquals(RefineEngine.RefineResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("缺少 target_id 或 content"));
    }

    // ==================== 测试脚手架 ====================

    private void stubResponse(String responseText) {
        when(chatProvider.generate(anyString(), anyList(), anyList()))
                .thenReturn(Mono.just(ChatCompletionResult.builder()
                        .message(Message.assistant(responseText))
                        .build()));
    }

    private RefineEngine.RefineResult run() {
        RefineEngine.RefineResult result = engine
                .run(workDirPath, "unit-test", "一段执行轨迹", null)
                .block();
        assertNotNull(result, "refine 应始终返回结果");
        return result;
    }

    private void assertNoChangeApplied() {
        assertTrue(journal.list(workDirPath, 0).isEmpty(), "被拒绝的提案不得留下变更记录");
    }
}
