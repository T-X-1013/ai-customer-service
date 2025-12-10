package com.tao.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RagResultValidator {

    /**
     * 判断 RAG 列表是否为空
     */
    public boolean isEmptyList(List<Map<String,Object>> ragList) {
        if (ragList == null || ragList.isEmpty()) {
            log.info("RAG结果列表为空");
            return true;
        }
        return false;
    }

    /**
     * 判断单个 RAG 对象是否有效
     * @param ragObject RAG结果数组中的单个对象
     * @return 如果对象为空，或者对象的关键字段为空，返回false；否则返回true
     */
    public boolean isValidRagObject(Map<String, Object> ragObject) {
        if (ragObject == null || ragObject.isEmpty()) {
            log.info("RAG结果对象为空");
            return false;
        }

        Object problem = ragObject.get("针对的问题");
        if (problem == null || problem.toString().trim().isEmpty()) {
            log.info("RAG结果对象的'针对的问题'字段为空");
            return false;
        }

        Object answer = ragObject.get("客服回答");
        if (answer == null || answer.toString().trim().isEmpty()) {
            log.info("RAG结果对象的'客服回答'字段为空");
            return false;
        }

        return true;
    }
}
