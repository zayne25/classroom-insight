package com.zhiketang.service.ai;

import java.util.Map;

/**
 * 主观题 / 作文 的批改能力提供者。
 *
 * 设计目的：把"批改智能"做成可替换的适配层——
 *   - 默认实现 {@code LocalHeuristicAiProvider}：本地启发式引擎，离线可用；
 *   - {@code HttpLlmAiProvider}：配置 endpoint + api-key 后即可切换为真实大模型。
 * 这样在没有网络或密钥的环境下系统依然可演示，且接入大模型时无需改动业务代码。
 */
public interface AiProvider {

    /** 引擎/模型标识，写入 ai_grading_results.model 便于溯源 */
    String id();

    /** 当前是否可用（未配置 endpoint 时不可用，自动降级到本地引擎） */
    boolean available();

    /**
     * @param assignType 主观题 / 作文
     * @param content    学生提交内容
     * @param rubric     评分标准（关键词、最低字数等）
     * @param maxScore   满分
     */
    AiGradeResult grade(String assignType, String content, Map<String, Object> rubric, int maxScore);
}
