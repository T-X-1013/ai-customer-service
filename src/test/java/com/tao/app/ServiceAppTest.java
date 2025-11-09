package com.tao.app;


import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;



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

    @Test
    void doChatWithRag() {
        String info = """
            客服：您好，中国联通请问是17620317069的机主吗？
            客户：啊，是的。
            客服：来电话是联通达汽油质用户，您保持号码正常使用，可以享受每个月26元话费返还每月呢返您是原话费，连续返还六个月，总共呢还您60元话费，返还话费呢可以拿来抵扣您套餐。月租党费用也是希望你号码用的好呢，多多支持联通。那现在为您当季领取你留意的使用，好吧。
            客户：嗯。
            客服：您好。
            客服：女士，您就把这手机号码正常使用，不停机不转网就可以享受到每个月给您返还的话，费了，连续返还六个月到期之后呢，停止返还活动也是自动失效的。
            客户：很好的。
            客服：嗯，那女士，您不用挂机，为了确保您本人本机想到并话费返还的中国联通10016开头给您发送1条圈短信，您收到短信在短信下方回复数字8就可以登记领取了短信，要给您这个7069的手机号码下发过去了，您看1下收到了吗？
            客服：您看1下如何问题呢？可以随时在线问我没有问题再回复就可以的。女士。
            客户：你告诉他。
            客户：怎么每月26元得两个视频会员在想60分。
            客户：这个是。
            客户：其他有什么变动吗？
            客服：没有女士，你可以领取到两块大招26元的会员，只需要用微信关注公众号，领取来用就可以了。又是原话费呢，是自动到您账户的，按您套餐也是不动的，没有合约，没有捆绑，没有其他增值业务的女士。
            客户：您好嘞，谢谢。
            客服：1866582，对，您就保持手机号码，正当使用，不停机不转网。他这个话费每个月是自动到您账户的，然后六个月活动到期通的活动整体就自动失效失效啦。
            客户：您好的。
            客服：嗯，那女士，您看没有问题，需要您在短信下方回复数字，是您本人本机想要到这个权益的。
            客户：好了。
            客服：收到你的回复了，女士您不用挂机，有个温馨提示给您说1下，您听1下就行。会员呢是在当月利领取到自闭领取呢会自动失效。稍后领会员的时候呢，你办理26元银行助理操上包系统会下发短信，提醒您去领取权益，中途退订，不再返话费，本月生效，次月自动续如期，本月就外扣费赠款不可抵扣。本月开始分六个月到账，每月到账10元，可以抵扣您套餐。月租房费用。稍后呢请根据语音提示N个1对我服务评价格满意。那感谢您的接听，祝您生活愉快，再见女士。
            """;

        String problem = """
            [
                {"问题": "询问每月26元话费返还的具体权益", "原文摘要": "客户:怎么每月26元得两个视频会员在想60分", "解释": "客户对每月26元话费返还的具体权益内容有疑问"}, 
                {"问题": "询问是否有其他变动", "原文摘要": "客户:其他有什么变动吗？", "解释": "客户关心除话费返还外是否有其他服务变动"}
            ]
            """;

        String result = serviceApp.doChatWithRag(info, problem);
        System.out.println("=== RAG 测试输出 ===");
        System.out.println(result);
        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isBlank());
    }


}