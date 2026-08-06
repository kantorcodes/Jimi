package io.leavesfly.jimi.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.RefineConfig;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.LLMFactory;
import io.leavesfly.jimi.llm.message.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 自我改进元循环
 * <p>
 * 读取执行轨迹（做过什么、结果如何），对 harness 补充层施加<b>最小</b>增量修改，
 * 让「从失败中学习」不再只体现为一次性的 prompt 文本，而是可持续累积的状态。
 * <p>
 * 四条护栏缺一不可，否则自我改进会退化为不可控漂移：
 * <ol>
 *   <li><b>最小编辑</b>：一次只允许一个 CRUD 操作，多操作直接拒绝</li>
 *   <li><b>基础提示词不可变</b>：只能写 {@code prompt-notes/} 补充层，
 *       不得触碰 {@code Agent.systemPrompt} 与 agent.yaml</li>
 *   <li><b>证据留痕</b>：每次变更必带 trigger，outcome 由后续验证结果回填</li>
 *   <li><b>补充层配额</b>：prompt notes 总量有上限，超限须先删除才能新增</li>
 * </ol>
 * <p>
 * 默认关闭（{@code refine.enabled=false}）。
 */
@Slf4j
@Service
public class RefineEngine {

    private static final String REFINE_SYSTEM_PROMPT = """
            你是一个 Agent 运行框架（harness）的改进者。你会读到一段执行轨迹，
            你的任务是从中提炼出**一条**可复用的改进，写入 harness 的补充层。

            严格规则：
            1. 只允许输出**一个**操作。不要输出多个操作，不要输出操作数组。
            2. 只允许改动补充层，禁止修改基础系统提示词。
            3. 改动必须小而具体：一条补充指令、一条记忆、或一个技能，不要重写整体。
            4. 改进必须有轨迹中的证据支撑。若轨迹中没有值得固化的教训或战术，
               返回 {"action": "none", "reason": "原因"}。
            5. 返回严格的 JSON，不要包含其他内容。

            可用的 kind：
            - prompt: 补充指令，适合「下次遇到同类情况应该怎么做」的短规则
            - memory: 长期记忆，适合项目事实、用户偏好、已验证结论
            - skill: 技能，适合成体系的可复用操作流程

            返回格式（二选一）：
            {"action": "upsert", "kind": "prompt|memory|skill", "target_id": "简短标识", "content": "内容", "reason": "为什么做这个改动"}
            {"action": "none", "reason": "为什么无需改动"}
            """;

    /** 单条 prompt note 的内容上限，配合总量配额共同约束补充层规模 */
    private static final int MAX_SINGLE_NOTE_CHARS = 1000;

    @Autowired
    private LLMFactory llmFactory;

    @Autowired
    private RefineConfig refineConfig;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HarnessStore harnessStore;

    @Autowired
    private TrajectoryReader trajectoryReader;

    /**
     * refine 是否启用
     */
    public boolean isEnabled() {
        return refineConfig.isEnabled();
    }

    /**
     * 是否应在任务成功后触发 refine
     */
    public boolean isTriggerOnSuccess() {
        return refineConfig.isEnabled() && refineConfig.isTriggerOnSuccess();
    }

