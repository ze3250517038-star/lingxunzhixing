<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { request, requestCalendar, requestForm, useSession } from './api.js'
import NoticeDiff from './NoticeDiff.vue'

const session = ref(null)
const page = ref('matters')
const busy = ref(false)
const error = ref('')
const message = ref('')
const credentials = ref({ username: '', password: '' })

const matters = ref([])
const selectedMatterId = ref(null)
const matterCategory = ref('ALL')
const searchTerm = ref('')
const todos = ref([])
const todoFilter = ref('ALL')
const todoWindow = ref('ALL')
const todoQuery = ref('')
const todoSort = ref('DUE')
const myFeedback = ref([])
const calendarMonth = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1, 12))
const calendarSelectedDate = ref(formatDateKey(new Date()))
const reminderDismissed = ref({})
const feedbackText = ref('')
const showFeedback = ref(false)

const adminMatters = ref([])
const reviewMatterId = ref('')
const history = ref([])
const version = ref(null)
const comparison = ref(null)
const feedback = ref([])
const events = ref([])

const emptyDraft = () => ({
  matterId: '', title: '', category: '竞赛报名', sourceUrl: '', noticeDate: '', snapshot: '', simulated: false,
  extractionMode: 'RULE'
})
const draft = ref(emptyDraft())
const proof = ref(null)
const proofDialog = ref(null)
const changeImpact = ref(null)
const correction = ref(null)
const correctionDialog = ref(null)
const quoteInput = ref(null)
const importWarnings = ref([])

const isReviewer = computed(() => session.value?.viewer?.role === 'reviewer')
const selectedMatter = computed(() => matters.value.find(item => item.matter.id === selectedMatterId.value))
const matterCategories = computed(() => ['ALL', ...new Set(matters.value.map(item => item.matter.category))])
const visibleMatters = computed(() => matterCategory.value === 'ALL'
  ? matters.value
  : matters.value.filter(item => item.matter.category === matterCategory.value))
const activeMatter = computed(() => adminMatters.value.find(item => item.id === reviewMatterId.value))
const awaiting = computed(() => version.value?.fields.filter(field => field.state === 'CANDIDATE').length ?? 0)
const stages = computed(() => selectedMatter.value?.version.fields.filter(field =>
  field.slot.type === '截止' || field.slot.type === '时间') ?? [])
const conditions = computed(() => selectedMatter.value?.version.fields.filter(field =>
  !['截止', '时间'].includes(field.slot.type)) ?? [])
const deadline = computed(() => stages.value.find(field => field.slot.role === '学生' && field.slot.type === '截止'))
const correctionFragments = computed(() => correction.value
  ? version.value.snapshot.split('\n').map((text, index) => ({ number: index + 1, text })).filter(fragment => fragment.text.trim())
  : [])
const correctionQuote = computed(() => correctionFragments.value.find(fragment => fragment.number === correction.value?.fragmentNo)?.text ?? '')
const correctionIsDate = computed(() => ['截止', '时间'].includes(correction.value?.slot?.type))
const studentPages = [
  { key: 'overview', label: '首页', icon: '◎' },
  { key: 'matters', label: '事项查询', icon: '⌕' },
  { key: 'todos', label: '我的待办', icon: '✓' },
  { key: 'calendar', label: '截止日历', icon: '▦' },
  { key: 'reminders', label: '提醒中心', icon: '!' },
  { key: 'subscriptions', label: '我的订阅', icon: '☆' },
  { key: 'my-feedback', label: '我的反馈', icon: '✎' }
]
const maintainerPages = [
  { key: 'intake', label: '通知录入', icon: '+' },
  { key: 'versions', label: '核验与版本', icon: '≋' },
  { key: 'feedback', label: '纠错反馈', icon: '!' },
  { key: 'audit', label: '审核记录', icon: '◷' }
]
const navigation = computed(() => isReviewer.value ? maintainerPages : studentPages)
const pageTitle = computed(() => navigation.value.find(item => item.key === page.value)?.label ?? '')
const taskStates = { PENDING: '待办理', DONE: '已完成', REVIEW: '待复核', IGNORED: '不适用' }
const taskOrder = { REVIEW: 0, PENDING: 1, DONE: 2, IGNORED: 3 }
const todoFilters = ['ALL', 'REVIEW', 'PENDING', 'DONE', 'IGNORED']
const todoWindows = ['ALL', 'URGENT', 'OVERDUE', 'WEEK', 'TODAY']
const todoWindowLabels = { ALL: '全部时间', URGENT: '紧急', OVERDUE: '已逾期', WEEK: '7天内', TODAY: '今天' }
const todoCounts = computed(() => {
  const counts = { ALL: todos.value.length, REVIEW: 0, PENDING: 0, DONE: 0, IGNORED: 0 }
  for (const task of todos.value) counts[task.state]++
  return counts
})
const visibleTodos = computed(() => {
  const query = todoQuery.value.trim().toLowerCase()
  return todos.value
    .filter(task => todoFilter.value === 'ALL' || task.state === todoFilter.value)
    .filter(task => todoWindow.value === 'ALL' || matchesTodoWindow(task, todoWindow.value))
    .filter(task => !query || [task.title, task.stage].some(text => text.toLowerCase().includes(query)))
    .sort((first, second) => {
      const rank = taskOrder[first.state] - taskOrder[second.state]
      if (rank) return rank
      if (todoSort.value === 'NEWEST') return second.id - first.id
      const risk = (taskRisk(first)?.score ?? 9) - (taskRisk(second)?.score ?? 9)
      if (risk) return risk
      const firstDate = todoDueKey(first) || '9999-12-31'
      const secondDate = todoDueKey(second) || '9999-12-31'
      return firstDate.localeCompare(secondDate) || first.title.localeCompare(second.title, 'zh-CN')
    })
})
const studentStats = computed(() => {
  const pending = todos.value.filter(task => task.state === 'PENDING')
  const review = todos.value.filter(task => task.state === 'REVIEW')
  const done = todos.value.filter(task => task.state === 'DONE')
  const ignored = todos.value.filter(task => task.state === 'IGNORED')
  const overdue = pending.filter(task => dueDays(task) != null && dueDays(task) < 0)
  const soon = pending.filter(task => {
    const days = dueDays(task)
    return days != null && days >= 0 && days <= 7
  })
  const urgent = pending.filter(task => {
    const days = dueDays(task)
    return days != null && days <= 3
  })
  const today = pending.filter(task => dueDays(task) === 0)
  const next = [...pending].filter(task => todoDueKey(task))
    .sort((first, second) => todoDueKey(first).localeCompare(todoDueKey(second)))[0] ?? null
  return {
    pending: pending.length, review: review.length, done: done.length, ignored: ignored.length,
    overdue: overdue.length, soon: soon.length, urgent: urgent.length, today: today.length, next,
    subscribed: matters.value.filter(item => item.subscribed).length
  }
})
const studentReviewTodos = computed(() => todos.value.filter(task => task.state === 'REVIEW').slice(0, 3))
const studentSubscribed = computed(() => matters.value.filter(item => item.subscribed))
const todayFocus = computed(() => todos.value
  .filter(task => task.state === 'REVIEW'
    || (task.state === 'PENDING' && (dueDays(task) ?? 999) <= 3))
  .sort((first, second) => {
    const stateRank = (first.state === 'REVIEW' ? 0 : 1) - (second.state === 'REVIEW' ? 0 : 1)
    if (stateRank) return stateRank
    const risk = (taskRisk(first)?.score ?? 9) - (taskRisk(second)?.score ?? 9)
    return risk || (todoDueKey(first) || '9999-12-31').localeCompare(todoDueKey(second) || '9999-12-31')
  })
  .slice(0, 4))
const calendarLabel = computed(() => {
  const date = calendarMonth.value
  return date.getFullYear() + '年' + (date.getMonth() + 1) + '月'
})
const calendarTodos = computed(() => todos.value.filter(task =>
  ['PENDING', 'REVIEW'].includes(task.state) && todoDueKey(task)))
const calendarCells = computed(() => {
  const year = calendarMonth.value.getFullYear()
  const month = calendarMonth.value.getMonth()
  const first = new Date(year, month, 1, 12)
  const start = new Date(year, month, 1 - first.getDay(), 12)
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + index, 12)
    const key = formatDateKey(date)
    return {
      key, day: date.getDate(), year: date.getFullYear(), month: date.getMonth(),
      inMonth: date.getMonth() === month, today: key === formatDateKey(new Date()),
      items: calendarTodos.value.filter(task => todoDueKey(task) === key)
    }
  })
})
const calendarSelectedTodos = computed(() =>
  calendarTodos.value.filter(task => todoDueKey(task) === calendarSelectedDate.value))
