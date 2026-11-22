package cn.sgu.lingxun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static cn.sgu.lingxun.Models.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class NoticeWorkflow {
    private static final RowMapper<Matter> MATTERS = (row, index) -> new Matter(row.getLong("id"),
            row.getString("title"), row.getString("category"), nullableLong(row, "current_version_id"));
    private static final RowMapper<Todo> TODOS = (row, index) -> new Todo(row.getLong("id"),
            row.getString("username"), row.getLong("matter_id"), row.getString("field_key"),
            row.getLong("acknowledged_version_id"), nullableLong(row, "target_version_id"), row.getString("todo_state"));
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NoticeWorkflow(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<MatterView> published(String username, String term) {
        String pattern = "%" + term.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.query("""
                SELECT m.* FROM matter m JOIN notice_version v ON v.id=m.current_version_id
                WHERE LOWER(m.title) LIKE ? ESCAPE '!' OR LOWER(v.snapshot) LIKE ? ESCAPE '!'
                ORDER BY m.id LIMIT 50
                """, MATTERS, pattern, pattern).stream()
                .map(matter -> new MatterView(matter, version(matter.currentVersionId()), subscribed(username, matter.id())))
                .toList();
    }

    public List<Matter> matters() {
        return db.query("SELECT * FROM matter ORDER BY id", MATTERS);
    }

    public List<Version> history(long matterId) {
        return db.query("SELECT * FROM notice_version WHERE matter_id=? ORDER BY version_no DESC LIMIT 30",
                this::mapVersion, matterId);
    }

    public Version version(long id) {
        return db.query("SELECT * FROM notice_version WHERE id=?", this::mapVersion, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "找不到该通知版本"));
    }

    @Transactional
    public Version draft(DraftInput input, String actor) {
        String snapshot = required(input.snapshot(), 40000, "请填写通知正文或去标识摘录");
        snapshot = snapshot.replace("\r\n", "\n").replace('\r', '\n');
        String source = officialSource(input.sourceUrl());
        if (input.noticeDate() == null) throw bad("请填写通知来源日期");
        Matter matter;
        if (input.matterId() == null) {
            String title = required(input.title(), 180, "请填写事项名称");
            if (!"竞赛报名".equals(input.category()) && !"学分认定".equals(input.category())) {
                throw bad("请选择事项类型");
            }
            long matterId = insert("INSERT INTO matter(title,category) VALUES (?,?)", title, input.category());
            matter = matter(matterId, true);
        } else {
            matter = matter(input.matterId(), true);
        }

        String hash = sha256(snapshot);
        var existing = db.query("""
                SELECT * FROM notice_version WHERE matter_id=? AND snapshot_hash=?
                """, this::mapVersion, matter.id(), hash).stream().findFirst();
        if (existing.isPresent()) {
            Version duplicate = existing.get();
            if ("DRAFT".equals(duplicate.state()) && Objects.equals(matter.currentVersionId(), duplicate.baseVersionId())) {
                return duplicate;
            }
            throw conflict("这份正文已归档。请在版本列表查看已有记录。");
        }
        int number = db.queryForObject("SELECT COALESCE(MAX(version_no), 0)+1 FROM notice_version WHERE matter_id=?",
                Integer.class, matter.id());
        long id = insert("""
                INSERT INTO notice_version(matter_id,version_no,base_version_id,source_url,notice_date,simulated,
                  snapshot,snapshot_hash,fields_json,version_state) VALUES (?,?,?,?,?,?,?,?,?,'DRAFT')
                """, matter.id(), number, matter.currentVersionId(), source, input.noticeDate(),
                input.simulated(), snapshot, hash, "[]");
        Version created = version(id);
        List<Field> fields = candidates(created, matter.category());
        db.update("UPDATE notice_version SET fields_json=? WHERE id=?", json(fields), id);
        audit(matter.id(), id, actor, "录入并归档", "V" + number + " 已保存正文快照，生成 " + fields.size() + " 个候选字段");
        return version(id);
    }

    private List<Field> candidates(Version draft, String category) {
        Map<String, Field> fields = new LinkedHashMap<>();
        if (draft.baseVersionId() != null) {
            for (Field field : version(draft.baseVersionId()).fields()) {
                Choice previous = field.choice();
                if (previous != null && "CONFIRMED".equals(field.state())) {
                    fields.put(field.key(), new Field(field.key(), field.slot(), List.of(previous), 0, "CANDIDATE", "继承"));
                } else {
                    String treatment = field.choices().size() > 1 ? "冲突" : "待核验";
                    fields.put(field.key(), new Field(field.key(), field.slot(), field.choices(), null, "CANDIDATE", treatment));
                }
            }
        }
        NoticeRules.extract(draft, category).forEach((slot, choices) -> {
            Field old = fields.get(slot.key());
            boolean conflict = choices.size() > 1;
            boolean needsCorrection = choices.get(0).needsCorrection();
            String treatment = conflict ? "冲突" : needsCorrection ? "待核验" : "新增";
            if (!conflict && !needsCorrection && old != null) {
                treatment = old.choice() != null && old.choice().value().equals(choices.get(0).value())
                        ? "继承" : "覆盖";
            }
            fields.put(slot.key(), new Field(slot.key(), slot, choices, conflict || needsCorrection ? null : 0,
                    "CANDIDATE", treatment));
        });
        return new ArrayList<>(fields.values());
    }

    @Transactional
    public Version review(long id, ReviewInput input, String actor) {
        Version reference = version(id);
        matter(reference.matterId(), true);
        Version draft = version(id);
        if (!"DRAFT".equals(draft.state())) throw conflict("只能核验草稿");
        Field current = draft.field(input.key());
        if (current == null) throw bad("找不到要核验的字段");
        Field reviewed;
        if ("confirm".equals(input.decision())) {
            if (input.option() == null && current.choices().size() > 1) throw bad("请明确选择经过核对的依据");
            int selected = input.option() == null ? 0 : input.option();
            if (selected < 0 || selected >= current.choices().size()) throw bad("请选择经过核对的依据");
            Choice choice = current.choices().get(selected);
            verifyChoice(choice, draft.matterId());
            String treatment = current.change();
            if ("冲突".equals(treatment) || "待核验".equals(treatment)) {
                treatment = resolvedTreatment(draft, current.key(), choice.value());
            }
            reviewed = new Field(current.key(), current.slot(), current.choices(), selected, "CONFIRMED", treatment);
        } else if ("hold".equals(input.decision())) {
            String treatment = current.choices().size() > 1 ? "冲突" : "待核验";
            reviewed = new Field(current.key(), current.slot(), current.choices(), null, "HOLD", treatment);
        } else if ("not_applicable".equals(input.decision())) {
            reviewed = new Field(current.key(), current.slot(), current.choices(), null, "NOT_APPLICABLE", "不适用");
        } else {
            throw bad("请选择核验结论");
        }
        saveField(draft, reviewed);
        audit(draft.matterId(), id, actor, "核验字段", current.key() + " · " + describe(reviewed));
        return version(id);
    }

    public List<Slot> fieldOptions(long id) {
        Version draft = version(id);
        if (!"DRAFT".equals(draft.state())) throw conflict("只能修正草稿");
        return NoticeRules.slots(matter(draft.matterId(), false).category());
    }

    @Transactional
    public Version correctField(long id, CorrectionInput input, String actor) {
        Matter matter = matter(version(id).matterId(), true);
        Version draft = version(id);
        if (!"DRAFT".equals(draft.state())) throw conflict("只能修正草稿");
        Slot slot = input.slot();
        if (!NoticeRules.slots(matter.category()).contains(slot)) throw bad("请选择事项支持的办理角色、阶段和字段");
        String reason = required(input.reason(), 300, "请填写修正或补录依据，最多300字");
        String value = required(input.value(), 40000, "请填写字段值");
        boolean deadline = "截止".equals(slot.type());
        if (deadline || "时间".equals(slot.type())) {
            try {
                value = NoticeDates.normalizeManual(value, deadline);
            } catch (DateTimeException invalid) {
                String detail = invalid.getMessage();
                if (invalid instanceof DateTimeParseException || detail == null || detail.isBlank()) {
                    detail = "请填写有效日期和时间，截止字段只接受一个日期";
                }
                throw bad(detail);
            }
            if (deadline) {
                LocalDate due;
                try {
                    due = LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
                } catch (DateTimeParseException invalid) {
                    throw bad("截止日期格式无法解析");
                }
                if (due.isBefore(draft.noticeDate())) throw bad("截止日期不能早于通知日期");
            }
        }
        Evidence proof = correctionEvidence(draft, input);
        Field before = draft.field(slot.key());
        Choice corrected = new Choice(value, proof, false, null);
        Field after = new Field(slot.key(), slot, List.of(corrected), 0, "CONFIRMED",
                resolvedTreatment(draft, slot.key(), value));
        if (after.equals(before)) return draft;
        saveField(draft, after);
        String previous = before == null ? "暂无" : before.choice() != null ? describeEvidence(before.choice())
                : String.join("\n", before.choices().stream().map(NoticeWorkflow::describeEvidence).toList());
        audit(draft.matterId(), id, actor, before == null ? "人工补录字段" : "人工修正字段",
                slot.key() + "\n原值与依据 · " + previous + "\n新值与依据 · " + describeEvidence(corrected)
                        + "\n核验说明 · " + reason);
        return version(id);
    }

    private void saveField(Version draft, Field reviewed) {
        List<Field> updated = new ArrayList<>(draft.fields());
        if (draft.field(reviewed.key()) == null) {
            updated.add(reviewed);
        } else {
            updated.replaceAll(field -> field.key().equals(reviewed.key()) ? reviewed : field);
        }
        db.update("UPDATE notice_version SET fields_json=? WHERE id=?", json(updated), draft.id());
    }

    private String resolvedTreatment(Version draft, String key, String value) {
        Field previous = draft.baseVersionId() == null ? null : version(draft.baseVersionId()).field(key);
        if (previous == null) return "新增";
        return confirmed(previous) && previous.choice().value().equals(value) ? "继承" : "覆盖";
    }

    private static Evidence correctionEvidence(Version draft, CorrectionInput input) {
        List<Fragment> fragments = NoticeRules.fragments(draft.snapshot());
        Integer number = input.fragmentNo();
        if (number == null || number < 1 || number > fragments.size()) throw bad("请选择本版本的证据片段");
        Fragment fragment = fragments.get(number - 1);
        Integer start = input.start();
        Integer end = input.end();
        String text = fragment.text();
        if (start == null || end == null || start < 0 || start >= end || end > text.length()
                || Character.isLowSurrogate(text.charAt(start))
                || (end < text.length() && Character.isLowSurrogate(text.charAt(end)))) {
            throw bad("证据区间不能定位到原文");
        }
        String quote = text.substring(start, end);
        if (quote.isBlank()) throw bad("证据片段不得为空白");
        if (NoticeRules.isTentative(text)) throw bad("这段原文标明暂定或待定，请选择已明确的原文或保留待复核");
        return new Evidence(draft.id(), number, fragment.start() + start, fragment.start() + end, quote);
    }

    private static String describeEvidence(Choice choice) {
        Evidence evidence = choice.evidence();
        return (choice.value() == null ? "待核定" : choice.value()) + " · 通知版本编号 " + evidence.versionId()
                + " · 证据片段 " + evidence.fragmentNo() + " [" + evidence.start() + ", " + evidence.end() + ") "
                + evidence.quote();
    }

    @Transactional
    public Version publish(long id, String actor) {
        Version reference = version(id);
        Matter matter = matter(reference.matterId(), true);
        Version draft = version(id);
        if (Objects.equals(matter.currentVersionId(), id) && "PUBLISHED".equals(draft.state())) return draft;
        if (!"DRAFT".equals(draft.state()) || !Objects.equals(matter.currentVersionId(), draft.baseVersionId())) {
            throw conflict("当前版本已变更，请以最新版本重新录入和核验。");
        }
        if (draft.fields().isEmpty()) throw conflict("请先根据原文补录可核验字段");
        if (draft.fields().stream().anyMatch(field -> "CANDIDATE".equals(field.state()))) {
            throw conflict("请先逐项确认、修正或标为待复核");
        }
        for (Field field : draft.fields()) {
            if ("CONFIRMED".equals(field.state())) verifyChoice(field.choice(), matter.id());
        }
        verifyDeadlines(draft);
        Version previous = matter.currentVersionId() == null ? null : version(matter.currentVersionId());
        db.update("UPDATE notice_version SET version_state='PUBLISHED' WHERE id=?", id);
        db.update("UPDATE matter SET current_version_id=? WHERE id=?", id, matter.id());
        synchronizeTodos(matter.id(), previous, draft);
        List<String> changes = draft.fields().stream().filter(field -> !"继承".equals(field.change()))
                .map(field -> field.key() + "  " + describe(previous == null ? null : previous.field(field.key()))
                        + " → " + describe(field)).toList();
        audit(matter.id(), id, actor, "发布版本", "V" + draft.number() + (changes.isEmpty() ? "（已确认继承）" : "\n" + String.join("\n", changes)));
        return version(id);
    }

    @Transactional
    public void withdraw(long matterId, long expectedVersion, String actor) {
        Matter matter = matter(matterId, true);
        if (!Objects.equals(matter.currentVersionId(), expectedVersion)) throw conflict("请刷新后选择当前版本");
        db.update("UPDATE matter SET current_version_id=NULL WHERE id=?", matterId);
        db.update("UPDATE notice_version SET version_state='WITHDRAWN' WHERE id=?", expectedVersion);
        db.update("UPDATE todo SET todo_state='REVIEW', target_version_id=NULL WHERE matter_id=?", matterId);
        audit(matterId, expectedVersion, actor, "撤回版本", "当前版本已撤回，相关待办转为待复核");
    }

    @Transactional
    public void subscribe(String username, long matterId) {
        Matter matter = matter(matterId, true);
        if (matter.currentVersionId() == null) throw conflict("这个事项没有生效的通知版本");
        if (subscribed(username, matterId)) return;
        db.update("INSERT INTO subscription(username,matter_id) VALUES (?,?)", username, matterId);
        addMissingTodos(username, matterId, version(matter.currentVersionId()));
    }

    @Transactional
    public void unsubscribe(String username, long matterId) {
        Matter matter = matter(matterId, true);
        if (!subscribed(username, matterId)) return;
        db.update("DELETE FROM todo_step WHERE todo_id IN (SELECT id FROM todo WHERE username=? AND matter_id=?)",
                username, matterId);
        db.update("DELETE FROM todo WHERE username=? AND matter_id=?", username, matterId);
        db.update("DELETE FROM subscription WHERE username=? AND matter_id=?", username, matterId);
        audit(matterId, matter.currentVersionId(), username, "取消订阅", "学生取消关注该事项，相关待办已移除");
    }

    @Transactional(readOnly = true)
    public List<TodoView> todos(String username) {
        return db.query("SELECT * FROM todo WHERE username=? ORDER BY id DESC", TODOS, username).stream()
                .map(this::todoView)
                .sorted(Comparator.comparingInt((TodoView view) -> todoRank(view.state()))
                        .thenComparing(view -> view.dueAt() == null ? "9999-12-31" : view.dueAt())
                        .thenComparingLong(TodoView::id))
                .toList();
    }

    private static int todoRank(String state) {
        return switch (state) {
            case "REVIEW" -> 0;
            case "PENDING" -> 1;
            case "DONE" -> 2;
            case "IGNORED" -> 3;
            default -> 4;
        };
    }

    public List<CalendarDeadline> calendarDeadlines(String username) {
        return db.query("""
                SELECT t.id AS todo_id,t.field_key,m.title,v.fields_json,v.version_no,v.simulated
                FROM todo t JOIN matter m ON m.id=t.matter_id
                JOIN notice_version v ON v.id=m.current_version_id AND v.id=t.target_version_id
                WHERE t.username=? AND t.todo_state='PENDING' AND v.id=t.acknowledged_version_id
                    AND v.version_state='PUBLISHED'
                ORDER BY t.id
                """, (row, index) -> {
            String key = row.getString("field_key");
            Field field = readFields(row.getString("fields_json")).stream()
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst().orElse(null);
            if (!confirmed(field) || !field.actionable()) return null;
            return new CalendarDeadline(row.getLong("todo_id"), row.getString("title"), field.slot().stage(),
                    field.choice().value(), row.getInt("version_no"), row.getBoolean("simulated"));
        }, username).stream().filter(Objects::nonNull).sorted(Comparator.comparing(CalendarDeadline::dueAt)).toList();
    }

    @Transactional
    public TodoView updateTodo(String username, long id, Long seenVersion, String operation) {
        Todo before = ownedTodo(username, id);
        Matter matter = matter(before.matterId(), true);
        Todo todo = ownedTodo(username, id);
        if ("IGNORED".equals(todo.state())) throw conflict("该字段已标为不适用，无需办理");
        if (seenVersion == null || !Objects.equals(matter.currentVersionId(), seenVersion)
                || !Objects.equals(todo.targetVersionId(), seenVersion)) {
            throw conflict("通知版本已更新，请重新查看待办");
        }
        Version active = version(seenVersion);
        Field field = active.field(todo.fieldKey());
        if (field != null && "NOT_APPLICABLE".equals(field.state())) {
            throw conflict("该字段已标为不适用，无需办理");
        }
        if (!confirmed(field)) throw conflict("该字段仍在待核验，确认后才能办理");
        String next;
        if ("confirm".equals(operation) && "REVIEW".equals(todo.state())) {
            next = "PENDING";
            audit(matter.id(), seenVersion, username, "确认待办变更", field.key() + " · " + describe(field));
        } else if ("done".equals(operation) && !"REVIEW".equals(todo.state())) {
            next = "DONE";
        } else if ("reopen".equals(operation) && "DONE".equals(todo.state())) {
            next = "PENDING";
        } else {
            throw conflict("请按当前待办状态操作");
        }
        int changed = db.update("UPDATE todo SET todo_state=?, acknowledged_version_id=? WHERE id=? AND username=? AND todo_state=?",
                next, seenVersion, id, username, todo.state());
        if (changed != 1) throw conflict("待办状态已变化，请刷新后重试");
        return todoView(ownedTodo(username, id));
    }

    @Transactional
    public TodoView updateStep(String username, long id, String stepKey, boolean done) {
        Todo before = ownedTodo(username, id);
        if ("REVIEW".equals(before.state())) throw conflict("通知已变更，请先确认新版本再勾选办理清单");
        if (!"PENDING".equals(before.state())) throw conflict("只有待办理事项可以勾选办理清单");
        Matter matter = matter(before.matterId(), true);
        Todo todo = ownedTodo(username, id);
        if (todo.targetVersionId() == null || !Objects.equals(matter.currentVersionId(), todo.targetVersionId())
                || !Objects.equals(todo.acknowledgedVersionId(), todo.targetVersionId())) {
            throw conflict("通知版本已更新，请刷新后重试");
        }
        Version active = version(todo.targetVersionId());
        String stage = active.field(todo.fieldKey()) == null ? "" : active.field(todo.fieldKey()).slot().stage();
        boolean known = todoSteps(todo, active, stage).stream().anyMatch(step -> step.key().equals(stepKey));
        if (!known) throw new ResponseStatusException(NOT_FOUND, "找不到这个办理步骤");
        int changed = db.update("""
                UPDATE todo_step SET version_id=?, step_done=?, updated_at=CURRENT_TIMESTAMP
                WHERE todo_id=? AND step_key=?
                """, active.id(), done, id, stepKey);
        if (changed == 0) {
            db.update("INSERT INTO todo_step(todo_id,step_key,version_id,step_done) VALUES (?,?,?,?)",
                    id, stepKey, active.id(), done);
        }
        return todoView(ownedTodo(username, id));
    }

    @Transactional
    public void feedback(String username, long matterId, long seenVersion, String content) {
        Matter matter = matter(matterId, true);
        if (!Objects.equals(matter.currentVersionId(), seenVersion)) throw conflict("通知版本已更新，请刷新后反馈");
        String message = required(content, 600, "请填写 600 字以内的纠错说明");
        db.update("INSERT INTO feedback(username,matter_id,version_id,message) VALUES (?,?,?,?)",
                username, matterId, seenVersion, message);
        audit(matterId, seenVersion, username, "提交纠错", message);
    }

    public List<Feedback> feedback() {
        return db.query("""
                SELECT f.*, m.title FROM feedback f JOIN matter m ON f.matter_id=m.id
                ORDER BY f.handled, f.id DESC LIMIT 100
                """, (row, index) -> new Feedback(row.getLong("id"), row.getLong("matter_id"), row.getLong("version_id"),
                row.getString("title"), row.getString("message"), row.getBoolean("handled"),
                row.getTimestamp("created_at").toLocalDateTime()));
    }

    public List<Feedback> myFeedback(String username) {
        return db.query("""
                SELECT f.*, m.title FROM feedback f JOIN matter m ON f.matter_id=m.id
                WHERE f.username=? ORDER BY f.id DESC LIMIT 50
                """, (row, index) -> new Feedback(row.getLong("id"), row.getLong("matter_id"), row.getLong("version_id"),
                row.getString("title"), row.getString("message"), row.getBoolean("handled"),
                row.getTimestamp("created_at").toLocalDateTime()), username);
    }

    @Transactional
    public void handleFeedback(long id, String actor) {
        Feedback selected = db.query("""
                SELECT f.*, m.title FROM feedback f JOIN matter m ON f.matter_id=m.id WHERE f.id=?
                """, (row, index) -> new Feedback(row.getLong("id"), row.getLong("matter_id"), row.getLong("version_id"),
                row.getString("title"), row.getString("message"), row.getBoolean("handled"),
                row.getTimestamp("created_at").toLocalDateTime()), id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "找不到这条反馈"));
        if (db.update("UPDATE feedback SET handled=TRUE WHERE id=? AND handled=FALSE", id) == 1) {
            audit(selected.matterId(), selected.versionId(), actor, "登记纠错核查", selected.message());
        }
    }

    public List<Event> events() {
        return db.query("""
                SELECT a.*, m.title FROM audit_event a JOIN matter m ON a.matter_id=m.id
                ORDER BY a.id DESC LIMIT 100
                """, (row, index) -> new Event(row.getLong("id"), row.getLong("matter_id"), nullableLong(row, "version_id"),
                row.getString("title"), row.getString("actor"), row.getString("action_name"), row.getString("detail"),
                row.getTimestamp("created_at").toLocalDateTime()));
    }

    private void verifyChoice(Choice choice, long matterId) {
        if (choice == null) throw bad("缺少经核验的字段值");
        if (choice.tentative()) throw bad("原文标明暂定或待定，请保留待复核");
        if (choice.reviewReason() != null) throw bad(choice.reviewReason());
        if (choice.value() == null || choice.evidence() == null) throw bad("字段值必须绑定原文依据");
        Evidence evidence = choice.evidence();
        Version source = version(evidence.versionId());
        List<Fragment> fragments = NoticeRules.fragments(source.snapshot());
        if (source.matterId() != matterId || evidence.fragmentNo() < 1 || evidence.fragmentNo() > fragments.size()) {
            throw bad("证据片段和通知版本不匹配");
        }
        Fragment fragment = fragments.get(evidence.fragmentNo() - 1);
        if (evidence.start() < fragment.start() || evidence.end() > fragment.end()
                || evidence.start() >= evidence.end()
                || !source.snapshot().substring(evidence.start(), evidence.end()).equals(evidence.quote())) {
            throw bad("证据区间不能定位到原文");
        }
    }

    private static void verifyDeadlines(Version draft) {
        for (Field field : draft.fields()) {
            if (!"CONFIRMED".equals(field.state()) || !"截止".equals(field.slot().type())) continue;
            Choice choice = field.choice();
            if (choice == null || choice.value() == null) continue;
            LocalDate due;
            try {
                String value = choice.value();
                due = LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
            } catch (DateTimeParseException invalid) {
                throw conflict("截止日期格式无法解析，请重新核验：" + field.key());
            }
            if (due.isBefore(draft.noticeDate())) {
                throw conflict("截止日期早于通知日期，请核对：" + field.key());
            }
        }
    }

    private void synchronizeTodos(long matterId, Version previous, Version next) {
        for (Todo todo : db.query("SELECT * FROM todo WHERE matter_id=?", TODOS, matterId)) {
            Field field = next.field(todo.fieldKey());
            if (field == null || "NOT_APPLICABLE".equals(field.state())) {
                db.update("UPDATE todo SET target_version_id=?, todo_state='IGNORED' WHERE id=?", next.id(), todo.id());
                continue;
            }
            boolean affected = previous == null || !confirmed(field) || "REVIEW".equals(todo.state())
                    || !requirements(previous, todo.fieldKey()).equals(requirements(next, todo.fieldKey()));
            if (affected) {
                db.update("UPDATE todo SET target_version_id=?, todo_state='REVIEW' WHERE id=?", next.id(), todo.id());
            } else {
                db.update("UPDATE todo SET target_version_id=?, acknowledged_version_id=? WHERE id=?",
                        next.id(), next.id(), todo.id());
            }
        }
        for (String username : db.queryForList("SELECT username FROM subscription WHERE matter_id=?", String.class, matterId)) {
            addMissingTodos(username, matterId, next);
        }
    }

    private static List<String> requirements(Version version, String key) {
        Field task = version.field(key);
        if (task == null) return List.of();
        return version.fields().stream()
                .filter(field -> field.slot().role().equals(task.slot().role()) && field.slot().stage().equals(task.slot().stage()))
                .sorted(Comparator.comparing(Field::key))
                .map(field -> field.key() + ":" + field.state() + ":" + describe(field)
                        + ":" + (field.choice() == null ? "" : field.choice().evidence().quote()))
                .toList();
    }

    private void addMissingTodos(String username, long matterId, Version version) {
        List<String> existing = db.queryForList("SELECT field_key FROM todo WHERE username=? AND matter_id=?",
                String.class, username, matterId);
        version.fields().stream().filter(field -> field.actionable() && confirmed(field) && !existing.contains(field.key()))
                .forEach(field -> db.update("""
                        INSERT INTO todo(username,matter_id,field_key,acknowledged_version_id,target_version_id,todo_state)
                        VALUES (?,?,?,?,?,'PENDING')
                        """, username, matterId, field.key(), version.id(), version.id()));
    }

    private TodoView todoView(Todo todo) {
        Version previous = version(todo.acknowledgedVersionId());
        Version current = todo.targetVersionId() == null ? null : version(todo.targetVersionId());
        Field field = previous.field(todo.fieldKey());
        Field currentField = current == null ? null : current.field(todo.fieldKey());
        String dueAt = null;
        if (confirmed(field)) dueAt = field.choice().value();
        if (confirmed(currentField)) dueAt = currentField.choice().value();
        if (currentField != null && "NOT_APPLICABLE".equals(currentField.state())) dueAt = null;
        String stage = field == null ? "" : field.slot().stage();
        Version active = current == null ? previous : current;
        return new TodoView(todo.id(), todo.matterId(), matter(todo.matterId(), false).title(), stage,
                todo.state(), previous.number(), current == null ? null : current.number(), todo.targetVersionId(),
                field, currentField, current != null && current.simulated(), dueAt, todoSteps(todo, active, stage));
    }

    private List<TodoStep> todoSteps(Todo todo, Version active, String stage) {
        Map<String, Boolean> done = new LinkedHashMap<>();
        db.queryForList("SELECT step_key,step_done FROM todo_step WHERE todo_id=? AND version_id=?",
                todo.id(), active.id()).forEach(mark ->
                done.put((String) mark.get("step_key"), Boolean.TRUE.equals(mark.get("step_done"))));
        List<TodoStep> steps = new ArrayList<>();
        addStep(steps, done, "read", "阅读校方原通知", active.sourceUrl(), "read");
        if (stage != null && !stage.isBlank()) {
            addStepIfConfirmed(steps, done, active, stage, "适用对象", "condition", "确认自己是否符合条件");
            addStepIfConfirmed(steps, done, active, stage, "材料", "materials", "准备材料");
            addStepIfConfirmed(steps, done, active, stage, "入口", "entry", "完成报名或提交");
        }
        String deadline = active.field(todo.fieldKey()) != null && confirmed(active.field(todo.fieldKey()))
                ? active.field(todo.fieldKey()).choice().value() : null;
        addStep(steps, done, "deadline", "在截止前完成", deadline == null ? "截止时间待维护者核定" : deadline, "deadline");
        return steps;
    }

    private void addStepIfConfirmed(List<TodoStep> steps, Map<String, Boolean> done, Version active, String stage,
                                    String type, String key, String label) {
        active.fields().stream()
                .filter(field -> field.slot().role().equals("学生") && field.slot().stage().equals(stage)
                        && field.slot().type().equals(type) && confirmed(field))
                .findFirst()
                .ifPresent(field -> addStep(steps, done, key, label, field.choice().value(), key));
    }

    private void addStep(List<TodoStep> steps, Map<String, Boolean> done, String key, String label,
                         String detail, String kind) {
        steps.add(new TodoStep(key, label, detail == null || detail.isBlank() ? "以当前通知为准" : detail.strip(),
                kind, done.getOrDefault(key, false)));
    }

    private Todo ownedTodo(String username, long id) {
        return db.query("SELECT * FROM todo WHERE id=? AND username=?", TODOS, id, username).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "找不到你的这条待办"));
    }

    private Matter matter(long id, boolean lock) {
        return db.query("SELECT * FROM matter WHERE id=?" + (lock ? " FOR UPDATE" : ""), MATTERS, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "找不到该事项"));
    }

    private boolean subscribed(String username, long matterId) {
        return db.queryForObject("SELECT COUNT(*) FROM subscription WHERE username=? AND matter_id=?",
                Integer.class, username, matterId) > 0;
    }

    private Version mapVersion(ResultSet row, int ignored) throws SQLException {
        return new Version(row.getLong("id"), row.getLong("matter_id"), row.getInt("version_no"),
                nullableLong(row, "base_version_id"), row.getString("source_url"),
                row.getDate("notice_date").toLocalDate(), row.getBoolean("simulated"), row.getString("snapshot"),
                row.getString("snapshot_hash"), readFields(row.getString("fields_json")), row.getString("version_state"));
    }

    private List<Field> readFields(String stored) {
        try {
            return json.readValue(stored, new TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("通知字段读取失败", invalid);
        }
    }

    private String json(List<Field> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("通知字段保存失败", invalid);
        }
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private void audit(long matterId, Long versionId, String actor, String action, String detail) {
        db.update("INSERT INTO audit_event(matter_id,version_id,actor,action_name,detail) VALUES (?,?,?,?,?)",
                matterId, versionId, actor, action, detail);
    }

    private static boolean confirmed(Field field) {
        return field != null && "CONFIRMED".equals(field.state()) && field.choice() != null;
    }

    private static String describe(Field field) {
        if (field == null) return "暂无";
        return field.choice() == null ? field.change() : field.choice().value();
    }

    private static Long nullableLong(ResultSet row, String key) throws SQLException {
        long value = row.getLong(key);
        return row.wasNull() ? null : value;
    }

    private static String required(String value, int limit, String error) {
        if (value == null || value.isBlank() || value.length() > limit) throw bad(error);
        return value.strip();
    }

    private static String officialSource(String url) {
        String value = required(url, 500, "请填写学校通知的 HTTPS 原始链接");
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (host.equals("sgu.edu.cn") || host.endsWith(".sgu.edu.cn"))) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException malformed) {
            throw bad("通知链接格式有误");
        }
        throw bad("证据来源限韶关学院 HTTPS 官方域名");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(CONFLICT, message);
    }
}
