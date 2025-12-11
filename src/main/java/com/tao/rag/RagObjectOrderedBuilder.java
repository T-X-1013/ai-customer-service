package com.tao.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** 构建严格字段顺序的 rag_object JSON */
public class RagObjectOrderedBuilder {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 字段顺序必须固定 */
    private static final String[] ORDERED_KEYS = new String[]{
            "针对的问题",
            "问题大类编号",
            "问题大类名称",
            "问题小类编号",
            "问题小类名称",
            "客服回答",
            "判断回答是否有效",
            "判断原因",
            "原文摘要",
            "解释"
    };

    public static String buildOrderedJson(Map<String, Object> src) {
        ObjectNode node = mapper.createObjectNode();

        for (String key : ORDERED_KEYS) {
            Object v = src.getOrDefault(key, "");
            node.put(key, v == null ? "" : v.toString());
        }

        return node.toString();
    }
}
