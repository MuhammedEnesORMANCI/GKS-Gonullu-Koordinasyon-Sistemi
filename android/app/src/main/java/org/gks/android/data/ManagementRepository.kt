package org.gks.android.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gks.android.data.remote.AddParticipantsRequest
import org.gks.android.data.remote.AddedParticipants
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.GroupCreateRequest
import org.gks.android.data.remote.GroupMember
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.GroupUserRequest
import org.gks.android.data.remote.GroupView
import org.gks.android.data.remote.InvitationCreateRequest
import org.gks.android.data.remote.LocationListView
import org.gks.android.data.remote.MapOperationView
import org.gks.android.data.remote.OperationArea
import org.gks.android.data.remote.OperationCreateRequest
import org.gks.android.data.remote.OperationStateRequest
import org.gks.android.data.remote.OperationView
import org.gks.android.data.remote.Page
import org.gks.android.data.remote.Participant
import org.gks.android.data.remote.PendingInvitation
import org.gks.android.data.remote.PersonCandidate
import org.gks.android.data.remote.RoleAssignment
import org.gks.android.data.remote.RoleAssignmentCreateRequest

/** Operasyon formu; `plannedEndAt` ISO-8601 (UTC) veya boş. Boş isteğe bağlı alanlar temizlenir (LD-52). */
data class OperationDraft(val name: String, val description: String, val plannedEndAt: String?, val area: OperationArea? = null)

/**
 * ADM/KOR operasyon yönetimi (OR-01 – OR-06, AC-01 – AC-03). Web paneliyle aynı uçlar; Android'de yalnızca
 * MFA'lı yönetim oturumunda çalışır (ADR-014). Yetki kararı her istekte sunucudadır.
 */
class ManagementRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	suspend fun operations(states: List<String>? = null): ApiResult<List<OperationView>> =
		caller.allPages { cursor -> api.operations(states, cursor) }

	suspend fun operation(id: String): ApiResult<OperationView> = caller.call { api.operation(id) }

	/** OR-01: ağ hatasında aynı istek aynı anahtarla tekrarlanır (ikinci operasyon oluşmaz). */
	suspend fun create(draft: OperationDraft, coordinatorIds: List<String>): ApiResult<OperationView> {
		val request = OperationCreateRequest(
			name = draft.name.trim(),
			description = draft.description.trim().ifEmpty { null },
			plannedEndAt = draft.plannedEndAt,
			coordinatorIds = coordinatorIds,
			area = draft.area,
		)
		return keys.run("operation:create:${request.hashCode()}") { key -> caller.call { api.createOperation(key, request) } }
	}

	/** OR-02: yalnızca değişen alanlar; boş açıklama veya bitiş `null` gider (temizler). */
	suspend fun update(current: OperationView, draft: OperationDraft): ApiResult<OperationView> {
		val changes = buildMap {
			draft.name.trim().takeIf { it != current.name }?.let { put("name", JsonPrimitive(it)) }
			val description = draft.description.trim().ifEmpty { null }
			if (description != current.description) put("description", description?.let(::JsonPrimitive) ?: JsonNull)
			if (draft.plannedEndAt != current.plannedEndAt) put("plannedEndAt", draft.plannedEndAt?.let(::JsonPrimitive) ?: JsonNull)
			if (draft.area != current.area) put("area", draft.area?.let { a ->
				JsonObject(buildMap {
					put("lat", JsonPrimitive(a.lat))
					put("lon", JsonPrimitive(a.lon))
					a.radiusM?.let { put("radiusM", JsonPrimitive(it)) }
				})
			} ?: JsonNull)
		}
		if (changes.isEmpty()) return ApiResult.Success(current)
		return caller.call { api.updateOperation(current.id, JsonObject(changes)) }
	}

	/** ADR-025 harita. */
	suspend fun mapOperations(includePlanned: Boolean): ApiResult<List<MapOperationView>> =
		caller.call { api.mapOperations(if (includePlanned) listOf("ACTIVE", "PLANNED") else listOf("ACTIVE")) }

	suspend fun mapOperation(id: String): ApiResult<MapOperationView> = caller.call { api.mapOperation(id) }

	/** Son konumlar; her çağrı sunucuda denetime yazılır (yalnızca kullanıcı isteyince). */
	suspend fun lastLocations(id: String): ApiResult<LocationListView> = caller.call { api.operationLocations(id) }

	/** OR-03: hedef durum; geçiş kuralı ve yetki sunucuda (§7.2). */
	suspend fun changeState(id: String, target: String): ApiResult<OperationView> =
		caller.call { api.changeOperationState(id, OperationStateRequest(target)) }

	/** AC-03: bu operasyonun aktif KOR atamaları (yalnızca ADM). */
	suspend fun coordinators(operationId: String): ApiResult<List<RoleAssignment>> =
		caller.allPages { cursor -> api.roleAssignments(operationId, "KOR", true, cursor) }

	/**
	 * Koordinatör adayları (yalnızca ADM): kullanıcı arama ucu (ID-19) henüz yok. Adaylar bu operasyonun
	 * katılımcıları (OR-06) ile başka operasyonlarda KOR olan kişilerdir; ikisi de gerçek API verisi.
	 */
	suspend fun coordinatorCandidates(operationId: String?): ApiResult<List<Candidate>> {
		val result = linkedMapOf<String, Candidate>()
		if (operationId != null) {
			when (val participants = caller.allPages { cursor -> api.participants(operationId, cursor) }) {
				is ApiResult.Failure -> return participants
				is ApiResult.Success -> participants.value.forEach { p ->
					result[p.user.id] = Candidate(p.user.id, p.user.displayName, Candidate.Source.PARTICIPANT)
				}
			}
		}
		when (val coordinators = caller.allPages { cursor -> api.roleAssignments(null, "KOR", true, cursor) }) {
			is ApiResult.Failure -> return coordinators
			is ApiResult.Success -> coordinators.value.forEach { a ->
				val id = a.user?.id ?: return@forEach
				result.putIfAbsent(id, Candidate(id, a.user.displayName, Candidate.Source.COORDINATOR_ELSEWHERE))
			}
		}
		return ApiResult.Success(result.values.sortedBy { it.displayName.lowercase() })
	}

	/** AC-01: kişiyi bu operasyonun KOR'u yapar (yalnızca ADM). */
	suspend fun grantCoordinator(operationId: String, userId: String): ApiResult<RoleAssignment> =
		caller.call { api.grantRole(RoleAssignmentCreateRequest(userId, "KOR", "OPERATION", operationId)) }

	/** AC-02. */
	suspend fun revoke(assignmentId: String): ApiResult<Unit> = caller.call { api.revokeRole(assignmentId) }

	// ---------------------------------------------------------------- gönüllüler (OR-06, ID-12 – ID-14)

	/** OR-06: operasyonun katılımcıları (GON), grupları ve açık atama sayıları; `notInGroup` grup ekleme seçicisi. */
	suspend fun participants(operationId: String, notInGroup: String? = null): ApiResult<List<Participant>> =
		caller.allPages { cursor -> api.participants(operationId, cursor, notInGroup = notInGroup) }

	/**
	 * Lider/üye/görev hedefi seçicileri: tüm sayfalar, yalnızca aktif hesaplar (RAPOR_7 madde 1). Devre dışı
	 * hesap katılımcı kaydı dursa da sunucuda reddedilir; seçicide gösterilmez. Sıra: ada göre.
	 */
	suspend fun participantCandidates(operationId: String, notInGroup: String? = null): ApiResult<List<Participant>> =
		when (val result = participants(operationId, notInGroup)) {
			is ApiResult.Success -> ApiResult.Success(result.value
				.filter { it.user.status == null || it.user.status == "ACTIVE" }
				.sortedBy { it.user.displayName.lowercase() })
			is ApiResult.Failure -> result
		}

	// ---------------------------------------------------------------- gruplar (GR-01 – GR-07)

	suspend fun groups(operationId: String): ApiResult<List<GroupSummary>> =
		caller.allPages { cursor -> api.groups(cursor, operationId = operationId) }

	suspend fun createGroup(operationId: String, name: String, description: String, leaderId: String, memberIds: List<String>): ApiResult<GroupView> =
		caller.call {
			api.createGroup(operationId, GroupCreateRequest(name.trim(), description.trim().ifEmpty { null }, leaderId, memberIds.filter { it != leaderId }))
		}

	suspend fun addGroupMember(groupId: String, userId: String): ApiResult<GroupMember> =
		caller.call { api.addGroupMember(groupId, GroupUserRequest(userId)) }

	suspend fun removeGroupMember(groupId: String, userId: String): ApiResult<Unit> =
		caller.call { api.removeGroupMember(groupId, userId) }

	suspend fun changeGroupLeader(groupId: String, userId: String): ApiResult<GroupView> =
		caller.call { api.changeGroupLeader(groupId, GroupUserRequest(userId)) }

	/** GR-07 (KOR): ad ve açıklama; boş açıklama `null` (temizler). */
	suspend fun renameGroup(groupId: String, name: String, description: String): ApiResult<GroupView> {
		val body = JsonObject(mapOf(
			"name" to JsonPrimitive(name.trim()),
			"description" to (description.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull),
		))
		return caller.call { api.updateGroup(groupId, body) }
	}

	/** ID-12: kayıtlı kişiye operasyon daveti (uygulama içi bildirim + e-posta), yeni kişiye kodlu davet e-postası. */
	/** RAPOR_7 madde 4: doğrudan ekleme adayları (sunucuda arama + sayfalama; boş arama gönderilmez). */
	suspend fun directCandidates(operationId: String, query: String, cursor: String? = null): ApiResult<Page<PersonCandidate>> =
		caller.call { api.participantCandidates(operationId, query.trim().ifEmpty { null }, cursor) }

	/** RAPOR_7 madde 4: aynı seçim tekrarında aynı Idempotency-Key. */
	suspend fun addParticipants(operationId: String, userIds: List<String>): ApiResult<AddedParticipants> {
		val request = AddParticipantsRequest(userIds)
		return keys.run("participants:add:$operationId:${userIds.sorted()}") { key -> caller.call { api.addParticipants(key, operationId, request) } }
	}

	suspend fun invite(operationId: String, email: String): ApiResult<PendingInvitation> {
		val request = InvitationCreateRequest(email.trim().lowercase(), "OPERATION", operationId)
		return keys.run("invite:$operationId:${request.email}") { key -> caller.call { api.createInvitation(key, request) } }
	}

	/** ID-13: bu operasyonun bekleyen davetleri. */
	suspend fun pendingInvitations(operationId: String): ApiResult<List<PendingInvitation>> =
		caller.allPages { cursor -> api.invitations("OPERATION", operationId, listOf("PENDING"), cursor) }

	/** ID-14. */
	suspend fun revokeInvitation(id: String): ApiResult<PendingInvitation> = caller.call { api.revokeInvitation(id) }

	data class Candidate(val id: String, val displayName: String, val source: Source) {
		enum class Source { PARTICIPANT, COORDINATOR_ELSEWHERE }
	}
}
