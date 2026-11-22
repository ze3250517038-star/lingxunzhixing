package cn.sgu.lingxun;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {
    @Test
    void writesReproducibleSimulatedEvaluationReports() throws Exception {
        Path dataset = Path.of("..", "docs", "datasets", "lingxun-simulated-reference-v1.json");
        Path output = Path.of("..", "docs", "evaluation");
        EvaluationRunner.Summary summary = EvaluationRunner.run(dataset, output);

        assertThat(summary.provenance()).isEqualTo("SIMULATED_REFERENCE");
        assertThat(summary.modes()).hasSize(3);
        assertThat(summary.modes()).allSatisfy(mode -> {
            assertThat(mode.rows()).isPositive();
            assertThat(mode.precision()).isBetween(0.0, 1.0);
            assertThat(mode.recall()).isBetween(0.0, 1.0);
            assertThat(mode.f1()).isBetween(0.0, 1.0);
        });
        assertThat(Files.readString(output.resolve("summary.csv")))
                .contains("SIMULATED_REFERENCE", "RULE", "HYBRID", "LLM");
        assertThat(output.resolve("field-predictions-rule.csv")).exists();
        assertThat(output.resolve("field-predictions-hybrid.csv")).exists();
        assertThat(output.resolve("field-predictions-llm.csv")).exists();
    }
}
