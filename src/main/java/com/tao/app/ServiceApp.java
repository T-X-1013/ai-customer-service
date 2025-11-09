package com.tao.app;


import com.tao.advisor.MyLoggerAdvisor;
import com.tao.chatmemory.FileBasedChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Slf4j
public class ServiceApp {

    private final ChatClient chatClient;

    @Resource
    private VectorStore serviceAppVectorStore;

    private static final String SYSTEM_PROMPT = "你是一个客服分析智能体";

    public ServiceApp(ChatModel dashscopeChatModel) {
        // 初始化基于文件的对话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，会增加token消耗，可按需开启，暂时用不到哈
//                       ,new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆， SSE 流式传输）
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }


    /**
     * AI RAG 检索增强对话
     * 使用向量数据库 (VectorStore) 进行知识召回
     * @param info     客服与客户对话文本
     * @param problem  客户提出的问题 (JSON 数组)
     * @return 模型输出（包含分类编号与名称）
     */
    public String doChatWithRag(String info, String problem) {
        // 构造提示词
        String prompt = String.format("""
            你是一个电信公司的客服总管，你将对一段客服与客户对话录音进行分析。
            你的任务是根据对话内容（<info></info>）和客户问题（<problem></problem>），
            结合客服异议分类知识（<context></context>），输出每个问题对应的大类和小类编号与名称。
            
            <info>%s</info>
            <problem>%s</problem>
            
            输出格式如下：
            [{"针对的问题": "", "问题大类编号": "", "问题大类名称": "", "问题小类编号": "", "问题小类名称": "", "原文摘要": "", "解释": ""}]
            """, info, problem);

        // 使用 RAG Advisor（检索增强）
        var ragAdvisor = com.tao.rag.ServiceAppRagCustomAdvisorFactory
                .createLoveAppRagCustomAdvisor(serviceAppVectorStore, "active");

        // 调用模型
        ChatResponse response = chatClient
                .prompt()
                .advisors(ragAdvisor) // 增加 RAG 检索增强
                .user(prompt)
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getText();
        log.info("RAG 输出: {}", content);
        return content;
    }


}
