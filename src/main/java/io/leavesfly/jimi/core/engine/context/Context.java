package io.leavesfly.jimi.core.engine.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.skill.SkillSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 上下文管理器
 * 负责维护对话历史、Token 计数和检查点机制
 * <p>
 * 功能特性：
 * 1. 消息历史管理（追加、查询、清空）
 * 2. Token 计数追踪
 * 3. 检查点机制（创建、回退）
 * 4. 持久化委托给 ContextRepository
 * 5. 上下文恢复
 * <p>
 * 设计改进（v2.0）：
 * - 分离持久化层：通过 ContextRepository 接口解耦存储实现
 * - 单一职责：仅负责业务逻辑，不涉及文件 I/O
 * - 依赖倒置：依赖接口而非具体实现
 */
@Slf4j
public class Context {

    private final ContextRepository repository;

    /**
     * 消息历史列表（只读视图对外暴露）
     */
    private final List<Message> history;

    /**
     * Token 计数
     */
    private int tokenCount;

    /**
     * 下一个检查点 ID（从 0 开始递增）
     */
    private int nextCheckpointId;

    /**
     * 激活的 Skills 列表
     */
    private final List<SkillSpec> activeSkills;

    /**
     * 最近一次回退（含上下文压缩）产生的归档文件路径
     * <p>
     * 压缩时原始历史会被轮转保存，此字段用于让上层构造溯源锚点，
     * 告知模型压缩前的完整历史仍可通过 Memory 搜索检索。
     */
    private Path lastArchivedPath;


    /**
     * 构造器（可选择Repository实现）
     *
     * @param fileBackend  文件后端路径
     * @param objectMapper JSON序列化工具
     */
    public Context(Path fileBackend, ObjectMapper objectMapper) {
        this.repository = new JSONLContextRepository(fileBackend, objectMapper);
        this.history = new ArrayList<>();
        this.tokenCount = 0;
        this.nextCheckpointId = 0;
        this.activeSkills = new ArrayList<>();
    }


    /**
     * 从持久化存储恢复上下文（异步版本）
     *
     * @return 是否成功恢复（true 表示恢复了数据，false 表示文件不存在或为空）
     */
    public Mono<Boolean> restore() {
        return repository.restore()
                .map(restoredContext -> {
                    // 恢复到内存
                    history.addAll(restoredContext.getMessages());
                    this.tokenCount = restoredContext.getTokenCount();
                    this.nextCheckpointId = restoredContext.getNextCheckpointId();

                    boolean hasData = !restoredContext.getMessages().isEmpty();
                    if (hasData) {
                        log.info("Restored context: {} messages, {} tokens, {} checkpoints",
                                history.size(), tokenCount, nextCheckpointId);
                    }
                    return hasData;
                })
                .onErrorResume(e -> {
                    log.error("Failed to restore context", e);
                    return Mono.just(false);
                });
    }

