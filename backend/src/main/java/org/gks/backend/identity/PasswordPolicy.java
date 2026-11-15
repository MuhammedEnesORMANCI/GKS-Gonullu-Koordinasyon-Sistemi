package org.gks.backend.identity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.gks.backend.ApiException.FieldError;
import org.springframework.stereotype.Component;

/**
 * Yeni parola kuralları (backend-api-design.md §5, {@code PasswordChangeRequest.newPassword}): 10..128 karakter,
 * e-postayı içeremez, yaygın parola listesinde olamaz.
 */
@Component
class PasswordPolicy {

	static final int MIN = 10;
	static final int MAX = 128;

	private final Set<String> common;

	PasswordPolicy() {
		try (InputStream in = PasswordPolicy.class.getResourceAsStream("common-passwords.txt")) {
			common = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(line -> line.toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	Optional<FieldError> check(String field, String password, String normalizedEmail) {
		if (password == null || password.isEmpty()) {
			return Optional.of(new FieldError(field, "REQUIRED", "Parola zorunludur"));
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN) {
			return Optional.of(new FieldError(field, "TOO_SHORT", "Parola en az " + MIN + " karakter olmalıdır"));
		}
		if (length > MAX) {
			return Optional.of(new FieldError(field, "TOO_LONG", "Parola en fazla " + MAX + " karakter olabilir"));
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (normalizedEmail != null && containsEmail(lower, normalizedEmail)) {
			return Optional.of(new FieldError(field, "NOT_ALLOWED_VALUE", "Parola e-posta adresini içeremez"));
		}
		if (common.contains(lower)) {
			return Optional.of(new FieldError(field, "NOT_ALLOWED_VALUE", "Bu parola çok yaygın"));
		}
		return Optional.empty();
	}

	private static boolean containsEmail(String password, String email) {
		int at = email.indexOf('@');
		String local = at > 0 ? email.substring(0, at) : email;
		return List.of(email, local).stream().anyMatch(part -> part.length() >= 3 && password.contains(part));
	}

}
