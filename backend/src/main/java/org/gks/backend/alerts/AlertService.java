package org.gks.backend.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.AuditTrail;
import org.gks.backend.Idempotency;
import org.gks.backend.IdempotencyResponse;
import org.gks.backend.Ids;
import org.gks.backend.PageCursor;
import org.gks.backend.RealtimeBus;
import org.gks.backend.access.ActingBasis;
import org.gks.backend.access.AlertAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.alerts.AlertStore.AlertRow;
import org.gks.backend.alerts.AlertStore.Counts;
import org.gks.backend.alerts.AlertStore.MyRow;
import org.gks.backend.alerts.AlertStore.RecipientRow;
import org.gks.backend.alerts.AlertViews.AlertPreview;
import org.gks.backend.alerts.AlertViews.AlertRecipientView;
import org.gks.backend.alerts.AlertViews.AlertSummary;
import org.gks.backend.alerts.AlertViews.AlertView;
import org.gks.backend.alerts.AlertViews.GroupRef;
import org.gks.backend.alerts.AlertViews.MyAlertView;
import org.gks.backend.alerts.AlertViews.MyRecipient;
import org.gks.backend.alerts.AlertViews.Page;
import org.gks.backend.alerts.AlertViews.PreviewRecipient;
import org.gks.backend.alerts.AlertViews.Ref;
import org.gks.backend.alerts.AlertViews.ResendResult;
import org.gks.backend.groups.GroupMembers;
import org.gks.backend.identity.UserDirectory;
import org.gks.backend.notifications.Notifier;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.gks.backend.organization.OperationState;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.Operations.OperationRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acil alarm (§11.1, emergency-alert-design). Yönetim işlemleri {@code alert.*} izni ister: operasyonun KOR'u veya ADM
 * (ADR-015, ADR-018). Alıcı işlemleri (AL-08, AL-13 – AL-15) yetkiden değil alıcılıktan doğar ve Android'dendir.
 * <p>
 * İletim bu aşamada gelen kutusu ({@code CRITICAL} bildirim) ve SSE'dir; FCM eklenince alıcı satırları {@code QUEUED}
 * doğup teslim işçisiyle ilerleyecek (ADR-018).
 */
@Service
class AlertService {

	static final int MAX_RECIPIENTS = 500;   // VS-02
	static final int MESSAGE_MAX = 500;
	static final int ACTION_MAX = 200;
	static final int SUMMARY_MESSAGE = 120;
	static final String RESOURCE = "ALERT";

	private final AlertStore store;
	private final AlertAccess access;
	private final Participation participation;
	private final Operations operations;
	private final GroupMembers groups;
	private final UserDirectory users;
	private final Notifier notifier;
	private final Idempotency idempotency;
	private final AuditTrail audit;
	private final ActingBasis acting;
	private final RealtimeBus realtime;
	private final Clock clock;

	AlertService(AlertStore store, AlertAccess access, Participation participation, Operations operations,
			GroupMembers groups, UserDirectory users, Notifier notifier, Idempotency idempotency, AuditTrail audit,
			ActingBasis acting, RealtimeBus realtime, Clock clock) {
		this.store = store;
		this.access = access;
		this.participation = participation;
		this.operations = operations;
		this.groups = groups;
		this.users = users;
		this.notifier = notifier;
		this.idempotency = idempotency;
		this.audit = audit;
		this.acting = acting;
		this.realtime = realtime;
		this.clock = clock;
	}

	record Draft(String message, String requestedAction, String priority, List<UUID> userIds, List<UUID> groupIds,
			UUID correctsAlertId) {
	}

	/** Çözülmüş hedef kitle: kişi → geldiği grup (kişi hedefinde {@code null}), sıralı ve gönderen hariç. */
	record Audience(Map<UUID, UUID> recipients, String fingerprint) {
	}

	// ---- yönetim ----

