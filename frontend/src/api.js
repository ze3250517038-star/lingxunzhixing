let token

export function useSession(session) {
  token = session?.csrf
}

async function send(path, method, body, accept) {
  const headers = { Accept: accept }
  if (token) headers['X-Lingxun-Token'] = token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch('/api' + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || '请求未完成，请重试。')
  }
  return response
}

export async function request(path, method = 'GET', body) {
  const response = await send(path, method, body, 'application/json')
  const content = await response.text()
  return content ? JSON.parse(content) : null
}

export async function requestCalendar() {
  const response = await send('/todos/calendar.ics', 'GET', undefined, 'text/calendar, application/json')
  return response.blob()
}