const calendarOverdueTodos = computed(() => {
  const today = formatDateKey(new Date())
  return calendarTodos.value.filter(task => todoDueKey(task) && todoDueKey(task) < today)
    .sort((first, second) => todoDueKey(first).localeCompare(todoDueKey(second)))
})
const calendarAgenda = computed(() => {
  const today = new Date()
  today.setHours(12, 0, 0, 0)
  const days = []
  for (let offset = 0; offset < 14; offset += 1) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offset, 12)
    const key = formatDateKey(date)
    const items = calendarTodos.value.filter(task => todoDueKey(task) === key)
    if (items.length) days.push({ key, weekday: weekdayLabel(date), label: displayValue(key), items })
  }
  return days
})
const reminders = computed(() => {
  const list = []
  for (const task of todos.value) {
    if (!['PENDING', 'REVIEW'].includes(task.state)) continue
    const days = dueDays(task)
    if (days == null) continue
    let bucket
    let label
    let tone
    if (days < 0) {
      bucket = 'OVERDUE'
      label = '已逾期 ' + (-days) + ' 天'
      tone = 'danger'
    } else if (days === 0) {
      bucket = 'TODAY'
      label = '今天截止'
      tone = 'danger'
    } else if (days <= 3) {
      bucket = 'SOON'
      label = '还剩 ' + days + ' 天'
      tone = 'warn'
    } else if (days <= 7) {
      bucket = 'WEEK'
      label = '本周内到期'
      tone = 'quiet'
    } else {
      continue
    }
    list.push({ key: reminderKey(task, bucket), task, bucket, label, tone })
  }
  return list.sort((first, second) => {
    const rank = (taskRisk(first.task)?.score ?? 9) - (taskRisk(second.task)?.score ?? 9)
    return rank || (todoDueKey(first.task) || '').localeCompare(todoDueKey(second.task) || '')
  })
})
const activeReminders = computed(() => reminders.value.filter(item => !reminderDismissed.value[item.key]))
const dismissedReminderCount = computed(() => Object.keys(reminderDismissed.value).length)
const fieldStates = { CONFIRMED: '已核验', CANDIDATE: '待人工核验', HOLD: '待复核', NOT_APPLICABLE: '不适用' }

function fieldChoice(field) {
  return field?.selected == null ? null : field.choices[field.selected]
}

function formatDateKey(date) {
  return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-'
    + String(date.getDate()).padStart(2, '0')
}

function todoDueKey(task) {
  return task.dueAt ? String(task.dueAt).slice(0, 10) : ''
}

function dateKeyDays(from, to) {
  if (!from || !to) return null
  const start = new Date(from + 'T12:00:00')
  const end = new Date(to + 'T12:00:00')
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return null
  return Math.round((end - start) / 86400000)
}

function dueDays(task) {
  const key = todoDueKey(task)
  return key ? dateKeyDays(formatDateKey(new Date()), key) : null
}

function matchesTodoWindow(task, window) {
  if (window === 'ALL') return true
  if (!['PENDING', 'REVIEW'].includes(task.state)) return false
  const days = dueDays(task)
  if (days == null) return false
  if (window === 'OVERDUE') return days < 0
  if (window === 'URGENT') return days <= 3
  if (window === 'TODAY') return days === 0
  if (window === 'WEEK') return days >= 0 && days <= 7
  return true
}

function dueMeta(task) {
  if (!task || !['PENDING', 'REVIEW'].includes(task.state)) return null
  const days = dueDays(task)
  if (days == null) return null
  if (days < 0) return { label: '已逾期 ' + (-days) + ' 天', tone: 'danger' }
  if (days === 0) return { label: '今天截止', tone: 'danger' }
  if (days <= 7) return { label: '还剩 ' + days + ' 天', tone: 'warn' }
  return { label: '还剩 ' + days + ' 天', tone: 'quiet' }
}

function taskRisk(task) {
  if (!task) return null
  if (task.state === 'REVIEW') return { label: '先确认变更', tone: 'warn', score: 0 }
  if (task.state !== 'PENDING') return { label: '已结束', tone: 'quiet', score: 9 }
  const days = dueDays(task)
  if (days == null) return { label: '截止待定', tone: 'quiet', score: 5 }
  if (days < 0) return { label: '已逾期', tone: 'danger', score: 0 }
  if (days === 0) return { label: '今天截止', tone: 'danger', score: 1 }
  if (days <= 3) return { label: '3 天内', tone: 'danger', score: 2 }
  if (days <= 7) return { label: '本周内', tone: 'warn', score: 3 }
  return { label: '正常', tone: 'quiet', score: 4 }
}

function nextStep(task) {
  if (task?.state === 'REVIEW') {
    return { key: 'review', label: '确认新版本', detail: '通知已变更，核对后继续办理', kind: 'review', done: false }
  }
  return (task?.steps ?? []).find(step => !step.done) ?? null
}

function nextStepLabel(task) {
  const step = nextStep(task)
  return step ? step.label : '所有步骤已完成'
}

function stepDoneCount(todo) {
  return (todo?.steps ?? []).filter(step => step.done).length
}

function stepDetail(step) {
  if (step.kind === 'deadline') return displayValue(step.detail)
  if (step.kind === 'read') return '打开事项详情中的校方原通知，核对办理要求'
  return step.detail
}

function weekdayLabel(date) {
  return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
}

function reminderKey(task, bucket) {
  return task.id + ':' + todoDueKey(task) + ':' + bucket + ':' + task.state
}

const isoDate = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}:\d{2}))?$/

function displayValue(value) {
  if (!value) return '暂无'
  const range = value.split('/')
  if (range.length === 2 && range.every(part => isoDate.test(part))) return range.map(displayValue).join(' 至 ')
  const date = isoDate.exec(value)
  return date ? date[1] + '年' + Number(date[2]) + '月' + Number(date[3]) + '日' + (date[4] ? ' ' + date[4] : '') : value
}

function extractionModeLabel(mode) {
  return { RULE: '规则引擎', HYBRID: '规则 + LLM', LLM: 'LLM 优先' }[mode] || mode || '未知'
}

function extractionStatusLabel(status) {
  return {
    RULE_ONLY: '仅规则',
    HYBRID_OK: '混合完成',
    LLM_OK: 'LLM 完成',
    RULE_FALLBACK: '已回退规则',
    HYBRID_PARTIAL: '混合部分完成'
  }[status] || status || '未知'
}

function sourceLabel(source) {
  return { RULE: '规则', LLM: 'LLM', HYBRID: '规则+LLM', MANUAL: '人工' }[source] || source || '未标注'
}

function supportLabel(level) {
  return { SUFFICIENT: '证据充分', WEAK: '证据偏弱', INSUFFICIENT: '证据不足', UNKNOWN: '未评估' }[level] || '未评估'
}

function publishedValue(field) {
  if (!field) return '暂无'
  if (field.state === 'NOT_APPLICABLE') return '不适用'
  return field.state === 'CONFIRMED' ? displayValue(fieldChoice(field)?.value) : '待复核'
}

function previousValue(field) {
  const previous = comparison.value?.fields.find(item => item.key === field.key)
  return previous ? publishedValue(previous) : '—'
}

