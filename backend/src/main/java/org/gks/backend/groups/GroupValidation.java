package org.gks.backend.groups;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.PageCursor;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.groups.GroupRequests.GroupCreateRequest;
import org.gks.backend.groups.GroupRequests.MeetingPointRequest;

import tools.jackson.databind.JsonNode;

/** §8.2 alan kuralları; uzunluklar DB §1.4 (grup adı 2..80, açıklama ≤ 1000, adres ≤ 300, nokta açıklaması ≤ 500). */
final class GroupValidation {

	static final int NAME_MIN = 2;
	static final int NAME_MAX = 80;
	static final int DESCRIPTION_MAX = 1000;
	static final int ADDRESS_MAX = 300;
	static final int MP_DESCRIPTION_MAX = 500;

	/** Grubun {@code PATCH} ile değiştirilemeyen görünüm alanları (AC-04.2 deseni: {@code READ_ONLY}). */
	private static final Set<String> READ_ONLY = Set.of("id", "operationId", "operationName", "leader", "memberCount",
			"archived", "myRole", "members", "meetingPoint", "createdAt", "type", "joinPolicy");

	private GroupValidation() {
	}

	record CreateInput(String name, String description, UUID leaderUserId, Set<UUID> memberUserIds) {
	}

	/** {@code null}: alan gönderilmedi; {@code Optional.empty()}: açıklama silinir. */
	record UpdateInput(String name, Optional<String> description) {
	}

	record MeetingPointInput(String address, Double lat, Double lon, String description, Instant time) {
	}

	record PageRequest(UUID operationId, boolean includeArchived, int limit, UUID after) {
	}

	static CreateInput create(GroupCreateRequest request) {
		if (request == null) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		String name = text("name", request.name(), NAME_MIN, NAME_MAX, true, errors);
		String description = text("description", request.description(), 0, DESCRIPTION_MAX, false, errors);
		Set<UUID> members = new LinkedHashSet<>();
		List<UUID> requested = request.memberUserIds() != null ? request.memberUserIds() : List.of();
		for (int i = 0; i < requested.size(); i++) {
			UUID member = requested.get(i);
			if (member == null) {
				errors.add(new FieldError("memberUserIds[" + i + "]", "REQUIRED", "Üye kimliği boş olamaz"));
			}
			else if (!members.add(member)) {
				errors.add(new FieldError("memberUserIds[" + i + "]", "NOT_ALLOWED_VALUE", "Üye listede birden fazla kez var"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		if (request.leaderUserId() != null) {
			members.remove(request.leaderUserId());   // §8.2: lider üye listesindeyse yok sayılır
		}
		return new CreateInput(name, description, request.leaderUserId(), members);
	}

	static UpdateInput update(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		for (String field : body.propertyNames()) {
			if (READ_ONLY.contains(field)) {
				errors.add(new FieldError(field, "READ_ONLY", "Bu alan değiştirilemez"));
			}
			else if (!field.equals("name") && !field.equals("description")) {
				errors.add(new FieldError(field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		String name = null;
		if (body.has("name")) {
			JsonNode node = body.get("name");
			if (node.isNull() || !node.isString()) {
				errors.add(new FieldError("name", node.isNull() ? "REQUIRED" : "INVALID_FORMAT", "Ad metin olmalıdır"));
			}
			else {
				name = text("name", node.stringValue(), NAME_MIN, NAME_MAX, true, errors);
			}
		}
		Optional<String> description = null;
		if (body.has("description")) {
			JsonNode node = body.get("description");
			if (node.isNull()) {
				description = Optional.empty();
			}
			else if (!node.isString()) {
				errors.add(new FieldError("description", "INVALID_FORMAT", "Açıklama metin olmalıdır"));
			}
			else {
				description = Optional.ofNullable(text("description", node.stringValue(), 0, DESCRIPTION_MAX, false, errors));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new UpdateInput(name, description);
	}

	/** AC-12.1: adres ve/veya harita noktası; {@code lat}/{@code lon} birlikte; zaman zorunlu. */
	static MeetingPointInput meetingPoint(MeetingPointRequest request) {
		if (request == null) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		String address = text("address", request.address(), 0, ADDRESS_MAX, false, errors);
		String description = text("description", request.description(), 0, MP_DESCRIPTION_MAX, false, errors);
		if ((request.lat() == null) != (request.lon() == null)) {
			String missing = request.lat() == null ? "lat" : "lon";
			errors.add(new FieldError(missing, "REQUIRED", "Enlem ve boylam birlikte verilmelidir"));
		}
		if (request.lat() != null && (request.lat() < -90 || request.lat() > 90 || request.lat().isNaN())) {
			errors.add(new FieldError("lat", "OUT_OF_RANGE", "Enlem -90 ile 90 arasında olmalıdır"));
		}
		if (request.lon() != null && (request.lon() < -180 || request.lon() > 180 || request.lon().isNaN())) {
			errors.add(new FieldError("lon", "OUT_OF_RANGE", "Boylam -180 ile 180 arasında olmalıdır"));
		}
		if (address == null && request.lat() == null && request.lon() == null) {
			errors.add(new FieldError("address", "REQUIRED", "Adres veya harita noktası zorunludur"));
		}
		if (request.time() == null) {
			errors.add(new FieldError("time", "REQUIRED", "Buluşma zamanı zorunludur"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new MeetingPointInput(address, request.lat(), request.lon(), description, request.time().toInstant());
	}

	/** §3.5: {@code limit} 1..100 (varsayılan 50), imleç opak base64url. */
	static PageRequest page(String operationId, String includeArchived, String limit, String cursor) {
		List<FieldError> errors = new ArrayList<>();
		UUID operation = null;
		if (operationId != null) {
			try {
				operation = UUID.fromString(operationId);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("operationId", "INVALID_FORMAT", "Geçersiz operasyon kimliği"));
			}
		}
		boolean archived = false;
		if (includeArchived != null) {
			if (includeArchived.equals("true") || includeArchived.equals("false")) {
				archived = Boolean.parseBoolean(includeArchived);
			}
			else {
				errors.add(new FieldError("includeArchived", "INVALID_FORMAT", "true veya false olmalıdır"));
			}
		}
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.LENIENT);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new PageRequest(operation, archived, page.limit(), page.after());
	}

	static void requireUser(String field, UUID userId) {
		if (userId == null) {
			throw ApiException.validation(List.of(new FieldError(field, "REQUIRED", "Kullanıcı kimliği zorunludur")));
		}
	}

	/** Kırpılmış metin; boş isteğe bağlı alan {@code null} olur. */
	private static String text(String field, String value, int min, int max, boolean required, List<FieldError> errors) {
		String stripped = value == null ? null : value.strip();
		if (stripped == null || stripped.isEmpty()) {
			if (required) {
				errors.add(new FieldError(field, "REQUIRED", "Bu alan zorunludur"));
			}
			return null;
		}
		int length = stripped.codePointCount(0, stripped.length());
		if (length < min) {
			errors.add(new FieldError(field, "TOO_SHORT", "En az " + min + " karakter olmalıdır"));
		}
		else if (length > max) {
			errors.add(new FieldError(field, "TOO_LONG", "En fazla " + max + " karakter olabilir"));
		}
		return stripped;
	}

}
