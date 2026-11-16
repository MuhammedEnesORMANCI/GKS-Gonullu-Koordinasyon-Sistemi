/**
 * Sözleşme tipleri: backend-api-design.md §5.2, §6, §7.4, §8.2, §9.3, §12.3, §12.4.
 * Alan adları ve enum değerleri birebir sözleşmeden alınmıştır.
 */

export type Uuid = string;
export type DateTime = string;

export interface Page<T> {
  items: T[];
  nextCursor?: string;
}

export interface PageRequest {
  limit?: number;
  cursor?: string;
}

// ---- Enum'lar (§12.4) ----
export type ClientType = 'WEB' | 'ANDROID';
export type Role = 'ADM' | 'KOR' | 'GON' | 'TEK' | 'UYM';
export type ScopeType = 'ORGANIZATION' | 'OPERATION';
export type UserStatus = 'ACTIVE' | 'DISABLED' | 'CLOSED';
export type OperationState = 'PLANNED' | 'ACTIVE' | 'SUSPENDED' | 'COMPLETED' | 'CANCELLED';
export type MembershipRole = 'LEADER' | 'MEMBER';
export type TaskState = 'DRAFT' | 'PUBLISHED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export type AssignmentStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CAPACITY_FULL' | 'REMOVED';

// ---- Ortak (§12.3) ----
export interface UserRef {
  id: Uuid;
  displayName: string;
}

export interface GroupRef {
  id: Uuid;
  name: string;
}

/** §5.2 UserSummary; izdüşüme göre isteğe bağlı alanlar yazılmayabilir (§3.8). */
export interface UserSummary {
  id: Uuid;
  displayName: string;
  status?: UserStatus;
  fullName?: string;
  email?: string;
  phone?: string;
}

// ---- identity (§5.2) ----
export interface LoginRequest {
  email: string;
  password: string;
  clientType: ClientType;
}

export interface SessionInfo {
  sessionId: Uuid;
  clientType: ClientType;
  mfaVerified: boolean;
  expiresAt: DateTime;
}

export interface LoginResponse {
  mfaRequired: boolean;
  mfaEnrollmentRequired?: boolean;
  mfaTicket?: string;
  mustChangePassword: boolean;
  accessTokenExpiresAt?: DateTime;
  session?: SessionInfo;
}

export interface RoleAssignmentView {
  id: Uuid;
  user: UserRef;
  role: Role;
  scopeType: ScopeType;
  scopeId: Uuid;
  scopeName: string;
  grantedBy: UserRef;
  grantedAt: DateTime;
  revokedAt?: DateTime;
}

/** ID-20 `UserDetail`: ADM tam; KOR kendi operasyonuyla sınırlı rol/grup; TEK yalnızca ad ve durum (§3.8). */
export interface UserDetail extends UserSummary {
  roles?: RoleAssignmentView[];
  groups?: Array<{ id: Uuid; name: string; operationId: Uuid; role: MembershipRole }>;
  mfaEnrolled?: boolean;
  lockedUntil?: DateTime;
  createdAt?: DateTime;
}

/** ID-19 sayfası (`includeTotal=true` ise `totalCount`). */
export interface UserPage extends Page<UserSummary> {
  totalCount?: number;
}

/** AU-01 denetim kaydı (K-26). `actorBasis`: işlemin dayandığı yetki (ADR-015: `ADM` = KOR ataması olmadan). */
export type ActorBasis = 'KOR' | 'ADM' | 'TEK' | 'UYM' | 'LEADER' | 'SELF' | 'SYSTEM';

export interface AuditEntryView {
  id: Uuid;
  actor?: UserRef;
  actorBasis: ActorBasis;
  action: string;
  resourceType: string;
  resourceId?: Uuid;
  operationId?: Uuid;
  operationName?: string;
  client: 'WEB' | 'ANDROID' | 'SYSTEM';
  at: DateTime;
  details?: Record<string, unknown>;
  /** Kimlik olaylarında ve HTTP isteğinden gelen kayıtlarda istemci IP'si (ADR-020). */
  ip?: string;
}

