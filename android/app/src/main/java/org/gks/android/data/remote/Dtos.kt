package org.gks.android.data.remote

import kotlinx.serialization.Serializable

/*
 * backend-api-design.md §5.2 şemaları; alan adları backend kayıtlarıyla (identity modülü) birebir aynıdır.
 * Backend bilinmeyen alanı reddettiği için (fail-on-unknown-properties) isteklerde yalnızca sözleşme alanları
 * gönderilir. Yanıtlarda yeni alanlar yok sayılır.
 */

/** ID-02. Android girişi her zaman `ANDROID` beyan eder ve kurulum kimliğini taşır; MFA adımı yoktur. */
@Serializable
data class LoginRequest(
	val email: String,
	val password: String,
	val clientType: String,
	val installationId: String,
) {
	override fun toString() = "LoginRequest[***]"
}

@Serializable
data class LoginResponse(
	val mfaRequired: Boolean,
	/** ADR-014: ADM/KOR Android'de de TOTP ister; ilk girişte kurulum gerekir. */
	val mfaEnrollmentRequired: Boolean = false,
	val mfaTicket: String? = null,
	val mustChangePassword: Boolean = false,
	val accessToken: String? = null,
	val refreshToken: String? = null,
	val accessTokenExpiresAt: String? = null,
	val session: SessionInfo? = null,
) {
	override fun toString() = "LoginResponse[mfaRequired=$mfaRequired, session=${session?.sessionId}]"
}

/** ID-03 / ID-04 (ADR-014: Android'de de; bilet 5 dk, tek kullanımlık). */
@Serializable
data class MfaTicketRequest(val mfaTicket: String) {
	override fun toString() = "MfaTicketRequest[***]"
}

@Serializable
data class MfaVerifyRequest(val mfaTicket: String, val code: String) {
	override fun toString() = "MfaVerifyRequest[***]"
}

/** ID-03: gizli anahtar yalnızca bu yanıtta bir kez döner; cihazda saklanmaz. */
@Serializable
data class MfaEnrollment(val otpauthUri: String, val secret: String) {
	override fun toString() = "MfaEnrollment[***]"
}

@Serializable
data class SessionInfo(
	val sessionId: String,
	val clientType: String,
	val mfaVerified: Boolean,
	val expiresAt: String,
)

/** ID-05 (Android): yenileme belirteci gövdede gider. */
@Serializable
data class RefreshRequest(val refreshToken: String) {
	override fun toString() = "RefreshRequest[***]"
}

@Serializable
data class TokenResponse(
	val accessToken: String? = null,
	val refreshToken: String? = null,
	val sessionId: String,
	val accessTokenExpiresAt: String,
) {
	override fun toString() = "TokenResponse[session=$sessionId]"
}

/** ID-11 `MeContext`. */
@Serializable
data class MeContext(
	val user: ContextUser,
	val session: ContextSession,
	val roles: List<RoleAssignment> = emptyList(),
	val operations: List<MyOperation> = emptyList(),
	val groups: List<MyGroup> = emptyList(),
	val pendingInvitations: List<PendingInvitation> = emptyList(),
	val permissions: EffectivePermissions = EffectivePermissions(),
)

@Serializable
data class ContextUser(val id: String, val displayName: String, val fullName: String? = null)

@Serializable
data class ContextSession(
	val sessionId: String,
	val clientType: String,
	val mfaVerified: Boolean = false,
	val expiresAt: String,
	val mustChangePassword: Boolean = false,
)

/** §6 `RoleAssignmentView` (`MeContext.roles` ve AC-03 rol atama listesi). */
@Serializable
data class RoleAssignment(
	val id: String,
	val role: String,
	val scopeType: String,
	val scopeId: String,
	val scopeName: String? = null,
	val user: UserRef? = null,
	val grantedAt: String? = null,
)

@Serializable
data class MyOperation(val id: String, val name: String, val state: String, val myRoles: List<String> = emptyList())

@Serializable
data class MyGroup(val id: String, val name: String, val operationId: String, val role: String)

