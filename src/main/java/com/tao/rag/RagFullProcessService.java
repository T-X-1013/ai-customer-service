package com.tao.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tao.app.ServiceApp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagFullProcessService {

    private final ServiceApp serviceApp;
    private final RagResultValidator validator;
    private final RagAnswerValidatorAgent answerAgent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 输入对话全文 info，调用 RAG 获取问题列表，并判断回答是否有效
     */
    public List<Map<String, Object>> processInfo(String info) {
        try {
            // 1. 获取 RAG 原始输出
            String ragJson = serviceApp.doClassifyWithRag(info);
            log.info("RAG原始输出: {}", ragJson);

            // 2. 解析 JSON
            List<Map<String,Object>> ragList = objectMapper.readValue(
                    ragJson,
                    new TypeReference<List<Map<String,Object>>>() {}
            );

            if (validator.isEmptyList(ragList)) {
                return List.of();
            }

            // 3. 遍历每个 RAG 对象判断回答有效性
            for (Map<String,Object> obj : ragList) {
                obj.put("判断回答是否有效", "");
                obj.put("判断原因", "");

                if (!validator.isValidRagObject(obj)) {
                    obj.put("判断回答是否有效", "否");
                    obj.put("判断原因", "字段缺失或为空");
                    continue;
                }

                String problem = obj.get("针对的问题").toString();
                String answer = obj.get("客服回答").toString();

                boolean valid = answerAgent.isAnswerValid(problem, answer);
                if (valid) {
                    obj.put("判断回答是否有效", "是");
                    obj.put("判断原因", "");
                } else {
                    obj.put("判断回答是否有效", "否");
                    obj.put("判断原因", "回答无效");
                }
            }

            return ragList;

        } catch (Exception e) {
            log.error("处理 info 失败", e);
            return List.of();
        }
    }
}
