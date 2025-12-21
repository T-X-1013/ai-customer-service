package com.tao.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tao.rag.RagProblemResolvedAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 问题解决性判断工具
 * 用于判断有效的客服回答是否真正解决了客户的问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProblemResolvedValidatorTool {

    private final RagProblemResolvedAgent resolvedAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 批量判断有效回答是否解决了问题
     *
     * @param info            原始对话全文
     * @param validAnswersJson 有效回答的 JSON 数组（已经过第一阶段"是否有效"判断的结果）
     * @return JSON 数组字符串，每条新增 "判断是否解决问题" 和 "解决问题的判断原因"
     */
    public String validateResolved(String info, String validAnswersJson) {
        if (validAnswersJson == null || validAnswersJson.isBlank()) {
            log.warn("有效回答输入为空");
            return "[]";
        }

        try {
            JsonNode root = objectMapper.readTree(validAnswersJson);
            if (!root.isArray() || root.size() == 0) {
                log.warn("有效回答为空数组");
                return "[]";
            }

            ArrayNode resultArray = objectMapper.createArrayNode();

            for (JsonNode obj : root) {
                String problem = obj.path("针对的问题").asText("").trim();
                String answer = obj.path("客服回答").asText("");
                String majorCode = obj.path("问题大类编号").asText("");
                String majorName = obj.path("问题大类名称").asText("");
                String minorCode = obj.path("问题小类编号").asText("");
                String minorName = obj.path("问题小类名称").asText("");
                String rawSummary = obj.path("原文摘要").asText("");
                String explain = obj.path("解释").asText("");
                String valid = obj.path("判断回答是否有效").asText("");
                String validReason = obj.path("判断原因").asText("");

                // 调用 LLM 判断是否解决问题
                String resolvedResult = resolvedAgent.validateProblemResolved(
                        "[" + obj.toString() + "]", problem, answer
                );

                // 清理模型输出
                resolvedResult = cleanResult(resolvedResult);
                if (resolvedResult == null || resolvedResult.isBlank()) {
                    log.warn("问题解决性 Validator 输出为空或非JSON → 标记为未解决");
                    resultArray.add(buildFullResult(
                            problem, majorCode, majorName, minorCode, minorName,
                            answer, valid, validReason, "否", "LLM 输出不可解析",
                            rawSummary, explain
                    ));
                    continue;
                }

                // 解析 Validator 输出
                try {
                    JsonNode parsed = objectMapper.readTree(resolvedResult);
                    JsonNode finalObj = parsed.isArray() ? parsed.get(0) : parsed;
                    String resolved = finalObj.path("判断是否解决问题").asText("");
                    String resolvedReason = finalObj.path("解决问题的判断原因").asText("");

                    resultArray.add(buildFullResult(
                            problem, majorCode, majorName, minorCode, minorName,
                            answer, valid, validReason, resolved, resolvedReason,
                            rawSummary, explain
                    ));
                } catch (Exception e) {
                    log.warn("问题解决性 Validator 输出解析失败: {}", resolvedResult);
                    resultArray.add(buildFullResult(
                            problem, majorCode, majorName, minorCode, minorName,
                            answer, valid, validReason, "否", "LLM 输出不可解析",
                            rawSummary, explain
                    ));
                }
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultArray);

        } catch (Exception e) {
            log.error("validateResolved 异常", e);
            return "[]";
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
     * 构建完整字段对象（包含问题解决性判断）
     */
    private ObjectNode buildFullResult(
            String problem,
            String majorCode,
            String majorName,
            String minorCode,
            String minorName,
            String answer,
            String valid,
            String validReason,
            String resolved,
            String resolvedReason,
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
        node.put("判断原因", validReason);
        node.put("判断是否解决问题", resolved);
        node.put("解决问题的判断原因", resolvedReason);
        node.put("原文摘要", summary);
        node.put("解释", explain);
        return node;
    }
}
