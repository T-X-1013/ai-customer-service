package com.tao.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagAnswerValidatorAgent {

    @Qualifier("validatorChatClient")
    private final ChatClient validatorChatClient;

    /**
     * 调用 Validator LLM 判断客服回答有效性（强制 JSON 严格输出）
     */
    public String validateAnswerWithRagResult(String ragResultJson, String problem, String answer) {

        String prompt = String.format("""
            你是一个电信公司的客服总管。你将根据 RAG 输出对客服回答进行有效性判断。
            
            =======================
            【 RAG 输出（仅一条）】
            %s
            =======================

            【用户问题】
            %s
            
            【客服回答】
            %s
            =======================

            你的任务：
            1. 判断客服回答是否真正回应了用户问题。若相关则判断有效；不相关则判断无效。
            2. 给出简洁且唯一的判断原因。
            3. 所有字段必须齐全，字段内容不能缺失。
            4. “问题大类编号 / 名称 / 小类编号 / 名称 / 原文摘要 / 解释”
               必须完整从 RAG 输入复制（不得修改）。
            5. “针对的问题”必须与传入的用户问题完全一致。
            6. 严禁出现任何除 JSON 外的内容（不能出现解释、前后缀、格式说明等）。

            =======================
            【严格输出格式，必须是 JSON 数组】
            [
              {
                "针对的问题": "",
                "问题大类编号": "",
                "问题大类名称": "",
                "问题小类编号": "",
                "问题小类名称": "",
                "客服回答": "",
                "判断回答是否有效": "",  // 是 或 否
                "判断原因": "",
                "原文摘要": "",
                "解释": ""
              }
            ]
            =======================

            绝对禁止输出注释、额外说明、自然语言，只能输出纯 JSON。
            """, ragResultJson, problem, answer);

        String resultText = validatorChatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .trim();

        log.info("validator LLM 输出（原始）: {}", resultText);
        return resultText;
    }
}
