package org.gks.backend.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.gks.backend.access.ActingBasis;
import org.gks.backend.AuditTrail;
import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Idempotency;
import org.gks.backend.IdempotencyResponse;
import org.gks.backend.Ids;
import org.gks.backend.access.AccessDirectory;
import org.gks.backend.notifications.Notifier;
import org.gks.backend.access.Decision;
import org.gks.backend.access.OperationAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Role;
import org.gks.backend.access.RoleAssignments;
import org.gks.backend.access.Subject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * ID-12 – ID-18 (backend §5, TD-01, TD-02, K-06; LD-37: kod gövdede taşınır). Kayıtlı olmayan e-postaya
 * {@code NEW_USER} (Android'de kodla kayıt), kayıtlı kullanıcıya {@code OPERATION} daveti (uygulamada kabul/ret).
 * Kabul, operasyon kapsamında GON katılımını oluşturur.
 */
@Service
class InvitationService {

	static final Duration LIFETIME = Duration.ofHours(72);   // K-06
	static final String RESOURCE_INVITATION = "INVITATION";
	static final String RESOURCE_USER = "USER";
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Pattern INSTALLATION_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private final Invitations invitations;
	private final InvitationCodes codes;
	private final InvitationMailer mailer;
	private final Users users;
	private final UserAccounts accounts;
	private final RefreshTokens refreshTokens;
	private final AccessTokens accessTokens;
	private final RoleAssignments roles;
	private final Participation participation;
	private final OperationAccess operationAccess;
	private final AccessDirectory.Scopes scopes;
	private final AccessDirectory.Users directory;
	private final Idempotency idempotency;
	private final Notifier notifier;
	private final Clock clock;
	private final AuditTrail audit;
	private final ActingBasis acting;

	InvitationService(Invitations invitations, InvitationCodes codes, InvitationMailer mailer, Users users,
			UserAccounts accounts, RefreshTokens refreshTokens, AccessTokens accessTokens, RoleAssignments roles,
			Participation participation, OperationAccess operationAccess, AccessDirectory.Scopes scopes,
			AccessDirectory.Users directory, Idempotency idempotency, Notifier notifier, Clock clock, AuditTrail audit,
			ActingBasis acting) {
		this.invitations = invitations;
		this.codes = codes;
		this.mailer = mailer;
		this.users = users;
		this.accounts = accounts;
		this.refreshTokens = refreshTokens;
		this.accessTokens = accessTokens;
		this.roles = roles;
		this.participation = participation;
		this.operationAccess = operationAccess;
		this.scopes = scopes;
		this.directory = directory;
		this.idempotency = idempotency;
		this.notifier = notifier;
		this.clock = clock;
		this.audit = audit;
		this.acting = acting;
	}

	/** §5 {@code InvitationView}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InvitationView(UUID id, Invitations.Kind kind, Invitations.Status status, String email, String scopeType,
			UUID scopeId, String scopeName, Instant expiresAt, Instant createdAt, UserRef createdBy) {
	}

	record UserRef(UUID id, String displayName) {
	}

	/** §5 {@code InvitationPublicView}: e-posta ve kişisel veri yok. */
	record InvitationPublicView(Invitations.Kind kind, String scopeName, String organizationName, Instant expiresAt) {
	}

	record CreateInput(String email, String scopeType, UUID scopeId) {
	}

	/** ID-16 gövdesi; parola ve kod metne dönüşmez. */
	record AcceptInput(String code, String password, String fullName, String displayName, String phone,
			String installationId) {
		@Override
		public String toString() {
			return "InvitationAccept[***]";
		}
	}

	record Page(List<InvitationView> items, UUID lastId) {
	}

	// ---------------------------------------------------------------- ID-12

	static CreateInput parseCreate(JsonNode body) {
		List<FieldError> errors = new ArrayList<>();
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of(new FieldError("body", "REQUIRED", "Gövde zorunludur")));
		}
		String email = text(body, "email");
		if (email == null || email.isBlank()) {
			errors.add(new FieldError("email", "REQUIRED", "E-posta zorunludur"));
		}
		else {
			email = UserAccounts.normalizeEmail(email);
			if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
				errors.add(new FieldError("email", "INVALID_FORMAT", "Geçerli bir e-posta adresi girin"));
			}
		}
		String scopeType = text(body, "scopeType");
		if (scopeType == null) {
			errors.add(new FieldError("scopeType", "REQUIRED", "Kapsam türü zorunludur"));
		}
		else if (!scopeType.equals("ORGANIZATION") && !scopeType.equals("OPERATION")) {
			errors.add(new FieldError("scopeType", "INVALID_ENUM", "ORGANIZATION veya OPERATION olmalıdır"));
		}
		UUID scopeId = null;
		String rawScope = text(body, "scopeId");
		if (rawScope == null) {
			errors.add(new FieldError("scopeId", "REQUIRED", "Kapsam zorunludur"));
		}
		else {
			try {
				scopeId = UUID.fromString(rawScope);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("scopeId", "INVALID_FORMAT", "Geçersiz kimlik"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new CreateInput(email, scopeType, scopeId);
	}

	@Transactional
	IdempotencyResponse create(Subject subject, CreateInput input, Idempotency.Request idem) {
		inviteDecision(subject, input.scopeType(), input.scopeId()).require();
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		String scopeName;
		if (input.scopeType().equals("OPERATION")) {
			AccessDirectory.ScopeInfo operation = scopes.operationForShare(input.scopeId())
				.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
			if (operation.closed()) {
				throw ApiException.of(Code.OPERATION_CLOSED);
			}
			scopeName = operation.name();
		}
		else {
			if (!scopes.organizationId().equals(input.scopeId())) {
				throw ApiException.validation(List.of(new FieldError("scopeId", "NOT_ALLOWED_VALUE", "Kurum bulunamadı")));
			}
			scopeName = scopes.names(List.of(input.scopeId())).get(input.scopeId());
		}

		Optional<Users.Credentials> registered = users.findByEmail(input.email());
		Invitations.Kind kind;
		UUID userId = null;
		if (registered.isPresent()) {
			if (input.scopeType().equals("ORGANIZATION")) {
				throw ApiException.alreadyExists("EMAIL_REGISTERED");   // kayıtlı kullanıcıya kurum daveti
			}
			if (registered.get().status() != Users.Status.ACTIVE) {
				throw ApiException.validation(List.of(new FieldError("email", "NOT_ALLOWED_VALUE", "Hesap aktif değil")));
			}
			userId = registered.get().id();
			if (participation.isParticipant(input.scopeId(), userId)) {
				throw ApiException.alreadyExists("ALREADY_PARTICIPANT");
			}
			kind = Invitations.Kind.OPERATION;
		}
		else {
			kind = Invitations.Kind.NEW_USER;
		}

		Instant now = clock.instant();
		invitations.expireStale(input.email(), input.scopeType(), input.scopeId(), now);
		UUID id = Ids.newId();
		String code = newUniqueCode();
		Instant expiresAt = now.plus(LIFETIME);
		try {
			invitations.insert(id, kind, input.email(), userId, input.scopeType(), input.scopeId(),
					codes.hash("t:" + codes.newToken()), codes.hash(code), expiresAt, subject.userId(), now);
		}
		catch (DuplicateKeyException ex) {
			throw ApiException.alreadyExists("INVITATION_PENDING");
		}
		if (kind == Invitations.Kind.OPERATION) {
			// Kayıtlı gönüllüye uygulama içi bildirim (communication.md §1.5 OPERATION_INVITE); davetle aynı işlemde.
			notifier.notify(Notifier.NotificationRequest.toUsers("invitation:" + id + ":CREATED",
					Notifier.Category.OPERATION_INVITE, Notifier.Kind.OPERATION_INVITE, Notifier.Priority.NORMAL,
					"Operasyon daveti: " + scopeName,
					"\"" + scopeName + "\" operasyonuna katılmaya davet edildiniz. Kabul veya reddetmek için açın.",
					Notifier.SourceType.INVITATION, id, input.scopeId(), List.of(userId), subject.userId()));
		}
		boolean operationScope = input.scopeType().equals("OPERATION");
		audit.record(subject.userId(), operationScope ? acting.operation(subject, input.scopeId()) : acting.organization(subject),
				subject.client(), "INVITATION_CREATED", "INVITATION", id, operationScope ? input.scopeId() : null,
				Map.of("scopeType", input.scopeType(), "kind", kind.name()));   // e-posta denetime yazılmaz
		String email = input.email();
		afterCommit(() -> {
			if (kind == Invitations.Kind.NEW_USER) {
				mailer.sendNewUser(email, scopeName, code, expiresAt);
			}
			else {
				mailer.sendOperation(email, scopeName, expiresAt);
			}
		});
		return idempotency.complete(idem, 201, view(invitations.find(id).orElseThrow()), RESOURCE_INVITATION, id);
	}

	/** Kod çakışması pratikte olmaz (30^8); yine de benzersizlik indeksine güvenmeden önce denetlenir. */
	private String newUniqueCode() {
		for (int attempt = 0; attempt < 5; attempt++) {
			String code = codes.newCode();
			if (invitations.findByCode(codes.hash(code), false).isEmpty()) {
				return code;
			}
		}
		throw new IllegalStateException("Benzersiz davet kodu üretilemedi");
	}

	// ---------------------------------------------------------------- ID-13, ID-14

	@Transactional(readOnly = true)
	Page list(Subject subject, String scopeType, UUID scopeId, Collection<Invitations.Status> statuses, UUID after,
			int limit) {
		Set<UUID> allowed = manageableScopes(subject);
		if (allowed != null && scopeId != null && !allowed.contains(scopeId)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		List<Invitations.Row> rows = invitations.list(allowed, scopeType, scopeId, statuses, after, limit + 1,
				clock.instant());
		boolean more = rows.size() > limit;
		List<Invitations.Row> current = more ? rows.subList(0, limit) : rows;
		return new Page(views(current), more ? current.getLast().id() : null);
	}

	@Transactional
	InvitationView revoke(Subject subject, UUID id) {
		Invitations.Row row = invitations.lock(id).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		Decision decision = inviteDecision(subject, row.scopeType(), row.scopeId());
		if (decision == Decision.FORBIDDEN) {
			throw ApiException.of(Code.NOT_FOUND);   // kapsam dışındaki davetin varlığı açık edilmez
		}
		decision.require();
		Invitations.Status status = row.effectiveStatus(clock.instant());
		if (status != Invitations.Status.PENDING) {
			throw ApiException.invalidStateTransition(status, Invitations.Status.REVOKED);
		}
		invitations.revoke(id);
		boolean operationScope = row.scopeType().equals("OPERATION");
		audit.record(subject.userId(), operationScope ? acting.operation(subject, row.scopeId()) : acting.organization(subject),
				subject.client(), "INVITATION_REVOKED", "INVITATION", id, operationScope ? row.scopeId() : null,
				Map.of("scopeType", row.scopeType()));
		return view(invitations.find(id).orElseThrow());
	}

	// ---------------------------------------------------------------- ID-15, ID-16 (kimliksiz)

	@Transactional(readOnly = true)
	InvitationPublicView lookup(String rawCode) {
		Invitations.Row row = byCode(rawCode, false);
		Invitations.Status status = row.effectiveStatus(clock.instant());
		if (status != Invitations.Status.PENDING) {
			throw ApiException.invitationInvalid(status.name());
		}
		UUID organizationId = scopes.organizationId();
		Map<UUID, String> names = scopes.names(List.of(row.scopeId(), organizationId));
		return new InvitationPublicView(row.kind(), names.get(row.scopeId()), names.get(organizationId), row.expiresAt());
	}

	/** Kodun anahtarlı özeti: ID-16'nın idempotency kapsam anahtarı ({@code anon:} + özet, §3.6). */
	String anonymousPrincipal(String rawCode) {
		String code = InvitationCodes.normalizeCode(rawCode);
		if (code == null) {
			throw ApiException.of(Code.NOT_FOUND);
		}
		return "anon:" + codes.hash(code);
	}

	/** ID-16 sonucu: yeni hesap ve Android oturumu. Belirteçler metne dönüşmez. */
	record Accepted(UUID userId, AccessTokens.Issued access, RefreshTokens.Issued refresh) {
		@Override
		public String toString() {
			return "InvitationAccepted[userId=" + userId + "]";
		}
	}

	@Transactional
	Accepted acceptNewUser(AcceptInput input, Idempotency.Request idem) {
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);   // aynı anahtar: 409 ALREADY_COMPLETED
		if (replay.isPresent()) {
			throw new IllegalStateException("Sır içeren yanıt tekrar edilemez");
		}
		Invitations.Row row = byCode(input.code(), true);
		Instant now = clock.instant();
		Invitations.Status status = row.effectiveStatus(now);
		if (status != Invitations.Status.PENDING) {
			throw ApiException.invitationInvalid(status.name());
		}
		if (row.kind() != Invitations.Kind.NEW_USER) {
			throw ApiException.alreadyExists("EMAIL_REGISTERED");   // kayıtlı kullanıcı girişten sonra kabul eder
		}
		if (input.installationId() == null || input.installationId().isEmpty()) {
			throw ApiException.validation(List.of(new FieldError("installationId", "REQUIRED", "Kurulum kimliği zorunludur")));
		}
		if (!INSTALLATION_ID.matcher(input.installationId()).matches()) {
			throw ApiException.validation(List.of(new FieldError("installationId", "INVALID_FORMAT", "Kurulum kimliği geçersiz")));
		}
		UUID userId = accounts.register(new NewUserAccount(row.email(), input.password(), input.fullName(),
				input.displayName(), input.phone()));
		if (row.scopeType().equals("OPERATION") && !roles.joinOperation(row.scopeId(), userId, row.createdBy())) {
			throw ApiException.invitationInvalid("OPERATION_CLOSED");
		}
		invitations.close(row.id(), Invitations.Status.ACCEPTED, userId, now);
		RefreshTokens.Issued refresh = refreshTokens.startSession(userId, ClientType.ANDROID, input.installationId(),
				false, now);
		AccessTokens.Issued access = accessTokens.issue(userId, refresh.sessionId(), ClientType.ANDROID, false, now);
		idempotency.completeRedacted(idem, 201, RESOURCE_USER, userId);
		return new Accepted(userId, access, refresh);
	}

	// ---------------------------------------------------------------- ID-17, ID-18 (kayıtlı kullanıcı)

	@Transactional
	IdempotencyResponse respond(Subject subject, UUID id, boolean accept, Idempotency.Request idem) {
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		Invitations.Row row = invitations.lock(id)
			.filter(r -> subject.userId().equals(r.userId()) && r.kind() == Invitations.Kind.OPERATION)
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		Instant now = clock.instant();
		Invitations.Status status = row.effectiveStatus(now);
		if (status != Invitations.Status.PENDING) {
			throw ApiException.invitationInvalid(status.name());
		}
		if (accept && !roles.joinOperation(row.scopeId(), subject.userId(), row.createdBy())) {
			throw ApiException.invitationInvalid("OPERATION_CLOSED");
		}
		invitations.close(id, accept ? Invitations.Status.ACCEPTED : Invitations.Status.DECLINED, subject.userId(), now);
		return idempotency.complete(idem, 200, view(invitations.find(id).orElseThrow()), RESOURCE_INVITATION, id);
	}

	/** {@code MeContext.pendingInvitations}. */
	@Transactional(readOnly = true)
	List<InvitationView> pendingFor(UUID userId) {
		return views(invitations.pendingFor(userId, clock.instant()));
	}

	// ---------------------------------------------------------------- yardımcılar

	private Invitations.Row byCode(String rawCode, boolean forUpdate) {
		String code = InvitationCodes.normalizeCode(rawCode);
		if (code == null) {
			throw ApiException.of(Code.NOT_FOUND);
		}
		return invitations.findByCode(codes.hash(code), forUpdate).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
	}

	/** {@code invitation.create}: kurum kapsamı ADM; operasyon kapsamı ADM veya o operasyonun KOR'u (MFA'lı web). */
	private Decision inviteDecision(Subject subject, String scopeType, UUID scopeId) {
		return scopeType.equals("ORGANIZATION") ? operationAccess.canCreate(subject)
				: operationAccess.canManage(subject, scopeId);
	}

	/** ID-13: {@code null} = tüm kapsamlar (ADM); aksi hâlde kullanıcının yönettiği operasyonlar. */
	private Set<UUID> manageableScopes(Subject subject) {
		Decision admin = operationAccess.canCreate(subject);
		if (admin.allowed()) {
			return null;
		}
		Set<UUID> allowed = new HashSet<>();
		Decision strongest = admin;
		for (Map.Entry<UUID, Set<Role>> entry : roles.operationRoles(subject.userId()).entrySet()) {
			if (!entry.getValue().contains(Role.KOR)) {
				continue;
			}
			Decision decision = operationAccess.canManage(subject, entry.getKey());
			if (decision.allowed()) {
				allowed.add(entry.getKey());
			}
			else if (decision == Decision.CLIENT_NOT_ALLOWED) {
				strongest = Decision.CLIENT_NOT_ALLOWED;
			}
		}
		if (allowed.isEmpty()) {
			strongest.require();
			throw ApiException.of(Code.FORBIDDEN);
		}
		return allowed;
	}

	private InvitationView view(Invitations.Row row) {
		return views(List.of(row)).getFirst();
	}

	private List<InvitationView> views(List<Invitations.Row> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> scopeNames = scopes.names(rows.stream().map(Invitations.Row::scopeId).distinct().toList());
		Map<UUID, String> userNames = directory.displayNames(rows.stream().map(Invitations.Row::createdBy).distinct()
			.toList());
		Instant now = clock.instant();
		return rows.stream()
			.map(row -> new InvitationView(row.id(), row.kind(), row.effectiveStatus(now), row.email(), row.scopeType(),
					row.scopeId(), scopeNames.get(row.scopeId()), row.expiresAt(), row.createdAt(),
					new UserRef(row.createdBy(), userNames.get(row.createdBy()))))
			.toList();
	}

	private static String text(JsonNode body, String field) {
		JsonNode node = body.get(field);
		return node == null || node.isNull() ? null : node.asString();
	}

	/** E-posta, davet işlemi commit edildikten sonra gider; geri alınan davet için e-posta gitmez. */
	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
	}

}
