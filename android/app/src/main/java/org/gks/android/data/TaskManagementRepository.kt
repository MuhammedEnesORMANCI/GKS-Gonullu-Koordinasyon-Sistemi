package org.gks.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gks.android.data.remote.AssignmentView
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.GroupUserRequest
import org.gks.android.data.remote.OperationStateRequest
import org.gks.android.data.remote.TaskCreateRequest
import org.gks.android.data.remote.TaskSummary
import org.gks.android.data.remote.TaskTargets
import org.gks.android.data.remote.TaskVersionRequest
import org.gks.android.data.remote.TaskView
import org.gks.android.data.remote.TimeWindow

/** Görev formu; zamanlar ISO-8601 (UTC) veya boş; `null` sayısal alan = sınırsız / varsayılan. */
data class TaskDraft(
	val title: String,
	val description: String,
	val priority: String,
	val meetingPoint: String,
	val start: String?,
	val end: String?,
	val capacity: Int?,
	val responseWindowMinutes: Int?,
	val userIds: List<String>,
	val groupIds: List<String>,
)

/**
 * KOR görev yönetimi (TK-01 – TK-09). Web paneliyle aynı uçlar; Android'de yalnızca MFA'lı yönetim oturumunda
 * (ADR-014). Yetki (`task.manage`, kapsam) ve durum kuralları (§9.2) sunucudadır.
 */
class TaskManagementRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	/** TK-07: KOR tüm görevler, ADM salt-okur. */
	suspend fun tasks(operationId: String): ApiResult<List<TaskSummary>> =
		caller.allPages { cursor -> api.operationTasks(operationId, cursor) }

	suspend fun task(id: String): ApiResult<TaskView> = caller.call { api.task(id) }

	/** TK-01: taslak (`DRAFT`); ağ hatasında aynı anahtarla tekrar (ikinci görev oluşmaz). */
	suspend fun create(operationId: String, draft: TaskDraft): ApiResult<TaskView> {
		val request = TaskCreateRequest(
			title = draft.title.trim(),
			description = draft.description.trim().ifEmpty { null },
			priority = draft.priority,
			meetingPoint = draft.meetingPoint.trim().ifEmpty { null },
			timeWindow = draft.start?.let { TimeWindow(it, draft.end) },
			targets = TaskTargets(draft.userIds, draft.groupIds),
			capacity = draft.capacity,
			responseWindowMinutes = draft.responseWindowMinutes,
		)
		return keys.run("task:create:$operationId:${request.hashCode()}") { key -> caller.call { api.createTask(operationId, key, request) } }
	}

	/**
	 * TK-02: yalnızca değişen alanlar + `version`. `targets` yalnızca taslakta gönderilir. Boş isteğe bağlı alan `null`
	 * (temizler; öncelik ve yanıt süresi varsayılana döner, LD-52).
	 */
	suspend fun update(current: TaskView, draft: TaskDraft): ApiResult<TaskView> {
		val changes = linkedMapOf<String, JsonElement>()
		fun text(value: String) = value.trim().ifEmpty { null }
		fun put(field: String, value: String?, old: String?) {
			if (value != old) changes[field] = value?.let(::JsonPrimitive) ?: JsonNull
		}
		put("title", draft.title.trim(), current.title)
		put("description", text(draft.description), current.description)
		put("priority", draft.priority, current.priority)
		put("meetingPoint", text(draft.meetingPoint), current.meetingPoint)
		if (draft.start != current.timeWindow?.start || draft.end != current.timeWindow?.end) {
			changes["timeWindow"] = draft.start?.let { start ->
				JsonObject(buildMap {
					put("start", JsonPrimitive(start))
					draft.end?.let { put("end", JsonPrimitive(it)) }
				})
			} ?: JsonNull
		}
		if (draft.capacity != current.capacity) changes["capacity"] = draft.capacity?.let(::JsonPrimitive) ?: JsonNull
		if (draft.responseWindowMinutes != current.responseWindowMinutes) {
			changes["responseWindowMinutes"] = draft.responseWindowMinutes?.let(::JsonPrimitive) ?: JsonNull
		}
		val targets = current.targets
		if (current.state == "DRAFT" && targets != null &&
			(draft.userIds.toSet() != targets.userIds.toSet() || draft.groupIds.toSet() != targets.groupIds.toSet())
		) {
			changes["targets"] = JsonObject(mapOf(
				"userIds" to JsonArray(draft.userIds.map(::JsonPrimitive)),
				"groupIds" to JsonArray(draft.groupIds.map(::JsonPrimitive)),
			))
		}
		if (changes.isEmpty()) return ApiResult.Success(current)
		val version = current.version ?: return ApiResult.Failure(ApiError.Unexpected)
		changes["version"] = JsonPrimitive(version)
		return caller.call { api.updateTask(current.id, JsonObject(changes)) }
	}

	/** TK-03 (Z): anahtar görev + sürümden türer; aynı yayın iki kez uygulanmaz. */
	suspend fun publish(id: String, version: Int): ApiResult<TaskView> =
		keys.run("task:publish:$id:$version") { key -> caller.call { api.publishTask(id, key, TaskVersionRequest(version)) } }

	/** TK-04: `IN_PROGRESS`, `DONE`, `CANCELLED`. */
	suspend fun changeState(id: String, target: String): ApiResult<TaskView> =
		caller.call { api.changeTaskState(id, OperationStateRequest(target)) }

	/** TK-09. */
	suspend fun assignments(taskId: String): ApiResult<List<AssignmentView>> =
		caller.allPages { cursor -> api.taskAssignments(taskId, cursor) }

	/** TK-05: mevcut REMOVED/REJECTED/CAPACITY_FULL atama yeniden PENDING olur. */
	suspend fun addAssignment(taskId: String, userId: String): ApiResult<AssignmentView> =
		keys.run("task:assign:$taskId:$userId") { key -> caller.call { api.addAssignment(taskId, key, GroupUserRequest(userId)) } }

	/** TK-06. */
	suspend fun removeAssignment(taskId: String, assignmentId: String): ApiResult<Unit> =
		caller.call { api.removeAssignment(taskId, assignmentId) }
}

/** §9.2 TK-04 geçişleri (yayın TK-03 ile ayrıdır). İstemci yalnızca düğme seçimi için kullanır. */
fun nextTaskStates(state: String): List<String> = when (state) {
	"DRAFT" -> listOf("CANCELLED")
	"PUBLISHED" -> listOf("IN_PROGRESS", "CANCELLED")
	"IN_PROGRESS" -> listOf("DONE", "CANCELLED")
	else -> emptyList()
}
