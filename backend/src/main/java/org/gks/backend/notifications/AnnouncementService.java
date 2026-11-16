package org.gks.backend.notifications;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.access.ActingBasis;
import org.gks.backend.AuditTrail;
import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Idempotency;
import org.gks.backend.IdempotencyResponse;
import org.gks.backend.Ids;
import org.gks.backend.PageCursor;
import org.gks.backend.access.AccessDirectory;
import org.gks.backend.access.AnnouncementAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.notifications.NotificationDirectories.GroupAudience;
import org.gks.backend.notifications.NotificationStore.AnnouncementRow;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Duyurular (NT-01 – NT-03, NT-06; US-11, AC-11.1 – 11.4). Hedef kitle gönderen kapsamıyla kesişir (K-31):
 * KOR yalnızca kendi {@code ACTIVE} operasyonunun katılımcıları ve grupları (N-19), ADM kurum geneli (K-09).
 * Kapsam dışı kişi veya grup sessizce düşürülmez, {@code 403 FORBIDDEN} döner (§11.3). Gönderen alıcı değildir.
 */
@Service
class AnnouncementService {

	static final int CONFIRMATION_THRESHOLD = 50;
	static final String RESOURCE = "ANNOUNCEMENT";

	private final AnnouncementAccess access;
	private final Participation participation;
	private final GroupAudience groups;
	private final AccessDirectory.Users users;
	private final AccessDirectory.Scopes scopes;
	private final Notifier notifier;
	private final NotificationStore store;
	private final Idempotency idempotency;
	private final AuditTrail audit;
	private final ActingBasis acting;

	AnnouncementService(AnnouncementAccess access, Participation participation, GroupAudience groups,
			AccessDirectory.Users users, AccessDirectory.Scopes scopes, Notifier notifier, NotificationStore store,
			Idempotency idempotency, AuditTrail audit, ActingBasis acting) {
		this.audit = audit;
		this.acting = acting;
		this.access = access;
		this.participation = participation;
		this.groups = groups;
		this.users = users;
		this.scopes = scopes;
		this.notifier = notifier;
		this.store = store;
		this.idempotency = idempotency;
	}

	/** §11.3 {@code AnnouncementTargetRequest}. */
	record Target(UUID operationId, List<UUID> userIds, List<UUID> groupIds, boolean organization) {
		Target {
			userIds = userIds == null ? List.of() : List.copyOf(userIds);
			groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
		}
	}

	record UserRef(UUID id, String displayName) {
	}

	record Preview(int recipientCount, List<UserRef> recipients, boolean confirmationRequired) {
	}

	record Delivery(int sent, int failed, int read) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AnnouncementView(UUID id, String title, String body, UUID operationId, String operationName,
			UserRef createdBy, Instant createdAt, int recipientCount, Delivery delivery) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AnnouncementPage(List<AnnouncementView> items, String nextCursor) {
	}

	private record Audience(UUID operationId, Map<String, Object> expr, Set<UUID> recipients) {
	}

	/** NT-01: yan etkisiz önizleme (alıcı sayısı ve adları; onay eşiği AC-11.3). */
	@Transactional
	Preview preview(Subject subject, Target target) {
		Audience audience = resolve(subject, target);
		Map<UUID, String> names = users.displayNames(audience.recipients());
		List<UserRef> recipients = audience.recipients().stream().map(id -> new UserRef(id, names.get(id))).toList();
		return new Preview(recipients.size(), recipients, recipients.size() > CONFIRMATION_THRESHOLD);
	}

	/** NT-02 ({@code Z}): {@code 202}; bildirim ve IN_APP teslimleri bu işlemde, PUSH (sağlayıcı varsa) kuyrukta. */
	@Transactional
	IdempotencyResponse create(Subject subject, Target target, String title, String body, boolean confirm,
			Idempotency.Request idem) {
		Audience audience = resolve(subject, target);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (audience.recipients().size() > CONFIRMATION_THRESHOLD && !confirm) {
			throw ApiException.semanticValidation(List.of(new FieldError("confirm", "CONFIRMATION_REQUIRED",
					"50'den fazla alıcıya gönderim için onay gerekir")));
		}
		UUID announcementKey = Ids.newId();
		UUID id = notifier.notify(new NotificationRequest("announcement:" + announcementKey, Notifier.Category.ANNOUNCEMENT,
				Notifier.Kind.ANNOUNCEMENT, Notifier.Priority.NORMAL, title, body, null, null, audience.operationId(),
				audience.expr(), audience.recipients(), subject.userId()))
			.orElseThrow();
		// LD-60 (7): ANNOUNCEMENT_SENT; başlık ve metin denetime yazılmaz (AC-27.2), yalnızca hedef türü ve sayılar.
		String targetKind = target.organization() ? "ORGANIZATION"
				: target.userIds().isEmpty() && target.groupIds().isEmpty() ? "OPERATION" : "SELECTED";
		audit.record(subject.userId(), target.organization() ? acting.organization(subject)
				: acting.operation(subject, audience.operationId()), subject.client(), "ANNOUNCEMENT_SENT", "NOTIFICATION", id,
				audience.operationId(), Map.of("target", targetKind, "recipientCount", audience.recipients().size(),
						"userCount", target.userIds().size(), "groupCount", target.groupIds().size()));
		return idempotency.complete(idem, 202, view(store.announcement(id).orElseThrow(), true), RESOURCE, id);
	}

