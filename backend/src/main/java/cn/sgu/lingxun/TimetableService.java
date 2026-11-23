package cn.sgu.lingxun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static cn.sgu.lingxun.Models.Course;
import static cn.sgu.lingxun.Models.CourseInput;
import static cn.sgu.lingxun.Models.Semester;
import static cn.sgu.lingxun.Models.SemesterInput;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
class TimetableService {
    private static final Set<String> COLORS = Set.of("sage", "ocean", "amber", "rose", "violet", "slate");
    private static final Set<String> WEEK_PATTERNS = Set.of("EVERY", "ODD", "EVEN");
    private static final long MAX_SEMESTER_DAYS = 400;

    private final JdbcTemplate db;

    TimetableService(JdbcTemplate db) {
        this.db = db;
    }

    List<Course> list(String username) {
        return db.query("""
                SELECT id, course_name, teacher, location, weekday, start_time, end_time,
                       color, note, week_pattern, updated_at
                FROM course WHERE username=?
                ORDER BY weekday, start_time, course_name
                """, courseMapper(), username);
    }

    int count(String username) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM course WHERE username=?", Integer.class, username);
        return count == null ? 0 : count;
    }

    Semester semester(String username) {
        return db.query("""
                SELECT semester_name, start_date, end_date, updated_at
                FROM timetable_semester WHERE username=?
                """, semesterMapper(), username).stream().findFirst().orElse(null);
    }

    Semester saveSemester(String username, SemesterInput input) {
        SemesterValue value = validateSemester(input);
        int updated = db.update("""
                UPDATE timetable_semester
                SET semester_name=?, start_date=?, end_date=?, updated_at=CURRENT_TIMESTAMP
                WHERE username=?
                """, value.name(), value.startDate(), value.endDate(), username);
        if (updated == 0) {
            db.update("""
                    INSERT INTO timetable_semester(username, semester_name, start_date, end_date)
                    VALUES (?,?,?,?)
                    """, username, value.name(), value.startDate(), value.endDate());
        }
        return semester(username);
    }

    Course create(String username, CourseInput input) {
        CourseValue value = validate(input);
        KeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO course(username, course_name, teacher, location, weekday,
                                       start_time, end_time, color, note, week_pattern)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, value.name());
            statement.setString(3, value.teacher());
            statement.setString(4, value.location());
            statement.setInt(5, value.weekday());
            statement.setString(6, value.startTime());
            statement.setString(7, value.endTime());
            statement.setString(8, value.color());
            statement.setString(9, value.note());
            statement.setString(10, value.weekPattern());
            return statement;
        }, keys);
        Number key = generatedId(keys);
        if (key == null) throw new ResponseStatusException(BAD_REQUEST, "课程保存失败，请重试");
        return find(username, key.longValue());
    }

    Course update(String username, long id, CourseInput input) {
        CourseValue value = validate(input);
        int updated = db.update("""
                UPDATE course SET course_name=?, teacher=?, location=?, weekday=?,
                       start_time=?, end_time=?, color=?, note=?, week_pattern=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND username=?
                """, value.name(), value.teacher(), value.location(), value.weekday(),
                value.startTime(), value.endTime(), value.color(), value.note(), value.weekPattern(),
                id, username);
        if (updated == 0) throw new ResponseStatusException(NOT_FOUND, "找不到这门课程");
        return find(username, id);
    }

    void delete(String username, long id) {
        int deleted = db.update("DELETE FROM course WHERE id=? AND username=?", id, username);
        if (deleted == 0) throw new ResponseStatusException(NOT_FOUND, "找不到这门课程");
    }

    private Course find(String username, long id) {
        return db.query("""
                SELECT id, course_name, teacher, location, weekday, start_time, end_time,
                       color, note, week_pattern, updated_at
                FROM course WHERE id=? AND username=?
                """, courseMapper(), id, username).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "找不到这门课程"));
    }

    private RowMapper<Course> courseMapper() {
        return (row, index) -> new Course(
                row.getLong("id"),
                row.getString("course_name"),
                row.getString("teacher"),
                row.getString("location"),
                row.getInt("weekday"),
                row.getString("start_time"),
                row.getString("end_time"),
                row.getString("color"),
                row.getString("note"),
                row.getString("week_pattern"),
                row.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private RowMapper<Semester> semesterMapper() {
        return (row, index) -> new Semester(
                row.getString("semester_name"),
                row.getDate("start_date").toLocalDate(),
                row.getDate("end_date").toLocalDate(),
                row.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private CourseValue validate(CourseInput input) {
        if (input == null) throw new ResponseStatusException(BAD_REQUEST, "缺少课程信息");
        String name = clean(input.name());
        if (name.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "课程名称不能为空");
        if (name.length() > 120) throw new ResponseStatusException(BAD_REQUEST, "课程名称不能超过 120 字");
        String teacher = clean(input.teacher());
        if (teacher.length() > 80) throw new ResponseStatusException(BAD_REQUEST, "教师姓名不能超过 80 字");
        String location = clean(input.location());
        if (location.length() > 100) throw new ResponseStatusException(BAD_REQUEST, "上课地点不能超过 100 字");
        String note = clean(input.note());
        if (note.length() > 300) throw new ResponseStatusException(BAD_REQUEST, "备注不能超过 300 字");
        int weekday = input.weekday() == null ? 0 : input.weekday();
        if (weekday < 1 || weekday > 7) throw new ResponseStatusException(BAD_REQUEST, "星期必须在周一到周日之间");
        int start = minutes(input.startTime());
        int end = minutes(input.endTime());
        if (end <= start) throw new ResponseStatusException(BAD_REQUEST, "下课时间必须晚于上课时间");
        if (end - start > 12 * 60) throw new ResponseStatusException(BAD_REQUEST, "单节课时长不能超过 12 小时");
        String color = clean(input.color());
        if (color.isEmpty()) color = "sage";
        if (!COLORS.contains(color)) throw new ResponseStatusException(BAD_REQUEST, "不支持这个课程颜色");
        String weekPattern = clean(input.weekPattern()).toUpperCase(Locale.ROOT);
        if (weekPattern.isEmpty()) weekPattern = "EVERY";
        if (!WEEK_PATTERNS.contains(weekPattern)) {
            throw new ResponseStatusException(BAD_REQUEST, "上课周次只能是每周、单周或双周");
        }
        return new CourseValue(name, teacher, location, weekday, format(start), format(end),
                color, note, weekPattern);
    }

    private SemesterValue validateSemester(SemesterInput input) {
        if (input == null) throw new ResponseStatusException(BAD_REQUEST, "缺少学期信息");
        String name = clean(input.name());
        if (name.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "学期名称不能为空");
        if (name.length() > 80) throw new ResponseStatusException(BAD_REQUEST, "学期名称不能超过 80 字");
        LocalDate start = parseDate(input.startDate(), "学期开始日期");
        LocalDate end = parseDate(input.endDate(), "学期结束日期");
        if (end.isBefore(start)) throw new ResponseStatusException(BAD_REQUEST, "学期结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(start, end) > MAX_SEMESTER_DAYS) {
            throw new ResponseStatusException(BAD_REQUEST, "一个学期不能超过 400 天");
        }
        return new SemesterValue(name, start, end);
    }

    private static LocalDate parseDate(String value, String label) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, label + "不能为空");
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(BAD_REQUEST, label + "格式应为 YYYY-MM-DD");
        }
    }

    private static int minutes(String value) {
        if (value == null || !value.matches("^(\\d{1,2}):(\\d{2})$")) {
            throw new ResponseStatusException(BAD_REQUEST, "时间格式应为 HH:mm");
        }
        String[] parts = value.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour > 23 || minute > 59) throw new ResponseStatusException(BAD_REQUEST, "时间格式应为 HH:mm");
        return hour * 60 + minute;
    }

    private static String format(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static Number generatedId(KeyHolder keys) {
        if (keys.getKeyList().isEmpty()) return null;
        var values = keys.getKeyList().get(0);
        Object direct = values.get("id");
        if (direct instanceof Number number) return number;
        direct = values.get("GENERATED_KEY");
        if (direct instanceof Number number) return number;
        return values.values().stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .findFirst().orElse(null);
    }

    private record CourseValue(String name, String teacher, String location, int weekday,
                               String startTime, String endTime, String color, String note,
                               String weekPattern) {}

    private record SemesterValue(String name, LocalDate startDate, LocalDate endDate) {}
}
