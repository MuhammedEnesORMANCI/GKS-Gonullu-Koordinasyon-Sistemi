package org.gks.backend.tasks;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * §9.3 alan kuralları; uzunluklar DB §1.4 (başlık 3..120 — LD-51, açıklama ≤ 2000, yer ≤ 300), kapasite 1..500,
 * yanıt süresi 5..1440 dk (varsayılan 60).
 */
final class TaskValidation {

	static final int TITLE_MIN = 3;
	static final int TITLE_MAX = 120;
	static final int DESCRIPTION_MAX = 2000;
	static final int MEETING_POINT_MAX = 300;
	static final int CAPACITY_MIN = 1;
	static final int CAPACITY_MAX = 500;
	static final int WINDOW_MIN = 5;
	static final int WINDOW_MAX = 1440;
	static final int DEFAULT_WINDOW = 60;

	private static final Set<String> CREATE_FIELDS = Set.of("title", "description", "priority", "meetingPoint",
			"timeWindow", "targets", "capacity", "responseWindowMinutes");

	/** {@code TaskView}'un {@code PATCH} ile değiştirilemeyen alanları (AC-04.2 deseni: {@code READ_ONLY}). */
	private static final Set<String> READ_ONLY = Set.of("id", "state", "acceptedCount", "pendingCount",
			"overdueCount", "createdAt", "createdBy", "publishedAt", "history", "myAssignment");

	private static final Pattern UUID_FORMAT = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private TaskValidation() {
	}

	record TimeWindowInput(Instant start, Instant end) {
	}

	record TargetsInput(List<UUID> userIds, List<UUID> groupIds) {
	}

	/**
	 * Gönderilen alanlar. {@code null}: alan gönderilmedi; {@code Optional.empty()}: alan {@code null} ile temizlendi
	 * (yalnızca isteğe bağlı alanlarda).
	 */
	record TaskFields(String title, Optional<String> description, TaskPriority priority,
			Optional<String> meetingPoint, Optional<TimeWindowInput> timeWindow, TargetsInput targets,
			Optional<Integer> capacity, Integer responseWindowMinutes, Integer version) {
	}

	record PageRequest(int limit, UUID after) {
	}

	/** TK-01 {@code TaskCreateRequest}: {@code title} ve {@code targets} zorunlu (AC-13.1). */
	static TaskFields create(JsonNode body) {
		return parse(body, false);
	}

	/**
	 * TK-02 {@code TaskUpdateRequest} (LD-52): gönderilmeyen alan korunur; açıkça {@code null} gönderilen isteğe
	 * bağlı alan temizlenir ({@code capacity: null} = sınırsız; varsayılanı olan {@code priority} ve
	 * {@code responseWindowMinutes} varsayılana döner); zorunlu alana ({@code title}, {@code targets},
	 * {@code version}) {@code null} → {@code 400 REQUIRED}.
	 */
	static TaskFields update(JsonNode body) {
		return parse(body, true);
	}

