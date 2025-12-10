package com.tao.failreason;

import com.tao.app.ServiceApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RagResultValidatorTest {

    @Resource
    private RagResultValidator ragResultValidator;

    @Resource
    private ServiceApp serviceApp;

    @Test
    void testIsValidRagResult() {
        // 使用有效的对话内容作为测试数据
        String info = """
            客服：哎，你好，请问您是幺三二八八幺二三二二六的机主本人，对吧？
            客服：哎，你好，咱们这边是联通给您来电的，就是来电就是通知您呢。您这个套餐呢下个月会扣到五十九元的月租的。就是您看您是保持使用呢，还是说给您换个便宜点的，帮你把费用调整下来呢？
            客服：哎，你好，因为现在您的下个月不是四G生五G，就四季就不再更新啦。就全国普及。五G以后咱们的月租会上调的，而且您的现在的消费都高达这个四十多块钱啦。所以这次来电就是看需不需要把费用给你调整下来呢？
            客户：好的。
            客户：好。
            客服：诶，你好。
            客服：啊，您是保持五十九元继续使用的，还是帮你把费用调整下来呢？
            客服：诶，你好，能听到我说话吗？
            客服：诶，你好。
            客服：呃，您是。
            客服：哦，你需不需要把费用给您调整下来呢？
            客户：谢谢谢。
            客服：诶，你好。
            客服：啊，咱们这次来电就是帮您把费用调整下来，把你帮你把扣钱扣费的业务都给您取消，不再扣费。
            客户：然后。
            客户：小姐姐，我是打电话。
            客服：以后您就不用再交到五十多块钱那么高的费用了，好吧。
            客服：诶，你好。
            客户：你怎么还没听见？
            客服：呃，这边听不到您的正面回应，那就不好意思打扰你了啊，再见。
        """;

        try {
            // 调用serviceApp的doClassifyWithRag方法获取原始RAG结果
            String ragResult = serviceApp.doClassifyWithRag(info);
            System.out.println("=== RAG原始结果 ===");
            System.out.println(ragResult);

            // 使用ObjectMapper解析JSON字符串为List<Map<String, Object>>
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.Map<String, Object>> resultList = objectMapper.readValue(
                    ragResult,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {}
            );

            // 遍历数组，对每个对象调用isValidRagObject方法
            System.out.println("\n=== 每个对象的有效性验证结果 ===");
            for (int i = 0; i < resultList.size(); i++) {
                java.util.Map<String, Object> ragObject = resultList.get(i);
                boolean isValid = ragResultValidator.isValidRagObject(ragObject);
                System.out.println("对象 " + (i + 1) + " 是否有效: " + isValid);
                System.out.println("对象内容: " + ragObject);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Assertions.fail("测试过程中发生异常: " + e.getMessage());
        }
    }
}