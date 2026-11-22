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
    record Choice(String value, Evidence evidence, boolean tentative, String reviewReason) {
        boolean needsCorrection() {
            return tentative || reviewReason != null;
        }
    }
    record Field(String key, Slot slot, List<Choice> choices, Integer selected, String state, String change) {
        Choice choice() {
            return selected == null ? null : choices.get(selected);
        }

        boolean actionable() {
            return slot.role().equals("学生") && slot.type().equals("截止")
                    && List.of("学分申报", "竞赛报名").contains(slot.stage());
        }
    }
    record Version(long id, long matterId, int number, Long baseVersionId, String sourceUrl,
                   LocalDate noticeDate, boolean simulated, String snapshot, String hash,
                   List<Field> fields, String state) {
        Field field(String key) {
            return fields.stream().filter(field -> field.key().equals(key)).findFirst().orElse(null);
        }
    }
    record DraftInput(Long matterId, String title, String category, String sourceUrl,
                      LocalDate noticeDate, boolean simulated, String snapshot) {}
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
    record Viewer(String username, String role) {}
    record Feedback(long id, long matterId, long versionId, String title, String message,
                    boolean handled, LocalDateTime createdAt) {}
    record Event(long id, long matterId, Long versionId, String title, String actor,
                 String action, String detail, LocalDateTime createdAt) {}
}
