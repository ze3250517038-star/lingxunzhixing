package cn.sgu.lingxun;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeDatesTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2026-11-16 | 报名时间11月20-21日 | 2026-11-21
            2026-11-16 | 报名时间为11月20—21日 | 2026-11-21
            2026-11-16 | 报名时间为2026年11月20日至21日17：30 | 2026-11-21T17:30
            2026-09-10 | 学生个人申报（2026年9月10日至12月20日） | 2026-12-20
            2026-10-26 | 报名时间统一为2026年10月27日-11月2日 | 2026-11-02
            2026-11-02 | 报名截止11月2日 | 2026-11-02
            2026-12-20 | 报名时间12月28日～1月5日 | 2027-01-05
            2026-12-20 | 报名时间为2026年12月28日至2027年1月5日 | 2027-01-05
            2026-12-20 | 报名时间为2026年12月28日起至次年1月5日 | 2027-01-05
            2026-12-20 | 报名截止明年1月5日 | 2027-01-05
            2027-01-02 | 报名时间12月28日至2027年1月5日 | 2027-01-05
            2026-12-20 | 报名截止2027年1月5日09:30 | 2027-01-05T09:30
            """)
    void normalizesTheDeadline(String noticeDate, String source, String dueAt) {
        var result = NoticeDates.extract(source, LocalDate.parse(noticeDate), true);
        assertThat(result.value()).isEqualTo(dueAt);
        assertThat(result.reviewReason()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2026-12-20 | 报名截止1月5日 | 年份
            2027-01-02 | 报名时间12月28日至1月5日 | 年份
            2026-11-16 | 报名截止11月20日或11月21日 | 多处日期
            2026-11-16 | 报名截止11月20日、21日 | 多处日期
            2026-11-16 | 报名截止11月20日- | 区间
            2026-11-16 | 报名截止11月20日-21 | 区间
            2026-11-16 | 报名时间自11月20日起 | 起始
            2026-11-16 | 报名时间11月21日—20日 | 结束早于开始
            2026-11-16 | 报名截止11月31日 | 不合法
            2026-11-16 | 报名截止2026年11月20日25:00 | 不合法
            2026-11-16 | 报名截止以补充通知为准 | 具体日期
            """)
    void flagsDatesThatNeedAReviewer(String noticeDate, String source, String reason) {
        var result = NoticeDates.extract(source, LocalDate.parse(noticeDate), true);
        assertThat(result.value()).isNull();
        assertThat(result.reviewReason()).contains(reason);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            初赛：2026年11月7日 9:00（具体地点另行通知） | 2026-11-07T09:00
            初赛：2026年11月7日9:00至8日17:30 | 2026-11-07T09:00/2026-11-08T17:30
            """)
    void retainsBothEndpointsForStageTimes(String source, String expected) {
        assertThat(NoticeDates.extract(source, LocalDate.of(2026, 10, 26), false).value()).isEqualTo(expected);
    }
}