    /**
     * 读取最近轨迹并执行一次 refine
     * <p>
     * 轨迹读取本身涉及磁盘扫描，同样放在弹性线程池，调用方可安全地在主流程中 subscribe。
     *
     * @param workDirPath 工作目录绝对路径
     * @param trigger     触发原因，会写入审计记录
     * @param focus       关注点，可为空
     * @return refine 结果
     */
    public Mono<RefineResult> runOnRecentTrajectory(String workDirPath, String trigger, String focus) {
        if (!refineConfig.isEnabled()) {
            return Mono.just(RefineResult.skipped("refine 未启用（refine.enabled=false）"));
        }
        return Mono.fromCallable(() -> trajectoryReader.readRecent(workDirPath, refineConfig.getTrajectoryWindow()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(trajectory -> run(workDirPath, trigger, trajectory, focus));
    }

    /**
     * 异步执行一次 refine
     * <p>
     * planning（LLM 调用）在弹性线程池执行，不阻塞主循环；变更落地本身是快速的文件写入。
     *
     * @param workDirPath 工作目录绝对路径
     * @param trigger     触发原因，会写入审计记录
     * @param trajectory  轨迹文本
     * @param focus       关注点，可为空
     * @return refine 结果
     */
    public Mono<RefineResult> run(String workDirPath, String trigger, String trajectory, String focus) {
        if (!refineConfig.isEnabled()) {
            return Mono.just(RefineResult.skipped("refine 未启用（refine.enabled=false）"));
        }
        if (trajectory == null || trajectory.isBlank()) {
            return Mono.just(RefineResult.skipped("轨迹为空，无可分析内容"));
        }

        return Mono.fromCallable(() -> planAndApply(workDirPath, trigger, trajectory, focus))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Refine failed", e);
                    return Mono.just(RefineResult.failed("refine 执行失败: " + e.getMessage()));
                });
    }

    /**
     * 规划并应用改进
     */
    private RefineResult planAndApply(String workDirPath, String trigger, String trajectory, String focus) {
        LLM llm = getRefineLLM();
        if (llm == null) {
            return RefineResult.failed("refine LLM 不可用");
        }

        String userPrompt = buildUserPrompt(trajectory, focus);
        ChatCompletionResult result = llm.getChatProvider()
                .generate(REFINE_SYSTEM_PROMPT, List.of(Message.user(userPrompt)), Collections.emptyList())
                .block();

        String response = (result != null && result.getMessage() != null)
                ? result.getMessage().getTextContent()
                : null;
        if (response == null || response.isBlank()) {
            return RefineResult.failed("refine LLM 无响应");
        }

        RefineProposal proposal;
        try {
            proposal = parseProposal(response);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected refine proposal: {}", e.getMessage());
            return RefineResult.rejected(e.getMessage());
        }

        if (proposal == null) {
            return RefineResult.skipped("模型判断无需改动");
        }

        Optional<String> violation = validate(workDirPath, proposal);
        if (violation.isPresent()) {
            log.warn("Rejected refine proposal: {}", violation.get());
            return RefineResult.rejected(violation.get());
        }

        Optional<HarnessChange> change = harnessStore.applyAndRecord(
                workDirPath,
                trigger != null && !trigger.isBlank() ? trigger : "refine",
                proposal.kind(), proposal.targetId(), proposal.content());

        if (change.isEmpty()) {
            return RefineResult.failed("变更已应用但审计记录写入失败");
        }

        log.info("Refine applied: id={}, kind={}, target={}, reason={}",
                change.get().getId(), proposal.kind(), proposal.targetId(), proposal.reason());
        return RefineResult.applied(change.get(), proposal.reason());
    }

    private String buildUserPrompt(String trajectory, String focus) {
        StringBuilder sb = new StringBuilder();
        if (focus != null && !focus.isBlank()) {
            sb.append("## 关注点\n").append(focus).append("\n\n");
        }
        sb.append("## 执行轨迹\n").append(trajectory).append("\n\n");
        sb.append("请按规则返回一个 JSON 操作。");
        return sb.toString();
    }

