package org.gks.android.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Bu başlık AuthInterceptor'a "Authorization ekleme" der ve ağa gitmeden silinir. */
const val NO_AUTH_HEADER = "X-Gks-No-Auth"
const val IDEMPOTENCY_HEADER = "Idempotency-Key"
const val PAGE_LIMIT = 100
const val INBOX_PAGE = 30

/**
 * Oturumlu istemci (Bearer + otomatik yenileme). Gönüllü uçları ve ADR-014 sonrası MFA'lı Android yönetim
 * oturumunda kullanılan yönetim uçları tanımlıdır; MFA'sız oturumda yönetim uçları `403 CLIENT_NOT_ALLOWED` döner.
 */
interface GksApi {

	// ------------------------------------------------------------ identity

	/** ID-02 (public). */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/auth/login")
	suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

	/** ID-03 (public; MFA bileti): TOTP kurulumu, gizli anahtar bir kez döner. */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/auth/mfa/enroll")
	suspend fun enrollMfa(@Body request: MfaTicketRequest): Response<MfaEnrollment>

	/** ID-04 (public; MFA bileti): doğru kod → MFA'lı Android yönetim oturumu (12 sa mutlak, ADR-014). */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/auth/mfa")
	suspend fun verifyMfa(@Body request: MfaVerifyRequest): Response<LoginResponse>

	/** ID-06: bu oturumu ve bağlı cihazı sunucuda sonlandırır. */
	@POST("api/v1/auth/logout")
	suspend fun logout(): Response<Unit>

	/** ID-06, kayıtta otomatik açılan oturumu kapatmak için: belirteç depoya yazılmadan doğrudan verilir. */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/auth/logout")
	suspend fun logoutWith(@Header("Authorization") bearer: String): Response<Unit>

	/** ID-09. */
	@GET("api/v1/me")
	suspend fun me(): Response<MeProfile>

	/** ID-10: kısmi; `phone: null` telefonu siler (LD-52). */
	@PATCH("api/v1/me")
	suspend fun updateProfile(@Body body: JsonObject): Response<MeProfile>

	/** ID-08: yanıt yeni Android belirteç çiftidir; eski oturum (bu dahil) iptal edilmiştir. */
	@POST("api/v1/me/password")
	suspend fun changePassword(@Body request: PasswordChangeRequest): Response<TokenResponse>

	/** ID-11. */
	@GET("api/v1/me/context")
	suspend fun meContext(): Response<MeContext>

	/** ID-15 (public; LD-37: kod gövdede). */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/invitations/lookup")
	suspend fun lookupInvitation(@Body request: CodeRequest): Response<InvitationPublicView>

	/** ID-16 (public; Idempotency-Key zorunlu, yanıt saklanmaz). */
	@Headers("$NO_AUTH_HEADER: true")
	@POST("api/v1/invitations/accept")
	suspend fun acceptInvitationCode(
		@Header(IDEMPOTENCY_HEADER) key: String,
		@Body request: InvitationAcceptRequest,
	): Response<LoginResponse>

	/** ID-17 (Android; Idempotency-Key zorunlu). */
	@POST("api/v1/invitations/{id}/accept")
	suspend fun acceptOperationInvitation(@Path("id") id: String, @Header(IDEMPOTENCY_HEADER) key: String): Response<Unit>

	/** ID-18 (Android; Idempotency-Key zorunlu). */
	@POST("api/v1/invitations/{id}/decline")
	suspend fun declineOperationInvitation(@Path("id") id: String, @Header(IDEMPOTENCY_HEADER) key: String): Response<Unit>

	// ------------------------------------------------------------ notifications (uygulama içi gelen kutusu)

	/** NT-04: en yeni önce. */
	@GET("api/v1/me/notifications")
	suspend fun inbox(@Query("cursor") cursor: String?, @Query("limit") limit: Int = INBOX_PAGE): Response<Page<InboxItem>>

	/** ADR-024: erişilebilir kanallar (okunmamış sayılarıyla). */
	@GET("api/v1/channels")
	suspend fun channels(): Response<List<ChannelView>>

	@GET("api/v1/channels/{id}/messages")
	suspend fun channelMessages(@Path("id") channelId: String, @Query("limit") limit: Int, @Query("cursor") cursor: String? = null): Response<Page<MessageView>>

	/** Çevrimdışı kuyruk aynı anahtarla yeniden gönderir; sunucu aynı mesajı döndürür. */
	@POST("api/v1/channels/{id}/messages")
	suspend fun sendMessage(@Path("id") channelId: String, @Header(IDEMPOTENCY_HEADER) key: String, @Body body: SendMessageRequest): Response<MessageView>

	@DELETE("api/v1/channels/{id}/messages/{messageId}")
	suspend fun deleteMessage(@Path("id") channelId: String, @Path("messageId") messageId: String): Response<MessageView>

