package com.tao.failreason;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tao.app.ServiceApp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RagResultValidator {

    private final ObjectMapper objectMapper;
    private final ServiceApp serviceApp;

    public RagResultValidator(ObjectMapper objectMapper, ServiceApp serviceApp) {
        this.objectMapper = objectMapper;
        this.serviceApp = serviceApp;
    }

    /**
     * 判断doClassifyWithRag返回的内容是否有效
     * @param info doClassifyWithRag方法的输入参数
     * @return 如果数组为空，或者数组中任何一个对象的"针对的问题"字段为空，返回false；否则返回true
     */
    public boolean isValidRagResult(String info) {
        try {
            // 调用doClassifyWithRag方法获取结果
            String ragResult = serviceApp.doClassifyWithRag(info);
            log.info("doClassifyWithRag返回结果: {}", ragResult);

            // 解析JSON字符串为List<Map<String, Object>>
            List<Map<String, Object>> resultList = objectMapper.readValue(
                    ragResult,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            // 如果数组为空，返回false
            if (resultList.isEmpty()) {
                log.info("RAG结果数组为空，输入信息: {}", info);
                return false;
            }

            // 检查数组中每个对象的"针对的问题"字段是否为空
            for (Map<String, Object> result : resultList) {
                Object targetProblem = result.get("针对的问题");
                if (targetProblem == null || targetProblem.toString().trim().isEmpty()) {
                    log.info("RAG结果中存在'针对的问题'字段为空的对象，输入信息: {}", info);
                    return false;
                }
            }

            // 所有检查都通过，返回true
            return true;
        } catch (Exception e) {
            // 如果JSON解析失败，也返回false
            log.error("解析RAG结果失败，输入信息: {}, 错误信息: {}", info, e.getMessage());
            return false;
        }
    }
}