async function work(fn, success = '') {
  if (busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await fn()
    message.value = success
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}

async function loadMatters(term = searchTerm.value) {
  matters.value = await request('/matters?q=' + encodeURIComponent(term))
  if (!matters.value.some(item => item.matter.id === selectedMatterId.value)) {
    selectMatter(matters.value[0]?.matter.id ?? null)
  }
}

async function loadTodos() {
  todos.value = await request('/todos')
}

async function loadStudentHome() {
  const [matterList, todoList, feedbackList] = await Promise.all([
    request('/matters?q=' + encodeURIComponent(searchTerm.value)),
    request('/todos'),
    request('/feedback/mine')
  ])
  matters.value = matterList
  todos.value = todoList
  myFeedback.value = feedbackList
  loadReminderDismissed()
  if (!matters.value.some(item => item.matter.id === selectedMatterId.value)) {
    selectMatter(matters.value[0]?.matter.id ?? null)
  }
}

function reminderStorageKey() {
  return 'lingxun-reminders:' + (session.value?.viewer?.username ?? 'guest')
}

function loadReminderDismissed() {
  try {
    const stored = localStorage.getItem(reminderStorageKey())
    reminderDismissed.value = stored ? JSON.parse(stored) : {}
  } catch {
    reminderDismissed.value = {}
  }
}

function persistReminderDismissed() {
  try {
    localStorage.setItem(reminderStorageKey(), JSON.stringify(reminderDismissed.value))
  } catch {
    // 浏览器禁用本地存储时，提醒仍可在当前页面使用。
  }
}

function dismissReminder(reminder) {
  reminderDismissed.value = { ...reminderDismissed.value, [reminder.key]: true }
  persistReminderDismissed()
}

function restoreReminders() {
  reminderDismissed.value = {}
  persistReminderDismissed()
}

function openReminder(reminder) {
  return openTodoTask(reminder.task)
}

async function openTodoTask(task) {
  todoFilter.value = task.state === 'REVIEW' ? 'REVIEW' : 'PENDING'
  todoWindow.value = 'ALL'
  todoQuery.value = task.title
  await openPage('todos')
}

function selectMatter(id) {
  selectedMatterId.value = id
  feedbackText.value = ''
  showFeedback.value = false
}

function setMatterCategory(category) {
  matterCategory.value = category
  if (!visibleMatters.value.some(item => item.matter.id === selectedMatterId.value)) {
    selectMatter(visibleMatters.value[0]?.matter.id ?? null)
  }
}

async function loadAdmin() {
  adminMatters.value = await request('/maintenance/matters')
}

async function inspectVersion(next) {
  version.value = next
  comparison.value = next?.baseVersionId
    ? history.value.find(item => item.id === next.baseVersionId) ?? await request('/versions/' + next.baseVersionId)
    : null
  changeImpact.value = next?.baseVersionId
    ? await request('/maintenance/versions/' + next.id + '/change-impact')
    : null
}

async function loadHistory(preferredId = null) {
  if (!reviewMatterId.value) {
    history.value = []
    await inspectVersion(null)
    return
  }
  history.value = await request('/maintenance/matters/' + reviewMatterId.value + '/versions')
  await inspectVersion(history.value.find(item => item.id === preferredId) ?? history.value[0] ?? null)
}

async function openPage(next) {
  if (next === 'overview') await loadStudentHome()
  if (next === 'matters') {
    matterCategory.value = 'ALL'
    await loadMatters()
  }
  if (next === 'todos') await loadTodos()
  if (next === 'calendar') {
    await loadTodos()
    focusNearestDeadline()
  }
  if (next === 'reminders') {
    await loadTodos()
    loadReminderDismissed()
  }
  if (next === 'subscriptions') {
    searchTerm.value = ''
    await loadMatters('')
  }
  if (next === 'my-feedback') myFeedback.value = await request('/feedback/mine')
  if (next === 'intake') await loadAdmin()
  if (next === 'versions') {
    await loadAdmin()
    if (!activeMatter.value) reviewMatterId.value = adminMatters.value[0]?.id ?? ''
    await loadHistory()
  }
  if (next === 'feedback') feedback.value = await request('/maintenance/feedback')
  if (next === 'audit') events.value = await request('/maintenance/audit')
  page.value = next
}

async function login(role) {
  const form = role ? { username: role, password: 'lingxun-demo' } : credentials.value
  session.value = await request('/login', 'POST', form)
  useSession(session.value)
  credentials.value = { username: '', password: '' }
  await openPage(isReviewer.value ? 'intake' : 'overview')
}

function loginDemo(role) {
  return work(() => login(role))
}

function loginForm() {
  return work(() => login())
}

function logout() {
  return work(async () => {
    await request('/logout', 'POST')
    session.value = await request('/session')
    useSession(session.value)
  })
}

function subscribe() {
  return work(async () => {
    await request('/matters/' + selectedMatterId.value + '/subscription', 'POST')
    await loadMatters()
  }, '已订阅，已核验的学生办理步骤已加入待办。')
}

function unsubscribeMatter(item) {
  if (!window.confirm('取消订阅会删除该事项下你的待办，确认继续？')) return
  return work(async () => {
    await request('/matters/' + item.matter.id + '/subscription', 'DELETE')
    await Promise.all([loadMatters(), loadTodos()])
  }, '已取消订阅，相关待办已移除。')
}

async function openTodoView(filter = 'ALL', window = 'ALL') {
  todoFilter.value = filter
  todoWindow.value = window
  todoQuery.value = ''
  await openPage('todos')
}

async function openMatter(item) {
  matterCategory.value = 'ALL'
  await openPage('matters')
  selectedMatterId.value = item.matter.id
}

async function openMatterById(id) {
  matterCategory.value = 'ALL'
  await openPage('matters')
  selectedMatterId.value = id
}

function moveCalendar(offset) {
  const date = calendarMonth.value
  calendarMonth.value = new Date(date.getFullYear(), date.getMonth() + offset, 1, 12)
  calendarSelectedDate.value = formatDateKey(calendarMonth.value)
}

function focusNearestDeadline() {
  const today = formatDateKey(new Date())
  const dates = [...new Set(calendarTodos.value.map(todoDueKey))].sort()
  const nearest = dates.find(date => date >= today) ?? dates[0]
  if (!nearest) return
  calendarSelectedDate.value = nearest
  const [year, month] = nearest.split('-')
  calendarMonth.value = new Date(Number(year), Number(month) - 1, 1, 12)
}

function selectCalendarDay(cell) {
  calendarSelectedDate.value = cell.key
  if (!cell.inMonth) calendarMonth.value = new Date(cell.year, cell.month, 1, 12)
}

function goCalendarToday() {
  const today = new Date()
  calendarMonth.value = new Date(today.getFullYear(), today.getMonth(), 1, 12)
  calendarSelectedDate.value = formatDateKey(today)
}

function changeTodo(todo, action) {
  return work(async () => {
    await request('/todos/' + todo.id, 'PATCH', { versionId: todo.targetVersionId, action })
    todos.value = await request('/todos')
  }, action === 'confirm' ? '已确认新版本，待办使用最新截止日期。' : '待办状态已更新。')
}

function toggleStep(todo, step, done) {
  return work(async () => {
    const updated = await request('/todos/' + todo.id + '/steps/' + encodeURIComponent(step.key),
      'PATCH', { done })
    todos.value = todos.value.map(item => item.id === updated.id ? updated : item)
  }, done ? '已勾选：' + step.label : '已取消勾选：' + step.label)
}

function downloadCalendar() {
  return work(async () => {
    const url = URL.createObjectURL(await requestCalendar())
    const file = document.createElement('a')
    file.href = url
    file.download = '岭讯知行-待办.ics'
    file.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }, '已导出当前待办理事项。')
}

function sendFeedback() {
  return work(async () => {
    await request('/matters/' + selectedMatterId.value + '/feedback', 'POST', {
      versionId: selectedMatter.value.version.id,
      message: feedbackText.value
    })
    showFeedback.value = false
    feedbackText.value = ''
  }, '已提交给维护者核查。')
}

function showEvidence(field, choice = fieldChoice(field)) {
  if (!choice?.evidence) return
  return work(async () => {
    const notice = await request('/versions/' + choice.evidence.versionId)
    proof.value = { field, choice, notice }
    await nextTick()
    proofDialog.value.showModal()
  })
}

function sample(kind) {
  const contest = adminMatters.value.find(item => item.title === '化学实验室安全知识竞赛')
  if (!contest) return
  importWarnings.value = []
  draft.value = {
    ...emptyDraft(),
    matterId: contest.id,
    noticeDate: '2026-11-01',
    sourceUrl: 'https://www.sgu.edu.cn/info/1065/126441.htm',
    simulated: true,
    snapshot: kind === 'conflict'
      ? '报名截止调整为2026年11月5日。\n报名截止调整为2026年11月6日。'
      : '报名截止调整为2026年11月4日。'
  }
}

function importFromUrl() {
  return work(async () => {
    if (!draft.value.sourceUrl) throw new Error('请先填写学校通知链接')
    const imported = await request('/maintenance/import/url', 'POST', { url: draft.value.sourceUrl })
    draft.value.snapshot = imported.text
    if (!draft.value.title && imported.title) draft.value.title = imported.title.slice(0, 180)
    importWarnings.value = imported.warnings ?? []
  }, '已抓取网页正文。请核对文字是否完整，再归档生成候选。')
}

function importFromFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  return work(async () => {
    const imported = await requestForm('/maintenance/import/file', file)
    draft.value.snapshot = imported.text
    if (!draft.value.title && imported.title) draft.value.title = imported.title.slice(0, 180)
    importWarnings.value = imported.warnings ?? []
  }, '已解析本地通知文件。请核对正文和字段后再归档。')
}

