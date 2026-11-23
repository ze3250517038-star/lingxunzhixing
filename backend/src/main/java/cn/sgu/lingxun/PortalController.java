package cn.sgu.lingxun;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.sgu.lingxun.Models.*;
import static cn.sgu.lingxun.SessionAccess.*;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
class PortalController {
    private record Login(String username, String password) {}
    private record TodoChange(Long versionId, String action) {}
    private record StepChange(boolean done) {}
    private record FeedbackInput(long versionId, String message) {}
    private record Withdrawal(long versionId) {}
    private record ImportInput(String url) {}

    private final SessionAccess access;
    private final NoticeWorkflow notices;
    private final NoticeImportService importer;
    private final TimetableService timetableService;

    PortalController(SessionAccess access, NoticeWorkflow notices, NoticeImportService importer,
                     TimetableService timetableService) {
        this.access = access;
        this.notices = notices;
        this.importer = importer;
        this.timetableService = timetableService;
    }

    @GetMapping("/session")
    SessionAccess.SessionView session(HttpServletRequest request) {
        return access.inspect(request);
    }

    @PostMapping("/login")
    SessionAccess.SessionView login(HttpServletRequest request, @RequestBody Login login) {
        return access.login(request, login.username(), login.password());
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request) {
        access.require(request, null, true);
        request.getSession(false).invalidate();
    }

    @GetMapping("/matters")
    List<MatterView> matters(HttpServletRequest request, @RequestParam(defaultValue = "") String q) {
        Viewer viewer = access.require(request, null, false);
        return notices.published(viewer.username(), q);
    }

    @GetMapping("/versions/{id}")
    Version evidence(HttpServletRequest request, @PathVariable long id) {
        Viewer viewer = access.require(request, null, false);
        Version version = notices.version(id);
        if ("DRAFT".equals(version.state()) && !REVIEWER.equals(viewer.role())) {
            throw new ResponseStatusException(NOT_FOUND, "找不到可供查看的通知版本");
        }
        return version;
    }

    @PostMapping("/matters/{id}/subscription")
    void subscribe(HttpServletRequest request, @PathVariable long id) {
        notices.subscribe(access.require(request, STUDENT, true).username(), id);
    }

    @DeleteMapping("/matters/{id}/subscription")
    void unsubscribe(HttpServletRequest request, @PathVariable long id) {
        notices.unsubscribe(access.require(request, STUDENT, true).username(), id);
    }

    @GetMapping("/todos")
    List<TodoView> todos(HttpServletRequest request) {
        return notices.todos(access.require(request, STUDENT, false).username());
    }

