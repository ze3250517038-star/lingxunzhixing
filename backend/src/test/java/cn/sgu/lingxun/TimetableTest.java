package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static cn.sgu.lingxun.Models.Course;
import static cn.sgu.lingxun.Models.Semester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:lingxun-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class TimetableTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    SessionAccess access;

    @Test
    void studentCanCreateUpdateAndDeleteOwnCourses() throws Exception {
        Client student = login("student", "lingxun-demo");
        Course[] seeded = decode(send(student, GET, "/api/timetable", null), Course[].class);
        assertThat(Arrays.stream(seeded).map(Course::name)).contains("物联网导论", "Java 程序设计");

        Course created = decode(send(student, POST, "/api/timetable", Map.of(
                "name", "嵌入式系统", "teacher", "王老师", "location", "实验楼 401",
                "weekday", 3, "startTime", "14:00", "endTime", "15:40",
                "color", "sage", "note", "单周上课")), Course.class);
        assertThat(created.id()).isPositive();
        assertThat(created.name()).isEqualTo("嵌入式系统");
        assertThat(created.weekday()).isEqualTo(3);

        Course updated = decode(send(student, PUT, "/api/timetable/" + created.id(), Map.of(
                "name", "嵌入式系统实验", "teacher", "王老师", "location", "实验楼 402",
                "weekday", 4, "startTime", "16:00", "endTime", "17:40",
                "color", "ocean", "note", "改为双周")), Course.class);
        assertThat(updated.name()).isEqualTo("嵌入式系统实验");
        assertThat(updated.weekday()).isEqualTo(4);
        assertThat(updated.startTime()).isEqualTo("16:00");

        send(student, DELETE, "/api/timetable/" + created.id(), null).andExpect(status().isOk());
        Course[] after = decode(send(student, GET, "/api/timetable", null), Course[].class);
        assertThat(Arrays.stream(after).map(Course::id)).doesNotContain(created.id());
    }

    @Test
    void timetableRejectsInvalidValuesAndMissingCsrf() throws Exception {
        Client student = login("student", "lingxun-demo");
        send(student, POST, "/api/timetable", course("", 1, "08:00", "09:40"))
                .andExpect(status().isBadRequest());
        send(student, POST, "/api/timetable", course("时间测试", 1, "09:40", "08:00"))
                .andExpect(status().isBadRequest());
        send(student, POST, "/api/timetable", course("星期测试", 8, "08:00", "09:40"))
                .andExpect(status().isBadRequest());
        send(student, POST, "/api/timetable", course("格式测试", 1, "8点", "09:40"))
                .andExpect(status().isBadRequest());
        send(new Client(student.session(), null), POST, "/api/timetable", course("无令牌", 2, "10:00", "11:40"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewerCannotUseStudentTimetableAndOtherStudentsCannotEditIt() throws Exception {
        Client reviewer = login("reviewer", "lingxun-demo");
        send(reviewer, GET, "/api/timetable", null).andExpect(status().isForbidden());

        access.provision("student-other", SessionAccess.STUDENT, "lingxun-other");
        Client other = login("student-other", "lingxun-other");
        Course created = decode(send(other, POST, "/api/timetable", course("别人的课程", 6, "09:00", "10:30")),
                Course.class);
        Client student = login("student", "lingxun-demo");
        send(student, PUT, "/api/timetable/" + created.id(), course("越权修改", 6, "09:00", "10:30"))
                .andExpect(status().isNotFound());
        send(student, DELETE, "/api/timetable/" + created.id(), null).andExpect(status().isNotFound());
    }

    @Test
    void studentCanSetSemesterAndInvalidDatesAreRejected() throws Exception {
        Client student = login("student", "lingxun-demo");
        Semester seeded = decode(send(student, GET, "/api/timetable/semester", null), Semester.class);
        assertThat(seeded.name()).contains("2026-2027");

        Semester saved = decode(send(student, PUT, "/api/timetable/semester", Map.of(
                "name", "2026-2027 学年第二学期",
                "startDate", "2027-02-22",
                "endDate", "2027-07-04")), Semester.class);
        assertThat(saved.name()).isEqualTo("2026-2027 学年第二学期");
        assertThat(saved.startDate()).isEqualTo("2027-02-22");
        assertThat(saved.endDate()).isEqualTo("2027-07-04");

        send(student, PUT, "/api/timetable/semester", Map.of(
                "name", "倒置日期", "startDate", "2027-07-04", "endDate", "2027-02-22"))
                .andExpect(status().isBadRequest());
        send(student, PUT, "/api/timetable/semester", Map.of(
                "name", "错误格式", "startDate", "2027/02/22", "endDate", "2027-07-04"))
                .andExpect(status().isBadRequest());
        send(new Client(student.session(), null), PUT, "/api/timetable/semester", Map.of(
                "name", "无令牌", "startDate", "2027-02-22", "endDate", "2027-07-04"))
                .andExpect(status().isForbidden());
    }

    @Test
    void courseWeekPatternIsValidatedAndSaved() throws Exception {
        Client student = login("student", "lingxun-demo");
        Course created = decode(send(student, POST, "/api/timetable", Map.of(
                "name", "编译原理", "teacher", "郑老师", "location", "信息楼 A401",
                "weekday", 2, "startTime", "16:00", "endTime", "17:40",
                "color", "violet", "note", "仅单周", "weekPattern", "odd")), Course.class);
        assertThat(created.weekPattern()).isEqualTo("ODD");

        Course updated = decode(send(student, PUT, "/api/timetable/" + created.id(), Map.of(
                "name", "编译原理", "teacher", "郑老师", "location", "信息楼 A401",
                "weekday", 2, "startTime", "16:00", "endTime", "17:40",
                "color", "violet", "note", "改为双周", "weekPattern", "EVEN")), Course.class);
        assertThat(updated.weekPattern()).isEqualTo("EVEN");

        send(student, POST, "/api/timetable", Map.of(
                "name", "非法周次", "teacher", "", "location", "", "weekday", 2,
                "startTime", "18:00", "endTime", "19:00", "color", "sage", "note", "",
                "weekPattern", "WEEKLY")).andExpect(status().isBadRequest());
        send(student, DELETE, "/api/timetable/" + created.id(), null).andExpect(status().isOk());
    }

    @Test
    void reviewerCannotReadStudentSemester() throws Exception {
        Client reviewer = login("reviewer", "lingxun-demo");
        send(reviewer, GET, "/api/timetable/semester", null).andExpect(status().isForbidden());
        send(reviewer, PUT, "/api/timetable/semester", Map.of(
                "name", "越权学期", "startDate", "2027-02-22", "endDate", "2027-07-04"))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> course(String name, int weekday, String start, String end) {
        return Map.of("name", name, "teacher", "", "location", "", "weekday", weekday,
                "startTime", start, "endTime", end, "color", "sage", "note", "");
    }

    private Client login(String username, String password) throws Exception {
        var response = send(null, POST, "/api/login", Map.of("username", username, "password", password))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        String token = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("csrf").asText();
        return new Client(session, token);
    }

    private ResultActions send(Client client, HttpMethod method, String uri, Object body) throws Exception {
        var message = request(method, uri);
        if (client != null) {
            message.session(client.session());
            if (client.token() != null) message.header("X-Lingxun-Token", client.token());
        }
        if (body != null) message.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(message);
    }

    private <T> T decode(ResultActions response, Class<T> type) throws Exception {
        return json.readValue(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), type);
    }

    private record Client(MockHttpSession session, String token) {}
}