	@POST("api/v1/channels/{id}/read")
	suspend fun markChannelRead(@Path("id") channelId: String, @Body body: ReadRequest): Response<Unit>

	@PUT("api/v1/channels/{id}/mute")
	suspend fun muteChannel(@Path("id") channelId: String, @Body body: MuteRequest): Response<Unit>

	/** ADR-023: kamu duyuruları (kimlik doğrulamasız uç; yalnızca yayındakiler). */
	@GET("api/v1/public/announcements")
	suspend fun publicAnnouncements(@Query("limit") limit: Int, @Query("cursor") cursor: String? = null): Response<Page<PublicAnnouncement>>

	/** NT-07 (LD-60): ayrıntı; okundu işaretlemez. */
	@GET("api/v1/me/notifications/{id}")
	suspend fun inboxItem(@Path("id") id: String): Response<InboxItem>

	/** NT-05: ilk okumada `read_at` dolar; tekrar zararsız. */
	@POST("api/v1/me/notifications/{id}/read")
	suspend fun markRead(@Path("id") id: String): Response<Unit>

	/** NT-08 (LD-60): yoklama ucu. */
	@GET("api/v1/me/notifications/unread-count")
	suspend fun unreadCount(): Response<UnreadCount>

	// ------------------------------------------------------------ organization

	/** OR-05. */
	@GET("api/v1/operations/{id}")
	suspend fun operation(@Path("id") id: String): Response<OperationView>

	// Yönetim uçları (ADR-014): web veya MFA'lı Android yönetim oturumu; yetki her istekte sunucuda.

