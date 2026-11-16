package org.gks.backend.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.AuditTrail;
import org.gks.backend.RealtimeBus;
import org.gks.backend.access.LocationAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.audit.LocationViews;
import org.gks.backend.groups.GroupMembers;
import org.gks.backend.identity.UserDirectory;
import org.gks.backend.location.LocationStore.LastLocation;
import org.gks.backend.location.LocationStore.SharingRow;
import org.gks.backend.organization.OperationState;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.ParticipantDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Konum paylaşımı (§10, K-15 – K-16, ADR-019). Gönüllü yalnızca {@code ACTIVE} operasyonda ve açıkça "paylaşımı
 * başlat" diyerek paylaşır, istediği an durdurur. Konumu yalnızca operasyonun KOR'u görür; her görüntüleme 5 dk'lık
 * pencerede tek denetim kaydına yazılır (LD-05). Geçmiş tutulmaz; son konum operasyon kapandıktan sonra
 * yapılandırılabilir süre (varsayılan 30 gün) saklanır.
 */
@Service
class LocationService {

	static final Duration LIVE = Duration.ofMinutes(2);
	static final Duration STALE = Duration.ofMinutes(15);
	static final Duration SKEW = Duration.ofMinutes(2);

	private final LocationStore store;
	private final LocationAccess access;
	private final Participation participation;
	private final Operations operations;
	private final ParticipantDetails.Groups groups;
	private final GroupMembers members;
	private final UserDirectory users;
	private final LocationViews views;
	private final AuditTrail audit;
	private final RealtimeBus realtime;
	private final Clock clock;
	private final Duration retention;