/** AC-01 `RoleAssignmentCreateRequest` (rol ve kapsam türü eşleşmeli: ADM/TEK/UYM kurum, KOR/GON operasyon). */
export interface RoleAssignmentCreateRequest {
  userId: Uuid;
  role: Role;
  scopeType: ScopeType;
  scopeId: Uuid;
}

export interface MyOperationView {
  id: Uuid;
  name: string;
  state: OperationState;
  myRoles: Role[];
}

export interface MyGroupView {
  id: Uuid;
  name: string;
  operationId: Uuid;
  role: MembershipRole;
}

export interface EffectivePermissions {
  organization: string[];
  operations: Record<Uuid, string[]>;
  groups: Record<Uuid, string[]>;
}

export interface MeContext {
  user: UserRef & { fullName?: string };
  session: SessionInfo & { mustChangePassword: boolean };
  roles: RoleAssignmentView[];
  operations: MyOperationView[];
  groups: MyGroupView[];
  /** Kullanıcıya gelen bekleyen operasyon davetleri (Android'de yanıtlanır; web göstermez). */
  pendingInvitations: InvitationView[];
  permissions: EffectivePermissions;
}

/** ID-03 `MfaEnrollResponse`: gizli anahtar yalnızca bu yanıtta bir kez döner. */
export interface MfaEnrollResponse {
  otpauthUri: string;
  secret: string;
}

/** ID-09 `MeProfile`. */
export interface MeProfile {
  id: Uuid;
  email: string;
  fullName: string;
  displayName: string;
  status: string;
  phone?: string;
  mfaEnrolled: boolean;
  createdAt: string;
}

/** ID-10 `MeProfileUpdate`: kısmi; `phone: null` telefonu siler (LD-52). */
export interface MeProfileUpdate {
  fullName?: string;
  displayName?: string;
  phone?: string | null;
}

// ---- notifications (§11.2 – 11.3, NT-04/05/07/08, LD-60) ----
export type NotificationCategory = 'ALERT' | 'TASK' | 'ANNOUNCEMENT' | 'MEETING_POINT' | 'OPERATION_INVITE' | 'SYSTEM';

/** `NotificationInboxItem`: `id` uygulama içi teslim kaydının kimliğidir. */
export interface InboxItem {
  id: Uuid;
  category: NotificationCategory;
  kind: string;
  priority: 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW';
  title: string;
  body?: string;
  createdAt: string;
  readAt?: string;
  link?: { type: 'TASK' | 'GROUP' | 'INVITATION' | 'OPERATION' | 'ANNOUNCEMENT' | 'ALERT'; id: Uuid };
  operationId?: Uuid;
  /** Olayın kimliği; aynı olay FCM ve SSE ile gelirse tekilleştirme anahtarı (ADR-022). */
  notificationId?: Uuid;
}

/** NT-01 `AnnouncementTargetRequest`: KOR → `operationId` (+ isteğe bağlı grup/kişi), ADM → `organization`. */
export interface AnnouncementTarget {
  operationId?: Uuid;
  groupIds?: Uuid[];
  userIds?: Uuid[];
  organization?: boolean;
}

export interface AnnouncementPreview {
  recipientCount: number;
  recipients: UserRef[];
  confirmationRequired: boolean;
}

export interface AnnouncementCreateRequest extends AnnouncementTarget {
  title: string;
  body: string;
  confirm?: boolean;
}

export interface AnnouncementView {
  id: Uuid;
  title: string;
  body?: string;
  operationId?: Uuid;
  operationName?: string;
  createdBy?: UserRef;
  createdAt: string;
  recipientCount: number;
  delivery: { sent: number; failed: number; read: number };
}

/** ID-08 `PasswordChangeRequest`. */
export interface PasswordChangeRequest {
  currentPassword: string;
  newPassword: string;
}

