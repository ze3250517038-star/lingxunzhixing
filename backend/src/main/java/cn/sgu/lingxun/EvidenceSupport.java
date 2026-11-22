package cn.sgu.lingxun;

import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static cn.sgu.lingxun.Models.*;

/**
 * 对“原文中存在这段证据”做进一步判断：这段证据是否足以支持当前字段结论。
 *
 * <p>这里刻意采用保守规则，不把语义判断交给模型。无法证明足够支持时返回
 * INSUFFICIENT/WEAK，由维护者继续核对，而不是自动认定为真实值。</p>
 */
final class EvidenceSupport {
    static final String SUFFICIENT = "SUFFICIENT";
    static final String WEAK = "WEAK";
    static final String INSUFFICIENT = "INSUFFICIENT";
    static final String UNKNOWN = "UNKNOWN";

    private EvidenceSupport() {}

    static List<Field> enrich(Version version, List<Field> fields) {
        List<Field> enriched = new ArrayList<>();
        for (Field field : fields) {
            List<Choice> choices = new ArrayList<>();
            for (Choice choice : field.choices()) {
                choices.add(assess(version, field.slot(), choice));
            }
            enriched.add(new Field(field.key(), field.slot(), choices, field.selected(), field.state(),
                    field.change(), field.source()));
        }
        return enriched;
    }

    static Choice assess(Version version, Slot slot, Choice choice) {
        if (choice == null) return null;
        String source = choice.source();
        if (choice.value() == null || choice.value().isBlank()) {
            return copy(choice, INSUFFICIENT, "字段值为空，无法判断证据支持");
        }
        if (choice.evidence() == null) {
            return copy(choice, INSUFFICIENT, "缺少原文证据");
        }
        Evidence evidence = choice.evidence();
        List<Fragment> fragments = NoticeRules.fragments(version.snapshot());
        if (evidence.fragmentNo() < 1 || evidence.fragmentNo() > fragments.size()) {
            return copy(choice, INSUFFICIENT, "证据片段编号超出原文范围");
        }
        Fragment fragment = fragments.get(evidence.fragmentNo() - 1);
        if (evidence.start() < fragment.start() || evidence.end() > fragment.end()
                || evidence.start() >= evidence.end()
                || !version.snapshot().substring(evidence.start(), evidence.end()).equals(evidence.quote())) {
            return copy(choice, INSUFFICIENT, "证据区间不能从原文回切");
        }
        String context = fragment.text();
        String quote = evidence.quote();
        if (choice.tentative() || choice.reviewReason() != null) {
            return copy(choice, INSUFFICIENT,
                    choice.reviewReason() == null ? "原文标注暂定或待定" : choice.reviewReason());
        }
        return switch (slot.type()) {
            case "截止", "时间" -> assessDate(version, slot, choice, context);
            case "适用对象" -> assessText(choice, context, quote,
                    List.of("适用对象", "参赛对象", "申报对象", "对象", "年级", "学院", "专业",
                            "学生", "在校", "全日制", "资格", "学历"));
            case "材料" -> assessText(choice, context, quote,
                    List.of("材料", "证明", "复印件", "原件", "盖章", "提交", "审核", "附件"));
            case "入口" -> assessText(choice, context, quote,
                    List.of("入口", "报名", "登录", "系统", "平台", "网址", "链接", "邮箱", "地点",
                            "提交", "http", "www"));
            default -> copy(choice, UNKNOWN, "该字段类型没有配置证据充分性规则");
        };
    }

    private static Choice assessDate(Version version, Slot slot, Choice choice, String context) {
        boolean deadline = "截止".equals(slot.type());
        NoticeDates.Result parsed = NoticeDates.extract(context, version.noticeDate(), deadline);
        if (parsed.value() == null) {
            if ("MANUAL".equals(choice.source()) && looseMonthDayMatch(context, choice.value())) {
                return copy(choice, SUFFICIENT, "原文的月日和时间与人工核定值一致，年份由维护者明确填写");
            }
            return copy(choice, INSUFFICIENT,
                    parsed.reviewReason() == null ? "引用文本没有可归一化的日期" : parsed.reviewReason());
        }
        String expected = normalizeValue(choice.value(), version, deadline);
        if (expected == null) {
            return copy(choice, INSUFFICIENT, "字段值不是可解析的日期");
        }
        if (!expected.equals(parsed.value())) {
            return copy(choice, WEAK, "字段值与引用文本中的日期不一致：原文为 " + parsed.value());
        }
        return copy(choice, SUFFICIENT, "引用文本包含与字段值一致的日期");
    }

    private static Choice assessText(Choice choice, String context, String quote, List<String> keywords) {
        String value = choice.value().toLowerCase(Locale.ROOT);
        String flatQuote = quote.toLowerCase(Locale.ROOT);
        String flatContext = context.toLowerCase(Locale.ROOT);
        if (!flatQuote.contains(value) && !flatContext.contains(value)) {
            return copy(choice, WEAK, "引用文本没有直接出现字段值");
        }
        boolean hasContext = keywords.stream().anyMatch(flatContext::contains);
        if (!hasContext) {
            return copy(choice, WEAK, "引用文本缺少该字段类型所需的上下文");
        }
        return copy(choice, SUFFICIENT, "引用文本包含字段值及对应上下文");
    }

    private static String normalizeValue(String value, Version version, boolean deadline) {
        try {
            return NoticeDates.normalizeManual(value, deadline);
        } catch (DateTimeException ignored) {
            NoticeDates.Result parsed = NoticeDates.extract(value, version.noticeDate(), deadline);
            return parsed.value();
        }
    }

    private static boolean looseMonthDayMatch(String context, String value) {
        if (value == null || value.length() < 10) return false;
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(value.substring(0, 10));
            String monthDay = date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
            String padded = String.format("%02d月%02d日", date.getMonthValue(), date.getDayOfMonth());
            if (!context.contains(monthDay) && !context.contains(padded)) return false;
            if (value.length() < 16 || value.charAt(10) != 'T') return true;
            String time = value.substring(11, 16);
            return context.contains(time) || context.contains(time.replace(":", "："))
                    || context.contains(time.replaceFirst("^0", ""));
        } catch (DateTimeException invalid) {
            return false;
        }
    }

    private static Choice copy(Choice choice, String level, String reason) {
        return new Choice(choice.value(), choice.evidence(), choice.tentative(), choice.reviewReason(),
                choice.source(), level, reason);
    }
}
