import type {
  ActorBasis,
  AlertDraftRequest,
  AlertPreview,
  AlertRecipientView,
  AlertResendResult,
  AlertState,
  AlertSummary,
  AlertView,
  Freshness,
  PublicAnnouncement,
  LocationListView,
  AuditEntryView,
  AnnouncementCreateRequest,
  AnnouncementPreview,
  AnnouncementTarget,
  AnnouncementView,
  AssignmentStatus,
  AssignmentView,
  GroupCreateRequest,
  GroupMemberView,
  GroupSummary,
  GroupUpdateRequest,
  GroupView,
  InboxItem,
  InvitationCreateRequest,
  InvitationStatus,
  InvitationView,
  LoginRequest,
  MfaEnrollResponse,
  PasswordChangeRequest,
  Role,
  RoleAssignmentCreateRequest,
  RoleAssignmentView,
  ScopeType,
  LoginResponse,
  MeContext,
  MeProfile,
  MeProfileUpdate,
  MeetingPoint,
  MeetingPointRequest,
  OperationCreateRequest,
  OperationState,
  OperationStateRequest,
  OperationUpdateRequest,
  OperationView,
  Page,
  PageRequest,
  ParticipantView,
  TaskCreateRequest,
  TaskState,
  TaskStateRequest,
  TaskSummary,
  TaskUpdateRequest,
  TaskView,
  UserDetail,
  UserPage,
  UserStatus,
  UserSummary,
  Uuid,
} from './types';

/** Kimlik akışı (ID-01 – ID-04, ID-06, ID-08). MFA bileti yalnızca bellekte tutulur (mapping §0.4). */
export interface AuthService {
  /** ID-01 `GET /auth/csrf`. */
  fetchCsrf(): Promise<void>;
  /** ID-02 `POST /auth/login` (`clientType=WEB`). */
  login(request: Omit<LoginRequest, 'clientType'>): Promise<LoginResponse>;
  /** ID-03 `POST /auth/mfa/enroll`: ilk girişte TOTP anahtarı (kurulum ID-04'teki ilk doğru kodla kalıcılaşır). */
  enrollMfa(mfaTicket: string): Promise<MfaEnrollResponse>;
  /** ID-04 `POST /auth/mfa`: doğru kod → `mfaVerified=true` web oturumu (çerezler). */
  verifyMfa(mfaTicket: string, code: string): Promise<LoginResponse>;
  /** ID-08 `POST /me/password`: tüm eski oturumlar iptal, çağıran yeni oturum alır (LD-58). */
  changePassword(request: PasswordChangeRequest): Promise<void>;
  /** ID-06 `POST /auth/logout`. Başarısız olsa da çağıran yerel temizliği yapar. */
  logout(): Promise<void>;
}

export interface InvitationFilter extends PageRequest {
  scopeType?: ScopeType;
  scopeId?: Uuid;
  status?: InvitationStatus[];
}

/** ID-12 – ID-14 (web). ID-15 – ID-18 Android uçlarıdır. */
export interface InvitationService {
  /** ID-13 `GET /invitations`. */
  list(filter?: InvitationFilter): Promise<Page<InvitationView>>;
  /** ID-12 `POST /invitations` (Z: `Idempotency-Key` zorunlu). */
  create(request: InvitationCreateRequest, idempotencyKey: string): Promise<InvitationView>;
  /** ID-14 `POST /invitations/{id}/revoke`. */
  revoke(id: Uuid): Promise<InvitationView>;
}

/** ID-09 / ID-10: kendi profili (web ve Android aynı uçlar). */
export interface AccountService {
  /** ID-09 `GET /me`. */
  getProfile(): Promise<MeProfile>;
  /** ID-10 `PATCH /me`: yalnızca `fullName`, `displayName`, `phone`. */
  updateProfile(update: MeProfileUpdate): Promise<MeProfile>;
}

export interface InboxFilter extends PageRequest {
  unreadOnly?: boolean;
}

/** Uygulama içi gelen kutusu (NT-04/05/07/08, LD-60): kişinin kendi kayıtları; web ve Android aynı uçlar. */
export interface NotificationService {
  list(filter?: InboxFilter): Promise<Page<InboxItem>>;
  get(id: Uuid): Promise<InboxItem>;
  markRead(id: Uuid): Promise<void>;
  unreadCount(): Promise<number>;
}

export interface AnnouncementFilter extends PageRequest {
  operationId?: Uuid;
}

/** NT-01 – NT-03, NT-06 (web; KOR kendi aktif operasyonu, ADM kurum geneli). */
export interface AnnouncementService {
  preview(target: AnnouncementTarget): Promise<AnnouncementPreview>;
  /** NT-02 (Z: `Idempotency-Key` zorunlu). */
  create(request: AnnouncementCreateRequest, idempotencyKey: string): Promise<AnnouncementView>;
  list(filter?: AnnouncementFilter): Promise<Page<AnnouncementView>>;
  get(id: Uuid): Promise<AnnouncementView>;
}

export interface RoleAssignmentFilter extends PageRequest {
  userId?: Uuid;
  scopeId?: Uuid;
  role?: Role;
  active?: boolean;
}

