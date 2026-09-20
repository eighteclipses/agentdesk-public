export interface Ticket {
  id: number; title: string; description: string; category: string; priority: string
  status: string; departmentName?: string; queueName?: string; queueId?: number
  requesterId?: number; assigneeId?: number | null; createdAt?: string; updatedAt?: string
  dueAt?: string | null; requesterName?: string; assigneeName?: string
}

export interface Comment {
  id: number; ticketId: number; authorId: number; authorName?: string; content: string; createdAt: string
}

export interface Attachment {
  id: number; ticketId: number; fileName: string; contentType?: string; size: number; objectKey?: string; createdAt: string
}

export const statusLabel: Record<string, string> = {
  NEW: '新建', TRIAGED: '已分析', ASSIGNED: '已分派', IN_PROGRESS: '处理中',
  PENDING_USER: '等待用户', RESOLVED: '已解决', CLOSED: '已关闭',
}

export const itemStatusLabel: Record<string, string> = {
  QUEUED: '排队中', PARSING: '解析中', NORMALIZING: '归一化', CHUNKING: '分块中',
  INDEXING: '索引中', REVIEW: '待审核', PUBLISHED: '已发布', FAILED: '失败',
  DUPLICATE: '重复跳过', CANCELED: '已取消', REJECTED: '已驳回', RETRACTED: '已撤回',
}

export interface NotificationItem {
  id: number; type: string; title: string; body?: string | null; link?: string | null
  refType?: string | null; refId?: number | null; read: boolean; createdAt: string
}

export interface NotificationPage {
  items: NotificationItem[]; total: number; unread: number
}

export interface StatBucket { label: string; count: number }
export interface DayPoint { date: string; count: number }

export interface DashboardStats {
  days: number
  status: StatBucket[]
  priority: StatBucket[]
  dailyCreated: DayPoint[]
  sla: { onTime: number; overdue: number; rate: number | null }
  qa: { conversations: number; messages: number; feedbackUp: number; feedbackDown: number; citationsTotal: number; citationsClicked: number }
}
