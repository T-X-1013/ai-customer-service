package com.tao.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tao.rag.RagAnswerValidatorAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InvalidAnswerValidatorTool {

    private final RagAnswerValidatorAgent answerAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 批量判断 RAG 输出中的回答是否有效
     *
     * @param info    原始对话全文
     * @param ragJson RAG 输出 JSON 数组
     * @return JSON 数组字符串，每条包含 "判断回答是否有效" 和 "判断原因"
     */
    public String validateBatch(String info, String ragJson) {
        if (ragJson == null || ragJson.isBlank()) {
            log.warn("RAG 输出为空 → 生成一条默认无效结果");
            return buildDefaultInvalidResult();
        }

        try {
            JsonNode root = objectMapper.readTree(ragJson);
            if (!root.isArray() || root.size() == 0) {
                log.warn("RAG 输出为空数组 → 生成一条默认无效结果");
                return buildDefaultInvalidResult();
            }

            ArrayNode resultArray = objectMapper.createArrayNode();

            for (JsonNode obj : root) {
                String problem = obj.path("针对的问题").asText("").trim();
                if (problem.isEmpty()) {
                    log.warn("RAG 对象缺少 '针对的问题'，跳过此项: {}", obj.toString());
                    continue;
                }

                String answer = obj.path("客服回答").asText("");
                String majorCode = obj.path("问题大类编号").asText("");
                String majorName = obj.path("问题大类名称").asText("");
                String minorCode = obj.path("问题小类编号").asText("");
                String minorName = obj.path("问题小类名称").asText("");
                String rawSummary = obj.path("原文摘要").asText("");
                String explain = obj.path("解释").asText("");

                // 回答为空 → 标记无效
                if (answer.isEmpty()) {
                    resultArray.add(buildFullResult(problem, majorCode, majorName,
                            minorCode, minorName, answer, "否", "客服无回答",
                            rawSummary, explain));
                    continue;
                }

                // 调用 Validator LLM
                String validatorResult = answerAgent.validateAnswerWithRagResult(
                        "[" + obj.toString() + "]", problem, answer
                );

                // 清理模型输出
                validatorResult = cleanResult(validatorResult);
                if (validatorResult == null || validatorResult.isBlank()) {
                    log.warn("单问题 Validator 输出为空或非JSON → 标记无效");
                    resultArray.add(buildFullResult(problem, majorCode, majorName,
                            minorCode, minorName, answer, "否", "LLM 输出不可解析",
                            rawSummary, explain));
                    continue;
                }

                // 解析 Validator 输出
                try {
                    JsonNode parsed = objectMapper.readTree(validatorResult);
                    JsonNode finalObj = parsed.isArray() ? parsed.get(0) : parsed;
                    String valid = finalObj.path("判断回答是否有效").asText("");
                    String reason = finalObj.path("判断原因").asText("");

                    resultArray.add(buildFullResult(problem, majorCode, majorName,
                            minorCode, minorName, answer, valid, reason,
                            rawSummary, explain));
                } catch (Exception e) {
                    log.warn("Validator 输出解析失败: {}", validatorResult);
                    resultArray.add(buildFullResult(problem, majorCode, majorName,
                            minorCode, minorName, answer, "否", "LLM 输出不可解析",
                            rawSummary, explain));
                }
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultArray);

        } catch (Exception e) {
            log.error("validateBatch 异常，返回空数组", e);
            return buildDefaultInvalidResult();
        }
    }

    /**
     * 清理模型输出：去掉 ``` 包裹、XML/HTML 标签，截断到首个 { 或 [
     */
    private String cleanResult(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim();

        // 去掉 ``` 包裹
        if (cleaned.startsWith("```")) {
            int firstLineBreak = cleaned.indexOf('\n');
            if (firstLineBreak > 0) cleaned = cleaned.substring(firstLineBreak + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }

        // 去掉 <...> 标签
        cleaned = cleaned.replaceAll("<[^>]+>", "").trim();

        // 截取第一个 { 或 [
        int brace = cleaned.indexOf('{');
        int bracket = cleaned.indexOf('[');
        int start = -1;
        if (brace >= 0 && bracket >= 0) start = Math.min(brace, bracket);
        else if (brace >= 0) start = brace;
        else if (bracket >= 0) start = bracket;

        if (start >= 0) cleaned = cleaned.substring(start).trim();
        else return null;

        if (!(cleaned.startsWith("{") || cleaned.startsWith("["))) return null;

        return cleaned;
    }

    /**
     * 构建完整字段对象
     */
    private ObjectNode buildFullResult(
            String problem,
            String majorCode,
            String majorName,
            String minorCode,
            String minorName,
            String answer,
            String valid,
            String reason,
            String summary,
            String explain
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("针对的问题", problem);
        node.put("问题大类编号", majorCode);
        node.put("问题大类名称", majorName);
        node.put("问题小类编号", minorCode);
        node.put("问题小类名称", minorName);
        node.put("客服回答", answer);
        node.put("判断回答是否有效", valid);
        node.put("判断原因", reason);
        node.put("原文摘要", summary);
        node.put("解释", explain);
        return node;
    }

    /**
     * 默认无效结果
     */
    private String buildDefaultInvalidResult() {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(buildFullResult("", "", "", "", "", "",
                "否", "RAG 结果为空", "", ""));
        return arr.toString();
    }
}

