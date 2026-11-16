package org.gks.backend.notifications;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FCM adaptörü (ADR-004, ADR-022). Yalnızca yüksek/normal öncelikli <b>data</b> mesajı gönderir; bildirimi Android
 * uygulaması kendisi oluşturur (kanal, kalıcı alarm, yanıt düğmeleri). Hizmet hesabı anahtarı yalnızca dosya yolundan
 * okunur ({@code GKS_FIREBASE_CREDENTIALS_FILE}); içerik, belirteç ve sağlayıcı hata iletisi günlüğe yazılmaz.
 * <p>
 * Hata eşlemesi: {@code UNREGISTERED}, {@code INVALID_ARGUMENT}, {@code SENDER_ID_MISMATCH} → geçersiz belirteç (cihaz
 * pasifleşir); {@code UNAVAILABLE}, {@code INTERNAL}, {@code QUOTA_EXCEEDED} → geçici (yeniden denenir); diğerleri →
 * reddedildi. Gönderim iş işleminin dışındadır; başarısızlık alarmı/duyuruyu geri almaz.
 */
class FcmPushGateway implements PushGateway {

	private static final Logger log = LoggerFactory.getLogger(FcmPushGateway.class);

	/** Alarm iletisinin cihaz çevrimdışıyken bekletilme süresi; duyurularda daha uzun. */
	static final Duration ALERT_TTL = Duration.ofHours(1);
	static final Duration DEFAULT_TTL = Duration.ofDays(1);

	/** Test ve kalıp için ağ çağrısı soyutlaması. */
	interface Sender {
		String send(com.google.firebase.messaging.Message message) throws FirebaseMessagingException;
	}

	private final Sender sender;

	FcmPushGateway(Sender sender) {
		this.sender = sender;
	}

	static FcmPushGateway fromCredentialsFile(String path) {
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("push.provider=fcm için GKS_FIREBASE_CREDENTIALS_FILE (anahtar dosyası yolu) tanımlı olmalı");
		}
		Path file = Path.of(path);
		if (!Files.isReadable(file)) {
			throw new IllegalStateException("Firebase hizmet hesabı anahtar dosyası okunamıyor (yol: GKS_FIREBASE_CREDENTIALS_FILE)");
		}
		try (InputStream in = new FileInputStream(file.toFile())) {
			FcmPushGateway gateway = create(GoogleCredentials.fromStream(in), null, new NetHttpTransport(), "gks");
			log.info("FCM push etkin");
			return gateway;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Firebase hizmet hesabı anahtarı yüklenemedi (" + ex.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * Taşıyıcı açıkça {@link NetHttpTransport} (JDK HttpURLConnection): firebase-admin 9.4.3'ün varsayılanı
	 * {@code ApacheHttp2Transport}, httpclient5 5.3 için yazılmış; Spring Boot sürüm yönetimi httpclient5'i 5.6'ya
	 * yükseltiyor ve bu sürüm sıkıştırılmış yanıtı kendisi açıp {@code Content-Encoding: gzip} başlığını bırakıyor,
	 * google-http-client gövdeyi ikinci kez açmaya çalışıp {@code ZipException: Not in GZIP format} veriyor
	 * (16.11.2026; gerçek HTTP durumu kayboluyordu). Testte sahte FCM sunucusuna yönlendirilmiş taşıyıcı verilir.
	 */
	static FcmPushGateway create(GoogleCredentials credentials, String projectId, HttpTransport transport, String appName) {
		FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(credentials).setHttpTransport(transport);
		if (projectId != null) {
			options.setProjectId(projectId);
		}
		FirebaseOptions built = options.build();
		FirebaseApp app = FirebaseApp.getApps().stream().filter(a -> a.getName().equals(appName)).findFirst()
			.orElseGet(() -> FirebaseApp.initializeApp(built, appName));
		FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);
		return new FcmPushGateway(messaging::send);
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Result send(Message message) {
		try {
			return Result.sent(sender.send(toFcm(message)));
		}
		catch (FirebaseMessagingException ex) {
			Result result = classify(ex);
			if (result.httpStatus() == null && ex.getCause() != null) {
				// Yanıt alınamadı (ağ, TLS, gövde çözme): yalnızca hata sınıfı yazılır, ileti değil.
				log.warn("FCM yanıtı alınamadı: {}", ex.getCause().getClass().getName());
			}
			return result;
		}
	}

	/**
	 * Önce FCM'ye özgü kod ({@link MessagingErrorCode}), yoksa genel Firebase kodu ({@code FCM_} önekiyle, ör.
	 * {@code FCM_UNAUTHENTICATED}, {@code FCM_PERMISSION_DENIED}); HTTP durumu varsa eklenir.
	 */
	static Result classify(FirebaseMessagingException ex) {
		MessagingErrorCode messaging = ex.getMessagingErrorCode();
		ErrorCode general = ex.getErrorCode();
		IncomingHttpResponse response = ex.getHttpResponse();
		String code = messaging != null ? messaging.name() : general == null ? null : "FCM_" + general.name();
		return classify(code).withHttpStatus(response == null ? null : response.getStatusCode());
	}

	static Result classify(String code) {
		if (code == null || code.equals("FCM_UNKNOWN")) {
			return Result.transientFailure("FCM_UNKNOWN");
		}
		return switch (code) {
			case "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH" -> Result.invalidToken(code);
			case "UNAVAILABLE", "INTERNAL", "QUOTA_EXCEEDED", "FCM_UNAVAILABLE", "FCM_INTERNAL", "FCM_DEADLINE_EXCEEDED",
					"FCM_RESOURCE_EXHAUSTED" -> Result.transientFailure(code);
			default -> Result.rejected(code);   // ör. FCM_UNAUTHENTICATED, FCM_PERMISSION_DENIED: yapılandırma sorunu
		};
	}

	/** Data alanları (hepsi metin): istemci `type`/`kind` ile karar verir, `notificationId` ile tekilleştirir. */
	static Map<String, String> data(Message m) {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("type", m.type());
		put(data, "kind", m.kind());
		put(data, "notificationId", m.notificationId());
		put(data, "inboxItemId", m.inboxItemId());
		put(data, "sourceType", m.sourceType());
		put(data, "sourceId", m.sourceId());
		put(data, "title", m.title());
		put(data, "body", m.body() == null ? null : m.body().length() > 1000 ? m.body().substring(0, 999) + "…" : m.body());
		data.put("priority", m.priority().name());
		return data;
	}

	static com.google.firebase.messaging.Message toFcm(Message m) {
		boolean alert = "ALERT".equals(m.type());
		AndroidConfig.Builder android = AndroidConfig.builder()
			.setPriority(m.priority() == Priority.HIGH ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
			.setTtl((alert ? ALERT_TTL : DEFAULT_TTL).toMillis());
		if (m.collapseKey() != null && alert) {
			android.setCollapseKey(m.collapseKey());   // çevrimdışıyken yalnızca alarmın son durumu bekler
		}
		return com.google.firebase.messaging.Message.builder()
			.setToken(m.token())
			.putAllData(data(m))
			.setAndroidConfig(android.build())
			.build();
	}

	private static void put(Map<String, String> data, String key, Object value) {
		if (value != null) {
			data.put(key, value.toString());
		}
	}

}
