package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static cn.sgu.lingxun.Models.*;
import static org.assertj.core.api.Assertions.assertThat;

class NoticeExtractionTest {
    private final Version draft = new Version(77, 9, 1, null, "https://example.edu/simulated/1",
            LocalDate.parse("2026-11-01"), true,
            "适用对象：全日制在校本科生。\n报名时间：2026年11月3日09:00至2026年11月10日17:30。\n材料要求：报名表。",
            "hash", List.of(), "DRAFT");

    @Test
    void hybridFallsBackToRuleWhenNoKeyIsConfiguredWithoutPretendingLlmSuccess() {
        NoticeExtractionService service = new NoticeExtractionService(disabledLlm());
        NoticeExtractionService.Outcome outcome = service.enhance(draft, "竞赛报名", ruleFields(), "HYBRID");

        assertThat(outcome.meta().mode()).isEqualTo("RULE_FALLBACK");
        assertThat(outcome.meta().status()).isEqualTo("DISABLED");
        assertThat(outcome.meta().warnings()).anyMatch(warning -> warning.contains("已回退规则模式"));
        assertThat(outcome.fields()).allSatisfy(field -> {
            assertThat(field.source()).isEqualTo("RULE");
            assertThat(field.choices()).allSatisfy(choice -> assertThat(choice.source()).isEqualTo("RULE"));
        });
    }

    @Test
    void llmModeAlsoFallsBackHonestlyWhenNoKeyIsConfigured() {
        NoticeExtractionService service = new NoticeExtractionService(disabledLlm());
        NoticeExtractionService.Outcome outcome = service.enhance(draft, "竞赛报名", ruleFields(), "LLM");

        assertThat(outcome.meta().mode()).isEqualTo("RULE_FALLBACK");
        assertThat(outcome.meta().model()).isNull();
        assertThat(outcome.fields()).isNotEmpty();
    }

    @Test
    void conditionThatSaysAnotherNoticeIsNotAutomaticallySelected() {
        Version pending = new Version(78, 9, 2, null, "https://example.edu/simulated/2",
                LocalDate.parse("2026-11-01"), true,
                "适用对象：另行通知。\n材料要求：待定。", "hash", List.of(), "DRAFT");
        NoticeExtractionService.Outcome outcome = new NoticeExtractionService(disabledLlm())
                .enhance(pending, "竞赛报名", ruleFields(pending), "RULE");

        assertThat(outcome.fields()).allSatisfy(field -> {
            assertThat(field.state()).isEqualTo("CANDIDATE");
            assertThat(field.selected()).isNull();
            assertThat(field.choices()).allSatisfy(choice -> {
                assertThat(choice.reviewReason()).isNotNull();
                assertThat(choice.support()).isEqualTo("INSUFFICIENT");
            });
        });
    }

    private static LlmClient disabledLlm() {
        return new LlmClient(new ObjectMapper(), false, "", "", "", 20, 12000);
    }

    private List<Field> ruleFields() {
        return ruleFields(draft);
    }

    private static List<Field> ruleFields(Version source) {
        List<Field> fields = new ArrayList<>();
        NoticeRules.extract(source, "竞赛报名").forEach((slot, choices) -> {
            boolean conflict = choices.size() > 1;
            boolean needsCorrection = choices.get(0).needsCorrection();
            fields.add(new Field(slot.key(), slot, choices, conflict || needsCorrection ? null : 0,
                    "CANDIDATE", conflict ? "冲突" : needsCorrection ? "待核验" : "新增"));
        });
        return fields;
    }
}