@Serializable
data class PendingInvitation(
	val id: String,
	val kind: String,
	val status: String,
	val scopeType: String,
	val scopeId: String,
	val scopeName: String? = null,
	val expiresAt: String,
	/** Yalnızca oluşturan kapsamın görünümünde (ID-13). */
	val email: String? = null,
	val createdAt: String? = null,
)

/** ID-12 (`invitation.create`; Idempotency-Key zorunlu). */
@Serializable
data class InvitationCreateRequest(val email: String, val scopeType: String, val scopeId: String) {
	override fun toString() = "InvitationCreateRequest[scope=$scopeId]"
}

/** Menü/düğme kararı içindir; yetki kaynağı değildir (sunucu her istekte denetler). */
@Serializable
data class EffectivePermissions(
	val organization: List<String> = emptyList(),
	val operations: Map<String, List<String>> = emptyMap(),
	val groups: Map<String, List<String>> = emptyMap(),
)

/** §4.1 `ProblemDetail` (RFC 9457) ve §4.2 hata kodu. */
@Serializable
data class Problem(
	val status: Int = 0,
	val code: String? = null,
	val title: String? = null,
	val errors: List<FieldProblem> = emptyList(),
	/** `410 INVITATION_INVALID` uzantısı: `EXPIRED`, `ACCEPTED`, `DECLINED`, `REVOKED`, `OPERATION_CLOSED`. */
	val invitationStatus: String? = null,
	/** `409 ALREADY_EXISTS` uzantısı (ör. `EMAIL_REGISTERED`). */
	val conflict: String? = null,
)

// ---------------------------------------------------------------- ortak

/** §3.5 imleçli sayfa. */
@Serializable
data class Page<T>(val items: List<T> = emptyList(), val nextCursor: String? = null)

/** §3 `UserRef`; gönüllü izdüşümünde `id` gelmeyebilir (ör. operasyon koordinatörleri). */
@Serializable
data class UserRef(val id: String? = null, val displayName: String)

// ---------------------------------------------------------------- identity: profil ve davetler

/** ID-09 `MeProfile`. */
@Serializable
data class MeProfile(
	val id: String,
	val email: String,
	val fullName: String,
	val displayName: String,
	val status: String,
	val phone: String? = null,
	val createdAt: String? = null,
)

/** ID-08: tüm oturumlar iptal edilir, çağıran yeni belirteç çifti alır (LD-58). */
@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String) {
	override fun toString() = "PasswordChangeRequest[***]"
}

/** ID-15 isteği; kod URL'de değil gövdede taşınır (LD-37). */
@Serializable
data class CodeRequest(val code: String) {
	override fun toString() = "CodeRequest[***]"
}

/** ID-15 `InvitationPublicView`: kişisel veri içermez. */
@Serializable
data class InvitationPublicView(
	val kind: String,
	val scopeName: String? = null,
	val organizationName: String? = null,
	val expiresAt: String,
)

/** ID-16 `InvitationAcceptRequest`; parola kuralları `PasswordChangeRequest.newPassword` ile aynı. */
@Serializable
data class InvitationAcceptRequest(
	val code: String,
	val password: String,
	val fullName: String,
	val displayName: String,
	val phone: String? = null,
	val installationId: String,
) {
	override fun toString() = "InvitationAcceptRequest[***]"
}

// ---------------------------------------------------------------- organization

/** §7.4 `OperationView` (gönüllü izdüşümü: koordinatörlerin yalnızca görünen adı). */
@Serializable
data class OperationView(
	val id: String,
	val name: String,
	val description: String? = null,
	val state: String,
	val plannedEndAt: String? = null,
	val activatedAt: String? = null,
	val closedAt: String? = null,
	val createdAt: String? = null,
	val coordinators: List<UserRef> = emptyList(),
	/** Yalnızca yönetim izdüşümünde (ADM, kendi operasyonunun KOR'u; LD-56). */
	val overdue: Boolean? = null,
	val pendingAssignmentCount: Int? = null,
)

/** OR-01 (`coordinatorIds` 1..10, tekil; boş isteğe bağlı alanlar gönderilmez). */
@Serializable
data class OperationCreateRequest(
	val name: String,
	val description: String? = null,
	val plannedEndAt: String? = null,
	val coordinatorIds: List<String>,
)

/** OR-03. */
@Serializable
data class OperationStateRequest(val targetState: String)

