package org.gks.android.data

import kotlinx.serialization.json.Json
import org.gks.android.data.remote.Page
import org.gks.android.data.remote.Problem
import org.gks.android.data.remote.parseProblem
import org.gks.android.data.session.RefreshUnavailableException
import retrofit2.Response
import java.io.IOException
import java.util.UUID

/** Ağ çağrısının sonucu; istisna yerine değer olarak taşınır. */
sealed interface ApiResult<out T> {
	data class Success<T>(val value: T) : ApiResult<T>
	data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
	/** Sunucuya ulaşılamadı (bağlantı, zaman aşımı, yanlış adres). */
	data object Network : ApiError

	/** Oturum yenilenemedi ama oturum sürüyor (ağ/503; LD-48). */
	data object RefreshUnavailable : ApiError

	data class Http(val problem: Problem) : ApiError

	/** Sözleşmeye uymayan yanıt (ör. Android girişinde MFA istenmesi). */
	data object Unexpected : ApiError
}

val ApiError.problem: Problem? get() = (this as? ApiError.Http)?.problem

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
	is ApiResult.Success -> ApiResult.Success(transform(value))
	is ApiResult.Failure -> this
}

/** Tüm depoların ortak Retrofit çağrı sarmalayıcısı: hata gövdesi `ProblemDetail` olarak çözülür. */
class ApiCaller(private val json: Json) {

	suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> {
		val response = try {
			block()
		} catch (e: RefreshUnavailableException) {
			return ApiResult.Failure(ApiError.RefreshUnavailable)
		} catch (e: IOException) {
			return ApiResult.Failure(ApiError.Network)
		}
		if (response.isSuccessful) {
			@Suppress("UNCHECKED_CAST")
			return ApiResult.Success(response.body() ?: Unit as T)
		}
		val errorBody = try {
			response.errorBody()?.string()
		} catch (e: IOException) {
			null
		}
		return ApiResult.Failure(ApiError.Http(parseProblem(json, response.code(), errorBody)))
	}

	/** §3.5 imleçli listenin tüm sayfaları (güvenlik sınırı: [maxPages] × 100 kayıt). */
	suspend fun <T> allPages(maxPages: Int = 10, page: suspend (cursor: String?) -> Response<Page<T>>): ApiResult<List<T>> {
		val items = mutableListOf<T>()
		var cursor: String? = null
		repeat(maxPages) {
			when (val result = call { page(cursor) }) {
				is ApiResult.Failure -> return result
				is ApiResult.Success -> {
					items += result.value.items
					cursor = result.value.nextCursor ?: return ApiResult.Success(items)
				}
			}
		}
		return ApiResult.Success(items)
	}
}

/**
 * Zorunlu `Idempotency-Key` (§3.6) için eylem başına anahtar. Ağ hatasında aynı eylem aynı anahtarla
 * tekrarlanır; böylece sunucuya ulaşmış ama yanıtı kaybolmuş istek ikinci kez uygulanmaz.
 * Kesin sonuçtan (başarı veya HTTP hatası) sonra anahtar bırakılır.
 */
class IdempotencyKeys {
	private val keys = mutableMapOf<String, String>()

	@Synchronized
	fun keyFor(action: String): String = keys.getOrPut(action) { UUID.randomUUID().toString() }

	@Synchronized
	fun settle(action: String, result: ApiResult<*>) {
		val transient = result is ApiResult.Failure &&
			(result.error == ApiError.Network || result.error == ApiError.RefreshUnavailable)
		if (!transient) keys.remove(action)
	}
}

/** Anahtarlı çağrı: anahtar eylemden üretilir, sonuca göre bırakılır. */
suspend fun <T> IdempotencyKeys.run(action: String, block: suspend (key: String) -> ApiResult<T>): ApiResult<T> =
	block(keyFor(action)).also { settle(action, it) }
