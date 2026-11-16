package org.gks.android

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.gks.android.data.AlertRepository
import org.gks.android.data.AnnouncementRepository
import org.gks.android.data.ApiCaller
import org.gks.android.data.AuthRepository
import org.gks.android.data.InvitationRepository
import org.gks.android.data.LocationRepository
import org.gks.android.data.ManagementRepository
import org.gks.android.data.TaskManagementRepository
import org.gks.android.data.NotificationRepository
import org.gks.android.data.VolunteerRepository
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.RefreshApi
import org.gks.android.data.session.AuthInterceptor
import org.gks.android.data.session.InstallationId
import org.gks.android.data.session.KeystoreSessionStore
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.SessionStore
import org.gks.android.data.session.TokenAuthenticator
import org.gks.android.data.session.TokenRefresher
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Elle bağımlılık kurulumu; uygulama boyunca tek örnek. */
class AppContainer(context: Context, val apiBaseUrl: String = BuildConfig.API_BASE_URL) {

	private val sessionStore: SessionStore = KeystoreSessionStore(context, GksJson)
	val sessions = SessionManager(sessionStore)

	private val installationId = InstallationId(context)
	private val caller = ApiCaller(GksJson)
	private val api: GksApi by lazy { createApis(apiBaseUrl, GksJson, sessions).first }

	val authRepository by lazy { AuthRepository(api, sessions, installationId::get, caller) }
	val invitationRepository by lazy { InvitationRepository(api, installationId::get, caller) }
	val volunteerRepository by lazy { VolunteerRepository(api, caller) }
	val notificationRepository by lazy { NotificationRepository(api, caller) }
	val alertRepository by lazy { AlertRepository(api, caller) }
	val locationRepository by lazy { LocationRepository(api, caller) }
	val managementRepository by lazy { ManagementRepository(api, caller) }
	val taskManagementRepository by lazy { TaskManagementRepository(api, caller) }
	val announcementRepository by lazy { AnnouncementRepository(api, caller) }
}

/** Yanıtlardaki yeni alanlar yok sayılır; isteklerde null alan gönderilmez (backend fazla alanı reddeder). */
@OptIn(ExperimentalSerializationApi::class)
val GksJson = Json {
	ignoreUnknownKeys = true
	explicitNulls = false
}

/** Oturumlu API ve ayrı (kimlik doğrulayıcısız) yenileme API'si. Testlerde de kullanılır. */
fun createApis(baseUrl: String, json: Json, sessions: SessionManager): Pair<GksApi, RefreshApi> {
	val base = OkHttpClient.Builder()
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.writeTimeout(20, TimeUnit.SECONDS)
		.build()
	val converter = json.asConverterFactory("application/json".toMediaType())

	val refreshApi = Retrofit.Builder().baseUrl(baseUrl).client(base).addConverterFactory(converter).build()
		.create(RefreshApi::class.java)
	val authed = base.newBuilder()
		.addInterceptor(AuthInterceptor(sessions, json))
		.authenticator(TokenAuthenticator(sessions, TokenRefresher(sessions, refreshApi), json))
		.build()
	val api = Retrofit.Builder().baseUrl(baseUrl).client(authed).addConverterFactory(converter).build()
		.create(GksApi::class.java)
	return api to refreshApi
}
