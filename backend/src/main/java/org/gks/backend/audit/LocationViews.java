package org.gks.backend.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.gks.backend.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Konum görüntüleme denetimi (LD-05, LD-44; DB §6.7): aynı kişi × operasyon için 5 dk'lık sabit pencerede tek
 * {@code LOCATION_VIEWED}. Sayaç kalıcı ara tabloda tutulur ({@code audit.location_view_windows}); pencere kapanınca
 * {@code audit_entries}'e tek satır eklenir. Konum modülü {@link #viewed} yöntemini <b>kendi işleminde</b>, konumları
 * okuduktan sonra çağırır; commit olmazsa konum yanıtlanmaz (kaydı olmayan konum erişimi yok).
 */
@Component
public class LocationViews {

	static final Duration WINDOW = Duration.ofMinutes(5);
	private static final Logger log = LoggerFactory.getLogger(LocationViews.class);

	private final JdbcClient jdbc;
	private final AuditStore store;
	private final Clock clock;

	LocationViews(JdbcClient jdbc, AuditStore store, Clock clock) {
		this.jdbc = jdbc;
		this.store = store;
		this.clock = clock;
	}

	private record Window(Instant start, Instant lastViewedAt, int count, String client, String filters) {
	}

	/** @param filters kullanılan süzgeç adları (ör. {@code groupId}); kimlik ve koordinat yazılmaz */
	@Transactional(propagation = Propagation.MANDATORY)
	public void viewed(UUID actorId, UUID operationId, String client, Set<String> filters) {
		Instant now = clock.instant();
		String joined = filters.isEmpty() ? null : String.join(",", new TreeSet<>(filters));
		for (int attempt = 0; attempt < 2; attempt++) {
			if (insert(actorId, operationId, client, joined, now)) {
				return;
			}
			Window window = lock(actorId, operationId);
			if (window == null) {
				continue;   // kapatma işi satırı az önce sildi: bir kez yeniden dene (DB §6.7 adım 2)
			}
			if (now.isBefore(window.start().plus(WINDOW))) {
				jdbc.sql("""
						update audit.location_view_windows
						   set query_count = query_count + 1, last_viewed_at = :now, filters_used = :filters
						 where actor_id = :actor and operation_id = :op""")
					.param("now", Timestamp.from(now)).param("filters", merge(window.filters(), joined))
					.param("actor", actorId).param("op", operationId).update();
			}
			else {
				close(actorId, operationId, window);
				jdbc.sql("""
						update audit.location_view_windows
						   set window_start = :now, last_viewed_at = :now, query_count = 1, client = :client, filters_used = :filters
						 where actor_id = :actor and operation_id = :op""")
					.param("now", Timestamp.from(now)).param("client", client).param("filters", joined)
					.param("actor", actorId).param("op", operationId).update();
			}
			return;
		}
		throw new IllegalStateException("Konum görüntüleme penceresi güncellenemedi");
	}

	/** Kapatma işi (her 1 dk): süresi dolan pencereler denetime yazılır ve silinir; kilitli satırlar atlanır. */
	@Scheduled(initialDelayString = "${gks.audit.location-window-flush:PT1M}",
			fixedDelayString = "${gks.audit.location-window-flush:PT1M}")
	@Transactional
	public int flush() {
		Instant cutoff = clock.instant().minus(WINDOW);
		List<Object[]> due = jdbc.sql("""
				select actor_id, operation_id, window_start, last_viewed_at, query_count, client, filters_used
				  from audit.location_view_windows where window_start <= :cutoff
				 order by window_start limit 200 for update skip locked""")
			.param("cutoff", Timestamp.from(cutoff))
			.query((rs, row) -> new Object[] { rs.getObject("actor_id", UUID.class), rs.getObject("operation_id", UUID.class),
					new Window(rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("last_viewed_at").toInstant(),
							rs.getInt("query_count"), rs.getString("client"), rs.getString("filters_used")) })
			.list();
		for (Object[] row : due) {
			close((UUID) row[0], (UUID) row[1], (Window) row[2]);
			jdbc.sql("delete from audit.location_view_windows where actor_id = :actor and operation_id = :op")
				.param("actor", row[0]).param("op", row[1]).update();
		}
		if (!due.isEmpty()) {
			log.debug("{} konum görüntüleme penceresi denetime yazıldı", due.size());
		}
		return due.size();
	}

	private void close(UUID actorId, UUID operationId, Window window) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("queryCount", window.count());
		details.put("windowStart", window.start().toString());
		details.put("windowEnd", window.start().plus(WINDOW).toString());
		details.put("lastViewedAt", window.lastViewedAt().toString());
		if (window.filters() != null) {
			details.put("filtersUsed", List.of(window.filters().split(",")));
		}
		// Konumu yalnızca KOR görür (K-15, ADR-019).
		store.insert(new AuditEvent(actorId, "KOR", window.client(), "LOCATION_VIEWED", "LOCATION", null, operationId,
				details, window.start(), null));
	}

	private boolean insert(UUID actorId, UUID operationId, String client, String filters, Instant now) {
		return jdbc.sql("""
				insert into audit.location_view_windows (actor_id, operation_id, window_start, last_viewed_at, query_count,
				                                         client, filters_used)
				values (:actor, :op, :now, :now, 1, :client, :filters)
				on conflict (actor_id, operation_id) do nothing""")
			.param("actor", actorId).param("op", operationId).param("now", Timestamp.from(now)).param("client", client)
			.param("filters", filters).update() == 1;
	}

	private Window lock(UUID actorId, UUID operationId) {
		return jdbc.sql("""
				select window_start, last_viewed_at, query_count, client, filters_used from audit.location_view_windows
				 where actor_id = :actor and operation_id = :op for update""")
			.param("actor", actorId).param("op", operationId)
			.query((rs, row) -> new Window(rs.getTimestamp("window_start").toInstant(),
					rs.getTimestamp("last_viewed_at").toInstant(), rs.getInt("query_count"), rs.getString("client"),
					rs.getString("filters_used")))
			.optional().orElse(null);
	}

	private static String merge(String a, String b) {
		Set<String> all = new TreeSet<>();
		for (String s : new String[] { a, b }) {
			if (s != null) {
				all.addAll(List.of(s.split(",")));
			}
		}
		return all.isEmpty() ? null : String.join(",", all);
	}

}