	/** AL-01: taslak + ilk önizleme ({@code 201}). */
	@Transactional
	AlertPreview create(Subject subject, UUID operationId, Draft draft) {
		access.canManage(subject, operationId).require();
		OperationRef operation = activeOperation(operationId);
		if (draft.correctsAlertId() != null) {
			AlertRow corrected = store.find(draft.correctsAlertId())
				.filter(a -> a.operationId().equals(operationId))
				.orElseThrow(() -> ApiException.semanticValidation(List.of(new FieldError("correctsAlertId", "NOT_FOUND",
						"Düzeltilecek alarm bu operasyonda bulunamadı"))));
			if (corrected.state() != AlertState.CANCELLED) {
				throw ApiException.invalidStateTransition(corrected.state(), "CORRECTED");
			}
			if (store.isCorrected(corrected.id())) {
				throw ApiException.alreadyExists("ALERT_ALREADY_CORRECTED");
			}
		}
		Audience audience = resolve(operationId, draft.userIds(), draft.groupIds(), subject.userId());
		if (audience.recipients().isEmpty()) {
			throw ApiException.semanticValidation(List.of(new FieldError("targets", "EMPTY_AUDIENCE",
					"Hedeflerde alarm alabilecek kimse yok")));
		}
		UUID id = Ids.newId();
		Instant now = clock.instant();
		store.insertDraft(id, operationId, draft.message(), draft.requestedAction(), draft.priority(), draft.userIds(),
				draft.groupIds(), draft.correctsAlertId(), subject.userId(), now);
		store.updatePreview(id, audience.fingerprint(), audience.recipients().size(), now);
		record(subject, "ALERT_DRAFTED", RESOURCE, id, operationId, Map.of("targetUserCount", draft.userIds().size(),
				"targetGroupCount", draft.groupIds().size(), "recipientCount", audience.recipients().size()));
		return preview(store.find(id).orElseThrow(), operation, audience);
	}

	/** AL-04: hedef kitle yeniden çözülür; {@code audienceHash} güncel değerdir. */
	@Transactional
	AlertPreview preview(Subject subject, UUID alertId) {
		AlertRow alert = managed(subject, alertId);
		if (alert.state() != AlertState.DRAFT) {
			throw ApiException.invalidStateTransition(alert.state(), AlertState.DRAFT);
		}
		Audience audience = resolve(alert.operationId(), alert.userIds(), alert.groupIds(), subject.userId());
		store.updatePreview(alertId, audience.fingerprint(), audience.recipients().size(), clock.instant());
		return preview(alert, operations.find(alert.operationId()).orElseThrow(), audience);
	}

	/** AL-03: yalnızca taslak silinir. */
	@Transactional
	void deleteDraft(Subject subject, UUID alertId) {
		AlertRow alert = managed(subject, alertId);
		store.lock(alertId);
		if (alert.state() != AlertState.DRAFT) {
			throw ApiException.invalidStateTransition(alert.state(), "DELETED");
		}
		store.deleteDraft(alertId);
		record(subject, "ALERT_DRAFT_DELETED", RESOURCE, alertId, alert.operationId(), Map.of());
	}

