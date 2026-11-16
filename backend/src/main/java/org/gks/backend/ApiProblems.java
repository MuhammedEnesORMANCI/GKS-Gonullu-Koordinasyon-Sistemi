package org.gks.backend;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ProblemDetail;

/**
 * Hata yanıtları ({@code application/problem+json}, RFC 9457).
 * Alanlar backend-api-design.md §4.1 ile aynıdır; başlıklar sabittir, kullanıcı girdisi içermez.
 */
public final class ApiProblems {

	public static final String PROBLEM_JSON = "application/problem+json";

	/**
	 * Kimlik doğrulama filtresinin belirteci reddetme nedenini güvenlik giriş noktasına ilettiği istek özniteliği
	 * (değeri bir {@link Code}). Herkese açık uçlarda kullanılmaz.
	 */
	public static final String AUTH_FAILURE = ApiProblems.class.getName() + ".authFailure";

	/** Hata kataloğundaki kodlar (api-and-data-design-basis.md §2, LD-36). */
	public enum Code {
		VALIDATION_FAILED(400, "validation-failed", "İstek doğrulanamadı"),
		AUTH_UNAUTHENTICATED(401, "auth-unauthenticated", "Kimlik doğrulama gerekli"),
		AUTH_INVALID_CREDENTIALS(401, "auth-invalid-credentials", "E-posta veya parola hatalı"),
		AUTH_TOKEN_EXPIRED(401, "auth-token-expired", "Erişim belirtecinin süresi doldu"),
		AUTH_SESSION_REVOKED(401, "auth-session-revoked", "Oturum sonlandırıldı"),
		AUTH_MFA_REQUIRED(401, "auth-mfa-required", "İki adımlı doğrulama bileti geçersiz veya süresi dolmuş"),
		FORBIDDEN(403, "forbidden", "Bu işlem için yetkiniz yok"),
		CSRF_TOKEN_INVALID(403, "csrf-token-invalid", "CSRF belirteci eksik veya geçersiz"),
		ACCOUNT_DISABLED(403, "account-disabled", "Hesap devre dışı"),
		AUTH_PASSWORD_CHANGE_REQUIRED(403, "auth-password-change-required", "Devam etmek için yeni parola belirlenmeli"),
		CLIENT_NOT_ALLOWED(403, "client-not-allowed", "Bu işlem bu oturumla yapılamaz"),
		NOT_FOUND(404, "not-found", "Kaynak bulunamadı"),
		ALREADY_EXISTS(409, "already-exists", "Kayıt zaten var"),
		/** ID-16 aynı anahtarla tekrar: sır içeren ilk yanıt yeniden verilmez; istemci girişe yönelir (§3.6). */
		ALREADY_COMPLETED(409, "already-completed", "İşlem daha önce tamamlandı"),
		INVALID_STATE_TRANSITION(409, "invalid-state-transition", "Bu durum geçişine izin verilmiyor"),
		OPERATION_CLOSED(409, "operation-closed", "Operasyon kapatılmış"),
		OPERATION_NOT_ACTIVE(409, "operation-not-active", "Operasyon aktif değil"),
		LEADER_REQUIRED(409, "leader-required", "Grup lidersiz bırakılamaz"),
		NOT_A_PARTICIPANT(409, "not-a-participant", "Kişi operasyona katılmamış"),
		GROUP_FULL(409, "group-full", "Grup üye sınırına ulaştı"),
		CAPACITY_FULL(409, "capacity-full", "Görev kapasitesi dolu"),
		CAPACITY_BELOW_ACCEPTED(409, "capacity-below-accepted", "Kapasite mevcut kabul sayısının altına indirilemez"),
		ASSIGNMENT_NOT_PENDING(409, "assignment-not-pending", "Atama yanıt bekleme durumunda değil"),
		VERSION_CONFLICT(409, "version-conflict", "Kayıt sürümü uyuşmuyor"),
		/** AL-05: önizlemeden sonra hedef kitle değişti; yeni önizleme {@code preview} uzantısında (TD-05, AC-20.8). */
		AUDIENCE_CHANGED(409, "audience-changed", "Hedef kitle önizlemeden sonra değişti"),
		ALERT_NOT_OPEN(409, "alert-not-open", "Alarm açık değil"),
		/** LC-03: paylaşım durdurulmuş; cihaz ön plan servisini kapatır (TD-18). */
		LOCATION_SHARING_STOPPED(409, "location-sharing-stopped", "Konum paylaşımı durduruldu"),
		INVITATION_INVALID(410, "invitation-invalid", "Davet geçersiz veya süresi dolmuş"),
		/** VS-02: alarm alıcı üst sınırı (500). */
		AUDIENCE_TOO_LARGE(422, "audience-too-large", "Hedef kitle üst sınırı aşıyor"),
		IDEMPOTENCY_KEY_MISMATCH(422, "idempotency-key-mismatch", "Aynı idempotency anahtarı farklı bir istekle kullanıldı"),
		ACCOUNT_LOCKED(423, "account-locked", "Hesap geçici olarak kilitlendi"),
		/** Kimlik doğrulamasız uçlarda istemci başına oran sınırı aşıldı; {@code Retry-After} başlığı (ADR-023). */
		RATE_LIMITED(429, "rate-limited", "Çok fazla istek; biraz sonra yeniden deneyin"),
		/** Altyapı (veritabanı) geçici olarak erişilemez; oturum sona ermiş sayılmaz (LD-48). */
		SERVICE_UNAVAILABLE(503, "service-unavailable", "Hizmet geçici olarak kullanılamıyor");

		final int status;
		final String type;
		final String title;

		Code(int status, String type, String title) {
			this.status = status;
			this.type = type;
			this.title = title;
		}
	}

	private ApiProblems() {
	}

	/** Güvenlik filtrelerinden (MVC dışı) yazılan yanıt. */
	public static void write(HttpServletRequest request, HttpServletResponse response, Code code) throws IOException {
		String json = "{\"type\":\"/problems/" + code.type + "\""
				+ ",\"title\":\"" + code.title + "\""
				+ ",\"status\":" + code.status
				+ ",\"code\":\"" + code.name() + "\""
				+ ",\"traceId\":\"" + TraceIdFilter.currentTraceId(request) + "\"}";
		response.setStatus(code.status);
		response.setContentType(PROBLEM_JSON);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(json);
	}

	/** Denetleyicilerden dönen yanıtın gövdesi; uzantılar çağıran tarafından eklenir. */
	static ProblemDetail problem(HttpServletRequest request, Code code) {
		ProblemDetail problem = ProblemDetail.forStatus(code.status);
		problem.setType(URI.create("/problems/" + code.type));
		problem.setTitle(code.title);
		problem.setProperty("code", code.name());
		problem.setProperty("traceId", TraceIdFilter.currentTraceId(request));
		return problem;
	}

}
