package com.tao.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 问题解决性判断 Agent
 * 用于判断有效的客服回答是否真正解决了客户的问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagProblemResolvedAgent {

    @Qualifier("problemResolveChatClient")
    private final ChatClient problemResolveChatClient;

    /**
     * 调用 LLM 判断客服回答是否解决了客户问题（强制 JSON 严格输出）
     */
    public String validateProblemResolved(String ragResultJson, String problem, String answer) {

        String prompt = String.format("""
            你是一个电信公司的客服总管。你将判断客服回答是否真正解决了客户的问题。
            
            =======================
            【 RAG 输出（仅一条）】
            %s
            =======================

            【客户问题】
            %s
            
            【客服回答】
            %s
            =======================

            你的任务：
            1. 判断客服回答是否真正解决了客户的问题。
               - 如果客服明确回应了问题、提供了解决方案或给出了满意的答复，判断为"是"
               - 如果客服的回答回避问题、没有实质性内容、或客户明显不满意，判断为"否"
            2. 给出简洁且唯一的判断原因，说明为什么认为问题已解决或未解决。
            3. 所有字段必须齐全，字段内容不能缺失。
            4. "问题大类编号 / 名称 / 小类编号 / 名称 / 原文摘要 / 解释"
               必须完整从 RAG 输入复制（不得修改）。
            5. "针对的问题"必须与传入的客户问题完全一致。
            6. "客服回答"必须与传入的客服回答完全一致。
            7. "判断回答是否有效"必须从 RAG 输入复制。
            8. "判断原因"必须从 RAG 输入复制。
            9. 严禁出现任何除 JSON 外的内容（不能出现解释、前后缀、格式说明等）。

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
                "判断回答是否有效": "",  // 从 RAG 输入复制
                "判断原因": "",  // 从 RAG 输入复制
                "判断是否解决问题": "",  // 是 或 否
                "解决问题的判断原因": "",  // 你的新判断原因
                "原文摘要": "",
                "解释": ""
              }
            ]
            =======================

            绝对禁止输出注释、额外说明、自然语言，只能输出纯 JSON。
            """, ragResultJson, problem, answer);

        String resultText = problemResolveChatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .trim();

        log.info("问题解决 LLM 输出（原始）: {}", resultText);
        return resultText;
    }
}
