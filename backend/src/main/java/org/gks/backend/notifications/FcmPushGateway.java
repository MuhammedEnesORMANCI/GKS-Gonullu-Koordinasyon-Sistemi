package org.gks.backend.notifications;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
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
			FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build();
			FirebaseApp app = FirebaseApp.getApps().stream().filter(a -> a.getName().equals("gks")).findFirst()
				.orElseGet(() -> FirebaseApp.initializeApp(options, "gks"));
			FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);
			log.info("FCM push etkin");
			return new FcmPushGateway(messaging::send);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Firebase hizmet hesabı anahtarı yüklenemedi (" + ex.getClass().getSimpleName() + ")");
		}
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
			MessagingErrorCode code = ex.getMessagingErrorCode();
			return classify(code == null ? null : code.name());
		}
	}

	static Result classify(String code) {
		if (code == null) {
			return Result.transientFailure("FCM_UNKNOWN");
		}
		return switch (code) {
			case "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH" -> Result.invalidToken(code);
			case "UNAVAILABLE", "INTERNAL", "QUOTA_EXCEEDED" -> Result.transientFailure(code);
			default -> Result.rejected(code);
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
