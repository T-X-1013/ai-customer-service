package com.tao.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tao.app.ServiceApp;
import com.tao.tools.InvalidAnswerValidatorTool;
import com.tao.tools.ProblemResolvedValidatorTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagFullProcessService {

    private final ServiceApp serviceApp;
    private final InvalidAnswerValidatorTool invalidAnswerValidatorTool;
    private final ProblemResolvedValidatorTool problemResolvedValidatorTool;
    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 主流程：
     * 1) 执行 RAG 分类
     * 2) Validator 判断有效性
     * 3) 写入所有无效回答到 failed_cases（严格字段顺序）
     * 4) 对有效回答进行第二阶段判断：是否解决问题
     * 5) 将未解决问题的案例写入 failed_cases
     * 6) 返回最终结果
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

            // 遍历所有无效回答，入库（严格字段顺序）
            for (Map<String, Object> obj : validatorList) {

                String valid = (String) obj.getOrDefault("判断回答是否有效", "");
                if (!"否".equals(valid))
                    continue;

                // 构建严格字段顺序 JSON
                String orderedRagObjectJson = RagObjectOrderedBuilder.buildOrderedJson(obj);

                insertFailedCase(
                        info,
                        orderedRagObjectJson,
                        (String) obj.get("针对的问题"),
                        (String) obj.get("客服回答"),
                        (String) obj.get("判断原因")
                );
            }

            // 第二阶段：有效回答进入第二个 LLM 判断
            // 筛选出有效的回答
            List<Map<String, Object>> validAnswers = validatorList.stream()
                    .filter(obj -> "是".equals(obj.getOrDefault("判断回答是否有效", "")))
                    .toList();

            if (validAnswers.isEmpty()) {
                log.info("没有有效回答，跳过第二阶段判断");
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(validatorList);
            }

            // 将有效回答转为 JSON，传给第二阶段判断
            String validAnswersJson = objectMapper.writeValueAsString(validAnswers);
            log.info("第二阶段输入（有效回答）: {}", validAnswersJson);

            // 调用第二阶段判断：是否解决问题
            String resolvedJson = problemResolvedValidatorTool.validateResolved(info, validAnswersJson);
            log.info("第二阶段输出（问题解决性判断）: {}", resolvedJson);

            List<Map<String, Object>> resolvedList =
                    objectMapper.readValue(resolvedJson, new TypeReference<>() {});

            // 遍历所有未解决问题的案例，入库
            for (Map<String, Object> obj : resolvedList) {
                String resolved = (String) obj.getOrDefault("判断是否解决问题", "");
                if (!"否".equals(resolved))
                    continue;

                // 构建严格字段顺序 JSON
                String orderedRagObjectJson = RagObjectOrderedBuilder.buildOrderedJson(obj);

                insertFailedCase(
                        info,
                        orderedRagObjectJson,
                        (String) obj.get("针对的问题"),
                        (String) obj.get("客服回答"),
                        "第二阶段：" + (String) obj.get("解决问题的判断原因")
                );
            }

            // 返回最终结果（合并两阶段判断）
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedList);

        } catch (Exception e) {
            log.error("处理 info 失败", e);

            insertFailedCase(info, "{}", null, null, "处理异常: " + e.getMessage());
            return "[]";
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

        jdbcTemplate.update(sql,
                info,
                ragObjectJson == null ? "{}" : ragObjectJson,
                problem,
                answer,
                reason,
                now,
                now
        );
    }
}
