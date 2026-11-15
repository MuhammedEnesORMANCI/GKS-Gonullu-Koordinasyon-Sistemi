package org.gks.backend.organization;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * Doğrulanmış {@code OperationUpdateRequest} (§7.4, kısmi güncelleme). Gönderilmeyen alan {@code null},
 * açıkça {@code null} gönderilen isteğe bağlı alan {@code Optional.empty()} olur (değeri siler).
 * Gövde ağaç olarak okunur, çünkü "gönderilmedi" ile "null gönderildi" ayrımı gerekir.
 */
record OperationUpdate(String name, Optional<String> description, Optional<Instant> plannedEndAt) {

	static final int NAME_MIN = 3;
	static final int NAME_MAX = 120;
	static final int DESCRIPTION_MAX = 2000;

	/** {@code OperationView}'daki, istemcinin değiştiremeyeceği alanlar (AC-04.2: {@code READ_ONLY}). */
	static final Set<String> READ_ONLY = Set.of("id", "state", "createdAt", "activatedAt", "closedAt",
			"coordinators", "overdue", "activeSharingCount", "openAlertCount", "pendingAssignmentCount");

	private static final Set<String> FIELDS = Set.of("name", "description", "plannedEndAt");

	static OperationUpdate parse(JsonNode body, Instant now) {
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		for (String field : body.propertyNames()) {
			if (READ_ONLY.contains(field)) {
				errors.add(new FieldError(field, "READ_ONLY", "Bu alan değiştirilemez"));
			}
			else if (!FIELDS.contains(field)) {
				errors.add(new FieldError(field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		String name = body.has("name") ? name(body.get("name"), errors) : null;
		Optional<String> description = body.has("description") ? description(body.get("description"), errors) : null;
		Optional<Instant> plannedEndAt = body.has("plannedEndAt")
				? plannedEndAt(body.get("plannedEndAt"), now, errors) : null;
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new OperationUpdate(name, description, plannedEndAt);
	}

	static String name(JsonNode node, List<FieldError> errors) {
		if (node.isNull()) {
			errors.add(new FieldError("name", "REQUIRED", "Ad boş bırakılamaz"));
			return null;
		}
		if (!node.isString()) {
			errors.add(new FieldError("name", "INVALID_FORMAT", "Ad metin olmalıdır"));
			return null;
		}
		String value = node.stringValue().strip();
		int length = value.codePointCount(0, value.length());
		if (length < NAME_MIN) {
			errors.add(new FieldError("name", "TOO_SHORT", "Ad en az " + NAME_MIN + " karakter olmalıdır"));
		}
		else if (length > NAME_MAX) {
			errors.add(new FieldError("name", "TOO_LONG", "Ad en fazla " + NAME_MAX + " karakter olabilir"));
		}
		return value;
	}

	static Optional<String> description(JsonNode node, List<FieldError> errors) {
		if (node.isNull()) {
			return Optional.empty();
		}
		if (!node.isString()) {
			errors.add(new FieldError("description", "INVALID_FORMAT", "Açıklama metin olmalıdır"));
			return Optional.empty();
		}
		String value = node.stringValue().strip();
		if (value.codePointCount(0, value.length()) > DESCRIPTION_MAX) {
			errors.add(new FieldError("description", "TOO_LONG",
					"Açıklama en fazla " + DESCRIPTION_MAX + " karakter olabilir"));
		}
		return value.isEmpty() ? Optional.empty() : Optional.of(value);
	}

	static Optional<Instant> plannedEndAt(JsonNode node, Instant now, List<FieldError> errors) {
		Instant value = null;
		if (node.isNull()) {
			return Optional.empty();
		}
		if (node.isString()) {
			try {
				value = OffsetDateTime.parse(node.stringValue()).toInstant();
			}
			catch (DateTimeParseException ex) {
				// aşağıda INVALID_FORMAT
			}
		}
		if (value == null) {
			errors.add(new FieldError("plannedEndAt", "INVALID_FORMAT", "Tarih ISO-8601 biçiminde olmalıdır"));
			return Optional.empty();
		}
		if (!value.isAfter(now)) {
			errors.add(new FieldError("plannedEndAt", "OUT_OF_RANGE", "Planlanan bitiş gelecekte olmalıdır"));
		}
		return Optional.of(value);
	}

}
