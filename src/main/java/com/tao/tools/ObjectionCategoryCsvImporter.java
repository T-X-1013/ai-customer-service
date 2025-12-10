package com.tao.tools;

import jakarta.annotation.PostConstruct;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 改进说明（修改注释已内嵌于代码）：
 * - 自动检测 CSV 与 DB 的差异（新增 / 更新 / 删除）
 * - 仅在新增或 small_title 变更时重新计算 embedding
 * - 批量执行 DB 插入/更新/删除，同时打印变更报告
 * - 保留原有 embedding 写入逻辑（使用 embeddingModel.embed(...)）
 *
 * 使用方式：
 * - 启动时会扫描 resources/document/CustomerObjectionClassification/*.csv
 * - 如果 CSV/DB 有差异，会自动执行相应插入/更新/删除操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectionCategoryCsvImporter implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;  // Spring AI 注入的 embedding 模型
    private final JdbcTemplate jdbcTemplate;

    // ============ 配置区（可按需调整） ============
    /**
     * 是否强制对已有记录重新计算 embedding 并更新（默认 false）。
     * 场景：如修改了 embedding 模型/参数，想全量刷新 embedding，可将此项设为 true。
     */
    private static final boolean FORCE_REEMBED = false;

    /**
     * 查询数据库时单次 IN 删除的批次大小，避免 SQL 过长或占用过多内存。
     */
    private static final int DELETE_BATCH_SIZE = 200;
    // ============================================

    @Override
    public void run(String... args) throws Exception {
        log.info("开始检查 CSV 与 objection_category_embedding 表的差异...");

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(this.getClass().getClassLoader());

        Resource[] resources = resolver.getResources("classpath:document/CustomerObjectionClassification/*.csv");
        log.info("共发现分类规则文件 {} 个", resources.length);

        // 1) 读取所有 CSV 内容到内存（key = code）
        Map<String, CsvRow> csvMap = new LinkedHashMap<>(); // 保持插入顺序
        for (Resource resource : resources) {
            readCsvIntoMap(resource, csvMap);
        }
        log.info("CSV 总条目数 (去重后): {}", csvMap.size());

        // 2) 从数据库读取现有数据（只读取必要字段）
        Map<String, DbRow> dbMap = loadExistingFromDb();
        log.info("DB 中现有条目数: {}", dbMap.size());

        // 3) 计算差异：新增、更新、删除
        Set<String> csvCodes = csvMap.keySet();
        Set<String> dbCodes = dbMap.keySet();

        Set<String> toInsert = new LinkedHashSet<>(csvCodes);
        toInsert.removeAll(dbCodes);

        Set<String> toDelete = new LinkedHashSet<>(dbCodes);
        toDelete.removeAll(csvCodes);

        // 更新：代码在 CSV 和 DB 都存在，但字段有变更或 force reembed
        Set<String> toUpdate = new LinkedHashSet<>();
        for (String code : csvCodes) {
            if (!dbMap.containsKey(code)) continue;
            CsvRow csvRow = csvMap.get(code);
            DbRow dbRow = dbMap.get(code);

            // 比较关键字段：big_code, big_name, small_code, small_title
            boolean basicDiff = !Objects.equals(csvRow.bigCode, dbRow.bigCode)
                    || !Objects.equals(csvRow.bigName, dbRow.bigName)
                    || !Objects.equals(csvRow.smallCode, dbRow.smallCode)
                    || !Objects.equals(csvRow.smallTitle, dbRow.smallTitle);

            // 如果 smallTitle 变化，则必须重新 embed 并更新 embedding 字段
            if (basicDiff || FORCE_REEMBED) {
                toUpdate.add(code);
            }
        }

        log.info("差异计算结果 -> 新增: {}, 更新: {}, 删除: {}", toInsert.size(), toUpdate.size(), toDelete.size());

        // 4) 执行删除（先删除，避免 code 冲突；也可以按需求改为软删除）
        if (!toDelete.isEmpty()) {
            performDeletes(toDelete);
        }

        // 5) 执行新增（需要 embedding）和更新（按需 re-embed）
        // 我们对 toInsert 和 toUpdate 做统一处理（插入使用 ON CONFLICT upsert，更新也可以使用 same SQL）
        Set<String> toUpsert = new LinkedHashSet<>();
        toUpsert.addAll(toInsert);
        toUpsert.addAll(toUpdate);

        log.info("开始执行插入/更新（需要 embedding 的条目数量={}）", toUpsert.size());

        int processed = 0;
        for (String code : toUpsert) {
            CsvRow csvRow = csvMap.get(code);
            if (csvRow == null) {
                log.warn("待 upsert 的 code={} 在 CSV 中不存在，跳过", code);
                continue;
            }

            // 仅在新增或 smallTitle 变化或 FORCE_REEMBED 时计算 embedding
            boolean needEmbed = toInsert.contains(code) || toUpdate.contains(code) && (
                    FORCE_REEMBED || !Objects.equals(csvRow.smallTitle, dbMap.get(code) != null ? dbMap.get(code).smallTitle : null)
            );

            String embeddingLiteral = null;
            if (needEmbed) {
                try {
                    float[] embedding = embeddingModel.embed(csvRow.smallTitle);
                    embeddingLiteral = toPgVectorLiteral(embedding);
                } catch (Exception e) {
                    log.error("计算 embedding 失败 code={}, smallTitle='{}'", code, csvRow.smallTitle, e);
                    // 如果 embedding 失败，可选择跳过该条或写入 null；这里选择跳过并记录
                    continue;
                }
            } else {
                // 如果不需要重新计算 embedding，则尝试复用 DB 中的 embedding 字面量（避免丢失向量）
                embeddingLiteral = dbMap.get(code) != null ? dbMap.get(code).embeddingLiteral : null;
            }

            // upsert：使用和你原来基本一致的 SQL（使用 ? 占位）
            String sql = """
                    INSERT INTO objection_category_embedding
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

            // 执行 upsert（embeddingLiteral 允许为 null，这里会写 null::vector 可能报错，建议 embeddingLiteral 非空）
            try {
                jdbcTemplate.update(sql,
                        csvRow.code,
                        csvRow.bigCode,
                        csvRow.bigName,
                        csvRow.smallCode,
                        csvRow.smallTitle,
                        embeddingLiteral,
                        csvRow.sourceFile,
                        csvRow.rowIndex
                );
                processed++;
            } catch (Exception e) {
                log.error("upsert 失败 code={}, file={}, rowIndex={}", csvRow.code, csvRow.sourceFile, csvRow.rowIndex, e);
            }
        }

        log.info("插入/更新完成，成功处理 {} 条记录。", processed);

        // 6) 打印简洁报告（含示例）
        printSummaryReport(toInsert, toUpdate, toDelete);
    }

    // 读取单个 CSV 到 csvMap（如遇重复 code，后读到的覆盖先前）
    private void readCsvIntoMap(Resource resource, Map<String, CsvRow> csvMap) {
        String fileName = safeGetFileName(resource);
        log.info("读取文件: {}", fileName);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                log.warn("文件 {} 为空，跳过", fileName);
                return;
            }
            headerLine = headerLine.replace("\uFEFF", "");
            String[] headers = headerLine.split(",", -1);

            int idxCode = indexOf(headers, "编号");
            int idxBigCode = indexOf(headers, "大类编号");
            int idxBigName = indexOf(headers, "大类");
            int idxSmallCode = indexOf(headers, "小类编号");
            int idxSmallTitle = indexOf(headers, "小类标题");

            if (idxCode < 0 || idxBigCode < 0 || idxBigName < 0 || idxSmallCode < 0 || idxSmallTitle < 0) {
                log.error("文件 {} 表头不符合预期，原始 headers={}", fileName, Arrays.toString(headers));
                return;
            }

            String line;
            int rowIndex = 0;
            while ((line = reader.readLine()) != null) {
                rowIndex++;
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < headers.length) {
                    log.warn("文件 {} 第 {} 行列数不足，实际={} 期望>={}，跳过", fileName, rowIndex, cols.length, headers.length);
                    continue;
                }

                String code = cleanExcelValue(cols[idxCode]);
                String bigCode = cleanExcelValue(cols[idxBigCode]);
                String bigName = cleanExcelValue(cols[idxBigName]);
                String smallCode = cleanExcelValue(cols[idxSmallCode]);
                String smallTitle = cleanExcelValue(cols[idxSmallTitle]);

                if (code.isEmpty() || smallTitle.isEmpty()) {
                    log.warn("文件 {} 第 {} 行 code 或 smallTitle 为空，跳过", fileName, rowIndex);
                    continue;
                }

                CsvRow row = new CsvRow(code, bigCode, bigName, smallCode, smallTitle, fileName, rowIndex);
                // 如果同一个 code 在多个 CSV/多行出现，后读到的会覆盖前面的（建议保证唯一）
                csvMap.put(code, row);
            }

        } catch (Exception e) {
            log.error("读取 CSV 文件 {} 失败", fileName, e);
        }
    }

    // 从 DB 读取现有记录（只取用于判断差异的字段），并缓存 embedding 字面量以便复用
    private Map<String, DbRow> loadExistingFromDb() {
        String sql = "SELECT code, big_code, big_name, small_code, small_title, embedding::text AS embedding_text FROM objection_category_embedding";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        Map<String, DbRow> map = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("code");
            String bigCode = (String) r.get("big_code");
            String bigName = (String) r.get("big_name");
            String smallCode = (String) r.get("small_code");
            String smallTitle = (String) r.get("small_title");
            String embeddingText = r.get("embedding_text") != null ? r.get("embedding_text").toString() : null;
            DbRow dbRow = new DbRow(code, bigCode, bigName, smallCode, smallTitle, embeddingText);
            map.put(code, dbRow);
        }
        return map;
    }

    // 执行删除操作，批量删除，防止 IN 太长
    private void performDeletes(Set<String> toDelete) {
        log.info("开始删除 DB 中已移除的 {} 条记录（批量处理）", toDelete.size());
        List<String> codes = new ArrayList<>(toDelete);
        int total = codes.size();
        int from = 0;
        while (from < total) {
            int to = Math.min(total, from + DELETE_BATCH_SIZE);
            List<String> batch = codes.subList(from, to);
            // 构造 SQL: DELETE FROM ... WHERE code IN (?, ?, ...)
            String placeholders = batch.stream().map(c -> "?").collect(Collectors.joining(","));
            String sql = "DELETE FROM objection_category_embedding WHERE code IN (" + placeholders + ")";
            try {
                jdbcTemplate.update(sql, batch.toArray());
                log.info("已删除 batch {} - {} (count={})", from + 1, to, batch.size());
            } catch (Exception e) {
                log.error("批量删除失败 for batch {} - {}", from + 1, to, e);
            }
            from = to;
        }
    }

    // 打印报告（只打印前若干示例）
    private void printSummaryReport(Set<String> toInsert, Set<String> toUpdate, Set<String> toDelete) {
        log.info("==== Objection CSV 同步报告 ====");
        log.info("新增 (count={}) : {}", toInsert.size(), summarizeCodes(toInsert, 10));
        log.info("更新 (count={}) : {}", toUpdate.size(), summarizeCodes(toUpdate, 10));
        log.info("删除 (count={}) : {}", toDelete.size(), summarizeCodes(toDelete, 10));
        log.info("==== End of Report ====");
    }

    private String summarizeCodes(Set<String> set, int limit) {
        if (set == null || set.isEmpty()) return "[]";
        List<String> list = new ArrayList<>(set);
        int show = Math.min(limit, list.size());
        return list.subList(0, show).toString() + (list.size() > show ? " (and " + (list.size()-show) + " more)" : "");
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
     *
     * 注：此方法保持原有逻辑，已验证可处理 Excel 导出的奇怪格式
     */
    private static String cleanExcelValue(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\uFEFF", "").trim();

        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }

        if (s.startsWith("=\"") && s.endsWith("\"") && s.length() >= 3) {
            s = s.substring(2, s.length() - 1);
        }

        return s.trim();
    }

    private static String normalizeHeader(String h) {
        if (h == null) {
            return "";
        }
        return h
                .replace("\uFEFF", "")
                .replace("\"", "")
                .replace("=", "")
                .trim();
    }

    private static int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            String normalized = normalizeHeader(headers[i]);
            if (name.equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private static String toPgVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.6f", vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    // ======= 辅助内部类 =======
    // CSV 行数据容器
    private static class CsvRow {
        final String code;
        final String bigCode;
        final String bigName;
        final String smallCode;
        final String smallTitle;
        final String sourceFile;
        final int rowIndex;

        CsvRow(String code, String bigCode, String bigName, String smallCode, String smallTitle, String sourceFile, int rowIndex) {
            this.code = code;
            this.bigCode = bigCode;
            this.bigName = bigName;
            this.smallCode = smallCode;
            this.smallTitle = smallTitle;
            this.sourceFile = sourceFile;
            this.rowIndex = rowIndex;
        }
    }

    // DB 行数据容器（只缓存用于比较和可能复用 embedding 的字段）
    private static class DbRow {
        final String code;
        final String bigCode;
        final String bigName;
        final String smallCode;
        final String smallTitle;
        final String embeddingLiteral; // embedding 字面量（如果 DB 支持 textcast）

        DbRow(String code, String bigCode, String bigName, String smallCode, String smallTitle, String embeddingLiteral) {
            this.code = code;
            this.bigCode = bigCode;
            this.bigName = bigName;
            this.smallCode = smallCode;
            this.smallTitle = smallTitle;
            this.embeddingLiteral = embeddingLiteral;
        }
    }
}
