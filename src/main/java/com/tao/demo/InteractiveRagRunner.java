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
 * 在项目根目录输入： Y
 *
 */

/**
 * 稳定版 Interactive RAG Runner：
 * 1. 保留所有原始格式（不 trim、不删除多余空格、不改换行）
 * 2. 输入严格等价于 JUnit 的 text block
 */
@Component
@Profile("interactive-rag")
public class InteractiveRagRunner implements CommandLineRunner {

    private final ServiceApp serviceApp;

    public InteractiveRagRunner(ServiceApp serviceApp) {
        this.serviceApp = serviceApp;
    }

    @Override
    public void run(String... args) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("粘贴多行对话，空行结束；输入 quit 退出：");

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

                    // 空行表示结尾
                    if (line.isEmpty()) {
                        break;
                    }

                    buf.append(line).append("\n");
                }

                // 不 trim，保留格式
                String message = buf.toString();

                // 空输入跳过
                if (message.replace("\n", "").trim().isEmpty()) {
                    continue;
                }

                System.out.println("=== 模型开始处理，请等待 ===");

                long start = System.currentTimeMillis();

                String result = serviceApp.doClassifyWithRag(message);

                double costSeconds = (System.currentTimeMillis() - start) / 1000.0;

                System.out.println("=== 模型输出 ===");
                System.out.println(result);

                System.out.println("=== 完成，总耗时: " + costSeconds + " 秒 ===");
            }
        }
    }
}