function createDraft() {
  return work(async () => {
    const recorded = await request('/maintenance/drafts', 'POST', {
      ...draft.value,
      matterId: draft.value.matterId || null
    })
    draft.value = emptyDraft()
    importWarnings.value = []
    reviewMatterId.value = recorded.matterId
    await loadAdmin()
    await loadHistory(recorded.id)
    page.value = 'versions'
  }, '正文已归档。请核对每个候选字段的角色、阶段和值。')
}

function reviewField(field, decision, option) {
  return work(async () => {
    rememberDraft(await request('/maintenance/versions/' + version.value.id + '/fields', 'PUT', {
      key: field.key, decision, option
    }))
  })
}

function rememberDraft(updated) {
  version.value = updated
  history.value = history.value.map(item => item.id === updated.id ? updated : item)
}

function editField(field = null) {
  return work(async () => {
    const options = field ? [field.slot] : (await request('/maintenance/versions/' + version.value.id + '/field-options'))
      .filter(slot => !version.value.fields.some(item =>
        item.slot.role === slot.role && item.slot.stage === slot.stage && item.slot.type === slot.type))
    if (!options.length) throw new Error('所有支持的字段都已归档，可在字段旁修正。')
    const choice = fieldChoice(field) ?? field?.choices[0]
    const value = choice?.value ?? ''
    const dateField = ['截止', '时间'].includes(field?.slot.type)
    const [begin = '', end = ''] = dateField ? value.split('/') : []
    const [date, time = ''] = begin.split('T')
    const [endDate, endTime = ''] = end.split('T')
    correction.value = {
      editing: field !== null, options, slot: field?.slot ?? '', value: dateField ? '' : value,
      date, time, endDate, endTime, reason: '',
      fragmentNo: choice?.evidence.versionId === version.value.id ? choice.evidence.fragmentNo : ''
    }
    await nextTick()
    correctionDialog.value.showModal()
  })
}

function saveCorrection() {
  return work(async () => {
    const edited = correction.value
    let value = edited.value
    if (correctionIsDate.value) {
      value = edited.date + (edited.time ? 'T' + edited.time : '')
      if (edited.endDate) value += '/' + edited.endDate + (edited.endTime ? 'T' + edited.endTime : '')
    }
    const { selectionStart, selectionEnd } = quoteInput.value
    const selected = selectionStart !== selectionEnd
    rememberDraft(await request('/maintenance/versions/' + version.value.id + '/fields/correction', 'PUT', {
      slot: edited.slot, value, reason: edited.reason, fragmentNo: edited.fragmentNo,
      start: selected ? selectionStart : 0, end: selected ? selectionEnd : correctionQuote.value.length
    }))
    correctionDialog.value.close()
  }, '已保存字段和原文依据，可在审核记录查看修正说明。')
}

function publishVersion() {
  return work(async () => {
    const posted = await request('/maintenance/versions/' + version.value.id + '/publish', 'POST')
    await loadAdmin()
    await loadHistory(posted.id)
  }, '版本已发布，相关待办已重新核对。')
}

function withdrawVersion() {
  if (!window.confirm('撤回后，其关联待办将转为待复核。确认撤回当前版本？')) return
  return work(async () => {
    await request('/maintenance/matters/' + reviewMatterId.value + '/withdraw', 'POST', { versionId: version.value.id })
    await loadAdmin()
    await loadHistory(version.value.id)
  }, '已撤回当前版本。')
}

function startCorrection(item) {
  return work(async () => {
    await openPage('intake')
    draft.value = { ...emptyDraft(), matterId: item.matterId }
  })
}

function handleFeedback(item) {
  return work(async () => {
    await request('/maintenance/feedback/' + item.id + '/handle', 'POST')
    feedback.value = await request('/maintenance/feedback')
  }, '已记录核查操作。')
}

onMounted(() => work(async () => {
  session.value = await request('/session')
  useSession(session.value)
  if (session.value.viewer) await openPage(isReviewer.value ? 'intake' : 'overview')
}))
</script>

