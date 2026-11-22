package cn.sgu.lingxun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static cn.sgu.lingxun.Models.*;

final class NoticeRules {
    private static final Pattern FIELD_LABEL = Pattern.compile(
            "^(适用对象|参赛对象|申报对象|材料要求|申报材料|报名材料|办理入口|报名入口|报名链接|登录地址)[：:]\\s*(.+)$");
    private static final Slot COLLEGE = new Slot("学院", "学院审核", "截止");
    private static final Slot SCHOOL = new Slot("学校", "学校终审", "截止");
    private static final Slot PRELIMINARY = new Slot("参赛学生", "初赛", "时间");
    private static final Slot FINAL = new Slot("参赛学生", "决赛", "时间");
    private static final List<String> CONDITION_TYPES = List.of("适用对象", "材料", "入口");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("适用对象", "适用对象"), Map.entry("参赛对象", "适用对象"),
            Map.entry("申报对象", "适用对象"), Map.entry("材料要求", "材料"),
            Map.entry("申报材料", "材料"), Map.entry("报名材料", "材料"),
            Map.entry("办理入口", "入口"), Map.entry("报名入口", "入口"),
            Map.entry("报名链接", "入口"), Map.entry("登录地址", "入口"));

    private NoticeRules() {}

    static List<Fragment> fragments(String snapshot) {
        List<Fragment> fragments = new ArrayList<>();
        int start = 0;
        String[] lines = snapshot.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            fragments.add(new Fragment(index + 1, start, start + line.length(), line));
            start += line.length() + 1;
        }
        return fragments;
    }

    static Map<Slot, List<Choice>> extract(Version version, String category) {
        Map<Slot, List<Choice>> candidates = new LinkedHashMap<>();
        Slot student = studentSlot(category);
        Slot context = student;
        for (Fragment fragment : fragments(version.snapshot())) {
            String line = fragment.text().strip();
            Slot stage = timeSlot(line, student);
            if (stage != null) context = stage;
            var label = FIELD_LABEL.matcher(line);
            Slot slot;
            String value;
            String reviewReason = null;
            if (label.matches()) {
                String type = TYPES.get(label.group(1));
                slot = new Slot(context.role(), context.stage(), type);
                value = label.group(2).strip();
            } else if (stage != null || line.matches("^(截止|时间)[：:].*")) {
                slot = context;
                var parsed = NoticeDates.extract(line, version.noticeDate(), "截止".equals(slot.type()));
                value = parsed.value();
                reviewReason = parsed.reviewReason();
            } else {
                continue;
            }
            Evidence evidence = new Evidence(version.id(), fragment.number(), fragment.start(), fragment.end(), fragment.text());
            Choice candidate = new Choice(value, evidence, isTentative(line), reviewReason);
            List<Choice> options = candidates.computeIfAbsent(slot, ignored -> new ArrayList<>());
            if (value == null || options.stream().noneMatch(option -> Objects.equals(option.value(), candidate.value())
                    && option.tentative() == candidate.tentative())) {
                options.add(candidate);
            }
        }
        return candidates;
    }

    static List<Slot> slots(String category) {
        List<Slot> slots = new ArrayList<>();
        for (Slot stage : List.of(studentSlot(category), COLLEGE, SCHOOL, PRELIMINARY, FINAL)) {
            slots.add(stage);
            for (String condition : CONDITION_TYPES) {
                slots.add(new Slot(stage.role(), stage.stage(), condition));
            }
        }
        return slots;
    }

    static boolean isTentative(String line) {
        return line.contains("暂定") || line.contains("待定");
    }

    private static Slot studentSlot(String category) {
        return new Slot("学生", category.equals("学分认定") ? "学分申报" : "竞赛报名", "截止");
    }

    private static Slot timeSlot(String line, Slot student) {
        if (startsWithHeading(line, "二级学院审核", "学院审核")) return COLLEGE;
        if (startsWithHeading(line, "学校终审", "校级终审")) return SCHOOL;
        if (startsWithHeading(line, "初赛")) return PRELIMINARY;
        if (startsWithHeading(line, "决赛")) return FINAL;
        if (startsWithHeading(line, "学生个人申报", "学生申报", "学生报名", "报名时间", "报名截止", "报名日期", "个人申报")) {
            return student;
        }
        return null;
    }

    private static boolean startsWithHeading(String line, String... labels) {
        String text = line.replaceFirst("^\\s*(?:[（(][一二三四五六七八九十0-9]+[）)]|[一二三四五六七八九十0-9]+[、.])\\s*", "");
        for (String label : labels) if (text.startsWith(label)) return true;
        return false;
    }

}
