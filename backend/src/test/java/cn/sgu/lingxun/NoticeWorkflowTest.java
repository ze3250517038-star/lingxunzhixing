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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.sgu.lingxun.Models.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:lingxun-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class NoticeWorkflowTest {
    private static final Slot DEADLINE = new Slot("学生", "竞赛报名", "截止");
    private static final String SOURCE = "https://www.sgu.edu.cn/info/1065/126441.htm";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void archiveWithoutCandidatesAndRequireEvidenceForManualEntry() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        String fragment = "📌请在2027年1月5日 17:30前完成登记。";
        String snapshot = "活动说明\n" + fragment;
        Version draft = archive(reviewer, null, "补录测试", "2026-12-20", snapshot);
        assertThat(draft.fields()).isEmpty();
        send(reviewer, POST, maintenance(draft, "/publish"), null).andExpect(status().isConflict());
        send(student, GET, "/api/versions/" + draft.id(), null).andExpect(status().isNotFound());

        var correction = correction("2027-01-05T17:30", 2, fragment, "2027年1月5日 17:30");
        send(student, PUT, maintenance(draft, "/fields/correction"), correction).andExpect(status().isForbidden());
        send(reviewer, PUT, maintenance(draft, "/fields/correction"),
                new CorrectionInput(DEADLINE, correction.value(), null, null, null, correction.reason()))
                .andExpect(status().isBadRequest());
        send(reviewer, PUT, maintenance(draft, "/fields/correction"),
                new CorrectionInput(DEADLINE, correction.value(), 2, 1, fragment.length(), correction.reason()))
                .andExpect(status().isBadRequest());
        send(reviewer, PUT, maintenance(draft, "/fields/correction"),
                correction("2027-02-30T17:30", 2, fragment, "2027年1月5日 17:30"))
                .andExpect(status().isBadRequest());
        send(reviewer, POST, maintenance(draft, "/publish"), null).andExpect(status().isConflict());

        var available = decode(send(reviewer, GET, maintenance(draft, "/field-options"), null), Slot[].class);
        assertThat(available).contains(DEADLINE);
        Version added = correct(reviewer, draft, correction);
        var field = added.field(DEADLINE.key());
        var evidence = field.choice().evidence();
        assertThat(field.state()).isEqualTo("CONFIRMED");
        assertThat(field.choice().value()).isEqualTo(correction.value());
        assertThat(evidence.versionId()).isEqualTo(draft.id());
        assertThat(evidence.fragmentNo()).isEqualTo(2);
        assertThat(evidence.quote()).isEqualTo("2027年1月5日 17:30");
        assertThat(added.snapshot()).isEqualTo(snapshot);
        assertThat(added.hash()).isEqualTo(draft.hash());
        assertThat(snapshot.substring(evidence.start(), evidence.end())).isEqualTo(evidence.quote());

        publish(reviewer, added);
        send(reviewer, PUT, maintenance(draft, "/fields/correction"), correction).andExpect(status().isConflict());
        var events = decode(send(reviewer, GET, "/api/maintenance/audit", null), Event[].class);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.versionId()).isEqualTo(draft.id());
            assertThat(event.action()).isEqualTo("人工补录字段");
            assertThat(event.detail()).contains("暂无", correction.value(), evidence.quote(), correction.reason());
        });
    }

    @Test
    void ambiguousAndTentativeValuesCannotBeDirectlyConfirmed() throws Exception {
        Client reviewer = login("reviewer");
        String unknown = "报名截止调整为1月5日。";
        String tentative = "初赛暂定为2027年1月7日。";
        Version draft = archive(reviewer, null, "歧义测试", "2026-12-20", unknown + "\n" + tentative);
        var pending = draft.field(DEADLINE.key());
        var stage = draft.field("参赛学生/初赛/时间");
        assertThat(pending.selected()).isNull();
        assertThat(pending.choices().get(0).reviewReason()).contains("年份");
        assertThat(pending.choices().get(0).evidence().quote()).isEqualTo(unknown);
        assertThat(stage.choices().get(0).tentative()).isTrue();
        send(reviewer, POST, maintenance(draft, "/publish"), null).andExpect(status().isConflict());
        send(reviewer, PUT, maintenance(draft, "/fields"), new ReviewInput(DEADLINE.key(), "confirm", 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(pending.choices().get(0).reviewReason()));
        int dateStart = tentative.indexOf("2027");
        send(reviewer, PUT, maintenance(draft, "/fields/correction"),
                new CorrectionInput(stage.slot(), "2027-01-07", 2, dateStart, tentative.length() - 1, "核对初赛原文"))
                .andExpect(status().isBadRequest());

        review(reviewer, draft, DEADLINE.key(), "hold");
        Version held = review(reviewer, draft, stage.key(), "hold");
        assertThat(publish(reviewer, held).field(DEADLINE.key()).state()).isEqualTo("HOLD");
        Client student = login("student");
        send(student, POST, "/api/matters/" + draft.matterId() + "/subscription", null).andExpect(status().isOk());
        assertThat(todos(student, draft.matterId())).isEmpty();
    }

    @Test
    void changedDeadlinePausesOldTodoAndCalendarUntilStudentConfirms() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version draft = archive(reviewer, null, "改期测试", "2026-11-16", "报名时间11月20–21日");
        assertThat(draft.field(DEADLINE.key()).choice().value()).isEqualTo("2026-11-21");
        publish(reviewer, review(reviewer, draft, DEADLINE.key(), "confirm"));
        send(student, POST, "/api/matters/" + draft.matterId() + "/subscription", null).andExpect(status().isOk());
        TodoView previous = todos(student, draft.matterId())[0];
        assertThat(previous.state()).isEqualTo("PENDING");
        assertThat(calendar(student)).contains("DTSTART;VALUE=DATE:20261121");

        String statement = "报名截止延至1月5日09:30。";
        Version update = archive(reviewer, draft.matterId(), null, "2026-12-20", statement);
        assertThat(update.field(DEADLINE.key()).choices().get(0).reviewReason()).contains("年份");
        var correction = correction("2027-01-05T09:30", 1, statement, "1月5日09:30");
        Version corrected = correct(reviewer, update, correction);
        assertThat(corrected.field(DEADLINE.key()).change()).isEqualTo("覆盖");
        var audit = decode(send(reviewer, GET, "/api/maintenance/audit", null), Event[].class);
        assertThat(audit).anySatisfy(event -> {
            assertThat(event.versionId()).isEqualTo(update.id());
            assertThat(event.action()).isEqualTo("人工修正字段");
            assertThat(event.detail()).contains(statement, correction.value(), correction.reason());
        });

        publish(reviewer, corrected);
        assertThat(publish(reviewer, corrected).id()).isEqualTo(update.id());
        TodoView[] entries = todos(student, draft.matterId());
        assertThat(entries).hasSize(1);
        TodoView held = entries[0];
        assertThat(held.state()).isEqualTo("REVIEW");
        assertThat(held.previous().choice().value()).isEqualTo("2026-11-21");
        assertThat(held.current().choice().value()).isEqualTo(correction.value());
        assertThat(calendar(student)).doesNotContain("20261121", "20270105");
        changeTodo(student, previous.id(), draft.id(), "confirm").andExpect(status().isConflict());
        changeTodo(student, held.id(), update.id(), "done").andExpect(status().isConflict());

        changeTodo(student, held.id(), update.id(), "confirm").andExpect(status().isOk());
        assertThat(todos(student, draft.matterId())[0].state()).isEqualTo("PENDING");
        assertThat(calendar(student)).contains("DTSTART:20270105T013000Z").doesNotContain("20261121");

        Version unresolved = archive(reviewer, draft.matterId(), null, "2027-01-04", "报名截止1月5日或1月6日。");
        publish(reviewer, review(reviewer, unresolved, DEADLINE.key(), "hold"));
        assertThat(todos(student, draft.matterId())[0].state()).isEqualTo("REVIEW");
        changeTodo(student, held.id(), unresolved.id(), "confirm").andExpect(status().isConflict());
        assertThat(calendar(student)).doesNotContain("20270105", "20270106");
    }

    @Test
    void onlyOneDraftBasedOnTheSameVersionCanPublish() throws Exception {
        Client firstReviewer = login("reviewer");
        Client secondReviewer = login("reviewer");
        Version base = archive(firstReviewer, null, "并行发布测试", "2026-11-16", "报名截止2026年11月24日。");
        publish(firstReviewer, review(firstReviewer, base, DEADLINE.key(), "confirm"));
        Version first = archive(firstReviewer, base.matterId(), null, "2026-11-16", "报名截止2026年11月25日。");
        Version second = archive(secondReviewer, base.matterId(), null, "2026-11-16", "报名截止2026年11月26日。");
        review(firstReviewer, first, DEADLINE.key(), "confirm");
        review(secondReviewer, second, DEADLINE.key(), "confirm");

        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstStatus = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return send(firstReviewer, POST, maintenance(first, "/publish"), null).andReturn().getResponse().getStatus();
            });
            var secondStatus = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return send(secondReviewer, POST, maintenance(second, "/publish"), null).andReturn().getResponse().getStatus();
            });
            assertThat(new int[]{firstStatus.get(10, TimeUnit.SECONDS), secondStatus.get(10, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void refusesDeadlineEarlierThanNoticeDate() throws Exception {
        Client reviewer = login("reviewer");
        Version draft = archive(reviewer, null, "倒挂日期测试", "2026-12-20", "报名截止2026年12月1日。");
        Version confirmed = review(reviewer, draft, DEADLINE.key(), "confirm");
        send(reviewer, POST, maintenance(confirmed, "/publish"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("早于通知日期")));
    }

    @Test
    void marksExistingTodoIgnoredWhenFieldBecomesNotApplicable() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version base = archive(reviewer, null, "不适用基准", "2026-11-16", "报名截止2026年11月20日。");
        publish(reviewer, review(reviewer, base, DEADLINE.key(), "confirm"));
        send(student, POST, "/api/matters/" + base.matterId() + "/subscription", null).andExpect(status().isOk());
        TodoView[] before = todos(student, base.matterId());
        assertThat(before).hasSize(1);
        assertThat(before[0].dueAt()).isEqualTo("2026-11-20");

        Version update = archive(reviewer, base.matterId(), null, "2026-11-16", "报名截止时间另行通知。");
        Version marked = decode(send(reviewer, PUT, maintenance(update, "/fields"),
                new ReviewInput(DEADLINE.key(), "not_applicable", null)), Version.class);
        assertThat(marked.field(DEADLINE.key()).state()).isEqualTo("NOT_APPLICABLE");
        publish(reviewer, marked);

        TodoView[] after = todos(student, base.matterId());
        assertThat(after).hasSize(1);
        assertThat(after[0].state()).isEqualTo("IGNORED");
        assertThat(after[0].dueAt()).isNull();
        changeTodo(student, after[0].id(), update.id(), "done").andExpect(status().isConflict());
    }

    @Test
    void sortsPendingTodosByDueDate() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version late = archive(reviewer, null, "排序晚", "2026-11-16", "报名截止2026年12月4日。");
        Version early = archive(reviewer, null, "排序早", "2026-11-16", "报名截止2026年12月2日。");
        publish(reviewer, review(reviewer, late, DEADLINE.key(), "confirm"));
        publish(reviewer, review(reviewer, early, DEADLINE.key(), "confirm"));
        send(student, POST, "/api/matters/" + late.matterId() + "/subscription", null).andExpect(status().isOk());
        send(student, POST, "/api/matters/" + early.matterId() + "/subscription", null).andExpect(status().isOk());

        TodoView[] mine = Arrays.stream(decode(send(student, GET, "/api/todos", null), TodoView[].class))
                .filter(view -> view.matterId() == late.matterId() || view.matterId() == early.matterId())
                .toArray(TodoView[]::new);
        assertThat(mine).hasSize(2);
        assertThat(mine[0].dueAt()).isEqualTo("2026-12-02");
        assertThat(mine[1].dueAt()).isEqualTo("2026-12-04");
    }

    @Test
    void studentCanUnsubscribeAndRemoveOnlyThatMattersTodos() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version base = archive(reviewer, null, "取消订阅测试", "2026-11-16", "报名截止2026年12月8日。");
        publish(reviewer, review(reviewer, base, DEADLINE.key(), "confirm"));
        send(student, POST, "/api/matters/" + base.matterId() + "/subscription", null).andExpect(status().isOk());
        assertThat(todos(student, base.matterId())).hasSize(1);

        send(student, DELETE, "/api/matters/" + base.matterId() + "/subscription", null)
                .andExpect(status().isOk());
        assertThat(todos(student, base.matterId())).isEmpty();
        MatterView[] views = decode(send(student, GET, "/api/matters", null), MatterView[].class);
        assertThat(Arrays.stream(views).filter(view -> view.matter().id() == base.matterId())
                .findFirst().orElseThrow().subscribed()).isFalse();
    }

    @Test
    void studentCanTickChecklistAndNewVersionResetsProgress() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version base = archive(reviewer, null, "办理清单测试", "2026-11-16", """
                报名截止2026年12月10日。
                材料要求：身份证、学生证。
                报名入口：教务系统提交报名表。
                """);
        Version confirmed = base;
        for (Field field : base.fields()) confirmed = review(reviewer, confirmed, field.key(), "confirm");
        publish(reviewer, confirmed);
        send(student, POST, "/api/matters/" + base.matterId() + "/subscription", null).andExpect(status().isOk());

        TodoView before = todos(student, base.matterId())[0];
        assertThat(before.steps()).extracting(TodoStep::key)
                .containsExactly("read", "materials", "entry", "deadline");
        assertThat(before.steps()).anySatisfy(step -> {
            assertThat(step.key()).isEqualTo("materials");
            assertThat(step.detail()).contains("身份证", "学生证");
            assertThat(step.done()).isFalse();
        });

        TodoView ticked = decode(send(student, PATCH, "/api/todos/" + before.id() + "/steps/materials",
                Map.of("done", true)), TodoView.class);
        assertThat(ticked.steps()).anySatisfy(step -> {
            assertThat(step.key()).isEqualTo("materials");
            assertThat(step.done()).isTrue();
        });

        Version update = archive(reviewer, base.matterId(), null, "2026-11-16", """
                报名截止2026年12月12日。
                材料要求：身份证、学生证。
                报名入口：教务系统提交报名表。
                """);
        Version revised = update;
        for (Field field : update.fields()) revised = review(reviewer, revised, field.key(), "confirm");
        publish(reviewer, revised);

        TodoView after = todos(student, base.matterId())[0];
        assertThat(after.state()).isEqualTo("REVIEW");
        assertThat(after.steps()).anySatisfy(step -> {
            assertThat(step.key()).isEqualTo("materials");
            assertThat(step.done()).isFalse();
        });
        send(student, PATCH, "/api/todos/" + after.id() + "/steps/materials", Map.of("done", true))
                .andExpect(status().isConflict());
    }

    @Test
    void studentSeesOwnFeedbackHistory() throws Exception {
        Client reviewer = login("reviewer");
        Client student = login("student");
        Version base = archive(reviewer, null, "反馈历史测试", "2026-11-16", "报名截止2026年12月9日。");
        publish(reviewer, review(reviewer, base, DEADLINE.key(), "confirm"));
        send(student, POST, "/api/matters/" + base.matterId() + "/feedback",
                Map.of("versionId", base.id(), "message", "截止日期需要再确认"))
                .andExpect(status().isOk());

        Feedback[] mine = decode(send(student, GET, "/api/feedback/mine", null), Feedback[].class);
        assertThat(Arrays.stream(mine).filter(item -> item.matterId() == base.matterId()))
                .anySatisfy(item -> {
                    assertThat(item.message()).isEqualTo("截止日期需要再确认");
                    assertThat(item.title()).isEqualTo("反馈历史测试");
                    assertThat(item.handled()).isFalse();
                });
    }

    @Test
    void unknownEndpointKeepsNotFoundInsteadOfServerError() throws Exception {
        send(null, GET, "/api/not-a-real-endpoint", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("请求的资源不存在。"));
    }

    private Version archive(Client client, Long matterId, String title, String date, String snapshot) throws Exception {
        var input = new DraftInput(matterId, title, "竞赛报名", SOURCE, LocalDate.parse(date), true, snapshot);
        return decode(send(client, POST, "/api/maintenance/drafts", input), Version.class);
    }

    private Version review(Client client, Version draft, String key, String decision) throws Exception {
        return decode(send(client, PUT, maintenance(draft, "/fields"), new ReviewInput(key, decision, 0)), Version.class);
    }

    private Version correct(Client client, Version draft, CorrectionInput correction) throws Exception {
        return decode(send(client, PUT, maintenance(draft, "/fields/correction"), correction), Version.class);
    }

    private Version publish(Client client, Version draft) throws Exception {
        return decode(send(client, POST, maintenance(draft, "/publish"), null), Version.class);
    }

    private TodoView[] todos(Client student, long matterId) throws Exception {
        return Arrays.stream(decode(send(student, GET, "/api/todos", null), TodoView[].class))
                .filter(todo -> todo.matterId() == matterId).toArray(TodoView[]::new);
    }

    private ResultActions changeTodo(Client student, long id, long versionId, String action) throws Exception {
        return send(student, PATCH, "/api/todos/" + id, Map.of("versionId", versionId, "action", action));
    }

    private String calendar(Client student) throws Exception {
        return send(student, GET, "/api/todos/calendar.ics", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).replaceAll("\\r\\n[ \\t]", "");
    }

    private static CorrectionInput correction(String value, int number, String fragment, String quote) {
        int start = fragment.indexOf(quote);
        return new CorrectionInput(DEADLINE, value, number, start, start + quote.length(), "对照通知全文核定日期");
    }

    private static String maintenance(Version version, String path) {
        return "/api/maintenance/versions/" + version.id() + path;
    }

    private Client login(String username) throws Exception {
        var response = send(null, POST, "/api/login", Map.of("username", username, "password", "lingxun-demo"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        String token = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("csrf").asText();
        return new Client(session, token);
    }

    private ResultActions send(Client client, HttpMethod method, String uri, Object body) throws Exception {
        var message = request(method, uri);
        if (client != null) message.session(client.session()).header("X-Lingxun-Token", client.token());
        if (body != null) message.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(message);
    }

    private <T> T decode(ResultActions response, Class<T> type) throws Exception {
        return json.readValue(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), type);
    }

    private record Client(MockHttpSession session, String token) {}
}