<template>
  <main v-if="!session?.viewer" class="welcome">
    <section class="welcome-card">
      <div class="brand welcome-brand"><span class="brand-mark">岭</span><span>岭讯知行</span></div>
      <h1>核对通知原文，<br>按自己的截止时间办事。</h1>
      <p>校园办事通知核验与任务清单系统</p>
      <p v-if="error" class="status error" role="alert">{{ error }}</p>
      <div v-if="session?.demo" class="welcome-actions">
        <button class="primary" :disabled="busy" @click="loginDemo('student')">进入学生端</button>
        <button class="outline" :disabled="busy" @click="loginDemo('reviewer')">进入维护端</button>
        <small>本地演示 · 校方通知去标识摘录</small>
      </div>
      <form v-else class="form-stack" @submit.prevent="loginForm">
        <label>账号<input v-model="credentials.username" autocomplete="username" required></label>
        <label>密码<input v-model="credentials.password" type="password" autocomplete="current-password" required></label>
        <button class="primary" :disabled="busy || !session">登录</button>
      </form>
    </section>
  </main>

  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">岭</span><span>岭讯知行</span></div>
      <nav aria-label="主导航">
        <button v-for="item in navigation" :key="item.key" :aria-current="item.key === page ? 'page' : undefined"
          :disabled="busy" @click="work(() => openPage(item.key))">
          <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}
          <span v-if="item.key === 'reminders' && activeReminders.length" class="nav-count">{{ activeReminders.length }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <span>{{ isReviewer ? '维护端' : '学生端' }}{{ session.demo ? ' · 本地演示' : '' }}</span>
        <button v-if="session.demo" class="sidebar-action" :disabled="busy" @click="loginDemo(isReviewer ? 'student' : 'reviewer')">
          {{ isReviewer ? '切换到学生端' : '切换到维护端' }}
        </button>
        <button v-else class="sidebar-action" :disabled="busy" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="main">
      <header class="page-header">
        <h1>{{ pageTitle }}</h1>
        <form v-if="page === 'matters'" class="search" @submit.prevent="work(loadMatters)">
          <input v-model="searchTerm" type="search" maxlength="100" aria-label="搜索事项" placeholder="搜事项名称或通知原文">
          <button class="outline" :disabled="busy">搜索</button>
        </form>
      </header>
      <p v-if="error" class="status error" role="alert">{{ error }}</p>
      <p v-if="message" class="status" role="status">{{ message }}</p>

      <div v-if="page === 'overview'" class="stack">
        <section class="panel student-hero">
          <div>
            <span class="label">学生端 · {{ displayValue(formatDateKey(new Date())) }}</span>
            <h2>把通知变成今天能做的事。</h2>
            <p class="quiet">先看「今天先做」，再按办理清单逐项勾选；通知变更先确认，其余事项按分类和截止时间慢慢筛。</p>
          </div>
          <div class="hero-actions">
            <button class="primary" :disabled="busy" @click="work(() => openTodoView('PENDING'))">
              去办理 <span v-if="studentStats.pending">· {{ studentStats.pending }}</span>
            </button>
            <button class="outline" :disabled="busy" @click="work(() => openPage('matters'))">浏览全部事项</button>
          </div>
        </section>

        <div class="stat-grid" aria-label="待办概览">
          <button class="stat-card" :disabled="busy" @click="work(() => openTodoView('PENDING'))">
            <span class="label">待办理</span><strong>{{ studentStats.pending }}</strong>
            <small>已确认、还能继续办理</small>
          </button>
          <button class="stat-card warn" :disabled="busy" @click="work(() => openTodoView('PENDING', 'WEEK'))">
            <span class="label">7 天内到期</span><strong>{{ studentStats.soon }}</strong>
            <small>含今天截止 · 紧急 {{ studentStats.urgent }} 项</small>
          </button>
          <button class="stat-card danger" :disabled="busy" @click="work(() => openTodoView('PENDING', 'OVERDUE'))">
            <span class="label">已逾期</span><strong>{{ studentStats.overdue }}</strong>
            <small>建议先处理或联系维护者</small>
          </button>
          <button class="stat-card" :disabled="busy" @click="work(() => openTodoView('REVIEW'))">
            <span class="label">待复核</span><strong>{{ studentStats.review }}</strong>
            <small>通知已变更，等你确认</small>
          </button>
        </div>

        <section v-if="todayFocus.length" class="panel focus-panel">
          <div class="panel-head">
            <div>
              <h3>今天先做</h3>
              <p class="quiet">按“待复核 → 已逾期 → 今天截止 → 3 天内”的顺序挑出最优先事项。</p>
            </div>
            <button class="text-button" :disabled="busy" @click="work(() => openTodoView('PENDING', 'URGENT'))">看紧急待办</button>
          </div>
          <div class="focus-list">
            <button v-for="task in todayFocus" :key="task.id" class="focus-row" :disabled="busy"
              @click="work(() => openTodoTask(task))">
              <span class="focus-copy">
                <strong>{{ task.title }}</strong>
                <small>{{ task.stage }} · 下一步：{{ nextStepLabel(task) }}</small>
              </span>
              <span class="due-badge" :class="taskRisk(task)?.tone">{{ taskRisk(task)?.label }}</span>
            </button>
          </div>
        </section>

        <div class="dashboard-grid">
          <section class="panel">
            <div class="panel-head">
              <h3>最近截止</h3>
              <button class="text-button" :disabled="busy" @click="work(() => openPage('calendar'))">看日历</button>
            </div>
            <div v-if="studentStats.next" class="next-deadline">
              <div>
                <strong>{{ studentStats.next.title }}</strong>
                <p class="quiet">{{ studentStats.next.stage }} · {{ taskStates[studentStats.next.state] }}</p>
              </div>
              <div class="next-date">
                <strong>{{ displayValue(todoDueKey(studentStats.next)) }}</strong>
                <span v-if="dueMeta(studentStats.next)" class="due-badge" :class="dueMeta(studentStats.next).tone">
                  {{ dueMeta(studentStats.next).label }}
                </span>
              </div>
            </div>
            <p v-else class="quiet">目前没有已确认的待办理截止日期。</p>
          </section>

          <section class="panel">
            <div class="panel-head">
              <h3>需要你确认</h3>
              <button class="text-button" :disabled="busy" @click="work(() => openTodoView('REVIEW'))">全部</button>
            </div>
            <div v-if="studentReviewTodos.length" class="mini-list">
              <button v-for="task in studentReviewTodos" :key="task.id" class="mini-row" :disabled="busy"
                @click="work(() => openTodoView('REVIEW'))">
                <span><strong>{{ task.stage }}</strong><small>{{ task.title }}</small></span>
                <span class="pill warn">待确认</span>
              </button>
            </div>
            <p v-else class="quiet">没有待复核的变更。</p>
          </section>
        </div>

        <section class="panel">
          <div class="panel-head">
            <h3>已订阅事项</h3>
            <button class="text-button" :disabled="busy" @click="work(() => openPage('subscriptions'))">管理订阅</button>
          </div>
          <div v-if="studentSubscribed.length" class="mini-list">
            <button v-for="item in studentSubscribed.slice(0, 4)" :key="item.matter.id" class="mini-row"
              :disabled="busy" @click="work(() => openMatter(item))">
              <span><strong>{{ item.matter.title }}</strong><small>{{ item.matter.category }} · V{{ item.version.number }}</small></span>
              <span class="quiet">查看</span>
            </button>
          </div>
          <p v-else class="quiet">还没有订阅事项。订阅后，已核验的学生办理步骤会自动进入待办。</p>
        </section>
      </div>

      <div v-else-if="page === 'matters'" class="browse">
        <div class="matter-list" aria-label="事项列表">
          <div class="category-filter" role="group" aria-label="按类型筛选事项">
            <button v-for="category in matterCategories" :key="category" class="outline"
              :aria-pressed="category === matterCategory" @click="setMatterCategory(category)">
              {{ category === 'ALL' ? '全部' : category }}
            </button>
          </div>
          <button v-for="item in visibleMatters" :key="item.matter.id" class="matter-card"
            :aria-pressed="item.matter.id === selectedMatterId" @click="selectMatter(item.matter.id)">
            <span class="label">{{ item.matter.category }}</span>
            <strong>{{ item.matter.title }}</strong>
            <small>V{{ item.version.number }}{{ item.version.simulated ? ' · 模拟变更' : '' }}{{ item.subscribed ? ' · 已订阅' : '' }}</small>
          </button>
          <p v-if="!visibleMatters.length" class="panel quiet">这个类型下没有匹配的已发布事项。</p>
        </div>
        <section v-if="selectedMatter" class="panel detail">
          <div class="panel-head">
            <div><h2>{{ selectedMatter.matter.title }}</h2>
              <p class="quiet">通知 V{{ selectedMatter.version.number }} · {{ selectedMatter.version.simulated ? '模拟版本' : displayValue(selectedMatter.version.noticeDate) }}</p>
            </div>
            <button class="primary" :disabled="busy"
              @click="selectedMatter.subscribed ? work(() => openPage('todos')) : subscribe()">
              {{ selectedMatter.subscribed ? '查看待办' : '订阅事项' }}
            </button>
            <button v-if="selectedMatter.subscribed" class="outline" :disabled="busy"
              @click="unsubscribeMatter(selectedMatter)">取消订阅</button>
          </div>
          <div v-if="deadline" class="deadline" :class="{ held: deadline.state !== 'CONFIRMED' }">
            <div><span class="label">学生办理截止</span><strong>{{ publishedValue(deadline) }}</strong></div>
            <button v-if="deadline.state === 'CONFIRMED'" class="outline" :disabled="busy" @click="showEvidence(deadline)">查看日期依据</button>
            <span v-else class="quiet">维护者正在核对来源。</span>
          </div>

          <section v-if="stages.length" class="section">
            <h3>办理阶段</h3>
            <div v-for="field in stages" :key="field.key" class="info-row">
              <div><strong>{{ field.slot.stage }}</strong><small>{{ field.slot.role }} · {{ field.slot.type }}</small></div>
              <span>{{ publishedValue(field) }}</span>
              <button v-if="field.state === 'CONFIRMED'" class="text-button" :disabled="busy" @click="showEvidence(field)">依据</button>
            </div>
          </section>

          <section v-if="conditions.length" class="section">
            <h3>办理条件</h3>
            <div v-for="field in conditions" :key="field.key" class="info-row">
              <strong>{{ field.slot.type }}</strong><span>{{ publishedValue(field) }}</span>
              <button v-if="field.state === 'CONFIRMED'" class="text-button" :disabled="busy" @click="showEvidence(field)">依据</button>
            </div>
          </section>

          <div class="footer-actions">
            <a :href="selectedMatter.version.sourceUrl" target="_blank" rel="noopener noreferrer">查看校方原通知</a>
            <button class="text-button" @click="showFeedback = !showFeedback">反馈纠错</button>
          </div>
          <form v-if="showFeedback" class="form-stack" @submit.prevent="sendFeedback">
            <label>哪项信息需要核查？
              <textarea v-model="feedbackText" rows="3" maxlength="600" required placeholder="例如，报名截止字段与最新通知不一致。"></textarea>
            </label>
            <button class="primary" :disabled="busy">提交反馈</button>
          </form>
        </section>
      </div>

      <div v-else-if="page === 'todos'" class="stack">
        <div v-if="todos.length" class="panel todo-tools">
          <div class="panel-head">
            <div class="todo-filters" role="group" aria-label="按状态筛选待办">
              <button v-for="filter in todoFilters" :key="filter" class="outline" :aria-pressed="filter === todoFilter"
                @click="todoFilter = filter">
                {{ filter === 'ALL' ? '全部' : taskStates[filter] }} <span>{{ todoCounts[filter] }}</span>
              </button>
            </div>
            <button class="outline" :disabled="busy || todoCounts.PENDING === 0" @click="downloadCalendar">导出待办理日历</button>
          </div>
          <div class="todo-toolbar">
            <div class="todo-filters" role="group" aria-label="按时间筛选待办">
              <button v-for="window in todoWindows" :key="window" class="outline"
                :aria-pressed="window === todoWindow" @click="todoWindow = window">
                {{ todoWindowLabels[window] }}
              </button>
            </div>
            <label class="inline-field">排序
              <select v-model="todoSort">
                <option value="DUE">按截止日</option>
                <option value="NEWEST">按最近变更</option>
              </select>
            </label>
            <input v-model="todoQuery" class="todo-search" type="search" maxlength="80"
              aria-label="搜索我的待办" placeholder="搜索事项或阶段">
          </div>
          <p class="quiet">日历文件记录导出时有效的待办理日期。改期后请重新导出并更新日历记录。</p>
        </div>
        <div v-if="!todos.length" class="panel empty">
          <p>订阅事项后，已核验的学生办理步骤会出现在这里。</p>
          <button class="outline" :disabled="busy" @click="work(() => openPage('matters'))">去看事项</button>
        </div>
        <p v-else-if="!visibleTodos.length" class="panel quiet">当前筛选下没有待办，换个状态或时间范围试试。</p>
        <article v-for="todo in visibleTodos" :key="todo.id" class="panel">
          <div class="panel-head">
            <div><h2>{{ todo.stage }}</h2><p class="quiet">{{ todo.title }}{{ todo.simulated ? ' · 模拟变更' : '' }}</p></div>
            <div class="todo-badges">
              <span v-if="dueMeta(todo)" class="due-badge" :class="dueMeta(todo).tone">{{ dueMeta(todo).label }}</span>
              <span class="pill" :class="{ warn: todo.state === 'REVIEW' }">{{ taskStates[todo.state] }}</span>
            </div>
          </div>
          <div v-if="todo.state === 'REVIEW'" class="section">
            <p class="quiet">{{ todo.current?.state === 'CONFIRMED' ? '旧日期已停用。核对新旧依据后确认最新办理要求。' : '旧日期已停用。维护者确认有效来源后才能继续办理。' }}</p>
            <div class="comparison">
              <div><span class="label">原待办依据 · V{{ todo.previousVersion }}</span>
                <strong>{{ publishedValue(todo.previous) }}</strong>
                <button class="text-button" :disabled="busy" @click="showEvidence(todo.previous)">查看旧依据</button>
              </div>
              <div><span class="label">当前通知{{ todo.currentVersion ? ' · V' + todo.currentVersion : '' }}</span>
                <strong>{{ todo.current ? publishedValue(todo.current) : '暂无生效版本' }}</strong>
                <button v-if="todo.current?.state === 'CONFIRMED'" class="text-button" :disabled="busy" @click="showEvidence(todo.current)">查看新依据</button>
              </div>
            </div>
            <button v-if="todo.current?.state === 'CONFIRMED'" class="primary" :disabled="busy"
              @click="changeTodo(todo, 'confirm')">确认新版本</button>
          </div>
          <div v-else-if="todo.state === 'IGNORED'" class="section">
            <p class="quiet">该字段已标为不适用，无需办理。</p>
          </div>
          <div v-else class="todo-date">
            <span><strong>{{ publishedValue(todo.current) }}</strong> · V{{ todo.currentVersion }}</span>
            <button class="outline" :disabled="busy" @click="changeTodo(todo, todo.state === 'DONE' ? 'reopen' : 'done')">
              {{ todo.state === 'DONE' ? '恢复待办理' : '标记完成' }}
            </button>
          </div>
          <div v-if="['PENDING', 'REVIEW'].includes(todo.state)" class="todo-checklist"
            :class="{ locked: todo.state === 'REVIEW' }">
            <div class="checklist-head">
              <strong>办理清单</strong>
              <span>{{ stepDoneCount(todo) }}/{{ (todo.steps || []).length }}</span>
            </div>
            <label v-for="step in (todo.steps || [])" :key="step.key" class="check-row" :class="{ done: step.done }">
              <input type="checkbox" :checked="step.done" :disabled="busy || todo.state === 'REVIEW'"
                @change="toggleStep(todo, step, $event.target.checked)">
              <span><strong>{{ step.label }}</strong><small>{{ stepDetail(step) }}</small></span>
            </label>
            <p v-if="todo.state === 'REVIEW'" class="quiet">通知已变更，确认新版本后才能继续勾选。</p>
            <p v-else-if="!(todo.steps || []).length" class="quiet">维护者核验后，这里会生成可勾选的办理步骤。</p>
          </div>
        </article>
      </div>

      <div v-else-if="page === 'calendar'" class="stack">
        <section class="panel calendar-panel">
          <div class="panel-head">
            <div>
              <h2>{{ calendarLabel }}</h2>
              <p class="quiet">只显示待办理和待复核的截止日期；点击日期查看当天事项。</p>
            </div>
            <div class="calendar-nav">
              <button class="outline" :disabled="busy" @click="moveCalendar(-1)">上月</button>
              <button class="outline" :disabled="busy" @click="goCalendarToday">今天</button>
              <button class="outline" :disabled="busy" @click="moveCalendar(1)">下月</button>
            </div>
          </div>
          <div class="calendar-weekdays" aria-hidden="true">
            <span>日</span><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span>
          </div>
          <div class="calendar-grid" role="grid" aria-label="截止日历">
            <button v-for="cell in calendarCells" :key="cell.key" class="calendar-day"
              :class="{ muted: !cell.inMonth, today: cell.today, selected: cell.key === calendarSelectedDate }"
              :aria-label="displayValue(cell.key) + '，' + cell.items.length + ' 项截止'"
              :disabled="busy" @click="selectCalendarDay(cell)">
              <span>{{ cell.day }}</span>
              <i v-if="cell.items.length" class="calendar-dot" aria-hidden="true"></i>
              <small v-if="cell.items.length">{{ cell.items.length }}</small>
            </button>
          </div>
          <p v-if="!calendarTodos.length" class="quiet">本月还没有已确认的截止日期。</p>
        </section>
        <section class="panel">
          <h3>{{ displayValue(calendarSelectedDate) }} 截止</h3>
          <div v-if="calendarSelectedTodos.length" class="mini-list">
            <button v-for="task in calendarSelectedTodos" :key="task.id" class="mini-row"
              :disabled="busy" @click="work(() => openTodoView(task.state === 'REVIEW' ? 'REVIEW' : 'PENDING'))">
              <span><strong>{{ task.stage }}</strong><small>{{ task.title }}</small></span>
              <span class="due-badge" :class="dueMeta(task)?.tone">{{ dueMeta(task)?.label }}</span>
            </button>
          </div>
          <p v-else class="quiet">这一天没有待办理或待复核的截止事项。</p>
        </section>
        <section v-if="calendarOverdueTodos.length" class="panel overdue-panel">
          <div class="panel-head">
            <div>
              <h3>已逾期</h3>
              <p class="quiet">这些事项的截止日期已经过去，建议先联系维护者确认是否还能办理。</p>
            </div>
            <button class="text-button" :disabled="busy" @click="work(() => openTodoView('PENDING', 'OVERDUE'))">看逾期待办</button>
          </div>
          <div class="mini-list">
            <button v-for="task in calendarOverdueTodos" :key="task.id" class="mini-row" :disabled="busy"
              @click="work(() => openTodoTask(task))">
              <span><strong>{{ task.title }}</strong><small>{{ task.stage }} · {{ displayValue(todoDueKey(task)) }}</small></span>
              <span class="due-badge danger">{{ taskRisk(task)?.label }}</span>
            </button>
          </div>
        </section>
        <section v-if="calendarAgenda.length" class="panel">
          <div class="panel-head">
            <div>
              <h3>未来 14 天</h3>
              <p class="quiet">按日期展开的办理清单，适合快速看这一周和下一周要做什么。</p>
            </div>
            <button class="text-button" :disabled="busy" @click="work(() => openTodoView('PENDING', 'URGENT'))">只看紧急</button>
          </div>
          <div class="agenda-list">
            <div v-for="day in calendarAgenda" :key="day.key" class="agenda-day">
              <div class="agenda-date">
                <strong>{{ day.weekday }}</strong>
                <small>{{ day.label }}</small>
              </div>
              <div class="agenda-items">
                <button v-for="task in day.items" :key="task.id" class="agenda-row" :disabled="busy"
                  @click="work(() => openTodoTask(task))">
                  <span><strong>{{ task.title }}</strong><small>{{ task.stage }}</small></span>
                  <span class="due-badge" :class="taskRisk(task)?.tone">{{ taskRisk(task)?.label }}</span>
                </button>
              </div>
            </div>
          </div>
        </section>
        <p v-else class="panel quiet">未来 14 天没有待办理或待复核的截止事项。</p>
      </div>

      <div v-else-if="page === 'reminders'" class="stack">
        <section class="panel reminder-hero">
          <div>
            <span class="label">站内提醒</span>
            <h2>别让截止日期只躺在日历里。</h2>
            <p class="quiet">系统按截止日自动生成提醒：已逾期、今天截止、3 天内和本周内。忽略只影响当前账号的本机显示。</p>
          </div>
          <div class="reminder-summary">
            <strong>{{ activeReminders.length }}</strong>
            <small>条待处理</small>
          </div>
        </section>
        <article v-for="item in activeReminders" :key="item.key" class="panel reminder-card">
          <div class="panel-head">
            <div>
              <span class="label">截止提醒</span>
              <h3>{{ item.task.title }}</h3>
              <p class="quiet">{{ item.task.stage }} · {{ displayValue(todoDueKey(item.task)) }}</p>
            </div>
            <span class="due-badge" :class="item.tone">{{ item.label }}</span>
          </div>
          <div class="reminder-next">
            <span>下一步：{{ nextStepLabel(item.task) }}</span>
            <div class="footer-actions">
              <button class="primary" :disabled="busy" @click="work(() => openReminder(item))">去处理</button>
              <button class="text-button" @click="dismissReminder(item)">本次忽略</button>
            </div>
          </div>
        </article>
        <div v-if="!activeReminders.length" class="panel empty">
          <p>{{ dismissedReminderCount ? '当前提醒都已忽略。' : '还没有临近截止的提醒，先安心处理手头的事。' }}</p>
          <button v-if="dismissedReminderCount" class="outline" @click="restoreReminders">恢复已忽略提醒</button>
          <button v-else class="outline" :disabled="busy" @click="work(() => openPage('todos'))">去看全部待办</button>
        </div>
      </div>

      <div v-else-if="page === 'subscriptions'" class="stack">
        <section class="panel">
          <div class="panel-head">
            <div>
              <h2>我的订阅</h2>
              <p class="quiet">订阅只影响你自己的待办，不会改变学校的通知。</p>
            </div>
            <button class="outline" :disabled="busy" @click="work(() => openPage('matters'))">添加订阅</button>
          </div>
        </section>
        <div v-if="studentSubscribed.length" class="stack">
          <article v-for="item in studentSubscribed" :key="item.matter.id" class="panel">
            <div class="panel-head">
              <div>
                <h3>{{ item.matter.title }}</h3>
                <p class="quiet">{{ item.matter.category }} · V{{ item.version?.number }} · {{ item.version?.simulated ? '模拟版本' : '当前生效版本' }}</p>
              </div>
              <span class="pill">已订阅</span>
            </div>
            <div class="footer-actions">
              <button class="primary" :disabled="busy" @click="work(() => openMatter(item))">查看事项</button>
              <button class="outline" :disabled="busy" @click="unsubscribeMatter(item)">取消订阅</button>
            </div>
          </article>
        </div>
        <div v-else class="panel empty">
          <p>还没有订阅事项。订阅后，已核验的学生办理步骤会自动进入待办。</p>
          <button class="outline" :disabled="busy" @click="work(() => openPage('matters'))">去浏览事项</button>
        </div>
      </div>

      <div v-else-if="page === 'my-feedback'" class="stack">
        <section class="panel">
          <div class="panel-head">
            <div>
              <h2>我的反馈</h2>
              <p class="quiet">反馈由维护者核查；已核查表示维护者已经处理过，不代表通知一定修改。</p>
            </div>
            <button class="outline" :disabled="busy" @click="work(() => openPage('matters'))">去反馈</button>
          </div>
        </section>
        <article v-for="item in myFeedback" :key="item.id" class="panel">
          <div class="panel-head">
            <div>
              <h3>{{ item.title }}</h3>
              <p class="quiet">{{ item.createdAt.replace('T', ' ').slice(0, 16) }}</p>
            </div>
            <span class="pill" :class="{ warn: !item.handled }">{{ item.handled ? '已核查' : '待核查' }}</span>
          </div>
          <p class="message-body">{{ item.message }}</p>
          <div class="footer-actions">
            <button class="text-button" :disabled="busy" @click="work(() => openMatterById(item.matterId))">查看对应事项</button>
          </div>
        </article>
        <p v-if="!myFeedback.length" class="panel quiet">还没有提交过反馈。发现通知和原文不一致时，可以在事项详情里提交。</p>
      </div>

      <section v-else-if="page === 'intake'" class="panel form-panel">
        <div class="panel-head">
          <h2>归档通知与生成候选</h2>
          <div v-if="session.demo" class="sample-actions">
            <button class="outline" :disabled="busy" @click="sample('change')">载入改期样例</button>
            <button class="outline" :disabled="busy" @click="sample('conflict')">载入冲突样例</button>
          </div>
        </div>
        <form class="form-stack" @submit.prevent="createDraft">
          <label>关联事项
            <select v-model="draft.matterId">
              <option value="">新建事项</option>
              <option v-for="matter in adminMatters" :key="matter.id" :value="matter.id">{{ matter.title }}</option>
            </select>
          </label>
          <div v-if="!draft.matterId" class="form-row">
            <label>事项名称<input v-model="draft.title" maxlength="180" required></label>
            <label>事项类型<select v-model="draft.category"><option>竞赛报名</option><option>学分认定</option></select></label>
          </div>
          <div class="form-row">
            <label>学校通知链接<input v-model="draft.sourceUrl" type="url" placeholder="https://www.sgu.edu.cn/…" maxlength="500" required></label>
            <label>通知日期<input v-model="draft.noticeDate" type="date" required></label>
          </div>
          <div class="footer-actions">
            <button type="button" class="outline" :disabled="busy" @click="importFromUrl">从链接抓取网页正文</button>
            <label class="text-button">上传通知文件（txt/md/html/docx/pdf）
              <input type="file" accept=".txt,.md,.html,.htm,.docx,.pdf" :disabled="busy" @change="importFromFile" hidden>
            </label>
            <span class="quiet">只抓取公开静态页面，拒绝内网链接；抓取后仍需人工核对。</span>
          </div>
          <p v-for="warning in importWarnings" :key="warning" class="quiet">{{ warning }}</p>
          <label>字段抽取方式
            <select v-model="draft.extractionMode">
              <option value="RULE">规则引擎（本地离线，结果来源标记为规则）</option>
              <option value="HYBRID">混合抽取（规则 + LLM，冲突转待复核）</option>
              <option value="LLM">LLM 优先（不可用时自动回退规则并标注）</option>
            </select>
          </label>
          <p class="quiet">LLM 由服务端环境变量配置；模型失败、超时或无 Key 时只退回规则模式，不会伪造 LLM 结果。</p>
          <label>正文或附件的去标识摘录
            <textarea v-model="draft.snapshot" rows="8" maxlength="40000" required
              placeholder="按段落粘贴，保留角色和阶段标题。可在单独一行填写“适用对象：…”“材料要求：…”“办理入口：…”。"></textarea>
          </label>
          <label v-if="session.demo" class="check"><input v-model="draft.simulated" type="checkbox">标为模拟补充通知</label>
          <button class="primary" :disabled="busy">归档并生成候选</button>
        </form>
      </section>

      <div v-else-if="page === 'versions'" class="browse">
        <div class="matter-list">
          <label class="label">事项<select v-model="reviewMatterId" :disabled="busy" @change="work(() => loadHistory())">
            <option v-for="matter in adminMatters" :key="matter.id" :value="matter.id">{{ matter.title }}</option>
          </select></label>
          <button v-for="item in history" :key="item.id" class="matter-card" :aria-pressed="item.id === version?.id"
            :disabled="busy" @click="work(() => inspectVersion(item))">
            <strong>V{{ item.number }}</strong>
            <span class="quiet">{{ item.state === 'DRAFT' ? '草稿' : item.state === 'WITHDRAWN' ? '已撤回' : item.id === activeMatter?.currentVersionId ? '当前生效' : '历史版本' }}</span>
            <small v-if="item.simulated">模拟补充通知</small>
          </button>
        </div>
        <section v-if="version" class="panel detail">
          <div class="panel-head">
            <div>
              <h2>V{{ version.number }} {{ version.state === 'DRAFT' ? '字段核验' : '版本详情' }}</h2>
              <p class="quiet">{{ version.simulated ? '模拟内容' : '学校来源' }} · {{ displayValue(version.noticeDate) }}<span v-if="comparison"> · 对照 V{{ comparison.number }}</span></p>
              <p v-if="version.extraction" class="quiet">抽取链路：{{ extractionModeLabel(version.extraction.mode) }} · {{ extractionStatusLabel(version.extraction.status) }}<span v-if="version.extraction.model"> · {{ version.extraction.model }}</span></p>
              <p v-if="version.extraction?.warnings?.length" class="quiet">抽取警告：{{ version.extraction.warnings.join('；') }}</p>
            </div>
            <button v-if="version.state === 'DRAFT'" class="primary" :disabled="busy || awaiting > 0 || !version.fields.length" @click="publishVersion">发布版本</button>
            <button v-else-if="activeMatter?.currentVersionId === version.id" class="outline" :disabled="busy" @click="withdrawVersion">撤回本版</button>
          </div>
          <details class="archive">
            <summary>查看已归档正文</summary>
            <pre>{{ version.snapshot }}</pre>
            <p class="hash">SHA-256 {{ version.hash }}</p>
            <a :href="version.sourceUrl" target="_blank" rel="noopener noreferrer">校方原通知</a>
          </details>
          <NoticeDiff v-if="comparison" :key="version.id" :previous="comparison" :current="version" />
          <details v-if="changeImpact?.fields.length" class="archive">
            <summary>字段级变更影响：{{ changeImpact.affectedFields }} 项，涉及角色 {{ changeImpact.affectedRoles.join('、') }}<span v-if="changeImpact.affectedTasks"> · {{ changeImpact.affectedTasks }} 项学生截止待办需复核</span></summary>
            <div class="stack">
              <p v-for="item in changeImpact.fields" :key="item.fieldKey" class="quiet">
                <strong>{{ item.fieldKey }}</strong> · {{ item.treatment }}：
                {{ item.previousValue || '上一版未确认' }} → {{ item.currentValue || '本版待复核' }}
                <span v-if="item.taskAffected"> · 影响学生待办</span>
              </p>
            </div>
          </details>
          <div v-if="version.state === 'DRAFT'" class="panel-head review-count">
            <p class="quiet">{{ version.fields.length ? '剩余 ' + awaiting + ' 项待人工核验。' : '未识别到字段，可从原文补录。' }}</p>
            <button class="outline" :disabled="busy" @click="editField()">补录字段</button>
          </div>
          <article v-for="field in version.fields" :key="field.key" class="review-field">
            <div class="panel-head">
              <div><h3>{{ field.slot.type }}</h3><small>{{ field.slot.role }} · {{ field.slot.stage }}</small></div>
              <span class="pill" :class="{ warn: field.change === '冲突' || field.state === 'HOLD' }">{{ field.change }} · {{ fieldStates[field.state] }}</span>
            </div>
            <p v-if="comparison" class="quiet">原版本 {{ previousValue(field) }}</p>
            <div v-for="(choice, index) in field.choices" :key="index" class="choice">
              <span>
                <strong>{{ choice.value == null ? '日期待核定' : displayValue(choice.value) }}</strong>
                <small v-if="choice.tentative">原文标注暂定或待定</small>
                <small v-if="choice.reviewReason">{{ choice.reviewReason }}</small>
                <small v-if="choice.extractionSource || choice.supportLevel" class="quiet">来源：{{ sourceLabel(choice.extractionSource) }} · {{ supportLabel(choice.supportLevel) }}<template v-if="choice.supportReason"> · {{ choice.supportReason }}</template></small>
              </span>
              <button class="text-button" :disabled="busy" @click="showEvidence(field, choice)">定位依据</button>
              <button v-if="version.state === 'DRAFT' && !choice.tentative && !choice.reviewReason" class="outline"
                :aria-label="'确认 ' + field.slot.stage + ' ' + field.slot.type + ' ' + displayValue(choice.value)"
                :disabled="busy || (field.state === 'CONFIRMED' && field.selected === index)"
                @click="reviewField(field, 'confirm', index)">核验通过</button>
            </div>
            <div v-if="version.state === 'DRAFT'" class="footer-actions">
              <button class="text-button" :disabled="busy" @click="editField(field)">修正字段</button>
              <button class="text-button warn-text" :disabled="busy || field.state === 'HOLD'"
                @click="reviewField(field, 'hold', null)">保留待复核</button>
              <button class="text-button" :disabled="busy || field.state === 'NOT_APPLICABLE'"
                @click="reviewField(field, 'not_applicable', null)">不适用</button>
            </div>
          </article>
        </section>
        <p v-else class="panel quiet">录入通知后，这里会显示版本和候选字段。</p>
      </div>

      <div v-else-if="page === 'feedback'" class="stack">
        <p v-if="!feedback.length" class="panel quiet">暂无学生纠错反馈。</p>
        <article v-for="item in feedback" :key="item.id" class="panel">
          <div class="panel-head"><h2>{{ item.title }}</h2><span class="pill" :class="{ warn: !item.handled }">{{ item.handled ? '已核查' : '待核查' }}</span></div>
          <p class="message-body">{{ item.message }}</p>
          <div class="footer-actions">
            <button class="outline" :disabled="busy" @click="startCorrection(item)">录入更正版本</button>
            <button v-if="!item.handled" class="text-button" :disabled="busy" @click="handleFeedback(item)">登记已核查</button>
          </div>
        </article>
      </div>

      <section v-else class="panel audit">
        <p v-if="!events.length" class="quiet">暂无操作记录。</p>
        <article v-for="event in events" :key="event.id" class="audit-event">
          <div class="panel-head"><h3>{{ event.action }}</h3><span class="quiet">{{ event.createdAt.replace('T', ' ').slice(0, 16) }}</span></div>
          <p class="quiet">{{ event.title }} · {{ event.actor }}</p>
          <p class="message-body">{{ event.detail }}</p>
        </article>
      </section>
    </main>
  </div>

  <dialog ref="correctionDialog" class="evidence-dialog" aria-labelledby="correction-title" @close="correction = null">
    <form v-if="correction" class="form-stack" @submit.prevent="saveCorrection">
      <div class="panel-head">
        <h2 id="correction-title">{{ correction.editing ? '修正字段' : '补录字段' }}</h2>
        <button type="button" class="outline" :disabled="busy" @click="correctionDialog.close()">取消</button>
      </div>
      <label>字段
        <select v-model="correction.slot" :disabled="correction.editing || busy" required>
          <option disabled value="">选择办理角色、阶段和字段</option>
          <option v-for="slot in correction.options" :key="[slot.role, slot.stage, slot.type].join('/')" :value="slot">
            {{ slot.role }} · {{ slot.stage }} · {{ slot.type }}
          </option>
        </select>
      </label>
      <template v-if="correctionIsDate">
        <div class="form-row">
          <label>{{ correction.slot.type === '截止' ? '截止日期' : '日期或区间起日' }}<input v-model="correction.date" type="date" required></label>
          <label>时间（可选）<input v-model="correction.time" type="time"></label>
        </div>
        <div v-if="correction.slot.type === '时间'" class="form-row">
          <label>区间结束日期（可选）<input v-model="correction.endDate" type="date" :min="correction.date"></label>
          <label>结束时间（可选）<input v-model="correction.endTime" type="time" :disabled="!correction.endDate"></label>
        </div>
      </template>
      <label v-else-if="correction.slot">字段值<textarea v-model="correction.value" rows="2" maxlength="40000" required></textarea></label>
      <label>证据片段
        <select v-model="correction.fragmentNo" required>
          <option disabled value="">选择本版本的原文片段</option>
          <option v-for="fragment in correctionFragments" :key="fragment.number" :value="fragment.number">
            {{ fragment.number }} · {{ fragment.text.length > 60 ? fragment.text.slice(0, 60) + '…' : fragment.text }}
          </option>
        </select>
      </label>
      <label v-if="correctionQuote">原文（可选中部分文字）
        <textarea ref="quoteInput" :value="correctionQuote" rows="3" readonly></textarea>
        <small class="quiet">未选中文字时引用整段。</small>
      </label>
      <label>核验说明<textarea v-model="correction.reason" rows="2" maxlength="300" required
        placeholder="说明为什么采用这个字段值"></textarea></label>
      <p v-if="error" class="status error" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">保存并核验</button>
    </form>
  </dialog>

  <dialog ref="proofDialog" class="evidence-dialog">
    <div v-if="proof">
      <div class="panel-head">
        <div><h2>{{ proof.field.slot.stage }} · {{ proof.field.slot.type }}</h2>
          <p class="quiet">V{{ proof.notice.number }} · 证据片段 {{ proof.choice.evidence.fragmentNo }}{{ proof.notice.simulated ? ' · 模拟通知' : '' }}</p>
        </div>
        <button class="outline" @click="proofDialog.close()">关闭</button>
      </div>
      <div class="proof-value"><span class="label">字段值</span><strong>{{ proof.choice.value == null ? '日期待核定' : displayValue(proof.choice.value) }}</strong></div>
      <blockquote>{{ proof.choice.evidence.quote }}</blockquote>
      <p v-if="isReviewer" class="quiet">字符区间 [{{ proof.choice.evidence.start }}, {{ proof.choice.evidence.end }})</p>
      <p v-if="proof.choice.extractionSource || proof.choice.supportLevel" class="quiet">
        抽取来源：{{ sourceLabel(proof.choice.extractionSource) }} · 证据判断：{{ supportLabel(proof.choice.supportLevel) }}
      </p>
      <p v-if="proof.choice.supportReason" class="quiet">{{ proof.choice.supportReason }}</p>
      <a :href="proof.notice.sourceUrl" target="_blank" rel="noopener noreferrer">查看校方原通知</a>
    </div>
  </dialog>
</template>
