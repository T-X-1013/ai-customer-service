package com.tao.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 从客服对话文本 info 中抽取客户异议 problem 的工具类。
 * 最终返回的字符串是一个 JSON 数组：
 * [
 *   {"问题": "...", "原文摘要": "...", "解释": "..."},
 *   ...
 * ]
 */



@Slf4j
@Component
public class ObjectionExtractTool {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 系统提示词 system prompt
     */

    //修改成1-3种,第五条输出要求修改了， ServiceApp.classifySingleProblemWithRag 的 prompt 也修改了
    private static final String SYSTEM_PROMPT = """
            你是一个电信公司的客服总管，你将对一段客服与客户对话录音进行分析。
            你的主要任务是，精准分析该会话中1-3种最明显的客户提出的异议问题，并给出提出问题的原文语句内容和解释。
            客服与客户的录音放在<info></info>的XML标签内。
                
            输出结果要求：
                    1、必须给出1-3个最明显的客户异议问题，并针对每一个问题给出对应的原文摘要和解释。
                    2、无异议与其他异议互斥，有其他异议时，不能判断为无异议，没有其他异议时，则可判断为无异议。若客户没有提问，则直接判定为无问题。
                    3、输出结果必须是一个合法的 JSON 对象，且顶层只有一个字段：`异议列表`。`异议列表` 的值是一个长度为 1–3 的 JSON 数组。数组中的每个元素是一个对象，包含字段：`问题`、`原文摘要`、`解释`。
            例如：
            {
                "异议列表": [
                   {
                    "问题": "询问联通公众号的相关操作",
                    "原文摘要": "客户:这个联通公众号在哪里关注啊,是不是微信",
                    "解释": "客户询问关注公众号的渠道"
                   }
                ]
            }
                4、JSON 中必须全部使用半角英文标点（如逗号`,`和冒号`:`），不得使用中文全角标点。
                5、严禁输出 ```json 、思考/推理过程或 <think>或任何 Markdown 代码块包裹等，只能输出纯 JSON。
             
            """;

    public ObjectionExtractTool(@Qualifier("ollamaChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

//   5、严禁使用 ```json 或任何 Markdown 代码块包裹输出，不能输出解释性文字，只能输出纯 JSON。
    /**
     * 从 info 中抽取 problem 列表。
     *
     * @param info 客服与客户的对话文本
     * @return JSON 字符串：
     * - 输出示例：
     * [
     * {"问题": "...", "原文摘要": "...", "解释": "..."},
     * ...
     * ]
     * - 如果解析失败，则直接返回模型原始输出，由上层自己兜底处理。
     */
    public String extractProblems(String info) {
        // 按提示词要求包一层 <info></info>，其实也可以不包
        String userPrompt = "<info>\n" + info + "\n</info>";

        ChatResponse response = chatClient
                .prompt()
                .user(userPrompt)
                .call()
                .chatResponse();

        String raw = response.getResult().getOutput().getText();
        // 如果需要查看日志排查问题再开启，平时禁用，减少系统开销
        // log.info("异议抽取原始输出: {}", raw);
        // 需要排查模型输出时可打开

        // 1) 去掉可能的 ```json / ``` 包裹
        String cleaned = raw.trim();
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


        try {
            String payload = cleaned;

            // 去掉 <think>、<info> 等 XML/HTML 标签
            if (payload.contains("<") && payload.contains(">")) {
                payload = payload.replaceAll("<[^>]+>", "").trim();
            }

            // 截取到首个 { 或 [，丢弃前导说明文本
            int brace = payload.indexOf('{');
            int bracket = payload.indexOf('[');
            int start = -1;
            if (brace >= 0 && bracket >= 0) {
                start = Math.min(brace, bracket);
            } else if (brace >= 0) {
                start = brace;
            } else if (bracket >= 0) {
                start = bracket;
            }
            if (start > 0) {
                payload = payload.substring(start).trim();
            }

            if (payload.isEmpty()) {
                log.warn("模型输出为空或仅含标签，返回空数组");
                return "[]";
            }

            JsonNode root = objectMapper.readTree(payload);
            if (root.isObject() && root.has("异议列表")) {
                root = root.get("异议列表");
            }
            if (root.isArray()) {
                String result = objectMapper.writeValueAsString(root);
                log.info("解析后的异议列表: {}", result);
                return result;
            }
        } catch (Exception e) {
            log.warn("解析“异议列表”失败，直接返回空数组", e);
        }

        return "[]";

    }
}
//        try {
//            JsonNode root = objectMapper.readTree(raw);
//            JsonNode listNode = root.get("异议列表");
//            if (listNode != null && listNode.isArray()) {
//                String result = objectMapper.writeValueAsString(listNode);
//                log.info("解析后的异议列表: {}", result);
//                return result;
//            }
//        } catch (Exception e) {
//            log.warn("解析“异议列表”失败，直接返回原始内容", e);
//        }
//
//        // 如果解析失败，就把大模型输出的原始结果直接返回
//        return raw;
//    }
//}