/** AC-01 – AC-03 (yalnızca ADM, `role.assign`; web veya MFA'lı Android yönetim oturumu). */
export interface RoleAssignmentService {
  list(filter?: RoleAssignmentFilter): Promise<Page<RoleAssignmentView>>;
  grant(request: RoleAssignmentCreateRequest): Promise<RoleAssignmentView>;
  /** AC-02: ADM kendi ADM atamasını ve kurumun son ADM atamasını kaldıramaz (`403`). */
  revoke(id: Uuid): Promise<void>;
}

export interface UserFilter extends PageRequest {
  status?: UserStatus;
  operationId?: Uuid;
  includeTotal?: boolean;
}

/** ID-19 – ID-25 kullanıcı ve hesap yönetimi (ADM; TEK durum ve oturum işlemleri; KOR kendi operasyonu listesi). */
export interface UserAdminService {
  list(filter?: UserFilter): Promise<UserPage>;
  get(id: Uuid): Promise<UserDetail>;
  /** ID-21: kendi hesabı `403`; gerekçe ≤ 300. */
  disable(id: Uuid, reason?: string): Promise<UserSummary>;
  /** ID-22: yalnızca ADM. */
  enable(id: Uuid): Promise<UserSummary>;
  revokeSessions(id: Uuid): Promise<void>;
  /** ID-24: geçici parola e-postayla gider; ilk girişte değişim zorunlu. */
  resetPassword(id: Uuid): Promise<void>;
  resetMfa(id: Uuid): Promise<void>;
}

export interface AuditFilter extends PageRequest {
  action?: string;
  actorBasis?: ActorBasis;
  operationId?: Uuid;
}

export interface AlertFilter {
  /** Verilmezse AL-16 (koordine edilen tüm operasyonlar; ADM: kurum). */
  operationId?: Uuid;
  states?: AlertState[];
}

/** Acil alarm AL-01, AL-03 – AL-12, AL-16 (yönetim; KOR ve ADM, ADR-018). Alıcı uçları Android'dedir. */
export interface AlertService {
  list(filter?: AlertFilter): Promise<Page<AlertSummary>>;
  get(alertId: Uuid): Promise<AlertView>;
  create(operationId: Uuid, request: AlertDraftRequest): Promise<AlertPreview>;
  preview(alertId: Uuid): Promise<AlertPreview>;
  /** AL-05 (Z). `409 AUDIENCE_CHANGED` → `error.details.preview`. */
  send(alertId: Uuid, version: number, audienceHash: string, idempotencyKey: string): Promise<AlertView>;
  deleteDraft(alertId: Uuid): Promise<void>;
  cancel(alertId: Uuid, reason: string | undefined, idempotencyKey: string): Promise<AlertView>;
  close(alertId: Uuid): Promise<AlertView>;
  resend(alertId: Uuid, recipientIds: Uuid[], idempotencyKey: string): Promise<AlertResendResult>;
  recipients(alertId: Uuid): Promise<Page<AlertRecipientView>>;
  /** Telefon bildirimi (FCM) durumu: backend `/actuator/health` → `components.push.status` (ADR-022). */
  pushStatus(): Promise<PushStatus>;
}

/** `UP`: FCM etkin; `DISABLED`: yapılandırmada kapalı; `DEGRADED`: istendi ama başlatılamadı; `UNKNOWN`: okunamadı. */
export type PushStatus = 'UP' | 'DISABLED' | 'DEGRADED' | 'UNKNOWN';

export interface LocationFilter {
  groupId?: Uuid;
  freshness?: Freshness;
}

/** LC-05: yalnızca operasyonun KOR'u (K-15); her görüntüleme denetime yazılır (5 dk pencerede tek kayıt). */
export interface LocationService {
  list(operationId: Uuid, filter?: LocationFilter): Promise<LocationListView>;
}

/** AU-01 `GET /audit-entries` (salt-okur; `audit.view`: ADM, UYM). Güncelleme/silme yoktur (K-26). */
export interface AuditService {
  list(filter?: AuditFilter): Promise<Page<AuditEntryView>>;
}

/** ID-11 `GET /me/context`. */
export interface MeContextService {
  getContext(): Promise<MeContext>;
}

/** OR-01, OR-05, OR-02, OR-03. */
export interface OperationService {
  /** OR-01 (d): yalnızca ADM; operasyon ve KOR atamaları tek işlemde. */
  create(request: OperationCreateRequest, idempotencyKey?: string): Promise<OperationView>;
  get(id: Uuid): Promise<OperationView>;
  update(id: Uuid, request: OperationUpdateRequest): Promise<OperationView>;
  changeState(id: Uuid, request: OperationStateRequest): Promise<OperationView>;
}

export interface OperationListFilter extends PageRequest {
  state?: OperationState[];
}

/** OR-04 `GET /operations`. */
export interface OperationListService {
  list(filter?: OperationListFilter): Promise<Page<OperationView>>;
}

export interface ParticipantFilter extends PageRequest {
  notInGroup?: Uuid;
}