// ---- invitations (§5, ID-12 – ID-14) ----
export type InvitationKind = 'NEW_USER' | 'OPERATION';
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'REVOKED';

export interface InvitationView {
  id: Uuid;
  kind: InvitationKind;
  status: InvitationStatus;
  email?: string;
  scopeType: ScopeType;
  scopeId: Uuid;
  scopeName?: string;
  expiresAt: DateTime;
  createdAt: DateTime;
  createdBy?: UserRef;
}

/** ID-12: `kind` istemciden gönderilmez; sunucu seçer (TD-01). */
export interface InvitationCreateRequest {
  email: string;
  scopeType: ScopeType;
  scopeId: Uuid;
}

// ---- organization (§7.4) ----
export interface OperationView {
  id: Uuid;
  name: string;
  state: OperationState;
  createdAt: DateTime;
  description?: string;
  plannedEndAt?: DateTime;
  activatedAt?: DateTime;
  closedAt?: DateTime;
  coordinators: UserRef[];
  overdue?: boolean;
  activeSharingCount?: number;
  openAlertCount?: number;
  pendingAssignmentCount?: number;
  /** ADR-025: operasyon bölgesi (merkez + isteğe bağlı yarıçap). */
  area?: OperationArea;
}

/** ADR-025: merkez koordinatı ve isteğe bağlı yarıçap (metre, 50..200000). */
export interface OperationArea {
  lat: number;
  lon: number;
  radiusM?: number;
}

/** ADR-025 `GET /map/operations`: harita özeti (gönüllü konumu ve alarm metni yok). */
export interface MapOperationView {
  id: Uuid;
  name: string;
  state: OperationState;
  area?: OperationArea;
  participantCount: number;
  meetingPoints: { groupId: Uuid; groupName: string; lat: number; lon: number; address?: string }[];
  openAlert?: { id: Uuid; sentAt: DateTime; counts: AlertCounts };
}

/**
 * OR-02: kısmi (LD-52). Gönderilmeyen alan korunur; isteğe bağlı alana açıkça `null` → temizlenir;
 * zorunlu alana `null` → 400 REQUIRED.
 */
export interface OperationUpdateRequest {
  name?: string;
  description?: string | null;
  plannedEndAt?: DateTime | null;
  area?: OperationArea | null;
}

/** OR-01 (yalnızca ADM): ad 3..120, açıklama ≤ 2000, planlanan bitiş gelecekte, 1..10 tekil koordinatör. */
export interface OperationCreateRequest {
  name: string;
  description?: string;
  plannedEndAt?: DateTime;
  coordinatorIds: Uuid[];
  area?: OperationArea;
}

export interface OperationStateRequest {
  targetState: OperationState;
}

export interface ParticipantView {
  user: UserSummary;
  joinedAt: DateTime;
  groups: GroupRef[];
  openAssignmentCount: number;
}

// ---- groups (§8.2) ----
export interface MeetingPoint {
  address?: string;
  lat?: number;
  lon?: number;
  description?: string;
  time?: DateTime;
  updatedBy: UserRef;
  updatedAt: DateTime;
}

export interface MeetingPointRequest {
  address?: string;
  lat?: number;
  lon?: number;
  description?: string;
  time: DateTime;
}

export interface GroupSummary {
  id: Uuid;
  name: string;
  operationId: Uuid;
  operationName: string;
  leader: UserRef;
  memberCount: number;
  archived: boolean;
  myRole?: MembershipRole;
}

export interface GroupMemberView {
  user: UserSummary;
  role: MembershipRole;
  joinedAt: DateTime;
}

export interface GroupView extends GroupSummary {
  description?: string;
  members: GroupMemberView[];
  meetingPoint?: MeetingPoint;
  createdAt: DateTime;
  type?: 'OPERATIONAL';
  joinPolicy?: 'MANAGED';
}

export interface GroupCreateRequest {
  name: string;
  description?: string;
  leaderUserId: Uuid;
  memberUserIds?: Uuid[];
}

