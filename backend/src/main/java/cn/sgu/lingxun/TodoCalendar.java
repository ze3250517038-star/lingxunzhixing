package cn.sgu.lingxun;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static cn.sgu.lingxun.Models.CalendarDeadline;

final class TodoCalendar {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private TodoCalendar() {}

    static byte[] export(List<CalendarDeadline> deadlines) {
        Calendar calendar = new Calendar();
        calendar.add(new ProdId("-//Lingxun Zhixing//Campus Deadlines//ZH-CN"));
        calendar.add(ImmutableVersion.VERSION_2_0);
        calendar.add(ImmutableCalScale.GREGORIAN);
        for (CalendarDeadline deadline : deadlines) {
            calendar.add(event(deadline));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            new CalendarOutputter().output(calendar, output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException("日历文件生成失败", error);
        }
    }

    private static VEvent event(CalendarDeadline deadline) {
        String name = (deadline.simulated() ? "【模拟】" : "") + deadline.title() + " · " + deadline.stage() + "截止";
        String time = deadline.dueAt();
        VEvent event;
        if (time.contains("T")) {
            var exactTime = LocalDateTime.parse(time).atZone(CAMPUS_ZONE).toInstant();
            event = new VEvent(exactTime, name);
        } else {
            var day = LocalDate.parse(time);
            event = new VEvent(day, day.plusDays(1), name);
        }
        event.add(new Uid("todo-" + deadline.todoId() + "@lingxun.local"));
        event.add(new Sequence(deadline.versionNumber()));
        event.add(new Description("依据岭讯知行通知 V" + deadline.versionNumber() + "。通知变更后请核对最新日期。"));
        return event;
    }
}
