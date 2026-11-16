package org.gks.backend.notifications;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems;
import org.gks.backend.AuditTrail;
import org.gks.backend.Ids;
import org.gks.backend.PageCursor;
import org.gks.backend.access.ActingBasis;
import org.gks.backend.access.AnnouncementAccess;
import org.gks.backend.access.Subject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kamu duyuruları (ADR-023). Yönetim yalnızca ADM (MFA'lı yönetim oturumu); her değişiklik aynı işlemde denetime
 * yazılır (başlık/metin değil, yalnızca durum ve uzunluklar). Halka açık okuma yalnızca {@code PUBLISHED} kayıtları
 * ve yalnızca {@code id, title, body, publishedAt} alanlarını döndürür.
 */
@Service
class PublicAnnouncementService {

	enum State { DRAFT, PUBLISHED }

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AdminView(UUID id, String title, String body, State state, int version, Instant createdAt, Instant updatedAt,
			Instant publishedAt) {
	}

	record PublicView(UUID id, String title, String body, Instant publishedAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Page<T>(List<T> items, String nextCursor) {
	}

	private final JdbcClient jdbc;
	private final AnnouncementAccess access;
	private final ActingBasis acting;
	private final AuditTrail audit;
	private final Clock clock;

	PublicAnnouncementService(JdbcClient jdbc, AnnouncementAccess access, ActingBasis acting, AuditTrail audit, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.acting = acting;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ halka açık

	@Transactional(readOnly = true)
	Page<PublicView> published(PageCursor page) {
		Instant afterAt = null;
		if (page.after() != null) {
			afterAt = jdbc.sql("select published_at from notifications.public_announcements where id = ? and state = 'PUBLISHED'")
				.param(page.after()).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
			if (afterAt == null) {
				return new Page<>(List.of(), null);   // imleçteki kayıt yayından kalkmış: liste sonu
			}
		}
		List<PublicView> rows = jdbc.sql("""
				select id, title, body, published_at from notifications.public_announcements
				where state = 'PUBLISHED'
				  and (cast(:afterAt as timestamptz) is null or (published_at, id) < (cast(:afterAt as timestamptz), :afterId))
				order by published_at desc, id desc limit :limit""")
			.param("afterAt", afterAt == null ? null : Timestamp.from(afterAt))
			.param("afterId", page.after())
			.param("limit", page.limit() + 1)
			.query((rs, i) -> new PublicView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
					rs.getTimestamp("published_at").toInstant()))
			.list();
		PageCursor.Slice<PublicView> slice = page.slice(rows, PublicView::id);
		return new Page<>(slice.items(), slice.nextCursor());
	}

	// ------------------------------------------------------------------ yönetim (ADM)

	@Transactional(readOnly = true)
	Page<AdminView> list(Subject subject, PageCursor page) {
		access.canManagePublicAnnouncements(subject).require();
		Instant afterAt = page.after() == null ? null
				: jdbc.sql("select updated_at from notifications.public_announcements where id = ?").param(page.after())
					.query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
		List<AdminView> rows = jdbc.sql("""
				select * from notifications.public_announcements
				where cast(:afterAt as timestamptz) is null or (updated_at, id) < (cast(:afterAt as timestamptz), :afterId)
				order by updated_at desc, id desc limit :limit""")
			.param("afterAt", afterAt == null ? null : Timestamp.from(afterAt))
			.param("afterId", page.after())
			.param("limit", page.limit() + 1)
			.query((rs, i) -> view(rs))
			.list();
		PageCursor.Slice<AdminView> slice = page.slice(rows, AdminView::id);
		return new Page<>(slice.items(), slice.nextCursor());
	}

	@Transactional(readOnly = true)
	AdminView get(Subject subject, UUID id) {
		access.canManagePublicAnnouncements(subject).require();
		return find(id);
	}

	@Transactional
	AdminView create(Subject subject, String title, String body) {
		access.canManagePublicAnnouncements(subject).require();
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.sql("""
				insert into notifications.public_announcements (id, title, body, state, version, created_by, created_at, updated_by, updated_at)
				values (?, ?, ?, 'DRAFT', 1, ?, ?, ?, ?)""")
			.params(id, title, body, subject.userId(), now, subject.userId(), now).update();
		record(subject, "PUBLIC_ANNOUNCEMENT_CREATED", id, Map.of("titleLength", title.length(), "bodyLength", body.length()));
		return find(id);
	}

	@Transactional
	AdminView update(Subject subject, UUID id, int version, String title, String body) {
		access.canManagePublicAnnouncements(subject).require();
		AdminView current = lock(id, version);
		jdbc.sql("""
				update notifications.public_announcements set title = ?, body = ?, version = version + 1, updated_by = ?, updated_at = ?
				where id = ?""")
			.params(title, body, subject.userId(), Timestamp.from(clock.instant()), id).update();
		record(subject, "PUBLIC_ANNOUNCEMENT_UPDATED", id, Map.of("state", current.state().name(), "titleLength", title.length(),
				"bodyLength", body.length()));
		return find(id);
	}

	@Transactional
	AdminView publish(Subject subject, UUID id, int version) {
		return transition(subject, id, version, State.PUBLISHED, "PUBLIC_ANNOUNCEMENT_PUBLISHED");
	}

	@Transactional
	AdminView unpublish(Subject subject, UUID id, int version) {
		return transition(subject, id, version, State.DRAFT, "PUBLIC_ANNOUNCEMENT_UNPUBLISHED");
	}

	private AdminView transition(Subject subject, UUID id, int version, State target, String action) {
		access.canManagePublicAnnouncements(subject).require();
		AdminView current = lock(id, version);
		if (current.state() == target) {
			throw ApiException.invalidStateTransition(current.state().name(), target.name());
		}
		Instant now = clock.instant();
		jdbc.sql("""
				update notifications.public_announcements
				set state = ?, published_at = ?, version = version + 1, updated_by = ?, updated_at = ? where id = ?""")
			.params(target.name(), target == State.PUBLISHED ? Timestamp.from(now) : null, subject.userId(), Timestamp.from(now), id)
			.update();
		record(subject, action, id, Map.of("from", current.state().name(), "to", target.name()));
		return find(id);
	}

	private AdminView lock(UUID id, int version) {
		AdminView current = jdbc.sql("select * from notifications.public_announcements where id = ? for update").param(id)
			.query((rs, i) -> view(rs)).optional()
			.orElseThrow(() -> ApiException.of(ApiProblems.Code.NOT_FOUND));
		if (current.version() != version) {
			throw ApiException.versionConflict(current.version());
		}
		return current;
	}

	private AdminView find(UUID id) {
		return jdbc.sql("select * from notifications.public_announcements where id = ?").param(id)
			.query((rs, i) -> view(rs)).optional()
			.orElseThrow(() -> ApiException.of(ApiProblems.Code.NOT_FOUND));
	}

	private static AdminView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp published = rs.getTimestamp("published_at");
		return new AdminView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
				State.valueOf(rs.getString("state")), rs.getInt("version"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant(), published == null ? null : published.toInstant());
	}

	private void record(Subject subject, String action, UUID id, Map<String, Object> details) {
		audit.record(subject.userId(), acting.organization(subject), subject.client(), action, "PUBLIC_ANNOUNCEMENT", id, null,
				details);
	}

}