	/** OR-04: ADM tümü, KOR koordine ettikleri (yönetim oturumunda). */
	@GET("api/v1/operations")
	suspend fun operations(
		@Query("state") states: List<String>?,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<OperationView>>

	/** OR-01 (`operation.create`, yalnızca ADM; idempotency "d"). */
	@POST("api/v1/operations")
	suspend fun createOperation(@Header(IDEMPOTENCY_HEADER) key: String, @Body request: OperationCreateRequest): Response<OperationView>

	/** OR-02 (`operation.manage`): kısmi; `null` isteğe bağlı alanı temizler (LD-52). */
	@PATCH("api/v1/operations/{id}")
	suspend fun updateOperation(@Path("id") id: String, @Body body: JsonObject): Response<OperationView>

	/** OR-03 (`operation.manage`, §7.2 geçiş tablosu). */
	@POST("api/v1/operations/{id}/state")
	suspend fun changeOperationState(@Path("id") id: String, @Body request: OperationStateRequest): Response<OperationView>

	/** OR-06 (ADM, kendi operasyonunun KOR'u). */
	@GET("api/v1/operations/{id}/participants")
	suspend fun participants(
		@Path("id") operationId: String,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
		/** Grup ekleme seçicisi: bu grubun üyesi olmayanlar (OR-06 `notInGroup`). */
		@Query("notInGroup") notInGroup: String? = null,
	): Response<Page<Participant>>

	/** ID-12 (ADM kurum/her operasyon, KOR kendi operasyonu; Z). */
	@POST("api/v1/invitations")
	suspend fun createInvitation(@Header(IDEMPOTENCY_HEADER) key: String, @Body request: InvitationCreateRequest): Response<PendingInvitation>

	/** ID-13: oluşturan kapsamın davetleri. */
	@GET("api/v1/invitations")
	suspend fun invitations(
		@Query("scopeType") scopeType: String,
		@Query("scopeId") scopeId: String,
		@Query("status") status: List<String>?,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<PendingInvitation>>

	/** ID-14: yalnızca `PENDING`. */
	@POST("api/v1/invitations/{id}/revoke")
	suspend fun revokeInvitation(@Path("id") id: String): Response<PendingInvitation>

	/** AC-03 (yalnızca ADM). */
	@GET("api/v1/role-assignments")
	suspend fun roleAssignments(
		@Query("scopeId") scopeId: String?,
		@Query("role") role: String?,
		@Query("active") active: Boolean?,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<RoleAssignment>>

	/** AC-01 (yalnızca ADM). */
	@POST("api/v1/role-assignments")
	suspend fun grantRole(@Body request: RoleAssignmentCreateRequest): Response<RoleAssignment>

	/** AC-02 (yalnızca ADM). */
	@DELETE("api/v1/role-assignments/{id}")
	suspend fun revokeRole(@Path("id") id: String): Response<Unit>

	// ------------------------------------------------------------ groups

	/** GR-02: aktif üyelikler (LDR/GON). */
	@GET("api/v1/groups")
	suspend fun groups(
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
		/** Operasyonun grupları (KOR kendi operasyonu, ADM salt-okur; BOŞLUK-8). */
		@Query("operationId") operationId: String? = null,
	): Response<Page<GroupSummary>>

	/** GR-01 (KOR kendi operasyonu; ADM yalnızca KOR olarak). */
	@POST("api/v1/operations/{id}/groups")
	suspend fun createGroup(@Path("id") operationId: String, @Body request: GroupCreateRequest): Response<GroupView>

	/** GR-04 (KOR). */
	@POST("api/v1/groups/{id}/members")
	suspend fun addGroupMember(@Path("id") groupId: String, @Body request: GroupUserRequest): Response<GroupMember>

	/** GR-05 (KOR): aktif lider çıkarılamaz (`409 LEADER_REQUIRED`). */
	@DELETE("api/v1/groups/{id}/members/{userId}")
	suspend fun removeGroupMember(@Path("id") groupId: String, @Path("userId") userId: String): Response<Unit>

	/** GR-06 (KOR): eski lider üye olur. */
	@PUT("api/v1/groups/{id}/leader")
	suspend fun changeGroupLeader(@Path("id") groupId: String, @Body request: GroupUserRequest): Response<GroupView>

	/** GR-03. */
	@GET("api/v1/groups/{id}")
	suspend fun group(@Path("id") id: String): Response<GroupView>

	/** GR-07 (LEADER: yalnızca `description`; `null` temizler, LD-52). */
	@PATCH("api/v1/groups/{id}")
	suspend fun updateGroup(@Path("id") id: String, @Body body: JsonObject): Response<GroupView>

	/** GR-08 (LEADER kendi grubu). */
	@PUT("api/v1/groups/{id}/meeting-point")
	suspend fun updateMeetingPoint(@Path("id") id: String, @Body request: MeetingPointRequest): Response<MeetingPoint>

	// ------------------------------------------------------------ tasks

	/** TK-10: tüm durumlar (LD-53 (9)). */
	@GET("api/v1/me/assignments")
	suspend fun myAssignments(
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<MyAssignmentView>>

	/** TK-07: gönüllü yalnızca kendisine atanmış görevleri görür. */
	@GET("api/v1/operations/{id}/tasks")
	suspend fun operationTasks(
		@Path("id") operationId: String,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<TaskSummary>>

	/** TK-01 (`task.manage`, KOR kendi operasyonu). */
	@POST("api/v1/operations/{id}/tasks")
	suspend fun createTask(
		@Path("id") operationId: String,
		@Header(IDEMPOTENCY_HEADER) key: String,
		@Body request: TaskCreateRequest,
	): Response<TaskView>

	/** TK-02: kısmi + `version`; `targets` yalnızca DRAFT; `null` isteğe bağlı alanı temizler/varsayılana döndürür (LD-52). */
	@PATCH("api/v1/tasks/{id}")
	suspend fun updateTask(@Path("id") id: String, @Body body: JsonObject): Response<TaskView>

	/** TK-03 (Z): DRAFT → PUBLISHED, atama anlık görüntüsü. */
	@POST("api/v1/tasks/{id}/publish")
	suspend fun publishTask(@Path("id") id: String, @Header(IDEMPOTENCY_HEADER) key: String, @Body request: TaskVersionRequest): Response<TaskView>

	/** TK-04 (§9.2 geçiş tablosu; `PUBLISHED` bu uçtan istenemez). */
	@POST("api/v1/tasks/{id}/state")
	suspend fun changeTaskState(@Path("id") id: String, @Body request: OperationStateRequest): Response<TaskView>

	/** TK-05 (görev PUBLISHED/IN_PROGRESS, kişi katılımcı). */
	@POST("api/v1/tasks/{id}/assignments")
	suspend fun addAssignment(@Path("id") taskId: String, @Header(IDEMPOTENCY_HEADER) key: String, @Body request: GroupUserRequest): Response<AssignmentView>

	/** TK-06: atama `REMOVED`. */
	@DELETE("api/v1/tasks/{id}/assignments/{assignmentId}")
	suspend fun removeAssignment(@Path("id") taskId: String, @Path("assignmentId") assignmentId: String): Response<Unit>

	/** TK-09 (KOR, ADM salt-okur). */
	@GET("api/v1/tasks/{id}/assignments")
	suspend fun taskAssignments(
		@Path("id") taskId: String,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<AssignmentView>>

	/** TK-08. */
	@GET("api/v1/tasks/{id}")
	suspend fun task(@Path("id") id: String): Response<TaskView>

	/** TK-11 (Android; Idempotency-Key zorunlu; gövde isteğe bağlı). */
	@POST("api/v1/assignments/{id}/accept")
	suspend fun acceptAssignment(@Path("id") id: String, @Header(IDEMPOTENCY_HEADER) key: String): Response<Unit>

	/** TK-12 (Android; Idempotency-Key zorunlu; gövde isteğe bağlı). */
	@POST("api/v1/assignments/{id}/reject")
	suspend fun rejectAssignment(@Path("id") id: String, @Header(IDEMPOTENCY_HEADER) key: String): Response<Unit>

	// ------------------------------------------------------------ duyurular (NT-01 – NT-03, NT-06; MFA'lı yönetim oturumu)

	/** NT-01: yan etkisiz alıcı önizlemesi. */
	@POST("api/v1/announcements/preview")
	suspend fun previewAnnouncement(@Body target: AnnouncementTarget): Response<AnnouncementPreview>

	/** NT-02 (`Z`: Idempotency-Key zorunlu) → `202`. */
	@POST("api/v1/announcements")
	suspend fun createAnnouncement(@Header(IDEMPOTENCY_HEADER) key: String, @Body request: AnnouncementCreateRequest): Response<AnnouncementView>

	/** NT-03: gönderen kapsamındaki duyurular, en yeni önce (`body` yok). */
	@GET("api/v1/announcements")
	suspend fun announcements(
		@Query("operationId") operationId: String?,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<AnnouncementView>>

	/** NT-06 (`body` dahil). */
	@GET("api/v1/announcements/{id}")
	suspend fun announcement(@Path("id") id: String): Response<AnnouncementView>

	// ------------------------------------------------------------ konum (LC-01 – LC-04)

	/** LC-01: açık başlatma; gövde isteğe bağlı (`UNAVAILABLE` + neden). Zaten paylaşılıyorsa aynı yanıt. */
	@POST("api/v1/operations/{id}/location-sharing")
	suspend fun startLocationSharing(@Path("id") operationId: String, @Body request: LocationSharingStartRequest): Response<LocationSharingView>

	/** LC-02: paylaşım yoksa da `204`. */
	@DELETE("api/v1/operations/{id}/location-sharing")
	suspend fun stopLocationSharing(@Path("id") operationId: String): Response<Unit>

	/** LC-03: tek ölçüm; `409 LOCATION_SHARING_STOPPED` / `OPERATION_NOT_ACTIVE` → servis durur (TD-18). */
	@POST("api/v1/operations/{id}/locations")
	suspend fun sendLocation(@Path("id") operationId: String, @Body request: LocationMeasurementRequest): Response<Unit>

	/** LC-04. */
	@GET("api/v1/me/location-sharing")
	suspend fun myLocationSharings(): Response<List<LocationSharingView>>

	// ------------------------------------------------------------ cihaz (ID-26)

	/** FCM belirtecini kaydeder / yeniler; kayıt bu oturuma bağlanır (çıkışta sunucu pasifleştirir). */
	@PUT("api/v1/devices/{installationId}")
	suspend fun registerDevice(@Path("installationId") installationId: String, @Body request: DeviceRegisterRequest): Response<DeviceView>

	// ------------------------------------------------------------ acil alarm (alıcı)

	/** AL-08: alıcısı olunan alarmlar (varsayılan `OPEN`); `state` virgüllü. */
	@GET("api/v1/me/alerts")
	suspend fun myAlerts(
		@Query("state") state: String?,
		@Query("cursor") cursor: String?,
		@Query("limit") limit: Int = PAGE_LIMIT,
	): Response<Page<MyAlertView>>

	/** AL-07: alıcıya `MyAlertView` döner (sayılar ve diğer alıcılar yok). */
	@GET("api/v1/alerts/{id}")
	suspend fun myAlert(@Path("id") id: String): Response<MyAlertView>

	/** AL-13: uygulama alarmı aldı; yalnızca ileri geçiş, etkisiz tekrar `204`. */
	@POST("api/v1/alert-recipients/{id}/delivered")
	suspend fun alertDelivered(@Path("id") recipientId: String, @Body request: AlertSignalRequest): Response<Unit>

	/** AL-14: alarm ekranı açıldı. */
	@POST("api/v1/alert-recipients/{id}/seen")
	suspend fun alertSeen(@Path("id") recipientId: String, @Body request: AlertSignalRequest): Response<Unit>

	/** AL-15 (Idempotency-Key zorunlu): yanıt veya yanıt değişikliği (TD-07). */
	@POST("api/v1/alert-recipients/{id}/responses")
	suspend fun respondToAlert(
		@Path("id") recipientId: String,
		@Header(IDEMPOTENCY_HEADER) key: String,
		@Body request: AlertResponseRequest,
	): Response<MyAlertView>
}

/** Yalnızca yenileme; kimlik doğrulayıcı (Authenticator) içinde eşzamanlı çağrılır, kendisi yenileme tetiklemez. */
interface RefreshApi {

	/** ID-05. */
	@POST("api/v1/auth/refresh")
	fun refresh(@Body request: RefreshRequest): Call<TokenResponse>
}