/** GR-07: kısmi (LD-52). `name` zorunlu alandır, `null` gönderilmez; `description: null` temizler. */
export interface GroupUpdateRequest {
  name?: string;
  description?: string | null;
}

// ---- tasks (§9.3) ----
export interface TimeWindow {
  start?: DateTime;
  end?: DateTime;
}

export interface TaskTargets {
  userIds?: Uuid[];
  groupIds?: Uuid[];
}

export interface TaskCreateRequest {
  title: string;
  description?: string;
  priority?: TaskPriority;
  meetingPoint?: string;
  timeWindow?: TimeWindow;
  targets: TaskTargets;
  capacity?: number;
  responseWindowMinutes?: number;
}

/** İsteğe bağlı alanları `null` kabul eden kısmi tip (LD-52). */
type Clearable<T, K extends keyof T> = { [P in keyof T]?: P extends K ? T[P] | null : T[P] };

/**
 * TK-02: kısmi (LD-52). `title`/`targets` zorunludur (null gönderilmez); diğer isteğe bağlı alanlar `null` ile
 * temizlenir (ör. `capacity: null` = sınırsız).
 */
export type TaskUpdateChanges = Clearable<
  TaskCreateRequest,
  'description' | 'meetingPoint' | 'timeWindow' | 'capacity' | 'responseWindowMinutes'
>;
export type TaskUpdateRequest = TaskUpdateChanges & { version: number };

export interface TaskPublishRequest {
  version: number;
}

export interface TaskStateRequest {
  targetState: Exclude<TaskState, 'PUBLISHED' | 'DRAFT'>;
}

export interface TaskSummary {
  id: Uuid;
  title: string;
  priority: TaskPriority;
  state: TaskState;
  acceptedCount: number;
  pendingCount?: number;
  overdueCount?: number;
  capacity?: number;
  timeWindow?: TimeWindow;
  createdAt: DateTime;
}

export interface TaskStateChange {
  state: TaskState;
  changedBy: UserRef;
  changedAt: DateTime;
}

export interface TaskView extends TaskSummary {
  description?: string;
  meetingPoint?: string;
  targets?: TaskTargets;
  responseWindowMinutes: number;
  version: number;
  createdBy: UserRef;
  publishedAt?: DateTime;
  history?: TaskStateChange[];
}

export interface AssignmentView {
  id: Uuid;
  taskId: Uuid;
  user: UserSummary;
  status: AssignmentStatus;
  respondBy: DateTime;
  overdue: boolean;
  respondedAt?: DateTime;
  removedBy?: UserRef;
}

export interface AssignmentAddRequest {
  userId: Uuid;
}

// ---- Acil alarm (§11.3, ADR-018) ----
export type AlertState = 'DRAFT' | 'SENT' | 'OPEN' | 'CANCELLED' | 'CLOSED';
export type AlertPriority = 'CRITICAL' | 'HIGH';
export type AlertRecipientState = 'QUEUED' | 'SENT' | 'DELIVERED' | 'SEEN' | 'RESPONDED' | 'UNANSWERED' | 'FAILED';
export type AlertResponseOption = 'RECEIVED' | 'NEED_HELP' | 'ON_MY_WAY';

export interface AlertCounts {
  total: number;
  responded: number;
  pending: number;
  unanswered: number;
  failed: number;
  needHelp: number;
}

export interface AlertDraftRequest {
  message: string;
  requestedAction?: string;
  priority?: AlertPriority;
  targets: { userIds: Uuid[]; groupIds: Uuid[] };
  correctsAlertId?: Uuid;
}

export interface AlertPreview {
  alertId: Uuid;
  operationId: Uuid;
  operationName: string;
  version: number;
  audienceHash: string;
  recipientCount: number;
  recipients: (UserRef & { groups: GroupRef[] })[];
  message?: string;
  requestedAction?: string;
  priority?: AlertPriority;
  correctsAlertId?: Uuid;
}

