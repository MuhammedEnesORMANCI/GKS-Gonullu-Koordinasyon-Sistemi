package org.gks.android.data

import org.gks.android.data.remote.CodeRequest
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.InvitationAcceptRequest
import org.gks.android.data.remote.InvitationPublicView

/** Davet kodu girilmiş kayıt formu. */
data class Registration(
	val code: String,
	val fullName: String,
	val displayName: String,
	val phone: String?,
	val password: String,
)

class InvitationRepository(
	private val api: GksApi,
	private val installationId: () -> String,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	/** ID-15: kodun geçerliliği ve kapsamı (kişisel veri dönmez). */
	suspend fun lookup(code: String): ApiResult<InvitationPublicView> =
		caller.call { api.lookupInvitation(CodeRequest(normalizeCode(code))) }

	/**
	 * ID-16: yeni gönüllü hesabı. Sunucu hesabı açınca bir Android oturumu da döndürür; ürün akışı kullanıcıyı
	 * giriş ekranına götürdüğü için bu oturum cihaza **yazılmaz** ve hemen ID-06 ile kapatılır (sunucuda açık
	 * oturum kalmaz). Aynı anahtarla tekrar `409 ALREADY_COMPLETED` döner: hesap zaten açılmıştır, başarı sayılır.
	 */
	suspend fun register(form: Registration): ApiResult<Unit> {
		val code = normalizeCode(form.code)
		val request = InvitationAcceptRequest(
			code = code,
			password = form.password,
			fullName = form.fullName.trim(),
			displayName = form.displayName.trim(),
			phone = form.phone?.trim()?.takeIf { it.isNotEmpty() },
			installationId = installationId(),
		)
		val result = keys.run("register:$code") { key -> caller.call { api.acceptInvitationCode(key, request) } }
		return when (result) {
			is ApiResult.Success -> {
				result.value.accessToken?.let { token -> caller.call { api.logoutWith("Bearer $token") } }
				ApiResult.Success(Unit)
			}
			is ApiResult.Failure ->
				if (result.error.problem?.code == "ALREADY_COMPLETED") ApiResult.Success(Unit) else result
		}
	}

	/** ID-17 / ID-18: kayıtlı gönüllünün operasyon daveti. */
	suspend fun respond(invitationId: String, accept: Boolean): ApiResult<Unit> =
		keys.run("invitation:$invitationId:$accept") { key ->
			caller.call {
				if (accept) api.acceptOperationInvitation(invitationId, key) else api.declineOperationInvitation(invitationId, key)
			}
		}

	companion object {
		/** Sunucuyla aynı normalleştirme (InvitationCodes.normalizeCode): tire ve boşluk atılır, büyük harf. */
		fun normalizeCode(code: String): String = code.replace("-", "").replace(" ", "").trim().uppercase()
	}
}
