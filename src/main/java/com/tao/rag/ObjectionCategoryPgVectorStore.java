package com.tao.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 Postgres+pgvector 的只读向量库实现。
 * 作用：根据用户 query 生成 embedding，去数据库表 objection_category_embedding 中做向量相似度查询
 * 将编号/名称等信息封装成 Document，供 RAG 使用。
 */
@Slf4j
@RequiredArgsConstructor
public class ObjectionCategoryPgVectorStore implements VectorStore {

    // embedding 模型
    private final EmbeddingModel embeddingModel;

    // JDBC 访问 pgvector 表
    private final JdbcTemplate jdbcTemplate;

    private static final String TABLE_NAME = "objection_category_embedding";

    /**
     * 写入逻辑
     * 当前未实现
     * @param documents
     */
    @Override
    public void add(List<Document> documents) {
        log.warn("ObjectionCategoryPgVectorStore.add() 当前未实现写入逻辑，已忽略 {} 条文档", documents.size());
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        int total = 0;
        for (String code : idList) {
            total += jdbcTemplate.update("DELETE FROM " + TABLE_NAME + " WHERE code = ?", code);
        }
        log.info("ObjectionCategoryPgVectorStore.delete(List<String>): 删除 {} 条记录", total);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        log.warn("ObjectionCategoryPgVectorStore.delete(Filter.Expression) 未实现过滤删除逻辑，收到表达式: {}", filterExpression);
    }

    /**
     * 查询逻辑
     * @param query
     * @return
     */
    @Override
    public List<Document> similaritySearch(String query) {
        SearchRequest request = SearchRequest.builder().query(query).build();
        return similaritySearch(request);
    }

    /**
     * 向量相似度检索
     * 核心逻辑!!!!!!
     * @param request
     * @return
     */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return List.of();   // 空查询直接返回空列表
        }

        int topK = request.getTopK() > 0 ? request.getTopK() : SearchRequest.DEFAULT_TOP_K;

        // 生成 query 的向量
        float[] embedding = embeddingModel.embed(query);
        String embeddingLiteral = toPgVectorLiteral(embedding);

        // pgvector 相似度查询 SQL
        String sql = """                                                                                                                                                                                                          
                  SELECT                                                                                                                                                                                                            
                      code,                                                                                                                                                                                                         
                      big_code,                                                                                                                                                                                                     
                      big_name,                                                                                                                                                                                                     
                      small_code,                                                                                                                                                                                                   
                      small_title,                                                                                                                                                                                                  
                      embedding <-> ?::vector AS distance                                                                                                                                                                           
                  FROM %s                                                                                                                                                                                                           
                  ORDER BY embedding <-> ?::vector                                                                                                                                                                                  
                  LIMIT ?                                                                                                                                                                                                           
                  """.formatted(TABLE_NAME);

        log.debug("向量检索SQL: {}", sql);
        log.debug("向量检索 query='{}', topK={}", query, topK);

        // 执行查询并封装 Document
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, embeddingLiteral);
                    ps.setString(2, embeddingLiteral);
                    ps.setInt(3, topK);
                },
                (rs, rowNum) -> {
                    // 把编号/名称都放进 content，方便 RAG 直接注入到 <context>
                    String content = """                                                                                                                                                                                          
                              code: %s                                                                                                                                                                                              
                              big_code: %s                                                                                                                                                                                          
                              big_name: %s                                                                                                                                                                                          
                              small_code: %s                                                                                                                                                                                        
                              small_title: %s                                                                                                                                                                                       
                              """.formatted(
                            rs.getString("code"),
                            rs.getString("big_code"),
                            rs.getString("big_name"),
                            rs.getString("small_code"),
                            rs.getString("small_title")
                    );

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("code", rs.getString("code"));
                    metadata.put("big_code", rs.getString("big_code"));
                    metadata.put("big_name", rs.getString("big_name"));
                    metadata.put("small_code", rs.getString("small_code"));
                    metadata.put("distance", rs.getDouble("distance"));
                    return new Document(content, metadata);
                }
        );
    }

    /**
     * 将 float[] 转成 pgvector 字面量格式 "[0.1,0.2,...]"，locale 固定为 US 避免逗号/小数点问题。
     * @param vec
     * @return
     */
    private static String toPgVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.6f", vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}