package com.tao.config;

import com.tao.advisor.MyLoggerAdvisor;
import com.tao.chatmemory.FileBasedChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT_SERVICE = "你是一个客服分析智能体";
    private static final String SYSTEM_PROMPT_VALIDATOR = "你是一个客服回答判断智能体";
    private static final String SYSTEM_PROMPT_PROBLEMRESOLVER = "你是一个判断客服回答是否解决了用户问题的智能体";

    /**
     * 主客服对话客户端
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";

        // 确保目录存在
        File dir = new File(fileDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);

        return builder
                .defaultSystem(SYSTEM_PROMPT_SERVICE)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor()
                )
                .build();
    }

    /**
     * 轻量级 RAG 分类客户端（无系统提示、无记忆）
     */
    @Bean
    @Qualifier("classifyChatClient")
    public ChatClient classifyChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * 验证客服回答的客户端
     */
    @Bean
    @Qualifier("validatorChatClient")
    public ChatClient validatorChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT_VALIDATOR)
                .build();
    }

    /**
     * 判断客服回答是否解决了用户问题的客户端
     */
    @Bean
    @Qualifier("problemResolverChatClient")
    public ChatClient problemResolverChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT_PROBLEMRESOLVER)
                .build();
    }
}