/** OR-06 `GET /operations/{id}/participants`. */
export interface ParticipantService {
  list(operationId: Uuid, filter?: ParticipantFilter): Promise<Page<ParticipantView>>;
}

export interface GroupListFilter extends PageRequest {
  includeArchived?: boolean;
}

/** GR-01 … GR-08. `idempotencyKey` yalnızca "d" (destekleyen) uçlarda gönderilir. */
export interface GroupService {
  /** GR-02 `GET /groups?operationId=`. */
  list(operationId: Uuid, filter?: GroupListFilter): Promise<Page<GroupSummary>>;
  /** GR-03. */
  get(groupId: Uuid): Promise<GroupView>;
  /** GR-01 `POST /operations/{id}/groups`. */
  create(operationId: Uuid, request: GroupCreateRequest, idempotencyKey?: string): Promise<GroupView>;
  /** GR-04. */
  addMember(groupId: Uuid, userId: Uuid, idempotencyKey?: string): Promise<GroupMemberView>;
  /** GR-05. */
  removeMember(groupId: Uuid, userId: Uuid): Promise<void>;
  /** GR-06. */
  changeLeader(groupId: Uuid, userId: Uuid, idempotencyKey?: string): Promise<GroupView>;
  /** GR-07. */
  update(groupId: Uuid, request: GroupUpdateRequest): Promise<GroupView>;
  /** GR-08. */
  setMeetingPoint(groupId: Uuid, request: MeetingPointRequest): Promise<MeetingPoint>;
}

/** TK-07: `state` tek değerdir (§9.1 `state?`); birden fazla değer backend'de 400 olur. */
export interface TaskListFilter extends PageRequest {
  state?: TaskState;
}

export interface AssignmentFilter extends PageRequest {
  status?: AssignmentStatus[];
  overdue?: boolean;
}

/** TK-01 … TK-09 (web tarafı; TK-10 … TK-12 Android uçlarıdır). */
export interface TaskService {
  /** TK-07. */
  list(operationId: Uuid, filter?: TaskListFilter): Promise<Page<TaskSummary>>;
  /** TK-08. */
  get(taskId: Uuid): Promise<TaskView>;
  /** TK-09. */
  assignments(taskId: Uuid, filter?: AssignmentFilter): Promise<Page<AssignmentView>>;
  /** TK-01 (d). */
  create(operationId: Uuid, request: TaskCreateRequest, idempotencyKey?: string): Promise<TaskView>;
  /** TK-02. */
  update(taskId: Uuid, request: TaskUpdateRequest): Promise<TaskView>;
  /** TK-03 (Z: `Idempotency-Key` zorunlu). */
  publish(taskId: Uuid, version: number, idempotencyKey: string): Promise<TaskView>;
  /** TK-04 (taslağı atma dahil: `CANCELLED`). */
  changeState(taskId: Uuid, request: TaskStateRequest): Promise<TaskView>;
  /** TK-05 (d). */
  addAssignment(taskId: Uuid, userId: Uuid, idempotencyKey?: string): Promise<AssignmentView>;
  /** TK-06. */
  removeAssignment(taskId: Uuid, assignmentId: Uuid): Promise<void>;
}

/** RT-01 olay türleri (§12.2); veri yalnızca kimlik, durum ve zaman taşır. */
export type LiveEventType =
  | 'inbox.changed'
  | 'announcement.sent'
  | 'operation.state.changed'
  | 'alert.opened'
  | 'alert.recipient.changed'
  | 'alert.cancelled'
  | 'alert.closed'
  | 'location.updated'
  | 'location.sharing.changed'
  | 'subscription.revoked'
  | 'session.revoked'
  | 'resync';

export interface LiveEvent {
  type: LiveEventType;
  id?: string;
  data: Record<string, unknown>;
}

export interface RealtimeService {
  /**
   * RT-01 `GET /stream`. Akışı açar ve olayları iletir; dönen işlev akışı kapatır. Akış kalıcı olarak kapanırsa
   * (ör. yetkisiz, oturum süresi) `onClosed` çağrılır; yeniden bağlanma kararı çağırandadır.
   */
  connect(topics: string[], onEvent: (event: LiveEvent) => void, onClosed: () => void): () => void;
}

/** Kamu duyuruları (ADR-023): halka açık site ve uygulama; iç duyurulardan ayrıdır. */
export interface PublicAnnouncementService {
  /** Kimlik doğrulamasız, yalnızca yayındakiler, yeniden eskiye. */
  listPublished(page?: PageRequest): Promise<Page<PublicAnnouncement>>;
}

export interface Services {
  auth: AuthService;
  account: AccountService;
  meContext: MeContextService;
  operations: OperationService;
  operationList: OperationListService;
  participants: ParticipantService;
  groups: GroupService;
  tasks: TaskService;
  invitations: InvitationService;
  notifications: NotificationService;
  announcements: AnnouncementService;
  roleAssignments: RoleAssignmentService;
  users: UserAdminService;
  audit: AuditService;
  alerts: AlertService;
  locations: LocationService;
  realtime: RealtimeService;
  publicAnnouncements: PublicAnnouncementService;
}
