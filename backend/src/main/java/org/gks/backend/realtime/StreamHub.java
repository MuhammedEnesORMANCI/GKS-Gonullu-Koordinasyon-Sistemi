package org.gks.backend.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.gks.backend.RealtimeBus;
import org.gks.backend.RealtimeBus.AccessChanged;
import org.gks.backend.RealtimeBus.SessionsEnded;
import org.gks.backend.RealtimeBus.TopicEvent;
import org.gks.backend.access.Decision;
import org.gks.backend.access.RealtimeAccess;
import org.gks.backend.access.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import tools.jackson.databind.json.JsonMapper;

/**
 * Açık SSE bağlantıları, konu abonelikleri ve yeniden oynatma arabelleği (§12.2, ADR-009). Tek örnek içindir: çoklu
 * örnekte olaylar örnekler arası bir kanal (ör. PostgreSQL {@code LISTEN/NOTIFY}) gerektirir.
 * <p>
 * Sıra güvencesi: arabelleğe ekleme ile bağlantı kaydı aynı kilitte yapılır; açılıştaki yeniden oynatma da bu kilitte
 * kuyruğa alınır (yanıt başlamadan yapılan gönderimler {@link SseEmitter} içinde bellekte bekler). Böylece bir olay
 * yeni bağlantıya ya yeniden oynatmayla ya canlı yayınla, bir kez gider.
 */
@Component
class StreamHub {

	static final long RETRY_MILLIS = 3000;

	private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

	private final RealtimeAccess access;
	private final JsonMapper json;
	private final Clock clock;
	private final Duration maxAge;
	private final Duration bufferAge;
	private final int bufferSize;

	private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
	private final Deque<Frame> buffer = new ArrayDeque<>();   // this ile korunur
	private final AtomicLong sequence = new AtomicLong();

	StreamHub(RealtimeAccess access, JsonMapper json, Clock clock,
			@Value("${gks.realtime.max-age:PT30M}") Duration maxAge,
			@Value("${gks.realtime.buffer-age:PT5M}") Duration bufferAge,
			@Value("${gks.realtime.buffer-size:10000}") int bufferSize) {
		this.access = access;
		this.json = json;
		this.clock = clock;
		this.maxAge = maxAge;
		this.bufferAge = bufferAge;
		this.bufferSize = bufferSize;
	}

	/** Arabellekteki olay; {@code data} hazır JSON. */
	record Frame(String id, long millis, String topic, String event, String data) {
	}

	/** Konu için karar (kullanıcının kendi konusu her zaman açıktır). */
	Decision authorize(Subject subject, String topic) {
		if (topic.equals(RealtimeBus.userTopic(subject.userId()))) {
			return Decision.ALLOW;
		}
		if (topic.equals(RealtimeBus.ORGANIZATION_TOPIC)) {
			return access.canFollowOrganization(subject);
		}
		if (topic.startsWith("location:")) {
			return access.canFollowLocation(subject, UUID.fromString(topic.substring("location:".length())));
		}
		if (topic.startsWith("operation:")) {
			return access.canFollowOperation(subject, UUID.fromString(topic.substring("operation:".length())));
		}
		return Decision.FORBIDDEN;
	}

	/** Yetkisi denetlenmiş konularla akışı açar; {@code lastEventId} arabellekte yoksa önce {@code resync}. */
	SseEmitter open(Subject subject, UUID sessionId, Collection<String> topics, String lastEventId) {
		SseEmitter emitter = new SseEmitter(maxAge.toMillis());
		Set<String> all = ConcurrentHashMap.newKeySet();
		all.add(RealtimeBus.userTopic(subject.userId()));
		all.addAll(topics);
		Connection connection = new Connection(subject, sessionId, all, emitter);
		emitter.onCompletion(() -> connections.remove(connection.id));
		emitter.onError(error -> connections.remove(connection.id));
		emitter.onTimeout(() -> {   // en geç 30 dk'da bir yeniden bağlanma (§2.2): bayat kimlikle süresiz akış yok
			connections.remove(connection.id);
			emitter.complete();
		});
		synchronized (this) {
			connections.put(connection.id, connection);
			connection.send(SseEmitter.event().reconnectTime(RETRY_MILLIS).comment("bağlandı"));
			if (lastEventId != null && !lastEventId.isBlank()) {
				List<Frame> replay = framesAfter(lastEventId);
				if (replay == null) {
					resync(connection);
				}
				else {
					replay.stream().filter(f -> all.contains(f.topic())).forEach(connection::send);
				}
			}
		}
		return emitter;
	}

