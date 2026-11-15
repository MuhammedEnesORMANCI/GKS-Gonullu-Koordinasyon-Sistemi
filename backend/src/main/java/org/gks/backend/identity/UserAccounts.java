package org.gks.backend.identity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Hesap oluşturma. MVP'de doğrudan bir "kullanıcı oluştur" ucu yoktur; hesaplar davet kabulüyle (ID-16) ve tohum
 * verisiyle oluşur, ikisi de bu servisi kullanır. Uzunluk sınırları DB §1.4 ile aynıdır.
 */
@Service
public class UserAccounts {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

	private final Users users;
	private final PasswordPolicy passwordPolicy;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	UserAccounts(Users users, PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordPolicy = passwordPolicy;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/**
	 * @return yeni kullanıcının kimliği
	 * @throws ApiException {@code VALIDATION_FAILED} veya {@code ALREADY_EXISTS} ({@code EMAIL_REGISTERED})
	 */
	public UUID register(NewUserAccount account) {
		return register(account, false);
	}

	/** İlk ADM kurulumu: hesap ilk girişte yeni parola ister (AC-03.5). */
	UUID registerRequiringPasswordChange(NewUserAccount account) {
		return register(account, true);
	}

	private UUID register(NewUserAccount account, boolean mustChangePassword) {
		List<FieldError> errors = new ArrayList<>();
		String email = normalizeEmail(account.email());
		if (email == null || email.length() < 3 || email.length() > 254 || !EMAIL.matcher(email).matches()) {
			errors.add(new FieldError("email", "INVALID_FORMAT", "Geçerli bir e-posta adresi girin"));
		}
		String fullName = text("fullName", account.fullName(), 2, 100, errors);
		String displayName = text("displayName", account.displayName(), 2, 40, errors);
		String phone = account.phone() == null || account.phone().isBlank() ? null : account.phone().strip();
		if (phone != null && !PHONE.matcher(phone).matches()) {
			errors.add(new FieldError("phone", "INVALID_FORMAT", "Telefon E.164 biçiminde olmalıdır (ör. +905551112233)"));
		}
		passwordPolicy.check("password", account.password(), email).ifPresent(errors::add);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		UUID id = Ids.newId();
		try {
			users.insert(id, email, passwordEncoder.encode(account.password()), fullName, displayName, phone,
					mustChangePassword,
					clock.instant());
		}
		catch (DuplicateKeyException ex) {
			throw ApiException.alreadyExists("EMAIL_REGISTERED");
		}
		return id;
	}

	/** E-posta karşılaştırması Türkçe yerel ayardan bağımsızdır (DB §1.2: {@code Locale.ROOT}). */
	static String normalizeEmail(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	static String text(String field, String value, int min, int max, List<FieldError> errors) {
		String stripped = value == null ? "" : value.strip();
		int length = stripped.codePointCount(0, stripped.length());
		if (length == 0) {
			errors.add(new FieldError(field, "REQUIRED", "Bu alan zorunludur"));
		}
		else if (length < min) {
			errors.add(new FieldError(field, "TOO_SHORT", "En az " + min + " karakter olmalıdır"));
		}
		else if (length > max) {
			errors.add(new FieldError(field, "TOO_LONG", "En fazla " + max + " karakter olabilir"));
		}
		return stripped;
	}

}
