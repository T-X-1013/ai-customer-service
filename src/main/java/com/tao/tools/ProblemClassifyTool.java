package com.tao.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.BiFunction;

/**
 * 客户异议“问题归类”工具
 * 之前的处理逻辑：多条问题 + 一次检索 + 一次回答，query 过长、语义混在一起，召回会变差
 * 现在按单个问题循环调用外部传入的分类函数，并合并结果
 */
@Slf4j
@Component
public class ProblemClassifyTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对多个已抽取的客户问题进行分类。
     *
     * @param info           客服与客户的对话文本
     * @param problemsJson   已抽取好的问题列表 JSON 字符串：
     *                       [
     *                         {"问题": "...", "原文摘要": "...", "解释": "..."},
     *                         {"问题": "...", "原文摘要": "...", "解释": "..."}
     *                       ]
     * @param singleClassifier 单问题分类函数，由 ServiceApp 提供：
     *                         (info, oneProblemJson) -> 单问题 RAG 输出 JSON 字符串
     * @return JSON 数组字符串：
     *         [
     *           {"针对的问题": "...", "问题大类编号": "...", ...},
     *           {"针对的问题": "...", "问题大类编号": "...", ...}
     *         ]
     */
    public String doClassify(String info,
                                  String problemsJson,
                                  BiFunction<String, String, String> singleClassifier) {
        if (problemsJson == null || problemsJson.isBlank()) {
            log.warn("问题列表problems为空");
            return "[]";
        }

        try {
            JsonNode root = objectMapper.readTree(problemsJson);

            // 如果不是数组，当成“单个问题”处理
            if (!root.isArray()) {
                String oneResult = singleClassifier.apply(info, problemsJson);
                return oneResult == null || oneResult.isBlank() ? "[]" : oneResult;
            }

            ArrayNode problemArray = (ArrayNode) root;
            ArrayNode resultArray = objectMapper.createArrayNode();

            for (JsonNode item : problemArray) {
                String oneProblemJson = objectMapper.writeValueAsString(item);
                log.debug("开始对单个问题做 RAG 分类: {}", oneProblemJson);

                String oneResult = singleClassifier.apply(info, oneProblemJson);
                if (oneResult == null || oneResult.isBlank()) {
                    log.warn("单问题分类返回空结果，跳过。问题：{}", oneProblemJson);
                    continue;
                }

                try {
                    JsonNode parsed = objectMapper.readTree(oneResult.trim());
                    if (parsed.isArray()) {
                        for (JsonNode node : parsed) {
                            resultArray.add(node);
                        }
                    } else if (!parsed.isNull()) {
                        resultArray.add(parsed);
                    }
                } catch (Exception e) {
                    log.warn("解析单问题分类结果失败，跳过本条。原始结果: {}", oneResult, e);
                }
            }

            String finalResult = objectMapper.writeValueAsString(resultArray);
            log.info("RAG 分类最终合并结果: {}", finalResult);
            return finalResult;

        } catch (Exception e) {
            log.error("doClassify 解析 problemsJson 失败，直接返回空数组", e);
            return "[]";
        }
    }


      /**
       * 清洗模型输出：去掉 ``` 包裹、XML/HTML 标签，并截断到首个 { 或 [
      */
      private String cleanResult(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        // 去掉 ``` 包裹
        if (cleaned.startsWith("```")) {
            int firstLineBreak = cleaned.indexOf('\n');
            if (firstLineBreak > 0) {
                cleaned = cleaned.substring(firstLineBreak + 1);
            }
            if (cleaned.endsWith("```")) {
                 cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }
        // 去掉 <...> 标签（如 <think>、HTML 等）
        if (cleaned.contains("<") && cleaned.contains(">")) {
            cleaned = cleaned.replaceAll("<[^>]+>", "").trim();
        }
        // 只保留从首个 { 或 [ 开始的部分
        int brace = cleaned.indexOf('{');
        int bracket = cleaned.indexOf('[');
        int start = -1;
        if (brace >= 0 && bracket >= 0) {
            start = Math.min(brace, bracket);
        } else if (brace >= 0) {
            start = brace;
        } else if (bracket >= 0) {
            start = bracket;
        }
        if (start > 0) {
            cleaned = cleaned.substring(start).trim();
        }
         // 非 JSON 开头直接判空
        if (!(cleaned.startsWith("{") || cleaned.startsWith("["))) {
             return null;
        }
        return cleaned;
      }

}