	private static TaskFields parse(JsonNode body, boolean patch) {
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		for (String field : body.propertyNames()) {
			if (patch && READ_ONLY.contains(field)) {
				errors.add(new FieldError(field, "READ_ONLY", "Bu alan değiştirilemez"));
			}
			else if (!CREATE_FIELDS.contains(field) && !(patch && field.equals("version"))) {
				errors.add(new FieldError(field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}

		String title = null;
		if (body.has("title") || !patch) {
			title = text(body.get("title"), "title", TITLE_MIN, TITLE_MAX, true, errors);
		}
		Optional<String> description = optionalText(body, "description", DESCRIPTION_MAX, errors);
		Optional<String> meetingPoint = optionalText(body, "meetingPoint", MEETING_POINT_MAX, errors);

		TaskPriority priority = null;
		if (body.has("priority")) {
			JsonNode node = body.get("priority");
			if (node.isNull()) {
				priority = TaskPriority.NORMAL;   // LD-52: isteğe bağlı alan temizlenir → varsayılan
			}
			else {
				priority = enumValue(node, "priority", TaskPriority.class, errors);
			}
		}

		Optional<TimeWindowInput> timeWindow = null;
		if (body.has("timeWindow")) {
			timeWindow = timeWindow(body.get("timeWindow"), errors);
		}

		TargetsInput targets = null;
		if (body.has("targets") || !patch) {
			targets = targets(body.get("targets"), errors);
		}

		Optional<Integer> capacity = null;
		if (body.has("capacity")) {
			JsonNode node = body.get("capacity");
			capacity = node.isNull() ? Optional.empty()
					: Optional.ofNullable(integer(node, "capacity", CAPACITY_MIN, CAPACITY_MAX, errors));
		}

		Integer window = null;
		if (body.has("responseWindowMinutes")) {
			JsonNode node = body.get("responseWindowMinutes");
			if (node.isNull()) {
				window = DEFAULT_WINDOW;   // LD-52: isteğe bağlı alan temizlenir → varsayılan (DB NOT NULL DEFAULT 60)
			}
			else {
				window = integer(node, "responseWindowMinutes", WINDOW_MIN, WINDOW_MAX, errors);
			}
		}

		Integer version = null;
		if (patch) {
			version = requiredVersion(body.get("version"), errors);
		}

		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		if (!patch) {
			return new TaskFields(title, description != null ? description : Optional.empty(),
					priority != null ? priority : TaskPriority.NORMAL,
					meetingPoint != null ? meetingPoint : Optional.empty(),
					timeWindow != null ? timeWindow : Optional.empty(), targets,
					capacity != null ? capacity : Optional.empty(), window != null ? window : DEFAULT_WINDOW, null);
		}
		return new TaskFields(title, description, priority, meetingPoint, timeWindow, targets, capacity, window, version);
	}

	/** TK-03 {@code TaskPublishRequest.version}. */
	static int version(Integer version) {
		if (version == null) {
			throw ApiException.validation(List.of(new FieldError("version", "REQUIRED", "Sürüm zorunludur")));
		}
		if (version < 1) {
			throw ApiException.validation(List.of(new FieldError("version", "OUT_OF_RANGE", "Sürüm pozitif olmalıdır")));
		}
		return version;
	}

	/** TK-04: {@code PUBLISHED} bu uçtan istenemez (yayın TK-03'tür, §9.3). */
	static TaskState targetState(TaskState state) {
		if (state == null) {
			throw ApiException.validation(List.of(new FieldError("targetState", "REQUIRED", "Hedef durum zorunludur")));
		}
		if (state == TaskState.PUBLISHED) {
			throw ApiException.validation(List.of(new FieldError("targetState", "NOT_ALLOWED_VALUE",
					"Görev bu uçtan yayınlanamaz; yayın ucunu kullanın")));
		}
		return state;
	}

	static UUID requireUser(UUID userId) {
		if (userId == null) {
			throw ApiException.validation(List.of(new FieldError("userId", "REQUIRED", "Kullanıcı kimliği zorunludur")));
		}
		return userId;
	}

	/** §3.5: {@code limit} 1..100 (varsayılan 50), imleç opak base64url. */
	static PageRequest page(String limit, String cursor, List<FieldError> errors) {
		int size = 50;
		if (limit != null) {
			try {
				size = Integer.parseInt(limit);
				if (size < 1 || size > 100) {
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
				after = strictUuid(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("cursor", "INVALID_FORMAT", "Geçersiz imleç"));
			}
		}
		return new PageRequest(size, after);
	}

	static String cursor(UUID lastId) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
	}

	static <E extends Enum<E>> E queryEnum(String value, String field, Class<E> type, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError(field, "NOT_ALLOWED_VALUE", "Değer izin verilenler arasında değil"));
			return null;
		}
	}

	/** Çoklu {@code status} sorgusu: tekrarlı parametre veya virgülle ayrılmış liste. */
	static Set<AssignmentStatus> statuses(List<String> values, List<FieldError> errors) {
		Set<AssignmentStatus> statuses = EnumSet.noneOf(AssignmentStatus.class);
		if (values == null) {
			return statuses;
		}
		for (String value : values) {
			for (String part : value.split(",")) {
				AssignmentStatus status = queryEnum(part.strip(), "status", AssignmentStatus.class, errors);
				if (status != null) {
					statuses.add(status);
				}
			}
		}
		return statuses;
	}

	static Boolean queryBoolean(String value, String field, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		if (value.equals("true") || value.equals("false")) {
			return Boolean.parseBoolean(value);
		}
		errors.add(new FieldError(field, "INVALID_FORMAT", "true veya false olmalıdır"));
		return null;
	}

	static UUID queryUuid(String value, String field, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return strictUuid(value);
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Geçersiz kimlik"));
			return null;
		}
	}

	private static Optional<String> optionalText(JsonNode body, String field, int max, List<FieldError> errors) {
		if (!body.has(field)) {
			return null;
		}
		JsonNode node = body.get(field);
		if (node.isNull()) {
			return Optional.empty();
		}
		return Optional.ofNullable(text(node, field, 0, max, false, errors));
	}

