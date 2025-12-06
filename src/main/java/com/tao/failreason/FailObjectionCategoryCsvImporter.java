package com.tao.failreason;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static cn.hutool.core.lang.Console.print;

/**
 * 启动时：
 * 1. 扫描 resources/document/CustomerObjectionClassification 下所有 csv
 * 2. 逐行读取，取“小类标题”做 embedding
 * 3. 写入 objection_category_embedding 表（4096 维向量）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailObjectionCategoryCsvImporter implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;  // Spring AI 注入的 embedding 模型
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        // 只在表为空时，才会导入
        Integer count = jdbcTemplate.queryForObject("select count(*) from fail_category", Integer.class);
        if (count != null && count > 0) {
            log.info("向量表已有数据({})，跳过 CSV 导入", count);
            return;
        }

        log.info("开始导入失败原因分类 CSV -> fail_category 表 ...");

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(this.getClass().getClassLoader());

        // 对应 src/main/resources/document/CustomerObjectionClassification/*.csv
        Resource[] resources = resolver.getResources(
                "classpath:document/failCategory/*.csv");

        log.info("共发现分类规则文件 {} 个", resources.length);

        for (Resource resource : resources) {
            importSingleCsv(resource);
        }

        log.info("失败原因分类 CSV 导入完成。");
    }

    private void importSingleCsv(Resource resource) {
        String fileName = safeGetFileName(resource);
        log.info("开始导入文件: {}", fileName);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                log.warn("文件 {} 为空，跳过", fileName);
                return;
            }

            // 去掉 BOM（UTF-8 带 BOM 时第一行开头会有一个 \uFEFF）
            headerLine = headerLine.replace("\uFEFF", "");

            String[] headers = headerLine.split(",", -1);
            log.info("文件 {} 原始表头: {}", fileName, Arrays.toString(headers));

            int idxCode       = indexOf(headers, "编号");
            print("idxCode:" ,idxCode);
            int idxBigCode    = indexOf(headers, "大类编号");
            int idxBigName    = indexOf(headers, "大类");
            int idxSmallCode  = indexOf(headers, "小类编号");
            int idxSmallTitle = indexOf(headers, "小类标题");

            if (idxCode < 0 || idxBigCode < 0 || idxBigName < 0
                    || idxSmallCode < 0 || idxSmallTitle < 0) {
                log.error("文件 {} 表头不符合预期，原始 headers={}", fileName, Arrays.toString(headers));
                return;
            }

            String line;
            int rowIndex = 0;

            while ((line = reader.readLine()) != null) {
                rowIndex++;
                if (line.isBlank()) {
                    continue;
                }

                // 简单按逗号切；你现在 CSV 列比较规整，这样足够
                String[] cols = line.split(",", -1);
                if (cols.length < headers.length) {
                    log.warn("文件 {} 第 {} 行列数不足，实际={} 期望>={}",
                            fileName, rowIndex, cols.length, headers.length);
                    continue;
                }

                String code       = cleanExcelValue(cols[idxCode]);
                String bigCode    = cleanExcelValue(cols[idxBigCode]);
                String bigName    = cleanExcelValue(cols[idxBigName]);
                String smallCode  = cleanExcelValue(cols[idxSmallCode]);
                String smallTitle = cleanExcelValue(cols[idxSmallTitle]);

                if (code.isEmpty() || smallTitle.isEmpty()) {
                    log.warn("文件 {} 第 {} 行 code 或 smallTitle 为空，跳过", fileName, rowIndex);
                    continue;
                }

                // 调本地 Qwen3 Embedding 模型，返回 4096 维向量
                float[] embedding = embeddingModel.embed(smallTitle);
                String embeddingLiteral = toPgVectorLiteral(embedding);

                // 写入 / 更新数据库
                String sql = """
                        INSERT INTO fail_category
                          (code, big_code, big_name, small_code, small_title,
                           embedding, source_file, row_index, created_at, updated_at)
                        VALUES
                          (?, ?, ?, ?, ?, ?::vector, ?, ?, now(), now())
                        ON CONFLICT (code) DO UPDATE SET
                          big_code    = EXCLUDED.big_code,
                          big_name    = EXCLUDED.big_name,
                          small_code  = EXCLUDED.small_code,
                          small_title = EXCLUDED.small_title,
                          embedding   = EXCLUDED.embedding,
                          source_file = EXCLUDED.source_file,
                          row_index   = EXCLUDED.row_index,
                          updated_at  = now()
                        """;

                jdbcTemplate.update(sql,
                        code,
                        bigCode,
                        bigName,
                        smallCode,
                        smallTitle,
                        embeddingLiteral,
                        fileName,
                        rowIndex
                );
            }

        } catch (Exception e) {
            log.error("导入文件 {} 失败", fileName, e);
        }
    }

    private String safeGetFileName(Resource resource) {
        try {
            return resource.getFilename();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * 处理你现在这种 "=""01001""" 形式的单元格导出数据：
     * - 去掉 BOM
     * - 去掉外层引号
     * - 去掉开头的 =" 和 结尾的 "
     */
    private static String cleanExcelValue(String raw) {
        if (raw == null) {
            return "";
        }
        // 顺手把 BOM 也去掉
        String s = raw.replace("\uFEFF", "").trim();

        // 去掉外层引号
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }

        // 去掉 =" 前缀（常见于 Excel 导出）
        if (s.startsWith("=\"") && s.endsWith("\"") && s.length() >= 3) {
            s = s.substring(2, s.length() - 1);
        }

        return s.trim();
    }

    /**
     * 规范化表头；去掉 BOM、引号、等号、前后空格
     */
    private static String normalizeHeader(String h) {
        if (h == null) {
            return "";
        }
        return h
                .replace("\uFEFF", "")  // 去 BOM
                .replace("\"", "")
                .replace("=", "")
                .trim();
    }

    /**
     * 根据中文表头名找到对应列下标
     */
    private static int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            String normalized = normalizeHeader(headers[i]);
            if (name.equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把 float[] 转成 pgvector 的字面量: "[0.1,0.2,...]"
     */
    private static String toPgVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // 用 US locale，避免逗号变成中文小数点格式
            sb.append(String.format(Locale.US, "%.6f", vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
