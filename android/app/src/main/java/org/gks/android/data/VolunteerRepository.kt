package org.gks.android.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.GroupView
import org.gks.android.data.remote.MeetingPoint
import org.gks.android.data.remote.MeetingPointRequest
import org.gks.android.data.remote.MyAssignmentView
import org.gks.android.data.remote.OperationView
import org.gks.android.data.remote.TaskSummary
import org.gks.android.data.remote.TaskView

/** Gönüllü / grup lideri okuma ve yanıt işlemleri: operasyon, grup ve görev. */
class VolunteerRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	// ---------------------------------------------------------------- operasyon

	suspend fun operation(id: String): ApiResult<OperationView> = caller.call { api.operation(id) }

	/** TK-07: bu operasyonda bana atanmış görevler. */
	suspend fun operationTasks(operationId: String): ApiResult<List<TaskSummary>> =
		caller.allPages { cursor -> api.operationTasks(operationId, cursor) }

	// ---------------------------------------------------------------- grup

	suspend fun groups(): ApiResult<List<GroupSummary>> = caller.allPages { cursor -> api.groups(cursor) }

	suspend fun group(id: String): ApiResult<GroupView> = caller.call { api.group(id) }

	/** GR-07: lider yalnızca açıklamayı değiştirir; boş metin açıklamayı temizler (`null`, LD-52). */
	suspend fun updateGroupDescription(id: String, description: String): ApiResult<GroupView> {
		val value = description.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
		return caller.call { api.updateGroup(id, JsonObject(mapOf("description" to value))) }
	}

	/** GR-08. */
	suspend fun updateMeetingPoint(id: String, request: MeetingPointRequest): ApiResult<MeetingPoint> =
		caller.call { api.updateMeetingPoint(id, request) }

	// ---------------------------------------------------------------- görev

	/** TK-10. */
	suspend fun myAssignments(): ApiResult<List<MyAssignmentView>> = caller.allPages { cursor -> api.myAssignments(cursor) }

	suspend fun task(id: String): ApiResult<TaskView> = caller.call { api.task(id) }

	/** TK-11 / TK-12: zorunlu `Idempotency-Key`; ağ hatasında aynı anahtarla tekrarlanır. */
	suspend fun respondToAssignment(assignmentId: String, accept: Boolean): ApiResult<Unit> =
		keys.run("assignment:$assignmentId:$accept") { key ->
			caller.call { if (accept) api.acceptAssignment(assignmentId, key) else api.rejectAssignment(assignmentId, key) }
		}
}
