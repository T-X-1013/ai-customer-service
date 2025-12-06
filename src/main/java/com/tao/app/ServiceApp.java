package com.tao.app;


import com.tao.advisor.MyLoggerAdvisor;
import com.tao.chatmemory.FileBasedChatMemory;
import com.tao.rag.ServiceAppRagCustomAdvisorFactory;
import com.tao.tools.ObjectionExtractTool;
import com.tao.tools.ProblemClassifyTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import org.springframework.ai.ollama.api.OllamaOptions;


@Component
@Slf4j
public class ServiceApp {

    private final ChatClient chatClient;


    /**
     * 专用于“单问题 RAG 分类”的轻量客户端（无默认 system、无记忆）
     */
    private final ChatClient classifyChatClient;

    @Resource
    private VectorStore serviceAppVectorStore;

    @Resource
    private ObjectionExtractTool objectionExtractTool;

    @Resource
    private ProblemClassifyTool problemClassifyTool;

    private static final String SYSTEM_PROMPT = "你是一个客服分析智能体";

    // 公用一个确定性配置
    private static final OllamaOptions DETERMINISTIC_OPTIONS = OllamaOptions.builder()
            .temperature(0.0) // 关闭随机性
            .topP(1.0)        // 只取最高概率
            .build();

    public ServiceApp(@Qualifier("ollamaChatModel") ChatModel chatModel)  {
        // 初始化基于文件的对话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                //新增关闭随机性25.12.3
                .defaultOptions(DETERMINISTIC_OPTIONS)

                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，会增加token消耗，可按需开启，暂时用不到哈
//                       ,new ReReadingAdvisor()
                )
                .build();

        classifyChatClient = ChatClient.builder(chatModel)
                .defaultOptions(DETERMINISTIC_OPTIONS)
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
            你的主要任务是：根据对话内容，以及已经分析好的客户在对话中提出的问题，使用你掌握的“客户异议分类”知识，对该问题进行精准归类，精准输出每个问题对应的大类和小类编号与名称并从 <info> 中寻找与该问题对应的客服回答。
            其中：
            - 对话文本放在 <info></info> 标签中；
            - 客户已分析好的问题放在 <problem></problem> 标签中；
            - 客户异议分类知识会通过 RAG 检索注入到对话中。
        
            <info>
            %s
            </info>
        
            <problem>
            %s
            </problem>
        
            输出要求：
            1）输出格式必须是一个 JSON 数组，例如：
            [
              {
                "针对的问题": "",
                "问题大类编号": "",
                "问题大类名称": "",
                "问题小类编号": "",
                "问题小类名称": "",
                "客服回答": "",  // 从 <info> 中找到对该问题的客服回复，找不到就留空
                "原文摘要": "",
                "解释": ""
              }
            ]
           
            输出规则：
            1. “针对的问题”需与输入问题完全一致；
            2. 大类/小类编号与名称必须和知识库保持一致，直接复制知识库里的原文，不能截断或改写，尤其不要省略冒号后的说明；
            3. 若无异议则输出空数组 [];
            4. 若无匹配项则输出大类编号 "00"、大类名称 "新分类"；
            5. 严禁输出任何多余文字或解释、严禁输出思考/推理过程或 <think> 等标签，只能输出纯 JSON。  
            6. 从 <info> 中寻找与该问题对应的客服回复，尽量原样复制；找不到则填空字符串，严禁编造；
          
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

    /**
     * AI RAG 检索增强对话, 输入端只有 info，调用工具提取出 problem
     * 使用向量数据库 (VectorStore) 进行知识召回
     * @param info     客服与客户对话文本
     * @return 模型输出（包含分类编号与名称）
     */
    public String doChatWithRag(String info) {
        // 1. 用工具类从对话文本中抽取客户异议 problem（JSON 数组字符串）
        String problem = objectionExtractTool.extractProblems(info);
        log.info("自动抽取到的 problem: {}", problem);

        // 2. 复用原有 RAG 方法完成分类逻辑
        return doChatWithRag(info, problem);
    }


    /**
     * 新主入口：
     * 1）从 info 中自动抽取 problem 列表；
     * 2）逐个问题调用 classifySingleProblemWithRag 做 RAG 归类；
     * 3）ProblemClassifyTool 负责循环 & 合并 JSON。
     */
    public String doClassifyWithRag(String info) {
        String problemsJson = objectionExtractTool.extractProblems(info);
        log.info("自动抽取到的 problem: {}", problemsJson);

        return problemClassifyTool.doClassify(
                info,
                problemsJson,
                this::classifySingleProblemWithRag
        );
    }


    /**
     * 【核心】对“单个问题”调用一次 RAG 分类。
     * 这里只在 ServiceApp 中调用大模型。
     *
     * @param info           对话全文
     * @param oneProblemJson 单个问题 JSON：
     *                       {"问题": "...", "原文摘要": "...", "解释": "..."}
     * @return JSON 数组字符串（通常长度为 0 或 1）
     */
    private String classifySingleProblemWithRag(String info, String oneProblemJson) {
        String prompt = String.format("""
            你是一个电信公司的客服总管，你将对一段客服与客户对话录音进行分析。
            你的主要任务是：根据对话内容，以及已经分析好的客户在对话中提出的问题，使用你掌握的“客户异议分类”知识，对该问题进行精准归类，精准输出每个问题对应的大类和小类编号与名称并从 <info> 中寻找与该问题对应的客服回答。
            
            其中：
            - 对话文本放在 <info></info> 标签中；
            - 客户已分析好的问题放在 <problem></problem> 标签中；
            - 客户异议分类知识会通过 RAG 检索注入到对话中。
        
            <info>
            %s
            </info>
        
            <problem>
            %s
            </problem>
        
            输出要求：
            1）输出格式必须是一个 JSON 数组，例如：
            [
              {
                "针对的问题": "",
                "问题大类编号": "",
                "问题大类名称": "",
                "问题小类编号": "",
                "问题小类名称": "",
                "客服回答": "",  // 从 <info> 中找到对该问题的客服回复，找不到就留空
                "原文摘要": "",
                "解释": ""
              }
            ]
           
            输出规则：
            1. “针对的问题”需与输入问题完全一致；
            2. 大类/小类编号与名称必须和知识库保持一致，直接复制知识库里的原文，不能截断或改写，尤其不要省略冒号后的说明；
            3. 若无异议则输出空数组 [];
            4. 若无匹配项则输出大类编号 "00"、大类名称 "新分类"；
            5. 严禁输出任何多余文字或解释、严禁输出思考/推理过程或 <think> 等标签，只能输出纯 JSON。  
            6. 从 <info> 中寻找与该问题对应的客服回复，尽量原样复制；找不到则填空字符串，严禁编造；
            """, info, oneProblemJson);

        var ragAdvisor = ServiceAppRagCustomAdvisorFactory
                .createLoveAppRagCustomAdvisor(serviceAppVectorStore, "active");

        ChatResponse response = classifyChatClient
                .prompt()
                .advisors(ragAdvisor)
                .user(prompt)
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getText();
        log.info("单问题 RAG 分类输出: {}", content);
        return content;
    }
}
