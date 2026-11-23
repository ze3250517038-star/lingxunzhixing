const DAY_MS = 86_400_000

export const weekPatternLabels = { EVERY: '每周', ODD: '单周', EVEN: '双周' }

export function parseDateKey(value) {
  if (!value) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value))
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12)
  return Number.isNaN(date.getTime()) ? null : date
}

export function dateKey(date) {
  return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0')
    + '-' + String(date.getDate()).padStart(2, '0')
}

export function mondayOf(date) {
  const monday = new Date(date)
  monday.setHours(12, 0, 0, 0)
  monday.setDate(monday.getDate() - (monday.getDay() || 7) + 1)
  return monday
}

export function weekNumberFor(date, semester) {
  const start = parseDateKey(semester?.startDate)
  if (!start) return null
  return Math.floor((mondayOf(date).getTime() - mondayOf(start).getTime()) / (7 * DAY_MS)) + 1
}

export function dateInSemester(date, semester) {
  const start = parseDateKey(semester?.startDate)
  const end = parseDateKey(semester?.endDate)
  if (!start || !end) return true
  return date >= start && date <= end
}

export function weekPatternMatches(pattern, weekNumber) {
  if (!pattern || pattern === 'EVERY' || weekNumber == null) return true
  if (pattern === 'ODD') return weekNumber % 2 === 1
  if (pattern === 'EVEN') return weekNumber % 2 === 0
  return true
}

export function courseActiveOnDate(course, semester, date) {
  if (!semester) return true
  if (!dateInSemester(date, semester)) return false
  return weekPatternMatches(course.weekPattern, weekNumberFor(date, semester))
}

export function semesterWeekMeta(semester, mondayDate) {
  const start = parseDateKey(semester?.startDate)
  const end = parseDateKey(semester?.endDate)
  if (!start || !end) {
    return { configured: false, active: true, before: false, after: false, weekNumber: null }
  }
  const monday = mondayOf(mondayDate)
  const weekEnd = new Date(monday)
  weekEnd.setDate(weekEnd.getDate() + 6)
  const weekNumber = weekNumberFor(monday, semester)
  return {
    configured: true,
    active: weekEnd >= start && monday <= end,
    before: weekEnd < start,
    after: monday > end,
    weekNumber
  }
}

export function semesterWeekLabel(meta) {
  if (!meta.configured) return '未设置学期'
  if (meta.before) return '学期尚未开始'
  if (meta.after) return '学期已结束'
  return '学期第 ' + meta.weekNumber + ' 周'
}

export function semesterRange(semester) {
  const start = parseDateKey(semester?.startDate)
  const end = parseDateKey(semester?.endDate)
  if (!start || !end) return ''
  return dateKey(start).replaceAll('-', '.') + ' — ' + dateKey(end).replaceAll('-', '.')
}

export function weekPatternLabel(pattern) {
  return weekPatternLabels[pattern] ?? weekPatternLabels.EVERY
}