	/** AL-05 (Z): onaylı gönderim. Sürüm ve hedef kitle özeti önizlemeyle aynı olmalı (TD-05, AC-20.8). */
	@Transactional
	IdempotencyResponse send(Subject subject, UUID alertId, int version, String audienceHash, Idempotency.Request idem) {
		AlertRow found = managed(subject, alertId);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		AlertRow alert = store.lock(alertId).orElseThrow();
		if (alert.state() != AlertState.DRAFT) {
			throw ApiException.invalidStateTransition(alert.state(), AlertState.OPEN);
		}
		if (version != alert.version()) {
			throw ApiException.versionConflict(alert.version());
		}
		OperationRef operation = operations.findForShare(found.operationId())
			.filter(op -> op.state() == OperationState.ACTIVE)
			.orElseThrow(() -> ApiException.of(Code.OPERATION_NOT_ACTIVE));
		Audience audience = resolve(alert.operationId(), alert.userIds(), alert.groupIds(), subject.userId());
		if (audience.recipients().isEmpty()) {
			throw ApiException.semanticValidation(List.of(new FieldError("targets", "EMPTY_AUDIENCE",
					"Hedeflerde alarm alabilecek kimse yok")));
		}
		if (!audience.fingerprint().equals(audienceHash)) {
			throw ApiException.audienceChanged(preview(alert, operation, audience));
		}
		Instant now = clock.instant();
		store.insertRecipients(alertId, audience.recipients(), now, Duration.ofSeconds(alert.reminderIntervalSeconds()));
		UUID notificationId = notifier.notify(new NotificationRequest("alert:" + alertId + ":SENT", Notifier.Category.ALERT,
				Notifier.Kind.ALERT_SENT, Notifier.Priority.CRITICAL, "ACİL ALARM: " + operation.name(), body(alert),
				Notifier.SourceType.ALERT, alertId, alert.operationId(),
				Map.of("alert", alertId.toString()), audience.recipients().keySet(), subject.userId()))
			.orElse(null);
		store.markOpen(alertId, subject.userId(), audience.recipients().size(), idem.key(), notificationId, now);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("recipientCount", audience.recipients().size());
		if (idem.key() != null) {
			details.put("idempotencyKey", idem.key());
		}
		record(subject, "ALERT_SENT", RESOURCE, alertId, alert.operationId(), details);
		if (alert.correctsAlertId() != null) {
			record(subject, "ALERT_CORRECTED", RESOURCE, alertId, alert.operationId(),
					Map.of("oldAlertId", alert.correctsAlertId(), "newAlertId", alertId));
		}
		publish(alert.operationId(), "alert.opened", Map.of("alertId", alertId, "state", "OPEN", "recipientCount",
				audience.recipients().size()));
		return idempotency.complete(idem, 202, view(store.find(alertId).orElseThrow()), RESOURCE, alertId);
	}

	/** AL-11 (Z): iptal; tüm alıcılara {@code CRITICAL} "ALARM İPTAL EDİLDİ" (AC-25.1). */
	@Transactional
	IdempotencyResponse cancel(Subject subject, UUID alertId, boolean reasonGiven, Idempotency.Request idem) {
		managed(subject, alertId);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		AlertRow alert = requireOpen(store.lock(alertId).orElseThrow());
		Instant now = clock.instant();
		store.cancel(alertId, subject.userId(), now);
		lifecycleNotice(alert, Notifier.Kind.ALERT_CANCELLED, Notifier.Priority.CRITICAL, "ALARM İPTAL EDİLDİ",
				subject.userId());
		record(subject, "ALERT_CANCELLED", RESOURCE, alertId, alert.operationId(), Map.of("reasonGiven", reasonGiven));
		publish(alert.operationId(), "alert.cancelled", Map.of("alertId", alertId, "state", "CANCELLED"));
		return idempotency.complete(idem, 200, view(store.find(alertId).orElseThrow()), RESOURCE, alertId);
	}

	/** AL-12: elle kapatma ({@code closeReason=MANUAL}). */
	@Transactional
	AlertView close(Subject subject, UUID alertId) {
		managed(subject, alertId);
		AlertRow alert = requireOpen(store.lock(alertId).orElseThrow());
		Instant now = clock.instant();
		store.close(alertId, subject.userId(), "MANUAL", now);
		lifecycleNotice(alert, Notifier.Kind.ALERT_CLOSED, Notifier.Priority.HIGH, "Alarm kapatıldı", subject.userId());
		record(subject, "ALERT_CLOSED", RESOURCE, alertId, alert.operationId(), Map.of("reason", "MANUAL"));
		publish(alert.operationId(), "alert.closed", Map.of("alertId", alertId, "state", "CLOSED"));
		return view(store.find(alertId).orElseThrow());
	}

