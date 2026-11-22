package cn.sgu.lingxun;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NoticeDates {
    private static final String JOIN = "\\h*(?:起\\h*)?(?:至|到|[-－–—−~～]{1,2})\\h*";
    private static final Pattern EXPRESSION = Pattern.compile("(?<![\\d年月])" + endpoint("Start", false)
            + "(?:" + JOIN + endpoint("End", true) + ")?");
    private static final Pattern EXTRA_DATE_OR_TIME = Pattern.compile(
            "(?:" + JOIN + "(?:\\d|待定|另行通知|$)|\\h*(?:[,，、]|或|和)\\h*\\d{1,2}\\h*[日号]"
                    + "|\\h*(?:(?:上午|下午|晚上)\\h*)?\\d{1,2}\\h*(?:[:：点时])|\\h*[:：]\\d{2}"
                    + "|\\h*[（(]?\\h*(?:左右|前后))");
    private static final Pattern START_ONLY = Pattern.compile("\\h*[（(]?\\h*(?:起|开始|开放|启动)");
    private static final Pattern ISO_VALUE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(?:T(\\d{2}:\\d{2}))?");

    private NoticeDates() {}

    record Result(String value, String reviewReason) {
        static Result pending(String reason) {
            return new Result(null, reason);
        }
    }

    static Result extract(String line, LocalDate noticeDate, boolean deadline) {
        Matcher match = EXPRESSION.matcher(line);
        if (!match.find()) return Result.pending("原文没有可归一化的具体日期，请核对后填写。");
        try {
            DateText start = read(match, "Start", 0);
            DateText end = read(match, "End", start.month());
            String remaining = line.substring(match.end());
            boolean extraDetails = EXTRA_DATE_OR_TIME.matcher(remaining).lookingAt();
            if (extraDetails || match.find()) {
                return Result.pending("同一片段有多处日期或未识别完整的区间，请核对适用日期。");
            }
            if (deadline && end == null && START_ONLY.matcher(remaining).lookingAt()) {
                return Result.pending("原文只给出起始日期，请核对截止日期。");
            }
            return resolve(start, end, noticeDate, deadline);
        } catch (DateTimeException invalid) {
            return Result.pending("日期或时间不合法，请核对原文。");
        }
    }

    private static String endpoint(String part, boolean shortEnd) {
        String yearAndMonth = "(?:(?<year" + part + ">\\d{4}\\h*年|今年|本年|明年|次年|翌年|同年|去年|上年)\\h*)?"
                + "(?<month" + part + ">\\d{1,2})\\h*月\\h*";
        String suffix = shortEnd ? "[日号]" : "(?:[日号]|(?=" + JOIN + "))";
        return (shortEnd ? "(?:" + yearAndMonth + ")?" : yearAndMonth)
                + "(?<day" + part + ">\\d{1,2})(?!\\d)\\h*" + suffix
                + "(?:\\h*(?<hour" + part + ">\\d{1,2})[:：](?<minute" + part + ">\\d{2})(?!\\d))?";
    }

    private static DateText read(Matcher match, String part, int fallbackMonth) {
        String day = match.group("day" + part);
        if (day == null) return null;
        String month = match.group("month" + part);
        String hour = match.group("hour" + part);
        LocalTime time = hour == null ? null
                : LocalTime.of(Integer.parseInt(hour), Integer.parseInt(match.group("minute" + part)));
        return new DateText(match.group("year" + part), month == null ? fallbackMonth : Integer.parseInt(month),
                Integer.parseInt(day), time);
    }

    private static Result resolve(DateText start, DateText end, LocalDate notice, boolean deadline) {
        int noticeYear = notice.getYear();
        boolean yearBoundary = end != null && start.month() >= 10 && end.month() <= 3;
        int inferredStartYear = noticeYear;
        if (start.year() == null && end != null && end.anchoredYear()) {
            inferredStartYear = end.resolveYear(noticeYear, noticeYear, noticeYear) - (yearBoundary ? 1 : 0);
        }
        int startYear = start.resolveYear(inferredStartYear, noticeYear, noticeYear);
        Point first = start.atYear(startYear);
        Point last = end == null ? first
                : end.atYear(end.resolveYear(startYear + (yearBoundary ? 1 : 0), noticeYear, startYear));

        if (last.isBefore(first)) {
            return Result.pending("区间结束早于开始，请核对日期、年份或时间。");
        }
        boolean assumedYear = start.year() == null && (end == null || end.year() == null);
        if (assumedYear && (last.date().isBefore(notice) || (yearBoundary && !last.date().minusYears(1).isBefore(notice)))) {
            return Result.pending("原文未写明年份，无法仅凭发布日期确定对应年份。");
        }
        String value = deadline || end == null ? last.value() : first.value() + "/" + last.value();
        return new Result(value, null);
    }

    static String normalizeManual(String value, boolean deadline) {
        String[] dates = value.split("/", -1);
        if (dates.length > 2 || (deadline && dates.length != 1)) {
            throw new DateTimeException("截止字段须为具体日期，时间字段可填写日期区间");
        }
        Point first = readIso(dates[0]);
        if (dates.length == 1) return first.value();
        Point last = readIso(dates[1]);
        if (last.isBefore(first)) throw new DateTimeException("区间结束早于开始");
        return first.value() + "/" + last.value();
    }

    private static Point readIso(String value) {
        Matcher match = ISO_VALUE.matcher(value);
        if (!match.matches()) throw new DateTimeException("请用 YYYY-MM-DD，可附加 T时:分");
        LocalDate date = LocalDate.parse(match.group(1));
        return new Point(date, match.group(2) == null ? null : LocalTime.parse(match.group(2)));
    }

    private record DateText(String year, int month, int day, LocalTime time) {
        boolean anchoredYear() {
            return year != null && !year.equals("次年") && !year.equals("翌年") && !year.equals("同年");
        }

        int resolveYear(int fallback, int noticeYear, int relativeYear) {
            if (year == null) return fallback;
            return switch (year) {
                case "今年", "本年" -> noticeYear;
                case "明年" -> noticeYear + 1;
                case "次年", "翌年" -> relativeYear + 1;
                case "同年" -> relativeYear;
                case "去年", "上年" -> noticeYear - 1;
                default -> Integer.parseInt(year.substring(0, 4));
            };
        }

        Point atYear(int year) {
            return new Point(LocalDate.of(year, month, day), time);
        }
    }

    private record Point(LocalDate date, LocalTime time) {
        Point {
            if (date.getYear() < 1 || date.getYear() > 9999) throw new DateTimeException("年份超出范围");
        }

        boolean isBefore(Point other) {
            return date.isBefore(other.date) || (date.equals(other.date) && time != null && other.time != null
                    && time.isBefore(other.time));
        }

        String value() {
            return time == null ? date.toString() : date.atTime(time).toString();
        }
    }
}
