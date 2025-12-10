package com.tao.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagAnswerValidatorAgent {

    @Qualifier("validatorChatClient")
    private final ChatClient validatorChatClient;

    /** 判断客服回答是否有效 */
    public boolean isAnswerValid(String problem, String answer) {
        String prompt = """
           你是一个电信公司的客服总管，你已经对一段客服与客户对话录音进行分析。
           你的主要任务是：根据对话内容，以及已经分析好的客户在对话中提出的问题和客服回答来判断客服回答是否准确回应用户提出的问题。
                   
            用户问题：%s
            客服回答：%s
            
            判断规则：
            1. 客服回答与用户提出的问题相关，则判断回答有效；
            2. 如果客服回答与用户提出的问题无关，或者信息不完整、错误，则判断回答无效；
            3. 不允许模型生成额外解释或文字，只能返回 JSON。
            
            输出要求：
            1）输出格式必须是一个 JSON 数组，例如：
            [
                {
                  "针对的问题": "...",
                  "客服回答": "...",
                  "判断回答是否有效": "是/否",
                  "判断原因": "",
                }
            ]
            输出规则：
            1. “针对的问题”需与输入问题完全一致；
            2. 严禁输出任何多余文字或解释、严禁输出思考/推理过程或 <think> 等标签，只能输出纯 JSON；  
            3. “判断回答是否有效”只能输入是或否；
            4. "判断原因"根据判断客服回答是否准确回应用户提出的问题来判断是否有效
        """.formatted(problem, answer);

        String resultText = validatorChatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .trim();

        log.info("客服回答有效性判断输出: {}", resultText);
        return resultText.contains("有效");
    }
}
