package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.sgu.lingxun.Models.*;

/**
 * 可复现评测工具：读取人工标注数据集，分别以 RULE、HYBRID、LLM 请求模式运行，
 * 逐字段输出预测、标准答案、证据定位和错误分析。
 *
 * <p>本工具不会伪造模型结果：未配置 API Key 时 HYBRID/LLM 会如实显示
 * RULE_FALLBACK，并在 CSV 的 effective_mode/status 列中体现。</p>
 */
public final class EvaluationRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path dataset = Path.of(args.length > 0 ? args[0] : "../docs/datasets/lingxun-simulated-reference-v1.json");
        Path output = Path.of(args.length > 1 ? args[1] : "../docs/evaluation");
        run(dataset, output);
    }

    static Summary run(Path dataset, Path output) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(dataset));
        String provenance = root.path("provenance").asText();
        if (!"SIMULATED_REFERENCE".equals(provenance)) {
            throw new IllegalArgumentException("当前工具只对 SIMULATED_REFERENCE 数据集执行，避免把模拟结果冒充真实实验");
        }
        JsonNode samples = root.path("samples");
        Files.createDirectories(output);
        LlmClient disabled = new LlmClient(JSON, false, "", "", "", 20, 12000);
        NoticeExtractionService service = new NoticeExtractionService(disabled);

        List<ModeSummary> summaries = new ArrayList<>();
        for (String requested : List.of("RULE", "HYBRID", "LLM")) {
            ModeResult result = evaluate(service, samples, requested);
            summaries.add(result.summary());
            writeCsv(output.resolve("field-predictions-" + requested.toLowerCase(Locale.ROOT) + ".csv"),
                    result.rows());
        }
        Summary summary = new Summary(dataset.toString(), provenance, LocalDateTime.now().toString(), summaries);
        writeSummary(output.resolve("summary.csv"), summary);
        Files.writeString(output.resolve("summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardCharsets.UTF_8);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        return summary;
    }

    private static ModeResult evaluate(NoticeExtractionService service, JsonNode samples, String requested) {
        List<Row> rows = new ArrayList<>();
        int expectedCount = 0, predictedCount = 0, tp = 0, fp = 0, fn = 0;
        int roleMismatch = 0, predictedWithValue = 0, locatable = 0, unsupported = 0;
        int uncertainAutoConfirmed = 0;
        Set<String> effectiveModes = new LinkedHashSet<>();
        Set<String> statuses = new LinkedHashSet<>();

        for (JsonNode sample : samples) {
            String id = sample.path("id").asText();
            String title = sample.path("title").asText();
            String category = sample.path("category").asText();
            String snapshot = sample.path("snapshot").asText();
            Version draft = new Version(1, 1, 1, null, sample.path("sourceUrl").asText(),
                    LocalDate.parse(sample.path("noticeDate").asText()), true, snapshot, "eval-hash",
                    List.of(), "DRAFT");
            NoticeExtractionService.Outcome outcome = service.enhance(draft, category, ruleFields(draft, category), requested);
            effectiveModes.add(outcome.meta().mode());
            statuses.add(outcome.meta().status());

            Map<String, String> expected = new LinkedHashMap<>();
            Set<String> uncertain = new LinkedHashSet<>();
            for (JsonNode field : sample.path("expectedFields")) {
                expected.put(field.path("key").asText(), field.path("value").asText());
            }
            for (JsonNode field : sample.path("expectedUncertain")) {
                uncertain.add(field.path("key").asText());
            }

            Map<String, Field> predictedFields = new LinkedHashMap<>();
            Map<String, String> predicted = new LinkedHashMap<>();
            for (Field field : outcome.fields()) {
                predictedFields.put(field.key(), field);
                if (field.selected() != null && field.choice() != null && field.choice().value() != null) {
                    predicted.put(field.key(), field.choice().value());
                }
            }

            for (Map.Entry<String, String> answer : expected.entrySet()) {
                expectedCount++;
                String key = answer.getKey();
                Field field = predictedFields.get(key);
                String value = predicted.get(key);
                boolean evidenceOk = evidenceLocatable(draft, field);
                boolean supported = supportSufficient(field);
                if (value != null) {
                    predictedCount++;
                    predictedWithValue++;
                    if (evidenceOk) locatable++;
                    if (!supported) unsupported++;
                }
                String result;
                String analysis;
                if (value == null) {
                    fn++;
                    result = "FALSE_NEGATIVE";
                    analysis = "系统未给出选定值；可能仍在候选/待复核，也可能是漏抽";
                } else if (Objects.equals(value, answer.getValue())) {
                    tp++;
                    result = "TRUE_POSITIVE";
                    analysis = evidenceOk && supported ? "" : "值正确但证据定位或充分性不足";
                } else {
                    fn++;
                    fp++;
                    result = "WRONG_VALUE";
                    analysis = "预测 " + value + "，标准答案 " + answer.getValue();
                }
                if (roleMismatch(predictedFields, key)) roleMismatch++;
                rows.add(new Row(id, title, requested, outcome.meta().mode(), outcome.meta().status(), key,
                        answer.getValue(), value, result, quote(field), evidenceOk, support(field), analysis));
            }

            for (Map.Entry<String, String> guess : predicted.entrySet()) {
                if (expected.containsKey(guess.getKey())) continue;
                predictedCount++;
                predictedWithValue++;
                if (evidenceLocatable(draft, predictedFields.get(guess.getKey()))) locatable++;
                if (!supportSufficient(predictedFields.get(guess.getKey()))) unsupported++;
                fp++;
                String analysis = uncertain.contains(guess.getKey())
                        ? "不确定字段被自动选中，违反不得自动确认要求"
                        : "标准答案未定义该字段，可能是多余候选或标注缺口";
                if (uncertain.contains(guess.getKey())) uncertainAutoConfirmed++;
                rows.add(new Row(id, title, requested, outcome.meta().mode(), outcome.meta().status(), guess.getKey(),
                        "", guess.getValue(), "FALSE_POSITIVE", quote(predictedFields.get(guess.getKey())),
                        evidenceLocatable(draft, predictedFields.get(guess.getKey())),
                        support(predictedFields.get(guess.getKey())), analysis));
            }
        }

        double precision = predictedCount == 0 ? 0 : (double) tp / predictedCount;
        double recall = expectedCount == 0 ? 0 : (double) tp / expectedCount;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double evidenceRate = predictedWithValue == 0 ? 0 : (double) locatable / predictedWithValue;
        double unsupportedRate = predictedWithValue == 0 ? 0 : (double) unsupported / predictedWithValue;
        ModeSummary summary = new ModeSummary(requested, String.join("/", effectiveModes),
                String.join("/", statuses), expectedCount, predictedCount, tp, fp, fn,
                round(precision), round(recall), round(f1), roleMismatch,
                round(evidenceRate), round(unsupportedRate), uncertainAutoConfirmed, rows.size());
        return new ModeResult(summary, rows);
    }

    private static boolean roleMismatch(Map<String, Field> predicted, String expectedKey) {
        String[] parts = expectedKey.split("/");
        if (parts.length != 3) return false;
        return predicted.values().stream().anyMatch(field -> field.slot().stage().equals(parts[1])
                && field.slot().type().equals(parts[2]) && !field.slot().role().equals(parts[0]));
    }

    private static boolean evidenceLocatable(Version draft, Field field) {
        if (field == null || field.choice() == null || field.choice().evidence() == null) return false;
        Evidence evidence = field.choice().evidence();
        List<Fragment> fragments = NoticeRules.fragments(draft.snapshot());
        if (evidence.fragmentNo() < 1 || evidence.fragmentNo() > fragments.size()) return false;
        Fragment fragment = fragments.get(evidence.fragmentNo() - 1);
        return evidence.start() >= fragment.start() && evidence.end() <= fragment.end()
                && evidence.start() < evidence.end()
                && evidence.quote().equals(draft.snapshot().substring(evidence.start(), evidence.end()));
    }

    private static boolean supportSufficient(Field field) {
        return field != null && field.choice() != null && EvidenceSupport.SUFFICIENT.equals(field.choice().support());
    }

    private static String quote(Field field) {
        return field == null || field.choice() == null || field.choice().evidence() == null
                ? "" : field.choice().evidence().quote();
    }

    private static String support(Field field) {
        return field == null || field.choice() == null ? "" : field.choice().support();
    }

    private static List<Field> ruleFields(Version source, String category) {
        List<Field> fields = new ArrayList<>();
        NoticeRules.extract(source, category).forEach((slot, choices) -> {
            boolean conflict = choices.size() > 1;
            boolean needsCorrection = choices.get(0).needsCorrection();
            fields.add(new Field(slot.key(), slot, choices, conflict || needsCorrection ? null : 0,
                    "CANDIDATE", conflict ? "冲突" : needsCorrection ? "待核验" : "新增"));
        });
        return fields;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static void writeCsv(Path file, List<Row> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("\uFEFFsample_id,title,requested_mode,effective_mode,status,field_key,expected_value,predicted_value,result,evidence_quote,evidence_locatable,support_level,error_analysis");
        for (Row row : rows) {
            lines.add(String.join(",", csv(row.sampleId()), csv(row.title()), csv(row.requestedMode()),
                    csv(row.effectiveMode()), csv(row.status()), csv(row.fieldKey()), csv(row.expected()),
                    csv(row.predicted()), csv(row.result()), csv(row.evidence()), String.valueOf(row.evidenceLocatable()),
                    csv(row.support()), csv(row.analysis())));
        }
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path file, Summary summary) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("\uFEFFdataset,provenance,generated_at,requested_mode,effective_mode,status,expected_fields,predicted_fields,true_positive,false_positive,false_negative,precision,recall,f1,role_mismatch,evidence_locatable_rate,unsupported_rate,uncertain_auto_confirmed,rows");
        for (ModeSummary mode : summary.modes()) {
            lines.add(String.join(",", csv(summary.dataset()), csv(summary.provenance()), csv(summary.generatedAt()),
                    csv(mode.requestedMode()), csv(mode.effectiveMode()), csv(mode.status()),
                    String.valueOf(mode.expectedFields()), String.valueOf(mode.predictedFields()),
                    String.valueOf(mode.truePositive()), String.valueOf(mode.falsePositive()),
                    String.valueOf(mode.falseNegative()), String.valueOf(mode.precision()),
                    String.valueOf(mode.recall()), String.valueOf(mode.f1()),
                    String.valueOf(mode.roleMismatch()), String.valueOf(mode.evidenceLocatableRate()),
                    String.valueOf(mode.unsupportedRate()), String.valueOf(mode.uncertainAutoConfirmed()),
                    String.valueOf(mode.rows())));
        }
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value == null) return "";
        String clean = value.replace("\r", " ").replace("\n", " ");
        return "\"" + clean.replace("\"", "\"\"") + "\"";
    }

    record Row(String sampleId, String title, String requestedMode, String effectiveMode, String status,
               String fieldKey, String expected, String predicted, String result, String evidence,
               boolean evidenceLocatable, String support, String analysis) {}

    record Summary(String dataset, String provenance, String generatedAt, List<ModeSummary> modes) {}

    record ModeSummary(String requestedMode, String effectiveMode, String status, int expectedFields,
                       int predictedFields, int truePositive, int falsePositive, int falseNegative,
                       double precision, double recall, double f1, int roleMismatch,
                       double evidenceLocatableRate, double unsupportedRate, int uncertainAutoConfirmed,
                       int rows) {}

    private record ModeResult(ModeSummary summary, List<Row> rows) {}
}
