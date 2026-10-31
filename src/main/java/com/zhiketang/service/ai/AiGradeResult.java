package com.zhiketang.service.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批改引擎的统一输出结构：分数 + 置信度 + 总体评语 + 逐项反馈
 */
@Data
public class AiGradeResult {

    /** AI 初评分数 */
    private double score;

    /** 置信度 0~1，低于阈值的结果会被强制标记为需人工复核 */
    private double confidence;

    /** 总体评语 */
    private String comment;

    /** 逐项反馈：得分点、错误定位与修改建议 */
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        /** 得分点名称，如「关键词：索引」「第3题」「必需结构：循环」 */
        private String name;
        /** 本项得分 */
        private double got;
        /** 本项满分 */
        private double max;
        /** 是否命中 */
        private boolean hit;
        /** 错误定位（未命中时说明问题所在） */
        private String error;
        /** 修改建议 */
        private String tip;

        public Item(String name, double got, double max, boolean hit, String error, String tip) {
            this.name = name;
            this.got = got;
            this.max = max;
            this.hit = hit;
            this.error = error;
            this.tip = tip;
        }
    }
}
