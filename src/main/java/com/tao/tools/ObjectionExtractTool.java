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
 * 返回 JSON 数组：
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

    private static final String SYSTEM_PROMPT = """
        你是一个电信公司的客服总管，你将对一段客服与客户对话录音进行分析。你的主要任务是，精准分析该会话中2-3种最明显的客户提出的异议问题，并给出提出问题的原文语句内容和解释。
        客服与客户的录音放在<info></info>的XML标签内。
                    
        输出结果要求：
        1、必须给出2-3个最明显的客户异议问题，并针对每一个问题给出对应的原文摘要和解释。
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
        5、严禁使用 ```json 或任何 Markdown 代码块包裹输出，不能输出解释性文字，只能输出纯 JSON。
    """;

    // 注入配置好的 chatClient Bean
    public ObjectionExtractTool(@Qualifier("chatClient") ChatClient chatClient) {

        this.chatClient = chatClient;
    }

    /**
     * 从 info 中抽取 problem 列表。
     *
     * @param info 客服与客户的对话文本
     * @return JSON 字符串，失败或无法解析时返回空数组 "[]"
     */
    public String extractProblems(String info) {
        String userPrompt = "<info>\n" + info + "\n</info>";

        ChatResponse response = chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse();

        // 在这里打印原始模型输出
        String raw = response.getResult().getOutput().getText();
//        log.info("模型原始输出: {}", raw);

        String payload = raw.trim();

        // 1) 去掉 ```json / ``` 包裹
        if (payload.startsWith("```")) {
            int firstLineBreak = payload.indexOf('\n');
            if (firstLineBreak > 0) payload = payload.substring(firstLineBreak + 1);
            if (payload.endsWith("```")) payload = payload.substring(0, payload.length() - 3);
            payload = payload.trim();
        }

        // 2) 去掉 HTML/XML 标签
        payload = payload.replaceAll("<[^>]+>", "").trim();

        // 3) 找到第一个合法 JSON 开始符号
        int brace = payload.indexOf('{');
        int bracket = payload.indexOf('[');
        int start = -1;
        if (brace >= 0 && bracket >= 0) start = Math.min(brace, bracket);
        else if (brace >= 0) start = brace;
        else if (bracket >= 0) start = bracket;

        if (start >= 0) {
            payload = payload.substring(start).trim();
        } else {
            log.warn("模型输出中找不到 JSON，返回空数组");
            return "[]";
        }

        // 4) 解析 JSON，如果失败返回空数组
        try {
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
