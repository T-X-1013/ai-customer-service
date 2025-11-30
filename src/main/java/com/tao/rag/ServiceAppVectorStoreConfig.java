package com.tao.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent客服 向量数据库配置
 * 初始化基于内存的向量数据库Bean
 */
@Slf4j
@Configuration
public class ServiceAppVectorStoreConfig {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 使用 Postgres + pgvector 的向量库，而不是本地 JSON
     */
    @Bean
    @Primary
    public VectorStore serviceAppVectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        log.info("初始化 ObjectionCategoryPgVectorStore，使用数据库 objection_category_embedding 作为向量库");
        return new ObjectionCategoryPgVectorStore(embeddingModel, jdbcTemplate);
    }


//    private static final String STORE_PATH =
//            System.getProperty("user.dir") + "/src/main/resources/vectorstoreJson/embeddings.json";
//
//
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    @Resource
//    private ServiceAppDocumentLoader serviceAppDocumentLoader;


//    @Bean
//    VectorStore serviceAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
//        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
//                .build();
//        // 加载文档
//        List<Document> documents = serviceAppDocumentLoader.loadMarkdowns();
//        log.info("已加载文档数量: {}", documents.size());
//        documents.forEach(doc -> log.info("文档元信息: {}", doc.getMetadata()));
//
//
//        simpleVectorStore.add(documents);
//        return simpleVectorStore;
//    }

//    @Bean
//    @Primary // 确保这是当前被注入的，因为目前直接从json中获取编码后的内容
//    public VectorStore serviceAppVectorStoreJson(EmbeddingModel qwenEmbeddingModel) throws Exception {
//        File storeFile = new File(STORE_PATH);
//        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(qwenEmbeddingModel).build();
//
//        String json = Files.readString(storeFile.toPath());
//        List<Map<String, Object>> entries = objectMapper.readValue(json, new TypeReference<>() {});
//        List<Document> docs = new ArrayList<>();
//
//        for (Map<String, Object> entry : entries) {
//            String text = (String) entry.get("text");
//            Map<String, Object> metadata = new HashMap<>();
//            if (entry.get("filename") != null) {
//                metadata.put("filename", entry.get("filename"));
//            }
//            docs.add(new Document(text, metadata));
//        }
//
//        // 将 json 中的文本和元数据导入向量存储
//        simpleVectorStore.add(docs);
//        log.info("已成功加载 {} 条文档向量。", docs.size());
//
//
//        return simpleVectorStore;
//    }
}




