<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  courseActiveOnDate,
  dateInSemester,
  dateKey,
  semesterWeekLabel,
  semesterWeekMeta,
  weekPatternLabel
} from './timetable'

const props = defineProps({
  courses: { type: Array, default: () => [] },
  semester: { type: Object, default: null }
})
const emit = defineEmits(['add', 'edit'])

const DAY_START = 8 * 60
const DAY_END = 20 * 60
const HOUR_HEIGHT = 72
const now = ref(new Date())
const weekOffset = ref(0)
let timer

const weekdayNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const hourRows = Array.from({ length: 12 }, (_, index) => String(index + 8).padStart(2, '0') + ':00')

const monday = computed(() => {
  const date = new Date(now.value)
  const day = date.getDay() || 7
  date.setDate(date.getDate() - day + 1 + weekOffset.value * 7)
  date.setHours(12, 0, 0, 0)
  return date
})
const weekDays = computed(() => Array.from({ length: 7 }, (_, index) => {
  const date = new Date(monday.value)
  date.setDate(monday.value.getDate() + index)
  const key = dateKey(date)
  return {
    weekday: index + 1,
    label: weekdayNames[index],
    day: date.getDate(),
    date: date.getMonth() + 1 + '月' + date.getDate() + '日',
    key,
    dateValue: date,
    inSemester: dateInSemester(date, props.semester),
    today: key === dateKey(now.value),
    weekend: index >= 5
  }
}))
const weekMeta = computed(() => semesterWeekMeta(props.semester, monday.value))
const weekRange = computed(() => {
  const start = weekDays.value[0]
  const end = weekDays.value[6]
  const sameMonth = monday.value.getMonth() === new Date(monday.value.getFullYear(), monday.value.getMonth(), monday.value.getDate() + 6).getMonth()
  return sameMonth
    ? (monday.value.getMonth() + 1) + '月' + start.day + '日 - ' + end.day + '日'
    : start.date + ' - ' + end.date
})
const todayCourses = computed(() => props.courses
  .filter(course => course.weekday === (now.value.getDay() || 7))
  .filter(course => courseActiveOnDate(course, props.semester, now.value))
  .sort((first, second) => first.startTime.localeCompare(second.startTime)))
const weekCourses = computed(() => props.courses.filter(course => {
  const day = weekDays.value[course.weekday - 1]
  return day && courseActiveOnDate(course, props.semester, day.dateValue)
}))
const termLabel = computed(() => semesterWeekLabel(weekMeta.value))
const nowTop = computed(() => {
  const minutes = now.value.getHours() * 60 + now.value.getMinutes()
  if (minutes < DAY_START || minutes > DAY_END) return null
  return (minutes - DAY_START) / 60 * HOUR_HEIGHT
})
const conflictIds = computed(() => {
  const conflicts = new Set()
  const dayGroups = weekCourses.value.reduce((groups, course) => {
    (groups[course.weekday] ??= []).push(course)
    return groups
  }, {})
  for (const courses of Object.values(dayGroups)) {
    for (let first = 0; first < courses.length; first += 1) {
      for (let second = first + 1; second < courses.length; second += 1) {
        if (overlaps(courses[first], courses[second])) {
          conflicts.add(courses[first].id)
          conflicts.add(courses[second].id)
        }
      }
    }
  }
  return conflicts
})
const courseLayouts = computed(() => {
  const layouts = new Map()
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    const courses = weekCourses.value
      .filter(course => course.weekday === weekday && conflictIds.value.has(course.id))
      .sort((first, second) => minutes(first.startTime) - minutes(second.startTime) || first.id - second.id)
    let component = []
    let componentEnd = -1
    const flush = () => {
      if (!component.length) return
      const laneEnds = []
      const assignments = new Map()
      for (const course of component) {
        const start = minutes(course.startTime)
        let lane = laneEnds.findIndex(end => end <= start)
        if (lane === -1) lane = laneEnds.length
        laneEnds[lane] = minutes(course.endTime)
        assignments.set(course.id, lane)
      }
      for (const [id, lane] of assignments) layouts.set(id, { lane, lanes: laneEnds.length })
      component = []
    }
    for (const course of courses) {
      const start = minutes(course.startTime)
      if (component.length && start >= componentEnd) flush()
      component.push(course)
      componentEnd = Math.max(componentEnd, minutes(course.endTime))
    }
    flush()
  }
  return layouts
})

function minutes(time) {
  const [hour, minute] = String(time || '00:00').split(':').map(Number)
  return hour * 60 + minute
}

function overlaps(first, second) {
  return minutes(first.startTime) < minutes(second.endTime) && minutes(second.startTime) < minutes(first.endTime)
}

