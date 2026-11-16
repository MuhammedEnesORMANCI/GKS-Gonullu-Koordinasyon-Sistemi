package org.gks.android.alarm

/** Bir alarmın cihazdaki durumu (ADR-022). */
enum class AlarmPhase {
	/** Yanıt yok, ısrarlı ses çalıyor (en çok [AlarmPolicy.insistentMillis]). */
	RINGING,
	/** Israrlı ses süresi doldu; birkaç dakikada bir kısa hatırlatma. */
	REMINDING,
	/** Kullanıcı uygulamadan sesi susturdu; bildirim ve bant kalır. */
	MUTED,
	/** Yanıt verildi; ses yok, bildirim alarm çözülene kadar kalır. */
	RESPONDED,
	CANCELLED,
	CLOSED;

	val resolved get() = this == CANCELLED || this == CLOSED
	val sounding get() = this == RINGING || this == REMINDING
}

data class AlarmState(
	val alertId: String,
	val title: String,
	val body: String?,
	val phase: AlarmPhase,
	val startedAt: Long,
	val response: String? = null,
)

/** Saf karar kuralları (birim testli); zamanlama ve bildirim çizimi [Alarms]'tadır. */
class AlarmPolicy(val insistentMillis: Long, val reminderMillis: Long) {

	/** Yeni alarm olayı: zaten bilinen alarm yeniden çalmaz; yanıtlanmış/çözülmüşse durumu korunur. */
	fun onSent(existing: AlarmState?, alertId: String, title: String, body: String?, now: Long): AlarmState =
		existing ?: AlarmState(alertId, title, body, AlarmPhase.RINGING, now)

	/** Zamanlayıcı tıkı: ısrarlı süre dolmuşsa hatırlatmaya geçer. */
	fun onTick(state: AlarmState, now: Long): AlarmState =
		if (state.phase == AlarmPhase.RINGING && now - state.startedAt >= insistentMillis) state.copy(phase = AlarmPhase.REMINDING)
		else state

	/** Bir sonraki tık; ses gerekmiyorsa `null`. */
	fun nextTick(state: AlarmState, now: Long): Long? = when (state.phase) {
		AlarmPhase.RINGING -> maxOf(now, state.startedAt + insistentMillis)
		AlarmPhase.REMINDING -> now + reminderMillis
		else -> null
	}

	fun onResponded(state: AlarmState, option: String) =
		if (state.phase.resolved) state else state.copy(phase = AlarmPhase.RESPONDED, response = option)

	fun onMuted(state: AlarmState) = if (state.phase.sounding) state.copy(phase = AlarmPhase.MUTED) else state

	fun onResolved(state: AlarmState, cancelled: Boolean) =
		state.copy(phase = if (cancelled) AlarmPhase.CANCELLED else AlarmPhase.CLOSED)

	/** Kullanıcı bildirimi kaydırıp kapattı: alarm çözülmediyse yeniden gösterilir. */
	fun shouldRepostOnDismiss(state: AlarmState?) = state != null && !state.phase.resolved

	/** Bildirim ısrarlı (FLAG_INSISTENT) mı çalsın? */
	fun insistent(state: AlarmState) = state.phase == AlarmPhase.RINGING
}