	/** NT-03: gönderen kapsamındaki duyurular (KOR kendi operasyonları; ADM tümü), en yeni önce. */
	@Transactional(readOnly = true)
	AnnouncementPage list(Subject subject, UUID operationId, PageCursor page) {
		AnnouncementAccess.ListScope scope = access.listScope(subject);
		if (scope.isEmpty()) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		if (operationId != null && !scope.organization() && !scope.operationIds().contains(operationId)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		List<AnnouncementRow> rows = store.announcements(scope.organization() ? null : scope.operationIds(),
				scope.organization(), operationId, page.after(), page.limit());
		PageCursor.Slice<AnnouncementRow> slice = page.slice(rows, AnnouncementRow::id);
		return new AnnouncementPage(views(slice.items(), false), slice.nextCursor());
	}

	/** NT-06: kapsam dışındaki duyurunun varlığı açık edilmez ({@code 404}). */
	@Transactional(readOnly = true)
	AnnouncementView get(Subject subject, UUID id) {
		AnnouncementAccess.ListScope scope = access.listScope(subject);
		if (scope.isEmpty()) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		AnnouncementRow row = store.announcement(id)
			.filter(r -> scope.organization() || (r.operationId() != null && scope.operationIds().contains(r.operationId())))
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		return view(row, true);
	}

	// ---------------------------------------------------------------- hedef kitle

	private Audience resolve(Subject subject, Target target) {
		if (target.organization()) {
			if (target.operationId() != null || !target.userIds().isEmpty() || !target.groupIds().isEmpty()) {
				throw ApiException.validation(List.of(new FieldError("organization", "NOT_ALLOWED_VALUE",
						"Kurum geneli duyuruda operasyon, kişi veya grup hedefi verilmez")));
			}
			access.canSendToOrganization(subject).require();
			return new Audience(null, Map.of("organization", true), withoutSender(users.activeUserIds(), subject));
		}
		if (target.operationId() == null) {
			throw ApiException.validation(List.of(new FieldError("operationId", "REQUIRED", "Operasyon zorunludur")));
		}
		AccessDirectory.ScopeInfo operation = scopes.operationForShare(target.operationId())
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		access.canSendToOperation(subject, operation.id()).require();
		if (!"ACTIVE".equals(operation.state())) {
			throw ApiException.of(Code.OPERATION_NOT_ACTIVE);   // §14 N-19: KOR duyurusu yalnızca aktif operasyonda
		}

		Set<UUID> recipients = new LinkedHashSet<>();
		Map<String, Object> expr = new LinkedHashMap<>();
		expr.put("operation", operation.id().toString());
		if (target.userIds().isEmpty() && target.groupIds().isEmpty()) {
			recipients.addAll(participation.participants(operation.id()));
		}
		else {
			for (UUID userId : target.userIds()) {
				if (!participation.isParticipant(operation.id(), userId)) {
					throw ApiException.of(Code.FORBIDDEN);   // kapsam dışı hedef
				}
				recipients.add(userId);
			}
			Map<UUID, List<UUID>> members = groups.activeMembers(operation.id(), new LinkedHashSet<>(target.groupIds()));
			if (!members.keySet().containsAll(target.groupIds())) {
				throw ApiException.of(Code.FORBIDDEN);   // başka operasyonun veya arşivli grup
			}
			members.values().forEach(recipients::addAll);
			if (!target.userIds().isEmpty()) {
				expr.put("users", target.userIds().stream().map(UUID::toString).toList());
			}
			if (!target.groupIds().isEmpty()) {
				expr.put("groups", target.groupIds().stream().map(UUID::toString).toList());
			}
		}
		Set<UUID> finalRecipients = withoutSender(recipients, subject);
		if (finalRecipients.isEmpty()) {
			throw ApiException.semanticValidation(List.of(new FieldError("targets", "EMPTY_AUDIENCE",
					"Hedeflerde duyuru alacak kimse yok")));
		}
		return new Audience(operation.id(), expr, finalRecipients);
	}

	private static Set<UUID> withoutSender(Collection<UUID> recipients, Subject subject) {
		Set<UUID> result = new LinkedHashSet<>(recipients);
		result.remove(subject.userId());
		return result;
	}

	// ---------------------------------------------------------------- görünüm

	private List<AnnouncementView> views(List<AnnouncementRow> rows, boolean withBody) {
		Set<UUID> people = new LinkedHashSet<>();
		Set<UUID> operations = new LinkedHashSet<>();
		rows.forEach(r -> {
			if (r.createdBy() != null) {
				people.add(r.createdBy());
			}
			if (r.operationId() != null) {
				operations.add(r.operationId());
			}
		});
		Map<UUID, String> names = people.isEmpty() ? Map.of() : users.displayNames(people);
		Map<UUID, String> operationNames = operations.isEmpty() ? Map.of() : scopes.names(operations);
		List<AnnouncementView> views = new ArrayList<>();
		for (AnnouncementRow r : rows) {
			views.add(new AnnouncementView(r.id(), r.title(), withBody ? r.body() : null, r.operationId(),
					r.operationId() == null ? null : operationNames.get(r.operationId()),
					r.createdBy() == null ? null : new UserRef(r.createdBy(), names.get(r.createdBy())), r.createdAt(),
					r.recipientCount(), new Delivery(r.sent(), r.failed(), r.read())));
		}
		return views;
	}

	private AnnouncementView view(AnnouncementRow row, boolean withBody) {
		return views(List.of(row), withBody).get(0);
	}

}
