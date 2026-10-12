export type RecordData = Record<string, any>
let authorization = ''
export function credentials(username: string, password: string) { authorization = 'Basic ' + btoa(username + ':' + password) }
export function clearCredentials() { authorization = '' }
export async function api(path: string, method = 'GET', body?: unknown, extra: Record<string, string> = {}): Promise<any> {
  const response = await fetch('/api/v1' + path, { method, headers: { Authorization: authorization, ...(body ? { 'Content-Type': 'application/json' } : {}), ...extra }, body: body ? JSON.stringify(body) : undefined })
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: `请求失败 (${response.status})` }))
    throw new Error(error.message + (error.details?.length ? '\n' + error.details.map((d: RecordData) => `第 ${d.line} 行：${d.message}`).join('\n') : ''))
  }
  return response.headers.get('content-type')?.includes('text/html') ? response.text() : response.json()
}
export function download(text: string, filename: string, type = 'text/html;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