	/** TD-06: operasyon kapanınca açık alarmlar aynı işlemde {@code CLOSED} ({@code OPERATION_CLOSED}). */
	@Transactional
	void closeForOperation(UUID operationId, UUID closedBy) {
		for (UUID alertId : store.openAlertIds(operationId)) {
			AlertRow alert = store.find(alertId).orElseThrow();
			store.close(alertId, null, "OPERATION_CLOSED", clock.instant());
			lifecycleNotice(alert, Notifier.Kind.ALERT_CLOSED, Notifier.Priority.HIGH, "Alarm kapatıldı (operasyon sona erdi)",
					closedBy);
			audit.record(closedBy, "SYSTEM", "SYSTEM", "ALERT_CLOSED", RESOURCE, alertId, operationId,
					Map.of("reason", "OPERATION_CLOSED"));
			publish(operationId, "alert.closed", Map.of("alertId", alertId, "state", "CLOSED"));
		}
	}

	/** AL-10 (Z): {@code UNANSWERED}/{@code FAILED} alıcılar yeniden iletilir; boş liste = hepsi. */
	@Transactional
	IdempotencyResponse resend(Subject subject, UUID alertId, List<UUID> recipientIds, Idempotency.Request idem) {
		managed(subject, alertId);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		AlertRow alert = requireOpen(store.lock(alertId).orElseThrow());
		List<UUID> requeued = store.resend(alertId, recipientIds, clock.instant(),
				Duration.ofSeconds(alert.reminderIntervalSeconds()));
		if (alert.sentNotificationId() != null) {
			notifier.resurface(alert.sentNotificationId(), requeued);
		}
		int skipped = recipientIds.isEmpty() ? 0 : Math.max(0, new HashSet<>(recipientIds).size() - requeued.size());
		record(subject, "ALERT_RESENT", RESOURCE, alertId, alert.operationId(), Map.of("recipientCount", requeued.size()));
		recipientsChanged(alert.operationId(), alertId, List.of());
		return idempotency.complete(idem, 202, new ResendResult(view(store.find(alertId).orElseThrow()), requeued.size(),
				skipped), RESOURCE, alertId);
	}

	// ---- okuma ----