	/** Kırpılmış metin; kontrol karakterleri (satır sonu ve sekme hariç) reddedilir (§3.1). */
	private static String text(JsonNode node, String field, int min, int max, boolean required, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			if (required) {
				errors.add(new FieldError(field, "REQUIRED", "Bu alan zorunludur"));
			}
			return null;
		}
		if (!node.isString()) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Metin olmalıdır"));
			return null;
		}
		String stripped = node.stringValue().strip();
		if (stripped.isEmpty()) {
			if (required) {
				errors.add(new FieldError(field, "REQUIRED", "Bu alan zorunludur"));
			}
			return null;
		}
		if (stripped.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Kontrol karakteri içeremez"));
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

	private static Integer integer(JsonNode node, String field, int min, int max, List<FieldError> errors) {
		if (!node.isIntegralNumber() || !node.canConvertToInt()) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Tam sayı olmalıdır"));
			return null;
		}
		int value = node.intValue();
		if (value < min || value > max) {
			errors.add(new FieldError(field, "OUT_OF_RANGE", min + " ile " + max + " arasında olmalıdır"));
			return null;
		}
		return value;
	}

	private static Integer requiredVersion(JsonNode node, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			errors.add(new FieldError("version", "REQUIRED", "Sürüm zorunludur"));
			return null;
		}
		return integer(node, "version", 1, Integer.MAX_VALUE, errors);
	}

	private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type, List<FieldError> errors) {
		if (!node.isString()) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Metin olmalıdır"));
			return null;
		}
		try {
			return Enum.valueOf(type, node.stringValue());
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError(field, "NOT_ALLOWED_VALUE", "Değer izin verilenler arasında değil"));
			return null;
		}
	}

	/**
	 * {@code start < end}; ikisi de isteğe bağlı ama verildiyse sıralı (§9.3). DB {@code ck_tasks_time_window}
	 * başlangıcı sonsuz aralığı reddettiği için bitiş tek başına verilemez; boş nesne alanı temizler.
	 */
	private static Optional<TimeWindowInput> timeWindow(JsonNode node, List<FieldError> errors) {
		if (node.isNull()) {
			return Optional.empty();
		}
		if (!node.isObject()) {
			errors.add(new FieldError("timeWindow", "INVALID_FORMAT", "Nesne olmalıdır"));
			return Optional.empty();
		}
		for (String field : node.propertyNames()) {
			if (!field.equals("start") && !field.equals("end")) {
				errors.add(new FieldError("timeWindow." + field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		Instant start = dateTime(node.get("start"), "timeWindow.start", errors);
		Instant end = dateTime(node.get("end"), "timeWindow.end", errors);
		if (start == null && end == null) {
			return Optional.empty();
		}
		if (start == null) {
			if (!node.has("start") || node.get("start").isNull()) {
				errors.add(new FieldError("timeWindow.start", "REQUIRED", "Bitiş verildiyse başlangıç zorunludur"));
			}
			return Optional.empty();
		}
		if (end != null && !start.isBefore(end)) {
			errors.add(new FieldError("timeWindow.end", "OUT_OF_RANGE", "Bitiş başlangıçtan sonra olmalıdır"));
		}
		return Optional.of(new TimeWindowInput(start, end));
	}

	private static Instant dateTime(JsonNode node, String field, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "ISO-8601 zaman olmalıdır"));
			return null;
		}
		try {
			return OffsetDateTime.parse(node.stringValue()).toInstant();
		}
		catch (DateTimeParseException ex) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "ISO-8601 zaman olmalıdır"));
			return null;
		}
	}

	/** En az bir kişi veya grup (§9.3); kimlikler tekrarlanamaz. */
	private static TargetsInput targets(JsonNode node, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			errors.add(new FieldError("targets", "REQUIRED", "Hedef zorunludur"));
			return null;
		}
		if (!node.isObject()) {
			errors.add(new FieldError("targets", "INVALID_FORMAT", "Nesne olmalıdır"));
			return null;
		}
		for (String field : node.propertyNames()) {
			if (!field.equals("userIds") && !field.equals("groupIds")) {
				errors.add(new FieldError("targets." + field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		int before = errors.size();
		List<UUID> users = ids(node.get("userIds"), "targets.userIds", errors);
		List<UUID> groups = ids(node.get("groupIds"), "targets.groupIds", errors);
		if (errors.size() == before && users.isEmpty() && groups.isEmpty()) {
			errors.add(new FieldError("targets", "REQUIRED", "En az bir kişi veya grup hedeflenmelidir"));
		}
		return new TargetsInput(users, groups);
	}

	private static List<UUID> ids(JsonNode node, String field, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (!node.isArray()) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Dizi olmalıdır"));
			return List.of();
		}
		Set<UUID> ids = new LinkedHashSet<>();
		for (int i = 0; i < node.size(); i++) {
			JsonNode item = node.get(i);
			String path = field + "[" + i + "]";
			if (item.isNull()) {
				errors.add(new FieldError(path, "REQUIRED", "Kimlik boş olamaz"));
				continue;
			}
			UUID id;
			try {
				id = item.isString() ? strictUuid(item.stringValue()) : null;
			}
			catch (IllegalArgumentException ex) {
				id = null;
			}
			if (id == null) {
				errors.add(new FieldError(path, "INVALID_FORMAT", "Geçersiz kimlik"));
			}
			else if (!ids.add(id)) {
				errors.add(new FieldError(path, "NOT_ALLOWED_VALUE", "Kimlik listede birden fazla kez var"));
			}
		}
		return List.copyOf(ids);
	}

	private static UUID strictUuid(String value) {
		if (!UUID_FORMAT.matcher(value).matches()) {
			throw new IllegalArgumentException("uuid");
		}
		return UUID.fromString(value);
	}

}
