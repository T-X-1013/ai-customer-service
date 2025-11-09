package com.tao.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ServiceAppTest {

    @Resource
    private ServiceApp serviceApp;

    @Test
    void testChat() {

        // 随机生成 chatId , 确保不会重复
        // 如果用同一个 chatId 再调用 doChat
        // 那么 advisor 就会从对应 .kryo 中把历史取出作为上下文供模型使用
        String chatId = UUID.randomUUID().toString();

        // 第一轮
        String message = "你好，我是小锯鳄";
        String answer = serviceApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "你是谁？你的角色定位是什么？";
        answer = serviceApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我叫什么来着？我刚跟你说过，把我的名字复述一遍";
        answer = serviceApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }
}