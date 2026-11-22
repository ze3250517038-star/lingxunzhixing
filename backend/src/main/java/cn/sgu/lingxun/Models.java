package cn.sgu.lingxun;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

final class Models {
    private Models() {}

    record Matter(long id, String title, String category, Long currentVersionId) {}
    record MatterView(Matter matter, Version version, boolean subscribed) {}
    record Fragment(int number, int start, int end, String text) {}
    record Evidence(long versionId, int fragmentNo, int start, int end, String quote) {}
    record Slot(String role, String stage, String type) {
        String key() {
            return role + "/" + stage + "/" + type;
        }
    }
    record Choice(String value, Evidence evidence, boolean tentative, String reviewReason,
                  String extractionSource, String supportLevel, String supportReason) {
        Choice(String value, Evidence evidence, boolean tentative, String reviewReason) {
            this(value, evidence, tentative, reviewReason, "RULE", null, null);
        }

        String source() {
            return extractionSource == null ? "UNKNOWN" : extractionSource;
        }

        String support() {
            return supportLevel == null ? "UNKNOWN" : supportLevel;
        }

        boolean needsCorrection() {
            return tentative || reviewReason != null;
        }
    }
    record Field(String key, Slot slot, List<Choice> choices, Integer selected, String state, String change,
                 String extractionSource) {
        Field(String key, Slot slot, List<Choice> choices, Integer selected, String state, String change) {
            this(key, slot, choices, selected, state, change, "RULE");
        }

        Choice choice() {
            return selected == null ? null : choices.get(selected);
        }

        String source() {
            return extractionSource == null ? "UNKNOWN" : extractionSource;
        }

        boolean actionable() {
            return slot.role().equals("学生") && slot.type().equals("截止")
                    && List.of("学分申报", "竞赛报名").contains(slot.stage());
        }
    }
    record ExtractionMeta(String mode, String status, String model, List<String> warnings, String updatedAt) {
        ExtractionMeta {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
    record Version(long id, long matterId, int number, Long baseVersionId, String sourceUrl,
                   LocalDate noticeDate, boolean simulated, String snapshot, String hash,
                   List<Field> fields, String state, ExtractionMeta extraction) {
        Version(long id, long matterId, int number, Long baseVersionId, String sourceUrl,
                LocalDate noticeDate, boolean simulated, String snapshot, String hash,
                List<Field> fields, String state) {
            this(id, matterId, number, baseVersionId, sourceUrl, noticeDate, simulated, snapshot, hash,
                    fields, state, new ExtractionMeta("RULE", "RULE_ONLY", null, List.of(), null));
        }

        Field field(String key) {
            return fields.stream().filter(field -> field.key().equals(key)).findFirst().orElse(null);
        }
    }
    record DraftInput(Long matterId, String title, String category, String sourceUrl,
                      LocalDate noticeDate, boolean simulated, String snapshot, String extractionMode) {
        DraftInput(Long matterId, String title, String category, String sourceUrl,
                   LocalDate noticeDate, boolean simulated, String snapshot) {
            this(matterId, title, category, sourceUrl, noticeDate, simulated, snapshot, "RULE");
        }
    }
    record ReviewInput(String key, String decision, Integer option) {}
    record CorrectionInput(Slot slot, String value, Integer fragmentNo, Integer start, Integer end, String reason) {}
    record Todo(long id, String username, long matterId, String fieldKey,
                long acknowledgedVersionId, Long targetVersionId, String state) {}
    record TodoStep(String key, String label, String detail, String kind, boolean done) {}
    record TodoView(long id, long matterId, String title, String stage, String state,
                    int previousVersion, Integer currentVersion, Long targetVersionId,
                    Field previous, Field current, boolean simulated, String dueAt,
                    List<TodoStep> steps) {}
    record CalendarDeadline(long todoId, String title, String stage, String dueAt, int versionNumber,
                            boolean simulated) {}
    record ChangeImpact(String fieldKey, String role, String stage, String type, String previousValue,
                        String currentValue, String treatment, boolean affected, boolean taskAffected) {}
    record ChangeSummary(long versionId, Long baseVersionId, List<ChangeImpact> fields,
                         List<String> affectedRoles, int affectedFields, int affectedTasks) {
        ChangeSummary {
            fields = fields == null ? List.of() : List.copyOf(fields);
            affectedRoles = affectedRoles == null ? List.of() : List.copyOf(affectedRoles);
        }
    }
    record Viewer(String username, String role) {}
    record Feedback(long id, long matterId, long versionId, String title, String message,
                    boolean handled, LocalDateTime createdAt) {}
    record Event(long id, long matterId, Long versionId, String title, String actor,
                 String action, String detail, LocalDateTime createdAt) {}
}
