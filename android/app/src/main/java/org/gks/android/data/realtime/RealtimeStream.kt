package org.gks.android.data.realtime

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Android SSE istemcisi (RT-01, ADR-017; yeni bağımlılık yok — yalnızca OkHttp). Kullanıcının `user:{id}` konusu
 * sunucuda kendiliğinden eklenir; gönüllü için gelen kutusu, duyuru ve alarm bildirimleri bu konudan gelir.
 *
 * - **Yetki her bağlantıda:** her bağlantı yeni bir istektir; erişim belirteci `AuthInterceptor`'dan, süresi dolmuşsa
 *   `TokenAuthenticator` yenilemesiyle gelir, sunucu konuları her açılışta yeniden denetler.
 * - **Yeniden bağlanma:** bağlantı koparsa (ağ, sunucunun 30 dk sınırı, 45 sn sessizlik) artan bekleme ile yeniden
 *   bağlanır ve `Last-Event-ID` gönderir; sunucu kaçanları yeniden oynatır ya da `resync` yollar.
 * - **Kullanım sayacı:** ekran (ön plan) ve konum ön plan servisi `acquire`/`release` ile akışı paylaşır; kimse
 *   kullanmıyorsa bağlantı kapanır. Uygulama tamamen kapalıyken akış yoktur (FCM gelene kadar).
 */
class RealtimeStream(
	client: OkHttpClient,
	private val baseUrl: String,
	private val scope: CoroutineScope,
	private val hasSession: () -> Boolean,
	private val backoff: (attempt: Int) -> Long = { attempt -> minOf(30_000L, 3_000L * (1L shl minOf(attempt, 4))) },
) {

	/** Sunucu 15 sn'de bir ping gönderir; 45 sn veri gelmezse bağlantı kopmuş sayılır (§12.2). */
	private val client = client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

	private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
	val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

	private val _connected = MutableStateFlow(false)
	val connected: StateFlow<Boolean> = _connected.asStateFlow()

	private var users = 0
	private var job: Job? = null
	@Volatile private var call: Call? = null
	@Volatile var lastEventId: String? = null
		private set

	@Synchronized
	fun acquire() {
		users++
		if (job == null) job = scope.launch { loop() }
	}

	@Synchronized
	fun release() {
		users = maxOf(0, users - 1)
		if (users == 0) {
			job?.cancel()
			job = null
			call?.cancel()
			_connected.value = false
		}
	}

	private suspend fun loop() {
		var attempt = 0
		while (scope.isActive) {
			if (!hasSession()) {
				delay(5_000)
				continue
			}
			val result = withContext(Dispatchers.IO) { connectOnce() }
			_connected.value = false
			attempt = if (result.receivedAny) 0 else attempt + 1
			delay(result.retryMillis ?: backoff(attempt))
		}
	}

	private class Result(val receivedAny: Boolean, val retryMillis: Long?)

	private fun connectOnce(): Result {
		val request = Request.Builder()
			.url(baseUrl.trimEnd('/') + "/api/v1/stream")
			.header("Accept", "text/event-stream")
			.header("Cache-Control", "no-cache")
			// RAPOR_7 madde 2: sıkıştırma istenmez; tünel/vekil gzip için olayları arabelleğe almasın.
			.header("Accept-Encoding", "identity")
			.apply { lastEventId?.let { header("Last-Event-ID", it) } }
			.build()
		val parser = SseParser()
		var received = false
		val current = client.newCall(request)
		call = current
		try {
			current.execute().use { response ->
				if (!response.isSuccessful) {
					// 401: oturum yenilenemedi (kimlik doğrulayıcı oturumu sonlandırır); 403/400: konu reddi.
					return Result(false, if (response.code == 401 || response.code == 403) 30_000L else null)
				}
				_connected.value = true
				received = true   // bağlantı kuruldu: bekleme süresi sıfırlanır
				val source = response.body?.source() ?: return Result(false, null)
				while (true) {
					val line = source.readUtf8Line() ?: break
					parser.feed(line)?.let { event -> _events.tryEmit(event) }
					parser.lastEventId?.let { lastEventId = it }
				}
			}
		}
		catch (ex: IOException) {
			// Ağ hatası, okuma zaman aşımı veya release() ile iptal: döngü yeniden dener ya da durur.
		}
		finally {
			call = null
		}
		return Result(received, parser.retryMillis?.takeIf { received })
	}
}
