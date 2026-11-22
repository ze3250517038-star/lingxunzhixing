package cn.sgu.lingxun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.sgu.lingxun.Models.*;

/**
 * 字段级变更语义：只根据“上一版已确认值”和“当前版已确认值/候选状态”判断，
 * 不把未确认候选当成已生效值。
 */
final class ChangeAnalyzer {
    private ChangeAnalyzer() {}

    static ChangeSummary analyze(Version previous, Version current) {
        Map<String, Field> before = new LinkedHashMap<>();
        for (Field field : previous.fields()) before.put(field.key(), field);
        Map<String, Field> after = new LinkedHashMap<>();
        for (Field field : current.fields()) after.put(field.key(), field);

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<ChangeImpact> impacts = new ArrayList<>();
        Set<String> roles = new LinkedHashSet<>();
        int affected = 0;
        int tasks = 0;
        for (String key : keys) {
            Field oldField = before.get(key);
            Field newField = after.get(key);
            Slot slot = newField != null ? newField.slot() : oldField.slot();
            String previousValue = confirmed(oldField) ? oldField.choice().value() : null;
            String currentValue = confirmed(newField) ? newField.choice().value() : null;
            String treatment = treatment(oldField, newField, previousValue, currentValue);
            boolean changed = !"继承".equals(treatment);
            boolean task = changed && isStudentDeadline(slot);
            if (changed) {
                affected++;
                roles.add(slot.role());
                if (task) tasks++;
            }
            impacts.add(new ChangeImpact(key, slot.role(), slot.stage(), slot.type(), previousValue,
                    currentValue, treatment, changed, task));
        }
        return new ChangeSummary(current.id(), previous.id(), impacts, new ArrayList<>(roles), affected, tasks);
    }

    private static String treatment(Field oldField, Field newField, String previousValue, String currentValue) {
        if (oldField == null) return "新增";
        if (newField == null) return "无法判断";
        if (newField.choices().size() > 1 && newField.selected() == null) return "冲突";
        if (!confirmed(oldField) || !confirmed(newField)) return "无法判断";
        return Objects.equals(previousValue, currentValue) ? "继承" : "覆盖";
    }

    private static boolean confirmed(Field field) {
        return field != null && "CONFIRMED".equals(field.state()) && field.choice() != null
                && field.choice().value() != null;
    }

    private static boolean isStudentDeadline(Slot slot) {
        return "学生".equals(slot.role()) && "截止".equals(slot.type())
                && List.of("学分申报", "竞赛报名").contains(slot.stage());
    }
}
