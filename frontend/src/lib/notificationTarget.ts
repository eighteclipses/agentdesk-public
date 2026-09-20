import type { NotificationItem } from './types'

export function notificationTarget(n: NotificationItem): string | null {
  if (n.refType === 'TICKET' && n.refId) {
    const path = n.link?.startsWith('/workspace/agent') ? '/workspace/agent' : '/workspace/tickets'
    return `${path}?ticket=${n.refId}`
  }
  return n.link?.startsWith('/') && !n.link.startsWith('//') ? n.link : null
}
