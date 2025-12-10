//package com.tao.failreason;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.tao.app.ServiceApp;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.util.Map;
//
//@Slf4j
//@Component
//public class RagResultValidator {
//
//    private final ObjectMapper objectMapper;
//    private final ServiceApp serviceApp;
//
//    public RagResultValidator(ObjectMapper objectMapper, ServiceApp serviceApp) {
//        this.objectMapper = objectMapper;
//        this.serviceApp = serviceApp;
//    }
//
//    /**
//     * 判断RAG结果数组中的单个对象是否有效
//     * @param ragObject RAG结果数组中的单个对象
//     * @return 如果对象为空，或者对象的"针对的问题"字段为空，返回false；否则返回true
//     */
//    public boolean isValidRagObject(Map<String, Object> ragObject) {
//        // 如果对象为空，返回false
//        if (ragObject == null || ragObject.isEmpty()) {
//            log.info("RAG结果对象为空");
//            return false;
//        }
//
//        // 检查对象的"针对的问题"字段是否为空
//        Object targetProblem = ragObject.get("针对的问题");
//        if (targetProblem == null || targetProblem.toString().trim().isEmpty()) {
//            log.info("RAG结果对象的'针对的问题'字段为空");
//            return false;
//        }
//
//        // 对象有效，返回true
//        return true;
//    }
//}