export interface AlertView {
  id: Uuid;
  operationId: Uuid;
  operationName?: string;
  state: AlertState;
  version: number;
  message: string;
  requestedAction?: string;
  priority: AlertPriority;
  responseOptions: AlertResponseOption[];
  createdBy?: UserRef;
  createdAt?: string;
  sentBy?: UserRef;
  sentAt?: string;
  recipientCount?: number;
  counts: AlertCounts;
  deliveryDelayed: boolean;
  cancelledBy?: UserRef;
  cancelledAt?: string;
  closedBy?: UserRef;
  closedAt?: string;
  closeReason?: 'MANUAL' | 'OPERATION_CLOSED';
  correctsAlertId?: Uuid;
  correctedByAlertId?: Uuid;
}

export interface AlertSummary {
  id: Uuid;
  operationId: Uuid;
  operationName?: string;
  state: AlertState;
  message: string;
  priority: AlertPriority;
  createdAt?: string;
  sentAt?: string;
  counts: AlertCounts;
}

/** `null` = bilinmiyor / olmadı (AC-24.2). */
export interface AlertRecipientView {
  id: Uuid;
  user: UserRef;
  group: GroupRef | null;
  state: AlertRecipientState;
  reminderCount: number;
  responseOption: AlertResponseOption | null;
  sentAt: string | null;
  deliveredAt: string | null;
  seenAt: string | null;
  respondedAt: string | null;
  respondedAtDevice: string | null;
  failureReason: string | null;
}

export interface AlertResendResult {
  alert: AlertView;
  requeuedCount: number;
  skippedCount: number;
}

// ---- Konum (§10.2, K-15, ADR-019) ----
export type Freshness = 'LIVE' | 'STALE' | 'LAST_KNOWN';
export type SharingState = 'OFF' | 'ACTIVE' | 'UNAVAILABLE' | 'STOPPED';

export interface LocationView {
  user: UserRef;
  sharingState: SharingState;
  lat: number;
  lon: number;
  accuracy?: number;
  measuredAt: string;
  receivedAt: string;
  freshness: Freshness;
  clockSkew: boolean;
  groups?: GroupRef[];
}

export interface LocationListView {
  operationId: Uuid;
  generatedAt: string;
  items: LocationView[];
  /** `WAITING`: paylaşım açık, henüz ölçüm yok (Ek). */
  notSharing: { user: UserRef; state: SharingState | 'WAITING' }[];
}

/** Halka açık (kamu) duyuru: kimlik doğrulamasız `GET /public/announcements` yalnızca yayındakileri döndürür. */
export interface PublicAnnouncement {
  id: Uuid;
  title: string;
  body: string;
  publishedAt: string;
}

export type PublicAnnouncementState = 'DRAFT' | 'PUBLISHED';

/** ADM yönetim görünümü (`/public-announcements`). */
export interface PublicAnnouncementAdminView {
  id: Uuid;
  title: string;
  body: string;
  state: PublicAnnouncementState;
  version: number;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string;
}

// ---- mesajlaşma (ADR-024) ----
export type ChannelKind = 'OPERATION' | 'COORDINATION' | 'GROUP';

export interface ChannelView {
  id: Uuid;
  kind: ChannelKind;
  operationId: Uuid;
  operationName: string;
  groupId?: Uuid;
  groupName?: string;
  adminsOnly: boolean;
  readOnly: boolean;
  canWrite: boolean;
  canModerate: boolean;
  muted: boolean;
  unread: number;
  lastMessageAt?: string;
}

export type MessageState = 'VISIBLE' | 'DELETED' | 'HIDDEN';

export interface MessageView {
  id: Uuid;
  channelId: Uuid;
  kind: 'USER' | 'SYSTEM';
  sender?: { id: Uuid; displayName: string };
  body?: string;
  state: MessageState;
  createdAt: string;
  mine: boolean;
  deletable: boolean;
}