    @GetMapping("/todos/calendar.ics")
    ResponseEntity<byte[]> downloadCalendar(HttpServletRequest request) {
        var deadlines = notices.calendarDeadlines(access.require(request, STUDENT, false).username());
        if (deadlines.isEmpty()) throw new ResponseStatusException(CONFLICT, "暂无可导出的待办理事项，请刷新待办查看。");
        String disposition = ContentDisposition.attachment()
                .filename("岭讯知行-待办.ics", StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(TodoCalendar.export(deadlines));
    }

    @PatchMapping("/todos/{id}")
    TodoView changeTodo(HttpServletRequest request, @PathVariable long id, @RequestBody TodoChange change) {
        return notices.updateTodo(access.require(request, STUDENT, true).username(), id, change.versionId(), change.action());
    }

    @PatchMapping("/todos/{id}/steps/{stepKey}")
    TodoView changeStep(HttpServletRequest request, @PathVariable long id, @PathVariable String stepKey,
                        @RequestBody StepChange change) {
        return notices.updateStep(access.require(request, STUDENT, true).username(), id, stepKey, change.done());
    }

    @GetMapping("/timetable")
    List<Course> timetable(HttpServletRequest request) {
        return timetableService.list(access.require(request, STUDENT, false).username());
    }

    @PostMapping("/timetable")
    Course createCourse(HttpServletRequest request, @RequestBody CourseInput input) {
        return timetableService.create(access.require(request, STUDENT, true).username(), input);
    }

    @PutMapping("/timetable/{id}")
    Course updateCourse(HttpServletRequest request, @PathVariable long id, @RequestBody CourseInput input) {
        return timetableService.update(access.require(request, STUDENT, true).username(), id, input);
    }

    @DeleteMapping("/timetable/{id}")
    void deleteCourse(HttpServletRequest request, @PathVariable long id) {
        timetableService.delete(access.require(request, STUDENT, true).username(), id);
    }

    @PostMapping("/matters/{id}/feedback")
    void feedback(HttpServletRequest request, @PathVariable long id, @RequestBody FeedbackInput input) {
        notices.feedback(access.require(request, STUDENT, true).username(), id, input.versionId(), input.message());
    }

    @GetMapping("/feedback/mine")
    List<Feedback> myFeedback(HttpServletRequest request) {
        return notices.myFeedback(access.require(request, STUDENT, false).username());
    }

    @GetMapping("/maintenance/matters")
    List<Matter> maintenanceMatters(HttpServletRequest request) {
        access.require(request, REVIEWER, false);
        return notices.matters();
    }

    @GetMapping("/maintenance/matters/{id}/versions")
    List<Version> history(HttpServletRequest request, @PathVariable long id) {
        access.require(request, REVIEWER, false);
        return notices.history(id);
    }

    @PostMapping("/maintenance/drafts")
    Version draft(HttpServletRequest request, @RequestBody DraftInput input) {
        String actor = access.require(request, REVIEWER, true).username();
        if (input.simulated() && !access.demo()) throw new ResponseStatusException(BAD_REQUEST, "模拟内容仅在演示环境使用");
        return notices.draft(input, actor);
    }

    @PostMapping("/maintenance/import/url")
    NoticeImportService.Preview importUrl(HttpServletRequest request, @RequestBody ImportInput input) {
        access.require(request, REVIEWER, true);
        return importer.fromUrl(input.url());
    }

    @PostMapping(value = "/maintenance/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    NoticeImportService.Preview importFile(HttpServletRequest request,
                                           @RequestParam("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        access.require(request, REVIEWER, true);
        return importer.fromFile(file.getOriginalFilename(), file.getBytes());
    }

    @PutMapping("/maintenance/versions/{id}/fields")
    Version review(HttpServletRequest request, @PathVariable long id, @RequestBody ReviewInput input) {
        return notices.review(id, input, access.require(request, REVIEWER, true).username());
    }

    @GetMapping("/maintenance/versions/{id}/field-options")
    List<Slot> fieldOptions(HttpServletRequest request, @PathVariable long id) {
        access.require(request, REVIEWER, false);
        return notices.fieldOptions(id);
    }

    @GetMapping("/maintenance/versions/{id}/change-impact")
    ChangeSummary changeImpact(HttpServletRequest request, @PathVariable long id) {
        access.require(request, REVIEWER, false);
        return notices.changeSummary(id);
    }

    @PutMapping("/maintenance/versions/{id}/fields/correction")
    Version correctField(HttpServletRequest request, @PathVariable long id, @RequestBody CorrectionInput input) {
        return notices.correctField(id, input, access.require(request, REVIEWER, true).username());
    }

    @PostMapping("/maintenance/versions/{id}/publish")
    Version publish(HttpServletRequest request, @PathVariable long id) {
        return notices.publish(id, access.require(request, REVIEWER, true).username());
    }

    @PostMapping("/maintenance/matters/{id}/withdraw")
    void withdraw(HttpServletRequest request, @PathVariable long id, @RequestBody Withdrawal input) {
        notices.withdraw(id, input.versionId(), access.require(request, REVIEWER, true).username());
    }

    @GetMapping("/maintenance/feedback")
    List<Feedback> feedback(HttpServletRequest request) {
        access.require(request, REVIEWER, false);
        return notices.feedback();
    }

    @PostMapping("/maintenance/feedback/{id}/handle")
    void handleFeedback(HttpServletRequest request, @PathVariable long id) {
        notices.handleFeedback(id, access.require(request, REVIEWER, true).username());
    }

    @GetMapping("/maintenance/audit")
    List<Event> audit(HttpServletRequest request) {
        access.require(request, REVIEWER, false);
        return notices.events();
    }

}
