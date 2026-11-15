package org.gks.backend;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.gks.backend.ApiException.FieldError;

/**
 * §3.5 sayfalama: {@code limit} 1..100 (varsayılan 50), imleç son öğe kimliğinin opak base64url hâli. Kimlikler
 * UUIDv7 olduğundan {@code id} sıralaması zaman sıralamasıdır.
 */
public record PageCursor(int limit, UUID after) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 100;

	/** Hatalar {@code errors} listesine eklenir; çağıran toplu {@code 400} üretir. */
	public static PageCursor parse(String limit, String cursor, List<FieldError> errors) {
		int size = DEFAULT_LIMIT;
		if (limit != null) {
			try {
				size = Integer.parseInt(limit);
				if (size < 1 || size > MAX_LIMIT) {
					errors.add(new FieldError("limit", "OUT_OF_RANGE", "limit 1 ile 100 arasında olmalıdır"));
				}
			}
			catch (NumberFormatException ex) {
				errors.add(new FieldError("limit", "INVALID_FORMAT", "limit sayı olmalıdır"));
			}
		}
		UUID after = null;
		if (cursor != null) {
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
				after = UUID.fromString(raw);
				if (!after.toString().equals(raw)) {
					throw new IllegalArgumentException(raw);
				}
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("cursor", "INVALID_FORMAT", "Geçersiz imleç"));
			}
		}
		return new PageCursor(size, after);
	}

	public static String encode(UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(id.toString().getBytes(StandardCharsets.US_ASCII));
	}

	/** {@code limit + 1} satır okunur; fazlası varsa son gösterilen öğe sonraki imleçtir. */
	public <T> Slice<T> slice(List<T> rows, java.util.function.Function<T, UUID> id) {
		if (rows.size() <= limit) {
			return new Slice<>(rows, null);
		}
		List<T> page = rows.subList(0, limit);
		return new Slice<>(List.copyOf(page), encode(id.apply(page.get(limit - 1))));
	}

	public record Slice<T>(List<T> items, String nextCursor) {
	}

}
