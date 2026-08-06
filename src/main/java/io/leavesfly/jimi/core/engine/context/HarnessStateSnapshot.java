package io.leavesfly.jimi.core.engine.context;

import io.leavesfly.jimi.harness.HarnessJournal;
import io.leavesfly.jimi.harness.HarnessStore;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * harness 状态快照注入器
 * <p>
 * 解决「写了当场读不到」的断裂：系统提示词在 {@code AgentRegistry.renderSystemPrompt()}
 * 时一次性渲染，{@code JIMI_MEMORY_SUMMARY} / {@code JIMI_SKILLS_SUMMARY} 也只在
 * {@code createBuiltinArgs()} 时计算一次。因此 Agent 中途用 {@code Memory} 写了记忆、
 * 用 {@code Skills} 建了技能，本次会话内都读不到，必须等下次启动 —— 自我改进的
 * 写-读回路是断的。
 * <p>
 * <b>为什么不重渲染系统提示词</b>：system prompt 是 LLM 的 KV 前缀缓存基准，每轮变动
 * 会让缓存全量失效，长会话成本显著上升。因此这里把快照作为一条消息注入历史末尾，
 * 基础提示词保持不可变，前缀缓存不受影响。
 * <p>
 * <b>为什么用瞬态消息</b>：快照是可从 {@code .jimi/harness/}、MEMORY.md 与技能注册表
 * 随时重算的派生状态。写入 append-only 的 JSONL 会让「去重」与「持久化一致性」二者
 * 不可兼得，因此只注入内存。
 */
@Slf4j
@Component
public class HarnessStateSnapshot {

    /** 快照消息的固定前缀，用于去重识别 */
    static final String SNAPSHOT_PREFIX = "[Harness State]";

    /** 快照内容上限（字符），超出则截断并提示改用工具读全文 */
    private static final int MAX_SNAPSHOT_CHARS = 8000;

    private final HarnessJournal harnessJournal;
    private final HarnessStore harnessStore;
    private final MemoryManager memoryManager;
    private final SkillRegistry skillRegistry;

    @Autowired
    public HarnessStateSnapshot(HarnessJournal harnessJournal,
                                HarnessStore harnessStore,
                                MemoryManager memoryManager,
                                SkillRegistry skillRegistry) {
        this.harnessJournal = harnessJournal;
        this.harnessStore = harnessStore;
        this.memoryManager = memoryManager;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 若 harness 状态自上次注入后发生过变更，则刷新快照
     * <p>
     * 未变更时不注入，避免每轮无谓地增加 token。
     *
     * @param context     目标上下文
     * @param workDirPath 工作目录绝对路径
     * @return 是否实际注入了快照
     */
    public boolean refreshIfDirty(Context context, String workDirPath) {
        if (context == null || workDirPath == null) {
            return false;
        }
        if (!harnessJournal.consumeDirty()) {
            return false;
        }
        return refresh(context, workDirPath);
    }

    /**
     * 强制刷新快照（先移除旧快照，再注入新快照）
     *
     * @param context     目标上下文
     * @param workDirPath 工作目录绝对路径
     * @return 是否实际注入了快照（内容为空时不注入）
     */
    public boolean refresh(Context context, String workDirPath) {
        // 先移除已有快照，保证历史中至多一条，不随步数累积
        int removed = context.removeTransientMessages(HarnessStateSnapshot::isSnapshotMessage);

        String content = render(workDirPath);
        if (content.isEmpty()) {
            if (removed > 0) {
                log.debug("Harness state snapshot cleared ({} stale removed)", removed);
            }
            return false;
        }

        List<ContentPart> parts = new ArrayList<>();
        parts.add(TextPart.of(content));
        Message snapshot = Message.builder()
                .role(MessageRole.ASSISTANT)
                .content(parts)
                .build();

        context.appendTransientMessage(snapshot);
        log.info("Harness state snapshot injected ({} chars, {} stale removed)", content.length(), removed);
        return true;
    }

    /**
     * 渲染当前 harness 状态
     *
     * @return 快照文本，无任何内容时返回空串
     */
    String render(String workDirPath) {
        String promptNotes = harnessStore.renderPromptNotes(workDirPath);
        String memorySummary = memoryManager.getConfig().isEnabled()
                ? memoryManager.getMemorySummary(workDirPath)
                : "";
        String skillsSummary = skillRegistry != null ? skillRegistry.generateSkillsSummary() : "";

        if (isBlank(promptNotes) && isBlank(memorySummary) && isBlank(skillsSummary)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(SNAPSHOT_PREFIX)
                .append(" 以下是当前会话内最新的 harness 状态（含本次会话中的变更），优先以此为准：\n");

        appendSection(sb, "补充指令（prompt notes）", promptNotes);
        appendSection(sb, "长期记忆", memorySummary);
        appendSection(sb, "可用技能", skillsSummary);

        String content = sb.toString();
        if (content.length() > MAX_SNAPSHOT_CHARS) {
            content = content.substring(0, MAX_SNAPSHOT_CHARS)
                    + "\n\n[harness 状态过长已截断，使用 Memory(action=read) 或 Skills(action=list) 读取全文]";
        }
        return content;
    }

    private void appendSection(StringBuilder sb, String title, String body) {
        if (isBlank(body)) {
            return;
        }
        sb.append("\n## ").append(title).append("\n").append(body.strip()).append("\n");
    }

    /**
     * 判断是否为本类注入的快照消息
     */
    static boolean isSnapshotMessage(Message message) {
        if (message == null || message.getRole() != MessageRole.ASSISTANT) {
            return false;
        }
        String text = message.getTextContent();
        return text != null && text.startsWith(SNAPSHOT_PREFIX);
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
