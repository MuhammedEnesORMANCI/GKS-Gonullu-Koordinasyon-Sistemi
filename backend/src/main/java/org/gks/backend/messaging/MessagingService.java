package org.gks.backend.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.AuditTrail;
import org.gks.backend.Ids;
import org.gks.backend.PageCursor;
import org.gks.backend.RealtimeBus;
import org.gks.backend.access.ActingBasis;
import org.gks.backend.access.ChannelAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.groups.GroupMembers;
import org.gks.backend.groups.GroupMembers.Membership;
import org.gks.backend.identity.UserDirectory;
import org.gks.backend.messaging.MessagingStore.ChannelRow;
import org.gks.backend.messaging.MessagingStore.ChannelStats;
import org.gks.backend.messaging.MessagingStore.MessageRow;
import org.gks.backend.notifications.Notifier;
import org.gks.backend.notifications.Notifier.PushOnlyRequest;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.Operations.OperationRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mesajlaşma kuralları (ADR-024).
 * <ul>
 * <li>Grup kanalı: grubun aktif üyeleri (lider dahil) + operasyonun KOR'u + ADM okur ve yazar.</li>
 * <li>Operasyon kanalı: operasyonun katılımcıları + KOR + ADM; "yalnızca yöneticiler" modunda yalnızca KOR/ADM yazar.</li>
 * <li>Koordinasyon kanalı: KOR, ADM ve operasyondaki grup liderleri.</li>
 * </ul>
 * Kapalı operasyonun kanalları salt okunurdur. Hak her istekte yeniden hesaplanır.
 */
@Service
class MessagingService implements SystemMessages {

	static final int BODY_MAX = 2000;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ChannelView(UUID id, ChannelKind kind, UUID operationId, String operationName, UUID groupId, String groupName,
			boolean adminsOnly, boolean readOnly, boolean canWrite, boolean canModerate, boolean muted, int unread,
			Instant lastMessageAt) {
	}

	record SenderRef(UUID id, String displayName) {
	}

	enum MessageState { VISIBLE, DELETED, HIDDEN }

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MessageView(UUID id, UUID channelId, String kind, SenderRef sender, String body, MessageState state,
			Instant createdAt, boolean mine, boolean deletable) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MessagePage(List<MessageView> items, String nextCursor) {
	}

	/** İsteği yapan kişinin bu istekteki hakları (tek seferde okunur). */
	record Viewer(Subject subject, boolean admin, Set<UUID> coordinated, Set<UUID> participating,
			Map<UUID, Membership> memberships) {

		UUID userId() {
			return subject.userId();
		}

		boolean coordinates(UUID operationId) {
			return admin || coordinated.contains(operationId);
		}

		boolean leads(UUID operationId) {
			return memberships.values().stream().anyMatch(m -> m.leader() && m.operationId().equals(operationId));
		}

		boolean canRead(ChannelRow c) {
			if (coordinates(c.operationId())) {
				return true;
			}
			return switch (c.kind()) {
				case OPERATION -> participating.contains(c.operationId());
				case COORDINATION -> leads(c.operationId());
				case GROUP -> memberships.containsKey(c.groupId());
			};
		}

		boolean canWrite(ChannelRow c) {
			return canRead(c) && c.closedAt() == null
					&& !(c.kind() == ChannelKind.OPERATION && c.adminsOnly() && !coordinates(c.operationId()));
		}
	}

	private final MessagingStore store;
	private final ChannelAccess access;
	private final Participation participation;
	private final GroupMembers groups;
	private final Operations operations;
	private final UserDirectory users;
	private final Notifier notifier;
	private final RealtimeBus realtime;
	private final AuditTrail audit;
	private final ActingBasis acting;
	private final MessageRateLimiter limiter;
	private final Clock clock;
	private final Duration deleteWindow;