    /**
     * 解析模型提案
     *
     * @return 提案，模型判断无需改动时返回 {@code null}
     * @throws IllegalArgumentException 提案违反「最小编辑」约束时抛出
     */
    private RefineProposal parseProposal(String response) {
        String json = extractJson(response);

        // 护栏 1：拒绝多操作。JSON 数组意味着模型试图一次做多处改动
        if (json.startsWith("[")) {
            throw new IllegalArgumentException("提案包含多个操作，违反最小编辑约束");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("提案 JSON 解析失败: " + response);
        }

        if (node.has("actions") || node.isArray()) {
            throw new IllegalArgumentException("提案包含多个操作，违反最小编辑约束");
        }

        String action = node.path("action").asText("");
        if ("none".equalsIgnoreCase(action)) {
            return null;
        }
        if (!"upsert".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException("未知的 action: " + action);
        }

        String kindText = node.path("kind").asText("");
        HarnessChange.Kind kind;
        try {
            kind = HarnessChange.Kind.valueOf(kindText.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 kind: " + kindText);
        }

        // 护栏 2：refine 不得改动子 Agent 规范（影响面过大，不属于最小编辑）
        if (kind == HarnessChange.Kind.SUBAGENT) {
            throw new IllegalArgumentException("refine 不允许改动 subagent 规范");
        }

        String targetId = node.path("target_id").asText("");
        String content = node.path("content").asText("");
        if (targetId.isBlank() || content.isBlank()) {
            throw new IllegalArgumentException("提案缺少 target_id 或 content");
        }

        return new RefineProposal(kind, targetId.trim(), content, node.path("reason").asText(""));
    }

    /**
     * 校验提案是否满足配额等约束
     *
     * @return 违规说明，通过校验时返回空
     */
    private Optional<String> validate(String workDirPath, RefineProposal proposal) {
        if (proposal.kind() != HarnessChange.Kind.PROMPT) {
            return Optional.empty();
        }

        if (proposal.content().length() > MAX_SINGLE_NOTE_CHARS) {
            return Optional.of(String.format("单条补充指令 %d 字符，超过上限 %d",
                    proposal.content().length(), MAX_SINGLE_NOTE_CHARS));
        }

        // 护栏 4：补充层总量配额
        String existing = harnessStore.readPromptNote(workDirPath, proposal.targetId());
        int delta = proposal.content().length() - (existing != null ? existing.length() : 0);
        return harnessStore.checkPromptNoteBudget(workDirPath, delta);
    }

    /**
     * 获取 refine 使用的 LLM，未配置时回退默认模型
     */
    private LLM getRefineLLM() {
        String model = refineConfig.getModel();
        if (model != null && !model.isEmpty()) {
            return llmFactory.getOrCreateLLM(model);
        }
        return llmFactory.getOrCreateLLM(null);
    }

    /**
     * 从响应中提取 JSON 片段，兼容 markdown code fence
     */
    private String extractJson(String response) {
        String trimmed = response.trim();

        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n") + 1;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        int arrayStart = trimmed.indexOf('[');
        int braceStart = trimmed.indexOf('{');
        // 数组先于对象出现时保留数组形态，交由护栏识别为多操作并拒绝
        if (arrayStart >= 0 && (braceStart < 0 || arrayStart < braceStart)) {
            return trimmed.substring(arrayStart);
        }

        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }

    /**
     * 模型提案的内部表示
     */
    private record RefineProposal(HarnessChange.Kind kind, String targetId, String content, String reason) {
    }

    /**
     * refine 执行结果
     *
     * @param status  结果状态
     * @param message 结果说明
     * @param change  实际产生的审计记录，仅 {@link Status#APPLIED} 时非空
     */
    public record RefineResult(Status status, String message, HarnessChange change) {

        public enum Status {
            /** 已应用改进 */
            APPLIED,
            /** 无需改进或未启用 */
            SKIPPED,
            /** 提案违反护栏被拒绝 */
            REJECTED,
            /** 执行失败 */
            FAILED
        }

        public static RefineResult applied(HarnessChange change, String reason) {
            return new RefineResult(Status.APPLIED, reason, change);
        }

        public static RefineResult skipped(String message) {
            return new RefineResult(Status.SKIPPED, message, null);
        }

        public static RefineResult rejected(String message) {
            return new RefineResult(Status.REJECTED, message, null);
        }

        public static RefineResult failed(String message) {
            return new RefineResult(Status.FAILED, message, null);
        }

        public boolean isApplied() {
            return status == Status.APPLIED;
        }
    }
}
