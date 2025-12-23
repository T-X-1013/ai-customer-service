package com.tao.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tao.app.ServiceApp;
import com.tao.tools.InvalidAnswerValidatorTool;
import com.tao.tools.ProblemResolvedTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagFullProcessService {

    private final ServiceApp serviceApp;
    private final InvalidAnswerValidatorTool invalidAnswerValidatorTool;
    private final ProblemResolvedTool problemResolvedTool;
    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 主流程：
     * 1) 执行 RAG 分类
     * 2) Validator 判断有效性（第一阶段）
     * 3) 无效回答直接写入 failed_cases
     * 4) 有效回答进入第二阶段：判断是否解决问题
     * 5) 根据解决性判断结果分别存入 success_cases 或 failed_cases
     * 6) 返回完整的判断结果
     */
    public String processInfo(String info) {
        try {
            // 调用 RAG
            String ragJson = serviceApp.doClassifyWithRag(info);
            log.info("RAG原始输出: {}", ragJson);

            if (ragJson == null || ragJson.isBlank() || ragJson.equals("[]")) {
                log.warn("RAG 输出为空 → 自动记录失败案例");
                insertFailedCase(info, "{}", null, null, "RAG结果为空");
                return "[]";
            }

            // 调用 Validator 自动判断
            String validatorJson = invalidAnswerValidatorTool.validateBatch(info, ragJson);
            log.info("Validator 合并结果: {}", validatorJson);

            List<Map<String, Object>> validatorList =
                    objectMapper.readValue(validatorJson, new TypeReference<>() {});

            // 第一阶段：处理无效回答，直接存入 failed_cases
            List<Map<String, Object>> validAnswers = new ArrayList<>();
            
            for (Map<String, Object> obj : validatorList) {
                String valid = (String) obj.getOrDefault("判断回答是否有效", "");
                
                if ("否".equals(valid)) {
                    // 无效回答直接存入失败案例表
                    String orderedRagObjectJson = RagObjectOrderedBuilder.buildOrderedJson(obj);
                    insertFailedCase(
                            info,
                            orderedRagObjectJson,
                            (String) obj.get("针对的问题"),
                            (String) obj.get("客服回答"),
                            (String) obj.get("判断原因")
                    );
                } else if ("是".equals(valid)) {
                    // 有效回答保存，进入第二阶段判断
                    validAnswers.add(obj);
                }
            }

            // 第二阶段：对有效回答判断是否解决了问题
            if (!validAnswers.isEmpty()) {
                processValidAnswers(info, validAnswers);
            }

            // 返回 validator 输出（已排序）
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(validatorList);

        } catch (Exception e) {
            log.error("处理 info 失败", e);

            insertFailedCase(info, "{}", null, null, "处理异常: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * 第二阶段：处理有效回答，判断是否解决了问题
     */
    private void processValidAnswers(String info, List<Map<String, Object>> validAnswers) {
        try {
            // 将有效回答转为 JSON 字符串
            String validAnswersJson = objectMapper.writeValueAsString(validAnswers);
            
            // 调用 ProblemResolvedTool 批量判断是否解决问题
            String resolvedResultJson = problemResolvedTool.validateResolved(info, validAnswersJson);
            log.info("问题解决性判断结果: {}", resolvedResultJson);
            
            // 解析判断结果
            JsonNode resultArray = objectMapper.readTree(resolvedResultJson);
            if (!resultArray.isArray() || resultArray.size() == 0) {
                log.warn("问题解决性判断结果为空");
                return;
            }
            
            // 遍历结果，根据是否解决问题存入不同的表
            for (JsonNode obj : resultArray) {
                String resolved = obj.path("判断是否解决问题").asText("");
                String resolvedReason = obj.path("解决问题的判断原因").asText("");
                
                // 构建严格字段顺序的 JSON
                String orderedJson = buildResolvedOrderedJson(obj);
                
                if ("是".equals(resolved)) {
                    // 解决了问题 → 存入成功案例表
                    insertSuccessCase(
                            info,
                            orderedJson,
                            obj.path("针对的问题").asText(""),
                            obj.path("客服回答").asText(""),
                            resolvedReason
                    );
                } else {
                    // 未解决问题 → 存入失败案例表
                    insertFailedCase(
                            info,
                            orderedJson,
                            obj.path("针对的问题").asText(""),
                            obj.path("客服回答").asText(""),
                            resolvedReason
                    );
                }
            }
            
        } catch (Exception e) {
            log.error("处理有效回答时异常", e);
        }
    }

    /**
     * 构建包含解决性判断的有序 JSON
     */
    private String buildResolvedOrderedJson(JsonNode obj) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"针对的问题\":\"").append(escapeJson(obj.path("针对的问题").asText(""))).append("\",");
            sb.append("\"问题大类编号\":\"").append(escapeJson(obj.path("问题大类编号").asText(""))).append("\",");
            sb.append("\"问题大类名称\":\"").append(escapeJson(obj.path("问题大类名称").asText(""))).append("\",");
            sb.append("\"问题小类编号\":\"").append(escapeJson(obj.path("问题小类编号").asText(""))).append("\",");
            sb.append("\"问题小类名称\":\"").append(escapeJson(obj.path("问题小类名称").asText(""))).append("\",");
            sb.append("\"客服回答\":\"").append(escapeJson(obj.path("客服回答").asText(""))).append("\",");
            sb.append("\"判断回答是否有效\":\"").append(escapeJson(obj.path("判断回答是否有效").asText(""))).append("\",");
            sb.append("\"判断原因\":\"").append(escapeJson(obj.path("判断原因").asText(""))).append("\",");
            sb.append("\"判断是否解决问题\":\"").append(escapeJson(obj.path("判断是否解决问题").asText(""))).append("\",");
            sb.append("\"解决问题的判断原因\":\"").append(escapeJson(obj.path("解决问题的判断原因").asText(""))).append("\",");
            sb.append("\"原文摘要\":\"").append(escapeJson(obj.path("原文摘要").asText(""))).append("\",");
            sb.append("\"解释\":\"").append(escapeJson(obj.path("解释").asText(""))).append("\"");
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            log.error("构建有序 JSON 失败", e);
            return "{}";
        }
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 插入成功案例
     */
    private void insertSuccessCase(String info, String ragObjectJson, String problem, String answer, String reason) {
        String sql = """
                INSERT INTO success_cases(info, rag_object, problem, answer, reason, created_at, updated_at)
                VALUES (?, ?::jsonb, ?, ?, ?, ?, ?)
                """;

        Timestamp now = new Timestamp(System.currentTimeMillis());

        try {
            jdbcTemplate.update(sql,
                    info,
                    ragObjectJson == null ? "{}" : ragObjectJson,
                    problem,
                    answer,
                    reason,
                    now,
                    now
            );
            log.info("✅ 成功案例已保存: {} - {}", problem, reason);
        } catch (Exception e) {
            log.error("保存成功案例失败", e);
        }
    }

    /**
     * 插入失败案例（rag_object 必须是 ordered JSON）
     */
    private void insertFailedCase(String info, String ragObjectJson, String problem, String answer, String reason) {

        String sql = """
                INSERT INTO failed_cases(info, rag_object, problem, answer, reason, created_at, updated_at)
                VALUES (?, ?::jsonb, ?, ?, ?, ?, ?)
                """;

        Timestamp now = new Timestamp(System.currentTimeMillis());

        try {
            jdbcTemplate.update(sql,
                    info,
                    ragObjectJson == null ? "{}" : ragObjectJson,
                    problem,
                    answer,
                    reason,
                    now,
                    now
            );
            log.info("❌ 失败案例已保存: {} - {}", problem, reason);
        } catch (Exception e) {
            log.error("保存失败案例失败", e);
        }
    }
}
