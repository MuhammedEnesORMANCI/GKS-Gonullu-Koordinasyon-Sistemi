package org.gks.backend.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.PageCursor;
import org.gks.backend.access.AccessDirectory;
import org.gks.backend.access.AuditAccess;
import org.gks.backend.access.Subject;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AU-01 {@code GET /audit-entries} (salt-okur; {@code audit.view}: ADM, UYM). Güncelleme veya silme ucu yoktur ve
 * olmayacaktır (K-26); veritabanı da tetikleyiciyle reddeder (V12).
 */
@RestController
@RequestMapping("/api/v1/audit-entries")
class AuditController {

	private static final Set<String> BASES = Set.of("KOR", "ADM", "TEK", "UYM", "LEADER", "SELF", "SYSTEM");

	private final AuditStore store;
	private final AuditAccess access;
	private final AccessDirectory.Users users;
	private final AccessDirectory.Scopes scopes;

	AuditController(AuditStore store, AuditAccess access, AccessDirectory.Users users, AccessDirectory.Scopes scopes) {
		this.store = store;
		this.access = access;
		this.users = users;
		this.scopes = scopes;
	}

	record Ref(UUID id, String displayName) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AuditEntryView(UUID id, Ref actor, String actorBasis, String action, String resourceType, UUID resourceId,
			UUID operationId, String operationName, String client, Instant at, Map<String, Object> details) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AuditPage(List<AuditEntryView> items, String nextCursor) {
	}

	@GetMapping
	@Transactional(readOnly = true)
	AuditPage list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String actorId, @RequestParam(required = false) List<String> action,
			@RequestParam(required = false) String resourceType, @RequestParam(required = false) String resourceId,
			@RequestParam(required = false) String operationId, @RequestParam(required = false) String actorBasis,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		Instant fromAt = instant("from", from, errors);
		Instant toAt = instant("to", to, errors);
		UUID actor = uuid("actorId", actorId, errors);
		UUID resource = uuid("resourceId", resourceId, errors);
		UUID operation = uuid("operationId", operationId, errors);
		Set<String> actions = new HashSet<>();
		for (String a : action == null ? List.<String>of() : action) {
			for (String part : a.split(",")) {
				if (!part.isBlank()) {
					actions.add(part.strip());
				}
			}
		}
		if (actorBasis != null && !BASES.contains(actorBasis)) {
			errors.add(new FieldError("actorBasis", "NOT_ALLOWED_VALUE", "Geçersiz yetki dayanağı"));
		}
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		access.canView(subject).require();
		var filter = new AuditStore.Filter(fromAt, toAt, actor, actions, resourceType, resource, operation, actorBasis);
		List<AuditStore.Row> rows = store.list(filter, page.after(), page.limit());
		PageCursor.Slice<AuditStore.Row> slice = page.slice(rows, AuditStore.Row::id);
		Collection<UUID> actorIds = slice.items().stream().map(AuditStore.Row::actorId).filter(id -> id != null).toList();
		Collection<UUID> operationIds = slice.items().stream().map(AuditStore.Row::operationId).filter(id -> id != null).toList();
		Map<UUID, String> names = actorIds.isEmpty() ? Map.of() : users.displayNames(actorIds);
		Map<UUID, String> operationNames = operationIds.isEmpty() ? new HashMap<>() : scopes.names(operationIds);
		List<AuditEntryView> items = slice.items().stream()
			.map(r -> new AuditEntryView(r.id(), r.actorId() == null ? null : new Ref(r.actorId(), names.get(r.actorId())),
					r.actorBasis(), r.action(), r.resourceType(), r.resourceId(), r.operationId(),
					r.operationId() == null ? null : operationNames.get(r.operationId()), r.client(), r.at(),
					r.details().isEmpty() ? null : r.details()))
			.toList();
		return new AuditPage(items, slice.nextCursor());
	}

	private static Instant instant(String field, String value, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException ex) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "ISO-8601 zaman olmalıdır"));
			return null;
		}
	}

	private static UUID uuid(String field, String value, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Geçersiz kimlik"));
			return null;
		}
	}

}