/** AC-01 rol ataması (yalnızca ADM, `role.assign`). */
@Serializable
data class RoleAssignmentCreateRequest(val userId: String, val role: String, val scopeType: String, val scopeId: String)

/** OR-06 `ParticipantView` (KOR izdüşümü). */
@Serializable
data class Participant(
	val user: ParticipantUser,
	val joinedAt: String? = null,
	val groups: List<GroupRef> = emptyList(),
	val openAssignmentCount: Int = 0,
)

@Serializable
data class ParticipantUser(val id: String, val displayName: String, val status: String? = null, val fullName: String? = null)

@Serializable
data class GroupRef(val id: String, val name: String)

// ---------------------------------------------------------------- groups

/** §8.2 `GroupSummary`. */
@Serializable
data class GroupSummary(
	val id: String,
	val name: String,
	val operationId: String,
	val operationName: String? = null,
	val leader: UserRef? = null,
	val memberCount: Int = 0,
	val archived: Boolean = false,
	val myRole: String? = null,
)

/** §8.2 `GroupView`. */
@Serializable
data class GroupView(
	val id: String,
	val name: String,
	val operationId: String,
	val operationName: String? = null,
	val leader: UserRef? = null,
	val memberCount: Int = 0,
	val archived: Boolean = false,
	val myRole: String? = null,
	val description: String? = null,
	val members: List<GroupMember> = emptyList(),
	val meetingPoint: MeetingPoint? = null,
)

@Serializable
data class GroupMember(val user: UserRef, val role: String, val joinedAt: String? = null)

/** GR-01: lider katılımcı olmalı; üyeler 0..49, tekil. */
@Serializable
data class GroupCreateRequest(
	val name: String,
	val description: String? = null,
	val leaderUserId: String,
	val memberUserIds: List<String> = emptyList(),
)

/** GR-04 / GR-06. */
@Serializable
data class GroupUserRequest(val userId: String)

@Serializable
data class MeetingPoint(
	val address: String? = null,
	val lat: Double? = null,
	val lon: Double? = null,
	val description: String? = null,
	val time: String? = null,
	val updatedBy: UserRef? = null,
	val updatedAt: String? = null,
)

/** GR-08: `address` (veya koordinat) ve `time` zorunlu; koordinat bu sürümde girilmez. */
@Serializable
data class MeetingPointRequest(val address: String, val description: String? = null, val time: String)

// ---------------------------------------------------------------- tasks

@Serializable
data class TimeWindow(val start: String? = null, val end: String? = null)

/** Gönüllünün bu görevdeki ataması (`myAssignment`). */
@Serializable
data class MyAssignmentRef(val id: String, val status: String, val respondBy: String? = null, val overdue: Boolean = false)

/** §9.3 `TaskSummary` (gönüllü izdüşümü). */
@Serializable
data class TaskSummary(
	val id: String,
	val operationId: String? = null,
	val operationName: String? = null,
	val title: String,
	val priority: String,
	val state: String,
	val acceptedCount: Int = 0,
	val capacity: Int? = null,
	val timeWindow: TimeWindow? = null,
	val createdAt: String? = null,
	val myAssignment: MyAssignmentRef? = null,
	/** Yalnızca KOR/ADM izdüşümünde (BOŞLUK-3). */
	val pendingCount: Int? = null,
	val overdueCount: Int? = null,
)

/** §9.3 `TaskView` (gönüllü izdüşümü: hedefler ve geçmiş gelmez). */
@Serializable
data class TaskView(
	val id: String,
	val title: String,
	val priority: String,
	val state: String,
	val acceptedCount: Int = 0,
	val capacity: Int? = null,
	val timeWindow: TimeWindow? = null,
	val myAssignment: MyAssignmentRef? = null,
	val description: String? = null,
	val meetingPoint: String? = null,
	val responseWindowMinutes: Int? = null,
	val createdBy: UserRef? = null,
	val publishedAt: String? = null,
	/** Yönetim izdüşümü (KOR/ADM): sürüm, hedefler, sayılar ve durum geçmişi (AC-15.2). */
	val version: Int? = null,
	val targets: TaskTargets? = null,
	val pendingCount: Int? = null,
	val overdueCount: Int? = null,
	val history: List<TaskStateChange> = emptyList(),
)