	MessagingService(MessagingStore store, ChannelAccess access, Participation participation, GroupMembers groups,
			Operations operations, UserDirectory users, Notifier notifier, RealtimeBus realtime, AuditTrail audit,
			ActingBasis acting, MessageRateLimiter limiter, Clock clock,
			@Value("${gks.messaging.delete-window:PT5M}") Duration deleteWindow) {
		this.store = store;
		this.access = access;
		this.participation = participation;
		this.groups = groups;
		this.operations = operations;
		this.users = users;
		this.notifier = notifier;
		this.realtime = realtime;
		this.audit = audit;
		this.acting = acting;
		this.limiter = limiter;
		this.clock = clock;
		this.deleteWindow = deleteWindow;
	}

	Viewer viewer(Subject subject) {
		Map<UUID, Membership> memberships = new LinkedHashMap<>();
		groups.memberships(subject.userId()).forEach(m -> memberships.put(m.groupId(), m));
		return new Viewer(subject, access.allOperations(subject), access.coordinatedOperations(subject),
				access.participatingOperations(subject.userId()), memberships);
	}

	// ------------------------------------------------------------------ kanallar

	@Transactional
	public List<ChannelView> channels(Subject subject) {
		Viewer v = viewer(subject);
		Map<UUID, OperationRef> ops = new LinkedHashMap<>();
		if (v.admin()) {
			operations.all().forEach(o -> ops.put(o.id(), o));
		}
		else {
			Set<UUID> ids = new LinkedHashSet<>(v.coordinated());
			ids.addAll(v.participating());
			v.memberships().values().forEach(m -> ids.add(m.operationId()));
			ids.forEach(id -> operations.find(id).ifPresent(o -> ops.put(o.id(), o)));
		}
		Instant now = clock.instant();
		for (OperationRef op : ops.values()) {
			ensure(op, now);
		}
		List<ChannelRow> visible = store.channels(ops.keySet()).stream().filter(v::canRead).toList();
		List<UUID> ids = visible.stream().map(ChannelRow::id).toList();
		Map<UUID, ChannelStats> stats = store.stats(ids, v.userId());
		Set<UUID> muted = store.muted(v.userId(), ids);
		Map<UUID, String> groupNames = groups.names(visible.stream().map(ChannelRow::groupId).filter(g -> g != null).toList());
		List<ChannelView> out = new ArrayList<>();
		for (ChannelRow c : visible) {
			ChannelStats s = stats.getOrDefault(c.id(), new ChannelStats(0, null));
			out.add(new ChannelView(c.id(), c.kind(), c.operationId(), ops.get(c.operationId()).name(), c.groupId(),
					c.groupId() == null ? null : groupNames.get(c.groupId()), c.adminsOnly(), c.closedAt() != null,
					v.canWrite(c), v.coordinates(c.operationId()), muted.contains(c.id()), s.unread(), s.lastMessageAt()));
		}
		out.sort(Comparator.comparing((ChannelView c) -> c.readOnly())
			.thenComparing(ChannelView::operationName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(c -> c.kind().ordinal())
			.thenComparing(c -> c.groupName() == null ? "" : c.groupName(), String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private void ensure(OperationRef op, Instant now) {
		store.ensure(op.id(), groups.groups(op.id()).stream().map(GroupMembers.GroupRef::id).toList(),
				op.state().isClosed() ? now : null, now);
	}

	/** Okuma hakkı yoksa 404 (kanalın varlığı sızmaz). */
	private ChannelRow readable(Viewer v, UUID channelId) {
		ChannelRow c = store.channel(channelId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (!v.canRead(c)) {
			throw ApiException.of(Code.NOT_FOUND);
		}
		return c;
	}

	private ChannelRow writable(Viewer v, UUID channelId) {
		ChannelRow c = readable(v, channelId);
		if (c.closedAt() != null || operations.find(c.operationId()).map(o -> o.state().isClosed()).orElse(true)) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
		if (!v.canWrite(c)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		return c;
	}

	// ------------------------------------------------------------------ mesajlar

	@Transactional(readOnly = true)
	public MessagePage messages(Subject subject, UUID channelId, PageCursor page) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		List<MessageRow> rows = store.page(c.id(), page.after(), page.limit() + 1);
		PageCursor.Slice<MessageRow> slice = page.slice(rows, MessageRow::id);
		return new MessagePage(views(v, slice.items()), slice.nextCursor());
	}

	@Transactional
	public MessageView send(Subject subject, UUID channelId, String body, String clientKey) {
		Viewer v = viewer(subject);
		// Tekrar gönderim (çevrimdışı kuyruk): aynı anahtar aynı mesajı döndürür, yeniden yayımlamaz.
		var existing = store.byClientKey(v.userId(), clientKey);
		if (existing.isPresent()) {
			readable(v, existing.get().channelId());
			return views(v, List.of(existing.get())).get(0);
		}
		ChannelRow c = writable(v, channelId);
		long retryAfter = limiter.acquire(v.userId());
		if (retryAfter > 0) {
			throw ApiException.rateLimited(retryAfter);
		}
		UUID id = Ids.newId();
		Instant now = clock.instant();
		if (!store.insert(id, c.id(), v.userId(), "USER", body, now, clientKey)) {
			MessageRow raced = store.byClientKey(v.userId(), clientKey).orElseThrow();
			return views(v, List.of(raced)).get(0);
		}
		store.markRead(c.id(), v.userId(), id, now);
		Set<UUID> readers = readers(c);
		publish(c, readers, "message.created", id);
		Set<UUID> push = new LinkedHashSet<>(readers);
		push.remove(v.userId());
		push.removeAll(store.mutedUsers(c.id()));
		notifier.pushOnly(new PushOnlyRequest("message:" + id, Notifier.Category.MESSAGE, Notifier.Kind.MESSAGE_POSTED,
				Notifier.SourceType.CHANNEL, c.id(), id, c.operationId(), push, v.userId()));
		return views(v, List.of(store.message(c.id(), id).orElseThrow())).get(0);
	}

	@Transactional
	public MessageView delete(Subject subject, UUID channelId, UUID messageId) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		MessageRow m = store.message(c.id(), messageId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (!v.userId().equals(m.senderId())) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		if (m.deletedAt() != null || m.hiddenAt() != null || !withinDeleteWindow(m)) {
			throw ApiException.invalidStateTransition(state(m).name(), MessageState.DELETED.name());
		}
		store.markDeleted(m.id(), clock.instant());
		publish(c, readers(c), "message.updated", m.id());
		return views(v, List.of(store.message(c.id(), m.id()).orElseThrow())).get(0);
	}

	@Transactional
	public MessageView hide(Subject subject, UUID channelId, UUID messageId) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		if (!v.coordinates(c.operationId())) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		MessageRow m = store.message(c.id(), messageId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (m.hiddenAt() == null) {
			store.markHidden(m.id(), v.userId(), clock.instant());
			// Moderasyon denetime yazılır; mesaj metni yazılmaz.
			audit.record(v.userId(), acting.operation(subject, c.operationId()), subject.client(), "MESSAGE_HIDDEN", "MESSAGE",
					m.id(), c.operationId(), Map.of("channelKind", c.kind().name(), "channelId", c.id().toString(),
							"senderId", m.senderId() == null ? "SYSTEM" : m.senderId().toString()));
			publish(c, readers(c), "message.updated", m.id());
		}
		return views(v, List.of(store.message(c.id(), m.id()).orElseThrow())).get(0);
	}

	@Transactional
	public void markRead(Subject subject, UUID channelId, UUID messageId) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		store.message(c.id(), messageId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		store.markRead(c.id(), v.userId(), messageId, clock.instant());
	}

	@Transactional
	public void mute(Subject subject, UUID channelId, boolean muted) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		store.setMuted(c.id(), v.userId(), muted, clock.instant());
	}

	@Transactional
	public void setAdminsOnly(Subject subject, UUID channelId, boolean adminsOnly) {
		Viewer v = viewer(subject);
		ChannelRow c = readable(v, channelId);
		if (c.kind() != ChannelKind.OPERATION || !v.coordinates(c.operationId())) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		if (c.closedAt() != null) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
		if (c.adminsOnly() != adminsOnly) {
			store.setAdminsOnly(c.id(), adminsOnly);
			audit.record(v.userId(), acting.operation(subject, c.operationId()), subject.client(), "CHANNEL_SETTINGS_CHANGED",
					"CHANNEL", c.id(), c.operationId(), Map.of("adminsOnly", adminsOnly));
			publish(c, readers(c), "channel.updated", null);
		}
	}

	// ------------------------------------------------------------------ sistem mesajı (diğer modüller)

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void post(UUID operationId, UUID groupId, String text) {
		OperationRef op = operations.find(operationId).orElse(null);
		if (op == null || op.state().isClosed()) {
			return;
		}
		Instant now = clock.instant();
		ensure(op, now);
		ChannelRow c = store.channelFor(operationId, groupId).orElse(null);
		if (c == null || c.closedAt() != null) {
			return;
		}
		UUID id = Ids.newId();
		String body = text.length() > BODY_MAX ? text.substring(0, BODY_MAX - 1) + "…" : text;
		store.insert(id, c.id(), null, "SYSTEM", body, now, null);
		publish(c, readers(c), "message.created", id);
	}

	// ------------------------------------------------------------------ yardımcılar

	/** Kanalın üye tabanlı okuyucuları (ADM hariç; ADM operasyon konusu üzerinden canlı güncellenir). */
	Set<UUID> readers(ChannelRow c) {
		Set<UUID> out = new LinkedHashSet<>(access.coordinators(c.operationId()));
		switch (c.kind()) {
			case OPERATION -> out.addAll(participation.participants(c.operationId()));
			case COORDINATION -> out.addAll(groups.leaders(c.operationId()));
			case GROUP -> out.addAll(groups.activeMembers(c.operationId(), List.of(c.groupId())).getOrDefault(c.groupId(), List.of()));
		}
		return out;
	}

	/** SSE: yalnızca kimlikler; içerik REST ile çekilir. KOR/ADM operasyon konusundan alır. */
	private void publish(ChannelRow c, Set<UUID> readers, String type, UUID messageId) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("channelId", c.id());
		data.put("operationId", c.operationId());
		if (messageId != null) {
			data.put("messageId", messageId);
		}
		for (UUID user : readers) {
			realtime.publish(RealtimeBus.userTopic(user), type, c.operationId(), data);
		}
		realtime.publish(RealtimeBus.operationTopic(c.operationId()), type, c.operationId(), data);
	}

	private boolean withinDeleteWindow(MessageRow m) {
		return !clock.instant().isAfter(m.createdAt().plus(deleteWindow));
	}

	private static MessageState state(MessageRow m) {
		return m.hiddenAt() != null ? MessageState.HIDDEN : m.deletedAt() != null ? MessageState.DELETED : MessageState.VISIBLE;
	}

	private List<MessageView> views(Viewer v, List<MessageRow> rows) {
		Set<UUID> senders = rows.stream().map(MessageRow::senderId).filter(s -> s != null).collect(Collectors.toCollection(HashSet::new));
		Map<UUID, String> names = users.displayNames(senders);
		return rows.stream().map(m -> {
			MessageState state = state(m);
			boolean mine = v.userId().equals(m.senderId());
			return new MessageView(m.id(), m.channelId(), m.kind(),
					m.senderId() == null ? null : new SenderRef(m.senderId(), names.getOrDefault(m.senderId(), "")),
					state == MessageState.VISIBLE ? m.body() : null, state, m.createdAt(), mine,
					mine && state == MessageState.VISIBLE && withinDeleteWindow(m));
		}).toList();
	}

}
