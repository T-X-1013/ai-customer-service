package com.tao.demo;

import com.tao.app.ServiceApp;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * 实现多次输入 info 输出分类
 * 输入 quit 退出
 * 开启终端
 * 在项目根目录输入： .\\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=interactive-rag"
 */
@Component
@Profile("interactive-rag") // 仅在该 profile 下启用，避免默认启动卡在交互
public class InteractiveRagRunner implements CommandLineRunner {

    private final ServiceApp serviceApp;

    public InteractiveRagRunner(ServiceApp serviceApp) {
        this.serviceApp = serviceApp;
    }

    @Override
    public void run(String... args) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("粘贴多行对话后再按回车、再空一行发送；输入 quit 退出：");
                StringBuilder buf = new StringBuilder();
                while (true) {
                    if (!scanner.hasNextLine()) {
                        return;
                    }
                    String line = scanner.nextLine();
                    if ("quit".equalsIgnoreCase(line.trim())) {
                        System.out.println("已退出。");
                        return;
                    }
                    if (line.isBlank()) { // 空行表示一段输入结束，开始调用模型
                        break;
                    }
                    buf.append(line).append('\n'); // 保留换行
                }
                String message = buf.toString().trim();
                if (message.isEmpty()) {
                    continue;
                }
                System.out.println("===  模型开始处理，请耐心等待  ===");

                long start = System.currentTimeMillis(); // 开始时间
                String result = serviceApp.doClassifyWithRag(message);
                double costSeconds = (System.currentTimeMillis() - start) / 1000.0;   // 总耗时

                System.out.println("=== 模型输出 ===");
                System.out.println(result);
                System.out.println("=== 完成，总耗时: " + costSeconds + " ms ===");

            }
        }
    }
}
