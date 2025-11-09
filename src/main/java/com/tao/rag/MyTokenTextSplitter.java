package com.tao.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * TokenTextSplitter 速览
 *
 * 【构造函数】
 * 1) TokenTextSplitter()
 * 2) TokenTextSplitter(int defaultChunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
 *                      int maxNumChunks, boolean keepSeparator)
 *    - 作用：通过自定义参数控制分割粒度与方式，适配不同应用场景。
 *
 * 【参数说明（默认值）】
 * - defaultChunkSize：每个文本块的目标大小（token，默认 800）
 * - minChunkSizeChars：每个文本块的最小大小（字符，默认 350）
 * - minChunkLengthToEmbed：被纳入输出的最小块长度（默认 5）
 * - maxNumChunks：从文本中生成的最大块数（默认 10000）
 * - keepSeparator：是否在块中保留分隔符/换行（默认 true）
 *
 * 【工作流程（简要）】
 * 1) 使用 CL100K_BASE 编码将输入文本转为 token
 * 2) 按 defaultChunkSize 对编码后的文本切成初始块
 * 3) 对每个块：
 *    - 解码回文本
 *    - 在长度 ≥ minChunkSizeChars 后，寻找合适断点（句号/问号/感叹号/换行）并在断点处截断
 *    - 根据 keepSeparator 决定是否保留分隔符（如换行）
 *    - 若块长度 ≥ minChunkLengthToEmbed，则加入输出
 * 4) 持续处理直至所有 token 完成或达到 maxNumChunks
 * 5) 若剩余文本长度 ≥ minChunkLengthToEmbed，则作为最后一块添加
 */
@Component
class MyTokenTextSplitter {

    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 400, 10, 5000, true);
        return splitter.apply(documents);
    }
}
