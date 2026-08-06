package io.leavesfly.jimi.harness;

/**
 * harness 组件的读写目标抽象
 * <p>
 * {@link HarnessJournal} 只负责审计与回滚编排，实际的落地读写委托给本接口，
 * 从而让审计层不依赖任何具体的存储实现（MEMORY.md、SKILL.md、prompt notes 等）。
 */
public interface HarnessTarget {

    /**
     * 是否支持该组件类型
     */
    boolean supports(HarnessChange.Kind kind);

    /**
     * 读取当前内容，用于捕获变更前快照
     *
     * @param workDirPath 工作目录绝对路径
     * @param kind        组件类型
     * @param targetId    目标标识
     * @return 当前内容，不存在时返回 {@code null}
     */
    String read(String workDirPath, HarnessChange.Kind kind, String targetId);

    /**
     * 写入内容（不存在则创建，存在则覆盖）
     *
     * @param workDirPath 工作目录绝对路径
     * @param kind        组件类型
     * @param targetId    目标标识
     * @param content     待写入内容
     */
    void write(String workDirPath, HarnessChange.Kind kind, String targetId, String content);

    /**
     * 删除目标
     *
     * @param workDirPath 工作目录绝对路径
     * @param kind        组件类型
     * @param targetId    目标标识
     */
    void delete(String workDirPath, HarnessChange.Kind kind, String targetId);
}