@Serializable
data class TaskTargets(val userIds: List<String> = emptyList(), val groupIds: List<String> = emptyList())

@Serializable
data class TaskStateChange(val state: String, val changedBy: UserRef? = null, val changedAt: String)

/** TK-01 (`task.manage`; idempotency "d"): boş isteğe bağlı alanlar gönderilmez. */
@Serializable
data class TaskCreateRequest(
	val title: String,
	val description: String? = null,
	val priority: String? = null,
	val meetingPoint: String? = null,
	val timeWindow: TimeWindow? = null,
	val targets: TaskTargets,
	val capacity: Int? = null,
	val responseWindowMinutes: Int? = null,
)

/** TK-03 (Z: Idempotency-Key zorunlu). */
@Serializable
data class TaskVersionRequest(val version: Int)

/** TK-09 `AssignmentView` (KOR/ADM). */
@Serializable
data class AssignmentView(
	val id: String,
	val taskId: String,
	val user: UserRef,
	val status: String,
	val respondBy: String? = null,
	val overdue: Boolean = false,
	val respondedAt: String? = null,
)

/** TK-10 `MyAssignmentView`. */
@Serializable
data class MyAssignmentView(
	val id: String,
	val status: String,
	val respondBy: String? = null,
	val overdue: Boolean = false,
	val task: TaskSummary,
)

// ---------------------------------------------------------------- notifications (NT-04/05/07/08, LD-60)

/** §11.3 `NotificationInboxItem`; `id` uygulama içi teslim kaydının kimliğidir. */
@Serializable
data class InboxItem(
	val id: String,
	val category: String,
	val kind: String,
	val priority: String,
	val title: String,
	val body: String? = null,
	val createdAt: String,
	val readAt: String? = null,
	val link: InboxLink? = null,
	val operationId: String? = null,
)

/** İstemci rotaya çevirir: `TASK`, `GROUP`, `INVITATION`, `OPERATION`, `ANNOUNCEMENT`. */
@Serializable
data class InboxLink(val type: String, val id: String)

@Serializable
data class UnreadCount(val unreadCount: Int)

@Serializable
data class FieldProblem(val field: String? = null, val code: String? = null, val message: String? = null)

// ---------------------------------------------------------------- duyurular (NT-01 – NT-03, NT-06)

/**
 * §11.3 `AnnouncementTargetRequest`. Kurum geneli (`organization = true`) başka hedefle birlikte gönderilmez;
 * operasyon hedefinde `userIds`/`groupIds` boşsa operasyonun tüm katılımcıları alıcıdır. Boş liste ve `null`
 * gönderilmez (backend fazla alanı reddeder; `GksJson.explicitNulls = false`).
 */
@Serializable
data class AnnouncementTarget(
	val operationId: String? = null,
	val userIds: List<String>? = null,
	val groupIds: List<String>? = null,
	val organization: Boolean? = null,
)

/** NT-02 gövdesi: hedef + başlık (3–120) + metin (1–2000) + 50'den fazla alıcıda `confirm`. */
@Serializable
data class AnnouncementCreateRequest(
	val operationId: String? = null,
	val userIds: List<String>? = null,
	val groupIds: List<String>? = null,
	val organization: Boolean? = null,
	val title: String,
	val body: String,
	val confirm: Boolean? = null,
)

/** NT-01 yanıtı. */
@Serializable
data class AnnouncementPreview(
	val recipientCount: Int = 0,
	val recipients: List<UserRef> = emptyList(),
	val confirmationRequired: Boolean = false,
)

@Serializable
data class AnnouncementDelivery(val sent: Int = 0, val failed: Int = 0, val read: Int = 0)

/** NT-02/NT-03/NT-06 `AnnouncementView`; listede `body` yoktur. */
@Serializable
data class AnnouncementView(
	val id: String,
	val title: String,
	val body: String? = null,
	val operationId: String? = null,
	val operationName: String? = null,
	val createdBy: UserRef? = null,
	val createdAt: String? = null,
	val recipientCount: Int = 0,
	val delivery: AnnouncementDelivery? = null,
)