	/** AL-07: yönetici → {@code AlertView}; alıcı → {@code MyAlertView}; diğerleri {@code 403}. */
	@Transactional(readOnly = true)
	Object get(Subject subject, UUID alertId) {
		AlertRow alert = store.find(alertId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (access.canManage(subject, alert.operationId()).allowed()) {
			return view(alert);
		}
		RecipientRow recipient = store.recipientOf(alertId, subject.userId())
			.orElseThrow(() -> ApiException.of(Code.FORBIDDEN));
		return myView(alert, recipient.id(), recipient.state(), recipient.responseOption(), recipient.respondedAt(),
				operations.names(List.of(alert.operationId())));
	}

	/** AL-06. */
	@Transactional(readOnly = true)
	Page<AlertSummary> listForOperation(Subject subject, UUID operationId, Set<AlertState> states, PageCursor page) {
		access.canManage(subject, operationId).require();
		return summaries(store.list(List.of(operationId), states, page.after(), page.limit()), page);
	}

	/** AL-16: koordine edilen operasyonlar (ADM: tümü); varsayılan {@code OPEN}. */
	@Transactional(readOnly = true)
	Page<AlertSummary> list(Subject subject, Set<AlertState> states, PageCursor page) {
		AlertAccess.ViewScope scope = access.viewScope(subject);
		if (!scope.all() && scope.operationIds().isEmpty()) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		return summaries(store.list(scope.all() ? null : scope.operationIds(), states, page.after(), page.limit()), page);
	}

	/** AL-09: alıcı tablosu; {@code NEED_HELP} önce, sonra durum, sonra ad (≤ 500 satır, tek sayfa). */
	@Transactional(readOnly = true)
	Page<AlertRecipientView> recipients(Subject subject, UUID alertId, Set<RecipientState> states,
			ResponseOption option) {
		AlertRow alert = managed(subject, alertId);
		List<RecipientRow> rows = store.recipients(alert.id()).stream()
			.filter(r -> states.isEmpty() || states.contains(r.state()))
			.filter(r -> option == null || option == r.responseOption())
			.toList();
		Map<UUID, String> names = users.displayNames(rows.stream().map(RecipientRow::userId).toList());
		Map<UUID, String> groupNames = groups.names(rows.stream().map(RecipientRow::viaGroupId)
			.filter(java.util.Objects::nonNull).distinct().toList());
		List<AlertRecipientView> items = rows.stream()
			.sorted(Comparator.comparing((RecipientRow r) -> r.responseOption() != ResponseOption.NEED_HELP)
				.thenComparing(r -> r.state().ordinal())
				.thenComparing(r -> names.getOrDefault(r.userId(), ""), String.CASE_INSENSITIVE_ORDER))
			.map(r -> new AlertRecipientView(r.id(), new Ref(r.userId(), names.get(r.userId())),
					r.viaGroupId() == null ? null : new GroupRef(r.viaGroupId(), groupNames.get(r.viaGroupId())),
					r.state().name(), r.reminderCount(), r.responseOption() == null ? null : r.responseOption().name(),
					r.sentAt(), r.deliveredAt(), r.seenAt(), r.respondedAt(), r.respondedAtDevice(), r.failureReason()))
			.toList();
		return new Page<>(items, null);
	}

	// ---- alıcı ----

	/** AL-08: alıcısı olunan alarmlar (varsayılan {@code OPEN}); tek çağrı, operasyon sayısından bağımsız. */
	@Transactional(readOnly = true)
	Page<MyAlertView> mine(Subject subject, Set<AlertState> states, PageCursor page) {
		List<MyRow> rows = store.mine(subject.userId(), states, page.after(), page.limit());
		PageCursor.Slice<MyRow> slice = page.slice(rows, r -> r.alert().id());
		Map<UUID, String> names = operations.names(slice.items().stream().map(r -> r.alert().operationId()).distinct().toList());
		return new Page<>(slice.items().stream()
			.map(r -> myView(r.alert(), r.recipientId(), r.state(), r.option(), r.respondedAt(), names)).toList(),
				slice.nextCursor());
	}

	/** AL-13/14: yalnızca ileri geçiş; kapalı alarmda ve tekrar çağrıda etkisiz. */
	@Transactional
	void signal(Subject subject, UUID recipientId, RecipientState target) {
		RecipientRow recipient = own(subject, recipientId);
		AlertRow alert = store.find(recipient.alertId()).orElseThrow();
		if (alert.state() == AlertState.OPEN && store.signal(recipientId, target, clock.instant())) {
			recipientsChanged(alert.operationId(), alert.id(), List.of(recipientId));
		}
	}

	/** AL-15 (Z): yanıt veya yanıt değişikliği (TD-07); alarm açık olmalı. */
	@Transactional
	IdempotencyResponse respond(Subject subject, UUID recipientId, ResponseOption option, Instant respondedAtDevice,
			Idempotency.Request idem) {
		own(subject, recipientId);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		RecipientRow recipient = store.lockRecipient(recipientId).orElseThrow();
		AlertRow alert = store.lock(recipient.alertId()).orElseThrow();
		if (alert.state() != AlertState.OPEN) {
			throw ApiException.alertNotOpen(alert.state().name());
		}
		Instant now = clock.instant();
		store.respond(recipientId, option, respondedAtDevice, idem.key(), now);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("alertId", alert.id());
		details.put("option", option.name());
		if (recipient.responseOption() != null) {
			details.put("previousOption", recipient.responseOption().name());
		}
		audit.record(subject.userId(), "SELF", subject.client(), "ALERT_RESPONDED", "ALERT_RECIPIENT", recipientId,
				alert.operationId(), details);
		recipientsChanged(alert.operationId(), alert.id(), List.of(recipientId));
		return idempotency.complete(idem, 200, myView(alert, recipientId, RecipientState.RESPONDED, option, now,
				operations.names(List.of(alert.operationId()))), "ALERT_RECIPIENT", recipientId);
	}

	/** Hatırlatma zamanlayıcısının işlediği durum değişiklikleri (UNANSWERED) panele duyurulur. */
	@Transactional
	int advanceReminders(int batch) {
		Map<UUID, List<UUID>> changed = store.advanceReminders(clock.instant(), batch);
		changed.forEach((alertId, recipients) -> store.find(alertId)
			.ifPresent(alert -> recipientsChanged(alert.operationId(), alertId, recipients)));
		return changed.values().stream().mapToInt(List::size).sum();
	}

	// ---- yardımcılar ----

	/**
	 * Gruplardaki aktif üyeler ∪ seçili kişiler; gönderen hariç (K-07); operasyon katılımcısı olmayan elenir. Kapsam
	 * dışı hedef (başka operasyonun grubu, katılımcı olmayan kişi) {@code 403} (AC-20.3, 20.7); 500'ü aşan
	 * {@code 422 AUDIENCE_TOO_LARGE}. Özet: sıralı alıcı kimliklerinin SHA-256'sı (N-02).
	 */
	private Audience resolve(UUID operationId, List<UUID> userIds, List<UUID> groupIds, UUID sender) {
		Set<UUID> participants = new HashSet<>(participation.participants(operationId));
		Map<UUID, UUID> recipients = new LinkedHashMap<>();
		for (UUID userId : userIds) {
			if (userId.equals(sender)) {
				continue;   // gönderen kendi alarmının alıcısı olmaz (K-07)
			}
			if (!participants.contains(userId)) {
				throw ApiException.of(Code.FORBIDDEN);
			}
			recipients.put(userId, null);
		}
		Map<UUID, List<UUID>> members = groups.activeMembers(operationId, groupIds);
		if (!members.keySet().containsAll(groupIds)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		members.forEach((groupId, list) -> list.forEach(member -> {
			if (!member.equals(sender) && participants.contains(member) && !recipients.containsKey(member)) {
				recipients.put(member, groupId);
			}
		}));
		if (recipients.size() > MAX_RECIPIENTS) {
			throw ApiException.audienceTooLarge(recipients.size(), MAX_RECIPIENTS);
		}
		List<String> sorted = recipients.keySet().stream().map(UUID::toString).sorted().toList();
		return new Audience(recipients, sha256(String.join(",", sorted)));
	}

	private AlertPreview preview(AlertRow alert, OperationRef operation, Audience audience) {
		Map<UUID, String> names = users.displayNames(audience.recipients().keySet());
		Map<UUID, String> groupNames = groups.names(alert.groupIds());
		Map<UUID, List<UUID>> memberships = groups.activeMembers(alert.operationId(), alert.groupIds());
		List<PreviewRecipient> recipients = new ArrayList<>();
		for (UUID userId : audience.recipients().keySet()) {
			List<GroupRef> via = memberships.entrySet().stream().filter(e -> e.getValue().contains(userId))
				.map(e -> new GroupRef(e.getKey(), groupNames.get(e.getKey()))).toList();
			recipients.add(new PreviewRecipient(userId, names.get(userId), via));
		}
		return new AlertPreview(alert.id(), alert.operationId(), operation.name(), alert.version(), audience.fingerprint(),
				recipients.size(), recipients, alert.message(), alert.requestedAction(), alert.priority(),
				alert.correctsAlertId());
	}

	AlertView view(AlertRow alert) {
		Set<UUID> people = new LinkedHashSet<>();
		for (UUID id : new UUID[] { alert.createdBy(), alert.sentBy(), alert.cancelledBy(), alert.closedBy() }) {
			if (id != null) {
				people.add(id);
			}
		}
		Map<UUID, String> names = users.displayNames(people);
		Counts counts = store.counts(List.of(alert.id())).getOrDefault(alert.id(), Counts.EMPTY);
		return new AlertView(alert.id(), alert.operationId(), operations.names(List.of(alert.operationId())).get(alert.operationId()),
				alert.state().name(), alert.version(), alert.message(), alert.requestedAction(), alert.priority(),
				ResponseOption.ALL, ref(alert.createdBy(), names), alert.createdAt(), ref(alert.sentBy(), names),
				alert.sentAt(), alert.recipientCount(), counts, false, ref(alert.cancelledBy(), names), alert.cancelledAt(),
				ref(alert.closedBy(), names), alert.closedAt(), alert.closeReason(), alert.correctsAlertId(),
				alert.correctedByAlertId());
	}

	private Page<AlertSummary> summaries(List<AlertRow> rows, PageCursor page) {
		PageCursor.Slice<AlertRow> slice = page.slice(rows, AlertRow::id);
		List<UUID> ids = slice.items().stream().map(AlertRow::id).toList();
		Map<UUID, Counts> counts = store.counts(ids);
		Map<UUID, String> names = operations.names(slice.items().stream().map(AlertRow::operationId).distinct().toList());
		return new Page<>(slice.items().stream().map(a -> new AlertSummary(a.id(), a.operationId(), names.get(a.operationId()),
				a.state().name(), a.message().length() > SUMMARY_MESSAGE ? a.message().substring(0, SUMMARY_MESSAGE - 1) + "…"
						: a.message(), a.priority(), a.createdAt(), a.sentAt(), counts.getOrDefault(a.id(), Counts.EMPTY)))
			.toList(), slice.nextCursor());
	}

	private static MyAlertView myView(AlertRow alert, UUID recipientId, RecipientState state, ResponseOption option,
			Instant respondedAt, Map<UUID, String> operationNames) {
		return new MyAlertView(alert.id(), alert.operationId(), operationNames.get(alert.operationId()), alert.state().name(),
				alert.message(), alert.requestedAction(), alert.priority(), ResponseOption.ALL, alert.sentAt(),
				new MyRecipient(recipientId, state.name(), option == null ? null : option.name(), respondedAt));
	}

	private AlertRow managed(Subject subject, UUID alertId) {
		AlertRow alert = store.find(alertId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		access.canManage(subject, alert.operationId()).require();
		return alert;
	}

	/** Alıcı kaydı yalnızca sahibine görünür; başkasınınki {@code 404} (varlık sızdırılmaz). */
	private RecipientRow own(Subject subject, UUID recipientId) {
		return store.recipient(recipientId).filter(r -> r.userId().equals(subject.userId()))
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
	}

	private OperationRef activeOperation(UUID operationId) {
		OperationRef operation = operations.findForShare(operationId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (operation.state() != OperationState.ACTIVE) {
			throw ApiException.of(Code.OPERATION_NOT_ACTIVE);
		}
		return operation;
	}

	private static AlertRow requireOpen(AlertRow alert) {
		if (alert.state() != AlertState.OPEN) {
			throw ApiException.alertNotOpen(alert.state().name());
		}
		return alert;
	}

	private void lifecycleNotice(AlertRow alert, Notifier.Kind kind, Notifier.Priority priority, String title, UUID by) {
		List<UUID> recipients = store.recipientUserIds(alert.id());
		notifier.notify(NotificationRequest.toUsers("alert:" + alert.id() + ":" + kind.name(), Notifier.Category.ALERT,
				kind, priority, title, body(alert), Notifier.SourceType.ALERT, alert.id(), alert.operationId(), recipients, by));
	}

	private static String body(AlertRow alert) {
		return alert.requestedAction() == null ? alert.message() : alert.message() + "\n" + alert.requestedAction();
	}

	private void recipientsChanged(UUID operationId, UUID alertId, Collection<UUID> recipientIds) {
		Counts counts = store.counts(List.of(alertId)).getOrDefault(alertId, Counts.EMPTY);
		publish(operationId, "alert.recipient.changed", Map.of("alertId", alertId, "recipientIds", List.copyOf(recipientIds),
				"counts", counts, "deliveryDelayed", false));
	}

	private void publish(UUID operationId, String type, Map<String, Object> data) {
		realtime.publish(RealtimeBus.operationTopic(operationId), type, operationId, data);
	}

	private void record(Subject subject, String action, String resourceType, UUID resourceId, UUID operationId,
			Map<String, Object> details) {
		audit.record(subject.userId(), acting.operation(subject, operationId), subject.client(), action, resourceType,
				resourceId, operationId, details);
	}

	private static Ref ref(UUID id, Map<UUID, String> names) {
		return id == null ? null : new Ref(id, names.get(id));
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