	LocationService(LocationStore store, LocationAccess access, Participation participation, Operations operations,
			ParticipantDetails.Groups groups, GroupMembers members, UserDirectory users, LocationViews views,
			AuditTrail audit, RealtimeBus realtime, Clock clock,
			@Value("${gks.location.retention-after-close:P30D}") Duration retention) {
		this.store = store;
		this.access = access;
		this.participation = participation;
		this.operations = operations;
		this.groups = groups;
		this.members = members;
		this.users = users;
		this.views = views;
		this.audit = audit;
		this.realtime = realtime;
		this.clock = clock;
		this.retention = retention;
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record SharingView(UUID operationId, String operationName, String state, Instant startedAt, Instant stoppedAt,
			Instant lastSentAt, String stopReason, String availabilityReason) {
	}

	record Ref(UUID id, String displayName) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record LocationView(Ref user, String sharingState, double lat, double lon, Double accuracy, Instant measuredAt,
			Instant receivedAt, String freshness, boolean clockSkew, List<ParticipantDetails.GroupRef> groups) {
	}

	record NotSharing(Ref user, String state) {
	}

	record LocationList(UUID operationId, Instant generatedAt, List<LocationView> items, List<NotSharing> notSharing) {
	}

	record Measurement(double lat, double lon, double accuracy, Instant measuredAt) {
	}

	/** LC-01: başlat ({@code availability=UNAVAILABLE} ise neden ile). Zaten paylaşılıyorsa aynı yanıt. */
	@Transactional
	SharingView start(Subject subject, UUID operationId, String availabilityReason) {
		requireActive(operationId);
		if (!participation.isParticipant(operationId, subject.userId())) {
			throw ApiException.of(Code.NOT_A_PARTICIPANT);
		}
		boolean wasSharing = store.lockSharing(subject.userId(), operationId).map(SharingRow::sharing).orElse(false);
		String state = availabilityReason == null ? "ACTIVE" : "UNAVAILABLE";
		store.start(subject.userId(), operationId, state, availabilityReason, clock.instant());
		if (!wasSharing) {
			audit.record(subject.userId(), "SELF", subject.client(), "LOCATION_SHARING_STARTED", "LOCATION", null, operationId,
					Map.of());
		}
		sharingChanged(operationId, subject.userId(), state);
		return view(store.lockSharing(subject.userId(), operationId).orElseThrow());
	}

	/** LC-02: durdur; paylaşım yoksa da başarılı (doğal idempotent). */
	@Transactional
	void stop(Subject subject, UUID operationId) {
		if (!store.stop(operationId, subject.userId(), "USER", clock.instant()).isEmpty()) {
			audit.record(subject.userId(), "SELF", subject.client(), "LOCATION_SHARING_STOPPED", "LOCATION", null, operationId,
					Map.of("reason", "USER"));
			sharingChanged(operationId, subject.userId(), "STOPPED");
		}
	}

	/** LC-03: tek ölçüm; denetim yok (yüksek hacim). Paylaşım durmuşsa {@code 409 LOCATION_SHARING_STOPPED}. */
	@Transactional
	void measure(Subject subject, UUID operationId, Measurement m) {
		requireActive(operationId);
		SharingRow sharing = store.lockSharing(subject.userId(), operationId)
			.filter(SharingRow::sharing)
			.orElseThrow(() -> ApiException.of(Code.LOCATION_SHARING_STOPPED));
		Instant now = clock.instant();
		boolean skew = Duration.between(m.measuredAt(), now).abs().compareTo(SKEW) > 0;
		store.record(subject.userId(), operationId, m.lat(), m.lon(), m.accuracy(), m.measuredAt(), now, skew);
		if (!"ACTIVE".equals(sharing.state())) {
			sharingChanged(operationId, subject.userId(), "ACTIVE");
		}
		realtime.publish(RealtimeBus.locationTopic(operationId), "location.updated", operationId,
				Map.of("userIds", List.of(subject.userId()), "measuredAt", m.measuredAt().toString()));
	}

	/** LC-04. */
	@Transactional(readOnly = true)
	List<SharingView> mine(Subject subject) {
		return store.sharingsOf(subject.userId()).stream().map(this::view).toList();
	}

	/**
	 * LC-05: yalnızca KOR (K-15); operasyon {@code ACTIVE} değilse konum gösterilmez (AC-08.2). Durdurmuş kişinin
	 * koordinatı dönmez (N-04). Görüntüleme pencereye bu işlemde yazılır (LD-05, LD-44).
	 */
	@Transactional
	LocationList list(Subject subject, UUID operationId, UUID groupId, String freshness) {
		access.canView(subject, operationId).require();
		requireActive(operationId);
		Instant now = clock.instant();
		List<UUID> participants = participation.participants(operationId);
		Set<UUID> scope = new LinkedHashSet<>(participants);
		if (groupId != null) {
			List<UUID> group = members.activeMembers(operationId, List.of(groupId)).get(groupId);
			if (group == null) {
				throw ApiException.of(Code.NOT_FOUND);
			}
			scope.retainAll(new HashSet<>(group));
		}
		Map<UUID, SharingRow> sharings = store.sharingsIn(operationId).stream()
			.collect(Collectors.toMap(SharingRow::userId, Function.identity()));
		Map<UUID, LastLocation> locations = store.lastLocations(operationId).stream()
			.collect(Collectors.toMap(LastLocation::userId, Function.identity()));
		Map<UUID, String> names = users.displayNames(scope);
		Map<UUID, List<ParticipantDetails.GroupRef>> groupsOf = groups.activeGroups(operationId, scope);

		List<LocationView> items = new ArrayList<>();
		List<NotSharing> notSharing = new ArrayList<>();
		for (UUID userId : scope) {
			Ref user = new Ref(userId, names.get(userId));
			SharingRow sharing = sharings.get(userId);
			LastLocation location = locations.get(userId);
			if (sharing == null || !sharing.sharing()) {
				notSharing.add(new NotSharing(user, sharing == null ? "OFF" : sharing.state()));
			}
			else if (location == null) {
				notSharing.add(new NotSharing(user, "WAITING"));   // paylaşım açık, henüz ölçüm gelmedi (Ek)
			}
			else {
				String fresh = freshness(location, now);
				if (freshness == null || freshness.equals(fresh)) {
					items.add(new LocationView(user, sharing.state(), location.lat(), location.lon(), location.accuracy(),
							location.measuredAt(), location.receivedAt(), fresh, location.clockSkew(),
							groupsOf.getOrDefault(userId, List.of())));
				}
			}
		}
		items.sort(Comparator.comparing((LocationView v) -> v.user().displayName() == null ? "" : v.user().displayName()));
		Set<String> filters = new LinkedHashSet<>();
		if (groupId != null) {
			filters.add("groupId");
		}
		if (freshness != null) {
			filters.add("freshness");
		}
		views.viewed(subject.userId(), operationId, subject.client(), filters);
		return new LocationList(operationId, now, items, notSharing);
	}

	/** Operasyon kapandı (aynı işlem): paylaşımlar durur, son konumların saklama süresi başlar (ADR-019). */
	@Transactional
	void operationClosed(UUID operationId, Instant at) {
		List<UUID> stopped = store.stop(operationId, null, "OPERATION_CLOSED", at);
		store.retainUntil(operationId, at.plus(retention));
		stopped.forEach(userId -> sharingChanged(operationId, userId, "STOPPED"));
	}

	/** Katılım kaldırıldı: paylaşım durur ve kişinin son konumu hemen silinir (artık katılımcı değil). */
	@Transactional
	void participationRevoked(UUID operationId, UUID userId, Instant at) {
		if (!store.stop(operationId, userId, "PARTICIPATION_REVOKED", at).isEmpty()) {
			sharingChanged(operationId, userId, "STOPPED");
		}
		store.deleteLastLocation(userId, operationId);
	}

	@Transactional
	int purgeExpired() {
		return store.purgeExpired(clock.instant());
	}

	static String freshness(LastLocation location, Instant now) {
		Instant reference = location.clockSkew() ? location.receivedAt() : location.measuredAt();
		Duration age = Duration.between(reference, now);
		return age.compareTo(LIVE) < 0 ? "LIVE" : age.compareTo(STALE) < 0 ? "STALE" : "LAST_KNOWN";
	}

	private void requireActive(UUID operationId) {
		operations.findForShare(operationId)
			.filter(op -> op.state() == OperationState.ACTIVE)
			.orElseThrow(() -> ApiException.of(Code.OPERATION_NOT_ACTIVE));
	}

	private SharingView view(SharingRow row) {
		return new SharingView(row.operationId(), operations.names(List.of(row.operationId())).get(row.operationId()),
				row.state(), row.startedAt(), row.stoppedAt(), row.lastSentAt(), row.stopReason(), row.availabilityReason());
	}

	private void sharingChanged(UUID operationId, UUID userId, String state) {
		realtime.publish(RealtimeBus.locationTopic(operationId), "location.sharing.changed", operationId,
				Map.of("userId", userId, "state", state));
	}

}
