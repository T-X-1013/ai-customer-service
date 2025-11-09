package com.tao.rag;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * MetadataEnricher 元数据增强器
 *
 * 【作用】
 * - 为文档补充更多“元信息”（metadata），便于后续检索与排序；
 * - 不改变文档内容本身与切分规则。
 *
 * 【常见实现】
 * 1) KeywordMetadataEnricher
 *    - 使用 AI 提取关键词并写入文档的 metadata。
 *
 * 2) SummaryMetadataEnricher
 *    - 使用 AI 生成文档摘要并写入 metadata；
 *    - 可结合相邻文档（前一条/后一条）共同生成，更加完整。
 */
@Component
class MyDocumentEnricher {

    private final ChatModel chatModel;

    MyDocumentEnricher(@Qualifier("dashscopeChatModel")ChatModel chatModel) {
        this.chatModel = chatModel;
    }
      
      // 关键词元信息增强器
    List<Document> enrichDocumentsByKeyword(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.chatModel, 5);
        return enricher.apply(documents);
    }
  
    // 摘要元信息增强器
    List<Document> enrichDocumentsBySummary(List<Document> documents) {
        SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(chatModel,
            List.of(SummaryMetadataEnricher.SummaryType.PREVIOUS, SummaryMetadataEnricher.SummaryType.CURRENT, SummaryMetadataEnricher.SummaryType.NEXT));
        return enricher.apply(documents);
    }
}
