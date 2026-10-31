package com.zhiketang.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 大模型批改提供者：调用 OpenAI 兼容的 /chat/completions 接口。
 *
 * 启用方式（application.yml）：
 * <pre>
 * ai.grading.provider: http
 * ai.grading.endpoint: https://xxx/v1/chat/completions
 * ai.grading.api-key:  sk-xxx
 * ai.grading.model:    qwen-plus
 * </pre>
 * 未配置 endpoint 时 {@link #available()} 返回 false，系统自动降级到本地启发式引擎；
 * 调用失败时同样降级，保证批改流程不中断。
 */
@Slf4j
@Component("httpLlmAiProvider")
public class HttpLlmAiProvider implements AiProvider {

    @Value("${ai.grading.endpoint:}")
    private String endpoint;

    @Value("${ai.grading.api-key:}")
    private String apiKey;

    @Value("${ai.grading.model:qwen-plus}")
    private String model;

    @Value("${ai.grading.http-timeout-ms:15000}")
    private int timeoutMs;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper om = new ObjectMapper();

    @jakarta.annotation.PostConstruct
    void initTimeout() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(java.time.Duration.ofMillis(timeoutMs));
        rest.setRequestFactory(factory);
    }

    @Override
    public String id() {
        return model;
    }

    @Override
    public boolean available() {
        return endpoint != null && !endpoint.isBlank();
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiGradeResult grade(String assignType, String content, Map<String, Object> rubric, int maxScore) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system",
                "content", "你是一名高校课程助教，严格依据评分标准批改作业，只输出JSON，不要输出多余文字。"));
        messages.add(Map.of("role", "user", "content", buildPrompt(assignType, content, rubric, maxScore)));
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> resp = rest.exchange(endpoint, HttpMethod.POST, entity, String.class);
        Map<String, Object> json = readMap(resp.getBody());
        List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("大模型返回为空");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        String text = String.valueOf(msg.get("content"));

        // 模型可能用 ```json 包裹，做一次清洗
        text = text.replaceAll("(?s)^```(json)?", "").replaceAll("(?s)```$", "").trim();
        Map<String, Object> out = readMap(text);

        AiGradeResult r = new AiGradeResult();
        r.setScore(((Number) out.getOrDefault("score", 0)).doubleValue());
        r.setConfidence(((Number) out.getOrDefault("confidence", 0.7)).doubleValue());
        r.setComment(String.valueOf(out.getOrDefault("comment", "")));

        List<AiGradeResult.Item> items = new ArrayList<>();
        Object raw = out.get("items");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                Map<String, Object> m = (Map<String, Object>) o;
                items.add(new AiGradeResult.Item(
                        String.valueOf(m.getOrDefault("name", "")),
                        ((Number) m.getOrDefault("got", 0)).doubleValue(),
                        ((Number) m.getOrDefault("max", 0)).doubleValue(),
                        Boolean.parseBoolean(String.valueOf(m.getOrDefault("hit", false))),
                        String.valueOf(m.getOrDefault("error", "")),
                        String.valueOf(m.getOrDefault("tip", ""))));
            }
        }
        r.setItems(items);
        return r;
    }

    /** 解析 JSON 字符串，失败时抛出运行时异常（由上层捕获后降级到本地引擎） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String s) {
        try {
            return om.readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析大模型返回失败：" + e.getMessage(), e);
        }
    }

    private String buildPrompt(String assignType, String content, Map<String, Object> rubric, int maxScore) {
        return "作业类型：" + assignType + "\n"
                + "满分：" + maxScore + "\n"
                + "评分标准（JSON）：" + rubric + "\n"
                + "学生作答：\n" + content + "\n\n"
                + "请输出如下JSON：{\"score\":数值,\"confidence\":0~1,\"comment\":\"总体评语\","
                + "\"items\":[{\"name\":\"得分点\",\"got\":得分,\"max\":该项满分,\"hit\":true/false,"
                + "\"error\":\"错误定位\",\"tip\":\"修改建议\"}]}";
    }
}
