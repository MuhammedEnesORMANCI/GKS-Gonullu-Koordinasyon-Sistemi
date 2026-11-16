package org.gks.backend.organization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;

import tools.jackson.databind.JsonNode;

/** Doğrulanmış {@code OperationCreateRequest} (§7.4): ad 3..120, açıklama ≤ 2000, planlanan bitiş gelecekte, 1..10 tekil KOR. */
record OperationCreate(String name, String description, Instant plannedEndAt, List<UUID> coordinatorIds,
		OperationArea area) {

	static final int MAX_COORDINATORS = 10;

	private static final Set<String> FIELDS = Set.of("name", "description", "plannedEndAt", "coordinatorIds", "area");

	static OperationCreate parse(JsonNode body, Instant now) {
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		for (String field : body.propertyNames()) {
			if (OperationUpdate.READ_ONLY.contains(field)) {
				errors.add(new FieldError(field, "READ_ONLY", "Bu alan değiştirilemez"));
			}
			else if (!FIELDS.contains(field)) {
				errors.add(new FieldError(field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		String name = body.has("name") ? OperationUpdate.name(body.get("name"), errors) : null;
		if (!body.has("name")) {
			errors.add(new FieldError("name", "REQUIRED", "Ad zorunludur"));
		}
		Optional<String> description = body.has("description")
				? OperationUpdate.description(body.get("description"), errors) : Optional.empty();
		Optional<Instant> plannedEndAt = body.has("plannedEndAt")
				? OperationUpdate.plannedEndAt(body.get("plannedEndAt"), now, errors) : Optional.empty();
		List<UUID> coordinators = coordinators(body.get("coordinatorIds"), errors);
		Optional<OperationArea> area = body.has("area") ? OperationArea.parse(body.get("area"), errors) : Optional.empty();
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new OperationCreate(name, description.orElse(null), plannedEndAt.orElse(null), coordinators,
				area.orElse(null));
	}

	private static List<UUID> coordinators(JsonNode node, List<FieldError> errors) {
		if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
			errors.add(new FieldError("coordinatorIds", "REQUIRED", "En az bir koordinatör seçilmelidir"));
			return List.of();
		}
		if (!node.isArray()) {
			errors.add(new FieldError("coordinatorIds", "INVALID_FORMAT", "Koordinatörler liste olmalıdır"));
			return List.of();
		}
		if (node.size() > MAX_COORDINATORS) {
			errors.add(new FieldError("coordinatorIds", "OUT_OF_RANGE",
					"En fazla " + MAX_COORDINATORS + " koordinatör seçilebilir"));
			return List.of();
		}
		Set<UUID> ids = new LinkedHashSet<>();
		for (int i = 0; i < node.size(); i++) {
			JsonNode item = node.get(i);
			UUID id = null;
			try {
				id = item.isString() ? UUID.fromString(item.stringValue()) : null;
			}
			catch (IllegalArgumentException ex) {
				// aşağıda INVALID_FORMAT
			}
			if (id == null) {
				errors.add(new FieldError("coordinatorIds[" + i + "]", "INVALID_FORMAT", "Geçersiz kullanıcı kimliği"));
			}
			else if (!ids.add(id)) {
				errors.add(new FieldError("coordinatorIds[" + i + "]", "NOT_ALLOWED_VALUE", "Koordinatör birden fazla kez var"));
			}
		}
		return List.copyOf(ids);
	}

}
