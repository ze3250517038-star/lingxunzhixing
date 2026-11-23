package cn.sgu.lingxun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

import static cn.sgu.lingxun.Models.*;

@Component
class DemoSetup implements ApplicationRunner {
    private final SessionAccess access;
    private final NoticeWorkflow notices;
    private final TimetableService timetable;
    private final String studentPassword;
    private final String reviewerPassword;

    DemoSetup(SessionAccess access, NoticeWorkflow notices, TimetableService timetable,
              @Value("${lingxun.student-password:}") String studentPassword,
              @Value("${lingxun.reviewer-password:}") String reviewerPassword) {
        this.access = access;
        this.notices = notices;
        this.timetable = timetable;
        this.studentPassword = studentPassword;
        this.reviewerPassword = reviewerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!access.demo()) {
            access.provision("student", SessionAccess.STUDENT, studentPassword);
            access.provision("reviewer", SessionAccess.REVIEWER, reviewerPassword);
            return;
        }
        access.provision("student", SessionAccess.STUDENT, "lingxun-demo");
        access.provision("reviewer", SessionAccess.REVIEWER, "lingxun-demo");
        seedCourses();
        if (!notices.matters().isEmpty()) return;
        seed("创新创业实践学分申报", "学分认定", "https://www.sgu.edu.cn/info/1065/123181.htm",
                LocalDate.of(2026, 9, 10), """
                学生个人申报（2026年9月10日至12月20日）
                适用对象：22级学生，以及有意修读本模块课程的大三、大四学生（23级及后续年级）。
                登录地址：jwc.sgu.edu.cn/jsxsd
                材料要求：没有原件的项目需要相关承办单位等审核并盖章方有效。
                二级学院审核（2026年9月11日至12月21日）
                学校终审（2026年9月11日至12月22日）
                """);
        seed("化学实验室安全知识竞赛", "竞赛报名", "https://www.sgu.edu.cn/info/1065/126441.htm",
                LocalDate.of(2026, 10, 26), """
                报名时间统一为2026年10月27日-11月2日
                参赛对象：韶关学院全日制在校学生
                报名入口：填写校方通知中的报名链接，并加入其中一个企业微信群。
                初赛：2026年11月7日 9:00（具体地点另行通知）
                决赛：2026年11月21日 （暂定）
                """);
        var contest = notices.matters().stream().filter(matter -> matter.category().equals("竞赛报名")).findFirst().orElseThrow();
        notices.subscribe("student", contest.id());
    }

    private void seedCourses() {
        if (timetable.count("student") > 0) return;
        timetable.create("student", new CourseInput("物联网导论", "李老师", "信息楼 A203", 1,
                "08:00", "09:40", "ocean", "第一周至第十六周"));
        timetable.create("student", new CourseInput("大学英语", "陈老师", "教学楼 B301", 1,
                "10:00", "11:40", "amber", "携带听力耳机"));
        timetable.create("student", new CourseInput("Java 程序设计", "周老师", "实验楼 302", 2,
                "14:00", "15:40", "sage", "每周实验课"));
        timetable.create("student", new CourseInput("计算机网络", "黄老师", "信息楼 A305", 3,
                "08:00", "09:40", "violet", ""));
        timetable.create("student", new CourseInput("数据库原理", "林老师", "信息楼 B202", 4,
                "10:00", "11:40", "rose", "课程作业在周五前提交"));
        timetable.create("student", new CourseInput("大学物理实验", "张老师", "物理实验中心", 5,
                "14:00", "16:30", "slate", "提前十分钟到实验室"));
    }

    private void seed(String title, String category, String source, LocalDate date, String body) {
        String actor = "预置样本";
        Version draft = notices.draft(new DraftInput(null, title, category, source, date, false, body), actor);
        for (Field field : draft.fields()) {
            String decision = field.choices().get(0).needsCorrection() ? "hold" : "confirm";
            notices.review(draft.id(), new ReviewInput(field.key(), decision, 0), actor);
        }
        notices.publish(draft.id(), actor);
    }
}
