package org.gks.backend;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modüllerin katalogdaki bir hata kodunu döndürmek için fırlattığı istisna (§4.1).
 * {@link #errors()} yalnızca {@code VALIDATION_FAILED} için, {@link #extensions()} koda özgü uzantılar,
 * {@link #headers()} koda özgü yanıt başlıkları içindir.
 */
public class ApiException extends RuntimeException {

	private final ApiProblems.Code code;
	private final List<FieldError> errors;
	private final Map<String, Object> extensions;
	private final Map<String, String> headers;
	private final Integer status;

	private ApiException(ApiProblems.Code code, List<FieldError> errors, Map<String, Object> extensions,
			Map<String, String> headers) {
		this(code, errors, extensions, headers, null);
	}

	private ApiException(ApiProblems.Code code, List<FieldError> errors, Map<String, Object> extensions,
			Map<String, String> headers, Integer status) {
		super(code.name());
		this.code = code;
		this.errors = List.copyOf(errors);
		this.extensions = extensions;
		this.headers = headers;
		this.status = status;
	}

	public static ApiException of(ApiProblems.Code code) {
		return new ApiException(code, List.of(), Map.of(), Map.of());
	}

	public static ApiException validation(List<FieldError> errors) {
		return new ApiException(ApiProblems.Code.VALIDATION_FAILED, errors, Map.of(), Map.of());
	}

	/**
	 * Anlamsal doğrulama hatası (§3.2 "422: … boş hedef kitle", §3.3 adım 10): kod {@code VALIDATION_FAILED} kalır,
	 * HTTP durumu {@code 422} olur (ör. {@code EMPTY_AUDIENCE}).
	 */
	public static ApiException semanticValidation(List<FieldError> errors) {
		return new ApiException(ApiProblems.Code.VALIDATION_FAILED, errors, Map.of(), Map.of(), 422);
	}

	/** {@code 409 INVALID_STATE_TRANSITION} ve {@code currentState}/{@code requestedState} uzantıları. */
	public static ApiException invalidStateTransition(Object currentState, Object requestedState) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("currentState", String.valueOf(currentState));
		extensions.put("requestedState", String.valueOf(requestedState));
		return new ApiException(ApiProblems.Code.INVALID_STATE_TRANSITION, List.of(), extensions, Map.of());
	}

	/** {@code 409 ALREADY_EXISTS} ve {@code conflict} nedeni (§4.1). */
	/** §3.6: {@code resourceId} ve {@code next} ({@code LOGIN}) uzantılarıyla. */
	public static ApiException alreadyCompleted(java.util.UUID resourceId, String next) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("resourceId", String.valueOf(resourceId));
		extensions.put("next", next);
		return new ApiException(ApiProblems.Code.ALREADY_COMPLETED, List.of(), extensions, Map.of());
	}

	/** {@code 410}: {@code invitationStatus} uzantısıyla (ör. {@code ACCEPTED}, {@code EXPIRED}). */
	public static ApiException invitationInvalid(String invitationStatus) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("invitationStatus", invitationStatus);
		return new ApiException(ApiProblems.Code.INVITATION_INVALID, List.of(), extensions, Map.of());
	}

	/** {@code 409 ALERT_NOT_OPEN} ve {@code alertState} uzantısı (AL-10 – AL-15). */
	public static ApiException alertNotOpen(String alertState) {
		return new ApiException(ApiProblems.Code.ALERT_NOT_OPEN, List.of(), Map.of("alertState", alertState), Map.of());
	}

	/** {@code 409 AUDIENCE_CHANGED} ve güncel önizleme ({@code preview}). */
	public static ApiException audienceChanged(Object preview) {
		return new ApiException(ApiProblems.Code.AUDIENCE_CHANGED, List.of(), Map.of("preview", preview), Map.of());
	}

	/** {@code 422 AUDIENCE_TOO_LARGE}: {@code recipientCount} ve {@code limit}. */
	public static ApiException audienceTooLarge(int recipientCount, int limit) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("recipientCount", recipientCount);
		extensions.put("limit", limit);
		return new ApiException(ApiProblems.Code.AUDIENCE_TOO_LARGE, List.of(), extensions, Map.of());
	}

	public static ApiException alreadyExists(String conflict) {
		return new ApiException(ApiProblems.Code.ALREADY_EXISTS, List.of(), Map.of("conflict", conflict), Map.of());
	}

	/** {@code 409 GROUP_FULL} ve {@code memberCount}, {@code limit} uzantıları (§4.1). */
	public static ApiException groupFull(int memberCount, int limit) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("memberCount", memberCount);
		extensions.put("limit", limit);
		return new ApiException(ApiProblems.Code.GROUP_FULL, List.of(), extensions, Map.of());
	}

	/** {@code 409 CAPACITY_FULL} ve {@code acceptedCount}, {@code capacity} uzantıları (§4.1, LD-46). */
	public static ApiException capacityFull(int acceptedCount, int capacity) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("acceptedCount", acceptedCount);
		extensions.put("capacity", capacity);
		return new ApiException(ApiProblems.Code.CAPACITY_FULL, List.of(), extensions, Map.of());
	}

	/** {@code 409 CAPACITY_BELOW_ACCEPTED} ve {@code acceptedCount} uzantısı (§4.1, AC-13.7). */
	public static ApiException capacityBelowAccepted(int acceptedCount) {
		return new ApiException(ApiProblems.Code.CAPACITY_BELOW_ACCEPTED, List.of(),
				Map.of("acceptedCount", acceptedCount), Map.of());
	}

	/** {@code 409 VERSION_CONFLICT} ve {@code currentVersion} uzantısı (§4.1). */
	public static ApiException versionConflict(int currentVersion) {
		return new ApiException(ApiProblems.Code.VERSION_CONFLICT, List.of(), Map.of("currentVersion", currentVersion),
				Map.of());
	}

	/** {@code 429 RATE_LIMITED}: {@code Retry-After} başlığı (saniye). */
	public static ApiException rateLimited(long retryAfterSeconds) {
		return new ApiException(ApiProblems.Code.RATE_LIMITED, List.of(), Map.of(),
				Map.of("Retry-After", Long.toString(Math.max(1, retryAfterSeconds))));
	}

	/** {@code 423 ACCOUNT_LOCKED}: {@code lockedUntil} uzantısı ve {@code Retry-After} başlığı (§4.1). */
	public static ApiException accountLocked(Instant lockedUntil, Instant now) {
		long seconds = Math.max(1, Duration.between(now, lockedUntil).toSeconds() + 1);
		return new ApiException(ApiProblems.Code.ACCOUNT_LOCKED, List.of(),
				Map.of("lockedUntil", lockedUntil.toString()), Map.of("Retry-After", Long.toString(seconds)));
	}

	public ApiProblems.Code code() {
		return code;
	}

	public List<FieldError> errors() {
		return errors;
	}

	public Map<String, Object> extensions() {
		return extensions;
	}

	public Map<String, String> headers() {
		return headers;
	}

	/** Kodun katalog durumunu geçersiz kılan HTTP durumu; yoksa {@code null} (kodun kendi durumu kullanılır). */
	public Integer status() {
		return status;
	}

	/** §4.1 {@code FieldError}: {@code field} JSON yolu, {@code code} alan hata kodu, {@code message} Türkçe. */
	public record FieldError(String field, String code, String message) {
	}

}
