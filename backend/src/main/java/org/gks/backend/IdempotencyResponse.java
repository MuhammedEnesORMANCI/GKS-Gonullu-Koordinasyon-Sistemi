package org.gks.backend;

import java.net.URI;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Idempotency kapsamındaki bir uç işleminin sonucu (§3.6). Gövde JSON metni olarak tutulur; böylece ilk yanıt ile
 * tekrar ({@code Idempotent-Replayed: true}) bayt düzeyinde aynıdır.
 * <p>
 * {@link #error()} dolu ise işlem durum değiştirip <b>commit edilmiş</b>, ama yanıt bir hatadır (yalnızca TK-11
 * savunma yolu, LD-45). Hata, işlem bittikten sonra {@link #toResponseEntity()} içinde fırlatılır.
 */
public record IdempotencyResponse(int status, String body, boolean replayed, UUID resourceId, ApiException error) {

	public static IdempotencyResponse failure(ApiException error) {
		return new IdempotencyResponse(0, null, false, null, error);
	}

	public ResponseEntity<String> toResponseEntity() {
		return toResponseEntity(null);
	}

	/** {@code 201} yanıtlarında {@code Location} kaynak kimliğinden üretilir (tekrar da aynı başlığı alır). */
	public ResponseEntity<String> toResponseEntity(Function<UUID, URI> location) {
		if (error != null) {
			throw error;
		}
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (replayed) {
			builder.header(Idempotency.REPLAYED_HEADER, "true");
		}
		if (location != null && status == 201 && resourceId != null) {
			builder.location(location.apply(resourceId));
		}
		if (body == null) {
			return builder.build();
		}
		return builder.contentType(MediaType.APPLICATION_JSON).body(body);
	}

}