	void publish(TopicEvent event) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("topic", event.topic());
		if (event.operationId() != null) {
			data.put("operationId", event.operationId());
		}
		data.putAll(event.data());
		data.put("at", event.at().toString());
		long millis = event.at().toEpochMilli();
		Frame frame = new Frame("%d-%06d".formatted(millis, sequence.incrementAndGet()), millis, event.topic(),
				event.type(), json.writeValueAsString(data));
		List<Connection> targets;
		synchronized (this) {
			buffer.addLast(frame);
			long oldest = clock.millis() - bufferAge.toMillis();
			while (buffer.size() > bufferSize || (!buffer.isEmpty() && buffer.peekFirst().millis() < oldest)) {
				buffer.removeFirst();
			}
			targets = connections.values().stream().filter(c -> c.topics.contains(frame.topic())).toList();
		}
		targets.forEach(c -> c.send(frame));
	}

	/** Oturum iptali: {@code session.revoked}, ardından akış kapanır. */
	void sessionsEnded(SessionsEnded event) {
		for (Connection c : List.copyOf(connections.values())) {
			boolean match = event.sessionId() != null ? event.sessionId().equals(c.sessionId)
					: c.subject.userId().equals(event.userId()) && !c.sessionId.equals(event.exceptSessionId());
			if (match) {
				c.send(SseEmitter.event().name("session.revoked")
					.data(json(Map.of("topic", RealtimeBus.userTopic(c.subject.userId()), "at", clock.instant().toString()))));
				close(c);
			}
		}
	}

	/** Yetki düşmesi: konu akıştan çıkarılır ve {@code subscription.revoked} gönderilir (T-SSE-02). */
	void accessChanged(AccessChanged event) {
		Set<String> closedTopics = "OPERATION_CLOSED".equals(event.reason()) && event.operationId() != null
				? Set.of(RealtimeBus.operationTopic(event.operationId()), RealtimeBus.locationTopic(event.operationId())) : Set.of();
		for (Connection c : List.copyOf(connections.values())) {
			if (event.userId() != null && !event.userId().equals(c.subject.userId())) {
				continue;
			}
			for (String topic : new ArrayList<>(c.topics)) {
				if (topic.equals(RealtimeBus.userTopic(c.subject.userId()))) {
					continue;
				}
				boolean revoke = closedTopics.contains(topic) || !authorize(c.subject, topic).allowed();
				if (revoke && c.topics.remove(topic)) {
					c.send(SseEmitter.event().name("subscription.revoked").data(json(Map.of("topic", topic,
							"reason", closedTopics.contains(topic) ? "OPERATION_CLOSED" : event.reason(), "at", clock.instant().toString()))));
				}
			}
		}
	}

	/** 15 sn'de bir {@code : ping}; istemci 45 sn sessizlikte bağlantıyı kopmuş sayar. */
	@Scheduled(initialDelayString = "${gks.realtime.ping-interval:PT15S}", fixedDelayString = "${gks.realtime.ping-interval:PT15S}")
	void ping() {
		connections.values().forEach(c -> c.send(SseEmitter.event().comment("ping")));
	}

	int connectionCount() {
		return connections.size();
	}

	/** {@code null}: kimlik arabellekte yok (çok eski, bozuk veya sunucu yeniden başladı). */
	private List<Frame> framesAfter(String lastEventId) {
		List<Frame> after = new ArrayList<>();
		boolean found = false;
		for (Frame f : buffer) {
			if (found) {
				after.add(f);
			}
			else if (f.id().equals(lastEventId)) {
				found = true;
			}
		}
		return found ? after : null;
	}

	/** RT-K2: istemci ilgili REST sorgularını yeniler; kimlik en yeni olaya taşınır ki sonraki bağlanma oradan sürsün. */
	private void resync(Connection c) {
		SseEventBuilder builder = SseEmitter.event().name("resync")
			.data(json(Map.of("topics", List.copyOf(new LinkedHashSet<>(c.topics)), "at", clock.instant().toString())));
		if (!buffer.isEmpty()) {
			builder.id(buffer.peekLast().id());
		}
		c.send(builder);
	}

	private String json(Map<String, Object> data) {
		return json.writeValueAsString(data);
	}

	private void close(Connection c) {
		connections.remove(c.id);
		try {
			c.emitter.complete();
		}
		catch (IllegalStateException ignored) {
			// zaten tamamlanmış
		}
	}

	private final class Connection {

		final UUID id = UUID.randomUUID();
		final Subject subject;
		final UUID sessionId;
		final Set<String> topics;
		final SseEmitter emitter;

		Connection(Subject subject, UUID sessionId, Set<String> topics, SseEmitter emitter) {
			this.subject = subject;
			this.sessionId = sessionId;
			this.topics = topics;
			this.emitter = emitter;
		}

		void send(Frame frame) {
			send(SseEmitter.event().id(frame.id()).name(frame.event()).data(frame.data()));
		}

		/** Kopmuş istemci bağlantıdan düşer; yayın diğer abonelere devam eder. */
		synchronized void send(SseEventBuilder event) {
			try {
				emitter.send(event);
			}
			catch (IOException | IllegalStateException ex) {
				log.debug("SSE bağlantısı kapandı: {}", ex.getMessage());
				close(this);
			}
		}

	}

}
