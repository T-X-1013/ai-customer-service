package com.tao.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 创建自定义的 RAG 检索增强顾问的工厂
 */
@Slf4j
public class ServiceAppRagCustomAdvisorFactory {
    private static final int TOP_K = 3;            // 或 3，按你想要的固定值
    private static final double THRESHOLD = 0.4;   // 相似度阈值

    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore) {
        return createLoveAppRagCustomAdvisor(vectorStore, THRESHOLD, TOP_K);
    }

    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, double threshold, int topK) {
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(threshold)
                .topK(topK)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }

    /**
     * 创建自定义的 RAG 检索增强顾问
     * @param vectorStore  向量存储
     * @param status       状态
     * @return             自定义的 RAG 检索增强顾问
     */
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        // 过滤特定状态的文档

        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(THRESHOLD) // 相似度阈值
                .topK(TOP_K) // 返回文档数量
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
//                .queryAugmenter(ServiceAppContextualQueryAugmenterFactory.createInstance())
                .build();
    }


}