function blockStyle(course) {
  const start = Math.max(DAY_START, minutes(course.startTime))
  const end = Math.min(DAY_END, minutes(course.endTime))
  const top = (start - DAY_START) / 60 * HOUR_HEIGHT
  const height = Math.max(34, (end - start) / 60 * HOUR_HEIGHT - 4)
  const style = { top: top + 'px', height: height + 'px' }
  const layout = courseLayouts.value.get(course.id)
  if (!layout) return style
  return {
    ...style,
    left: 'calc(' + layout.lane * 100 / layout.lanes + '% + 2px)',
    width: 'calc(' + 100 / layout.lanes + '% - 4px)',
    right: 'auto'
  }
}

function coursesFor(day) {
  return props.courses
    .filter(course => course.weekday === day.weekday)
    .filter(course => courseActiveOnDate(course, props.semester, day.dateValue))
    .sort((first, second) => first.startTime.localeCompare(second.startTime))
}

function goWeek(offset) {
  weekOffset.value += offset
}

function goToday() {
  weekOffset.value = 0
}

function courseTime(course) {
  return course.startTime + ' - ' + course.endTime
}

onMounted(() => {
  timer = window.setInterval(() => { now.value = new Date() }, 60000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <section class="timetable-shell">
    <header class="timetable-toolbar">
      <div>
        <span class="eyebrow">课程周视图</span>
        <h2>{{ weekRange }}</h2>
        <p class="quiet">
          <strong class="week-term">{{ termLabel }}</strong>
          <span> · </span>
          <template v-if="weekOffset === 0">
            {{ todayCourses.length ? '今天 ' + todayCourses.length + ' 节课，最早 ' + todayCourses[0].startTime : '今天没有排课' }}
          </template>
          <template v-else>本周 {{ weekCourses.length }} 节课</template>
          <span v-if="conflictIds.size"> · {{ conflictIds.size }} 节课时间冲突</span>
        </p>
      </div>
      <div class="calendar-nav">
        <button class="outline" @click="goWeek(-1)">上一周</button>
        <button class="outline" @click="goToday">本周</button>
        <button class="outline" @click="goWeek(1)">下一周</button>
        <button class="primary" @click="emit('add', 0)">+ 添加课程</button>
      </div>
    </header>

    <p v-if="conflictIds.size" class="schedule-warning">
      当前周次内存在课程时间重叠，系统已标红并并排显示。单双周课程按学期周次错开后，不会误报冲突。
    </p>
    <p v-else-if="!weekMeta.configured" class="schedule-warning soft">
      还没有设置学期起止日期，单双周暂不参与过滤。请先在“学期设置”中填写开始和结束日期。
    </p>
    <p v-else-if="weekMeta.before || weekMeta.after" class="schedule-warning soft">
      这一周不在学期范围内，课程不会显示。你可以切换到学期内的周次查看。
    </p>

    <div class="week-scroll">
      <div class="week-board">
        <div class="time-axis" aria-hidden="true">
          <div class="time-head"></div>
          <div v-for="hour in hourRows" :key="hour" class="time-slot">{{ hour }}</div>
        </div>
        <div v-for="day in weekDays" :key="day.key" class="weekly-day"
          :class="{ today: day.today, weekend: day.weekend, outside: !day.inSemester }">
          <div class="day-head">
            <span>{{ day.label }}</span>
            <strong>{{ day.day }}</strong>
            <small v-if="day.today">今天</small>
            <button class="day-add" :aria-label="'在' + day.label + '添加课程'" @click="emit('add', day.weekday)">+</button>
          </div>
          <div class="day-track">
            <i v-if="day.today && nowTop != null" class="now-line" :style="{ top: nowTop + 'px' }" aria-hidden="true"></i>
            <button v-for="course in coursesFor(day)" :key="course.id"
              class="course-block" :class="['tone-' + course.color, { conflict: conflictIds.has(course.id) }]"
              :style="blockStyle(course)"
              :aria-label="course.name + '，' + courseTime(course) + '，' + weekPatternLabel(course.weekPattern)"
              @click="emit('edit', course)">
              <strong>
                {{ course.name }}
                <em v-if="course.weekPattern && course.weekPattern !== 'EVERY'" class="course-pattern">
                  {{ weekPatternLabel(course.weekPattern) }}
                </em>
              </strong>
              <span>{{ courseTime(course) }}</span>
              <small v-if="course.location">{{ course.location }}</small>
            </button>
            <p v-if="!coursesFor(day).length" class="day-empty">{{ day.inSemester ? '无课' : '学期外' }}</p>
          </div>
        </div>
      </div>
    </div>

    <footer class="timetable-foot">
      <span class="quiet">点击课程可编辑或删除；单双周按学期起始日期换算的周次生效，课程只保存在当前账号。</span>
      <button v-if="todayCourses.length" class="text-button" @click="emit('edit', todayCourses[0])">编辑今天第一节课</button>
    </footer>
  </section>
</template>
