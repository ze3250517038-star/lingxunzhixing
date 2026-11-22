package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static cn.sgu.lingxun.Models.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模拟参考数据集的结构与规则抽取回归。
 *
 * <p>这里只验证 SIMULATED_REFERENCE 数据本身可被规则引擎消费，不能作为真实通知
 * 上的准确率结论。</p>
 */
class SimulatedDatasetTest {
    private static final Path DATASET = Path.of("..", "docs", "datasets", "lingxun-simulated-reference-v1.json");

    @Test
    void simulatedReferenceSamplesAreConsistentWithRuleExtraction() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(DATASET));
        assertThat(root.path("provenance").asText()).isEqualTo("SIMULATED_REFERENCE");
        JsonNode samples = root.path("samples");
        assertThat(samples.size()).isEqualTo(10);

        LlmClient disabled = new LlmClient(new ObjectMapper(), false, "", "", "", 20, 12000);
        NoticeExtractionService extraction = new NoticeExtractionService(disabled);

        for (JsonNode sample : samples) {
            String snapshot = sample.path("snapshot").asText();
            String category = sample.path("category").asText();
            Version draft = new Version(1, 1, 1, null, sample.path("sourceUrl").asText(),
                    LocalDate.parse(sample.path("noticeDate").asText()), true, snapshot, "test-hash",
                    List.of(), "DRAFT");
            NoticeExtractionService.Outcome outcome = extraction.enhance(draft, category, ruleFields(draft, category), "RULE");

            for (JsonNode expected : sample.path("expectedFields")) {
                assertThat(snapshot).contains(expected.path("quote").asText());
                Field field = outcome.fields().stream()
                        .filter(candidate -> candidate.key().equals(expected.path("key").asText()))
                        .findFirst().orElse(null);
                assertThat(field).as(sample.path("id").asText() + " missing " + expected.path("key").asText())
                        .isNotNull();
                assertThat(field.choice()).as(sample.path("id").asText() + " has no selected choice").isNotNull();
                assertThat(field.choice().value()).isEqualTo(expected.path("value").asText());
                assertThat(field.choice().support()).isEqualTo("SUFFICIENT");
            }

            for (JsonNode uncertain : sample.path("expectedUncertain")) {
                Field field = outcome.fields().stream()
                        .filter(candidate -> candidate.key().equals(uncertain.path("key").asText()))
                        .findFirst().orElse(null);
                if (field != null) {
                    assertThat(field.choice()).as(sample.path("id").asText() + " uncertain field was auto-selected")
                            .isNull();
                    assertThat(field.state()).isNotEqualTo("CONFIRMED");
                }
            }
        }
    }

    private static List<Field> ruleFields(Version draft, String category) {
        List<Field> fields = new java.util.ArrayList<>();
        NoticeRules.extract(draft, category).forEach((slot, choices) -> {
            boolean conflict = choices.size() > 1;
            boolean needsCorrection = choices.get(0).needsCorrection();
            fields.add(new Field(slot.key(), slot, choices, conflict || needsCorrection ? null : 0,
                    "CANDIDATE", conflict ? "冲突" : needsCorrection ? "待核验" : "新增"));
        });
        return fields;
    }
}