    /**
     * 追加单条或多条消息到上下文
     *
     * @param messages 单条消息或消息列表
     */
    @SuppressWarnings("unchecked")
    public Mono<Void> appendMessage(Object messages) {
        return Mono.defer(() -> {
            log.debug("Appending message(s) to context: {}", messages);

            List<Message> messageList;
            if (messages instanceof Message) {
                messageList = Collections.singletonList((Message) messages);
            } else if (messages instanceof List) {
                // 类型安全检查：验证 List 中的元素类型
                List<?> rawList = (List<?>) messages;
                messageList = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Message) {
                        messageList.add((Message) item);
                    } else if (item != null) {
                        log.warn("Skipping non-Message item in list: {}", item.getClass().getName());
                    }
                }
                if (messageList.isEmpty() && !rawList.isEmpty()) {
                    throw new IllegalArgumentException(
                            "List does not contain any valid Message objects");
                }
            } else {
                throw new IllegalArgumentException("Messages must be Message or List<Message>");
            }


            // 添加到内存
            history.addAll(messageList);

            // 持久化
            return repository.appendMessages(messageList);
        });
    }

    /**
     * 更新 Token 计数并持久化
     *
     * @param count 新的 token 计数
     */
    public Mono<Void> updateTokenCount(int count) {
        return Mono.defer(() -> {
            log.debug("Updating token count in context: {}", count);
            this.tokenCount = count;

            // 持久化
            return repository.updateTokenCount(count);
        });
    }

    /**
     * 创建检查点
     *
     * @param addUserMessage 是否添加用户可见的检查点消息
     * @return 检查点 ID
     */
    public Mono<Integer> checkpoint(boolean addUserMessage) {
        return Mono.defer(() -> {
            int checkpointId = nextCheckpointId++;
            log.debug("Checkpointing, ID: {}", checkpointId);

            // 持久化检查点
            return repository.saveCheckpoint(checkpointId)
                    .then(Mono.defer(() -> {
                        // 可选：添加用户可见的检查点消息
                        if (addUserMessage) {
                            Message checkpointMessage = Message.builder()
                                    .role(MessageRole.USER)
                                    .content(List.of(TextPart.of("<system>CHECKPOINT " + checkpointId + "</system>")))
                                    .build();
                            return appendMessage(checkpointMessage).thenReturn(checkpointId);
                        }
                        return Mono.just(checkpointId);
                    }));
        });
    }

    /**
     * 回退到指定检查点
     * 回退后，指定检查点及之后的所有内容将被移除
     * 原文件会被轮转保存
     *
     * @param checkpointId 检查点 ID（0 是第一个检查点）
     */
    public Mono<Void> revertTo(int checkpointId) {
        return Mono.defer(() -> {
            log.debug("Reverting checkpoint, ID: {}", checkpointId);

            if (checkpointId >= nextCheckpointId) {
                return Mono.error(new IllegalArgumentException(
                        "Checkpoint " + checkpointId + " does not exist"
                ));
            }

            // 使用 repository 执行回退
            return repository.revertToCheckpoint(checkpointId)
                    .map(restoredContext -> {
                        // 清空并恢复内存状态
                        history.clear();
                        history.addAll(restoredContext.getMessages());
                        this.tokenCount = restoredContext.getTokenCount();
                        this.nextCheckpointId = restoredContext.getNextCheckpointId();
                        this.lastArchivedPath = restoredContext.getArchivedPath();

                        log.info("Reverted to checkpoint {}: {} messages, {} tokens",
                                checkpointId, history.size(), tokenCount);
                        return restoredContext;
                    })
                    .then();
        });
    }

    /**
     * 获取最近一次回退（含上下文压缩）产生的归档文件路径
     *
     * @return 归档文件路径，从未发生回退时为 {@code null}
     */
    public Path getLastArchivedPath() {
        return lastArchivedPath;
    }

    /**
     * 获取消息历史（只读视图）
     */
    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
     * 追加一条瞬态消息（仅内存，不持久化）
     * <p>
     * 适用于可从外部状态重算的<b>派生信息</b>（如 harness 状态快照）。
     * 这类消息不写入 append-only 的 JSONL：一旦写入，后续去重就会造成
     * 内存与磁盘不一致；而不写入则可在需要时随时重建。
     *
     * @param message 待追加的瞬态消息
     */
    public void appendTransientMessage(Message message) {
        if (message != null) {
            history.add(message);
        }
    }

    /**
     * 移除满足条件的瞬态消息（仅内存）
     * <p>
     * 谓词必须只能匹配由 {@link #appendTransientMessage(Message)} 注入的消息，
     * 否则会造成内存历史与持久化历史偏离。
     *
     * @param predicate 匹配条件
     * @return 实际移除的消息数
     */
    public int removeTransientMessages(Predicate<Message> predicate) {
        if (predicate == null) {
            return 0;
        }
        int before = history.size();
        history.removeIf(predicate);
        return before - history.size();
    }

    /**
     * 获取 Token 计数
     */
    public int getTokenCount() {
        return tokenCount;
    }

    /**
     * 获取检查点数量
     */
    public int getnCheckpoints() {
        return nextCheckpointId;
    }

    /**
     * 添加激活的 Skills
     *
     * @param skills 要添加的 Skills 列表
     * @return 完成的 Mono
     */
    public Mono<Void> addActiveSkills(List<SkillSpec> skills) {
        return Mono.defer(() -> {
            if (skills == null || skills.isEmpty()) {
                return Mono.empty();
            }

            // 去重添加
            for (SkillSpec skill : skills) {
                if (!isSkillActive(skill.getName())) {
                    activeSkills.add(skill);
                    log.debug("Added active skill: {}", skill.getName());
                }
            }

            return Mono.empty();
        });
    }

    /**
     * 获取激活的 Skills
     *
     * @return Skills 列表（只读视图）
     */
    public List<SkillSpec> getActiveSkills() {
        return Collections.unmodifiableList(activeSkills);
    }

    /**
     * 检查某个 Skill 是否已激活
     *
     * @param skillName Skill 名称
     * @return 是否已激活
     */
    public boolean isSkillActive(String skillName) {
        return activeSkills.stream()
                .anyMatch(skill -> skill.getName().equals(skillName));
    }

    /**
     * 清除所有激活的 Skills
     *
     * @return 完成的 Mono
     */
    public Mono<Void> clearActiveSkills() {
        return Mono.defer(() -> {
            int count = activeSkills.size();
            activeSkills.clear();
            log.debug("Cleared {} active skills", count);
            return Mono.empty();
        });
    }

    // ==================== 内部辅助方法 ====================
}
