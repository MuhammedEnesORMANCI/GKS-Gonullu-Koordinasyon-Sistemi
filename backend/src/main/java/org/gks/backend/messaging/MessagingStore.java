package org.gks.backend.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class MessagingStore {

	record ChannelRow(UUID id, UUID operationId, UUID groupId, ChannelKind kind, boolean adminsOnly, Instant closedAt) {
	}

	record MessageRow(UUID id, UUID channelId, UUID senderId, String kind, String body, Instant createdAt, Instant deletedAt,
			Instant hiddenAt) {
	}

	record ChannelStats(int unread, Instant lastMessageAt) {
	}

	private final JdbcClient jdbc;

	MessagingStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Operasyon/koordinasyon/grup kanalları yoksa oluşturur (otomatik kanal). */
	void ensure(UUID operationId, Collection<UUID> groupIds, Instant closedAt, Instant now) {
		Timestamp closed = closedAt == null ? null : Timestamp.from(closedAt);
		for (ChannelKind kind : List.of(ChannelKind.OPERATION, ChannelKind.COORDINATION)) {
			jdbc.sql("""
					insert into messaging.channels (id, operation_id, group_id, kind, created_at, closed_at)
					values (?, ?, null, ?, ?, ?) on conflict do nothing""")
				.params(Ids.newId(), operationId, kind.name(), Timestamp.from(now), closed).update();
		}
		for (UUID groupId : groupIds) {
			jdbc.sql("""
					insert into messaging.channels (id, operation_id, group_id, kind, created_at, closed_at)
					values (?, ?, ?, 'GROUP', ?, ?) on conflict do nothing""")
				.params(Ids.newId(), operationId, groupId, Timestamp.from(now), closed).update();
		}
	}

	List<ChannelRow> channels(Collection<UUID> operationIds) {
		if (operationIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("select * from messaging.channels where operation_id in (:ops) order by operation_id, kind, id")
			.param("ops", operationIds).query((rs, i) -> channel(rs)).list();
	}

	Optional<ChannelRow> channel(UUID id) {
		return jdbc.sql("select * from messaging.channels where id = ?").param(id).query((rs, i) -> channel(rs)).optional();
	}

	Optional<ChannelRow> channelFor(UUID operationId, UUID groupId) {
		return groupId == null
				? jdbc.sql("select * from messaging.channels where operation_id = ? and kind = 'OPERATION'").param(operationId)
					.query((rs, i) -> channel(rs)).optional()
				: jdbc.sql("select * from messaging.channels where group_id = ?").param(groupId).query((rs, i) -> channel(rs)).optional();
	}

	Map<UUID, ChannelStats> stats(Collection<UUID> channelIds, UUID userId) {
		Map<UUID, ChannelStats> out = new HashMap<>();
		if (channelIds.isEmpty()) {
			return out;
		}
		jdbc.sql("""
				select c.id,
				       count(m.id) filter (where (r.last_read_id is null or m.id > r.last_read_id)
				                              and m.sender_id is distinct from :user
				                              and m.deleted_at is null and m.hidden_at is null) as unread,
				       max(m.created_at) as last_at
				  from messaging.channels c
				  left join messaging.messages m on m.channel_id = c.id
				  left join messaging.reads r on r.channel_id = c.id and r.user_id = :user
				 where c.id in (:ids)
				 group by c.id""")
			.param("user", userId).param("ids", channelIds)
			.query((rs, i) -> out.put(rs.getObject("id", UUID.class), new ChannelStats(rs.getInt("unread"),
					rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant())))
			.list();
		return out;
	}

	Set<UUID> muted(UUID userId, Collection<UUID> channelIds) {
		if (channelIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("select channel_id from messaging.mutes where user_id = :u and channel_id in (:ids)")
			.param("u", userId).param("ids", channelIds).query(UUID.class).list());
	}

	Set<UUID> mutedUsers(UUID channelId) {
		return new HashSet<>(jdbc.sql("select user_id from messaging.mutes where channel_id = ?").param(channelId)
			.query(UUID.class).list());
	}

	void setMuted(UUID channelId, UUID userId, boolean muted, Instant now) {
		if (muted) {
			jdbc.sql("insert into messaging.mutes (channel_id, user_id, muted_at) values (?, ?, ?) on conflict do nothing")
				.params(channelId, userId, Timestamp.from(now)).update();
		}
		else {
			jdbc.sql("delete from messaging.mutes where channel_id = ? and user_id = ?").params(channelId, userId).update();
		}
	}

	void setAdminsOnly(UUID channelId, boolean adminsOnly) {
		jdbc.sql("update messaging.channels set admins_only = ? where id = ?").params(adminsOnly, channelId).update();
	}

	List<MessageRow> page(UUID channelId, UUID before, int limit) {
		return jdbc.sql("""
				select * from messaging.messages
				 where channel_id = :c and (cast(:before as uuid) is null or id < :before)
				 order by id desc limit :limit""")
			.param("c", channelId).param("before", before).param("limit", limit).query((rs, i) -> message(rs)).list();
	}

	Optional<MessageRow> message(UUID channelId, UUID messageId) {
		return jdbc.sql("select * from messaging.messages where id = ? and channel_id = ?").params(messageId, channelId)
			.query((rs, i) -> message(rs)).optional();
	}

	Optional<MessageRow> byClientKey(UUID senderId, String clientKey) {
		return jdbc.sql("select * from messaging.messages where sender_id = ? and client_key = ?").params(senderId, clientKey)
			.query((rs, i) -> message(rs)).optional();
	}

	/** @return eklendiyse true; aynı istemci anahtarı zaten varsa false (tekilleştirme) */
	boolean insert(UUID id, UUID channelId, UUID senderId, String kind, String body, Instant now, String clientKey) {
		return jdbc.sql("""
				insert into messaging.messages (id, channel_id, sender_id, kind, body, created_at, client_key)
				values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing""")
			.params(id, channelId, senderId, kind, body, Timestamp.from(now), clientKey).update() == 1;
	}

	void markDeleted(UUID messageId, Instant now) {
		jdbc.sql("update messaging.messages set deleted_at = ? where id = ? and deleted_at is null")
			.params(Timestamp.from(now), messageId).update();
	}

	void markHidden(UUID messageId, UUID by, Instant now) {
		jdbc.sql("update messaging.messages set hidden_at = ?, hidden_by = ? where id = ? and hidden_at is null")
			.params(Timestamp.from(now), by, messageId).update();
	}

	void markRead(UUID channelId, UUID userId, UUID messageId, Instant now) {
		jdbc.sql("""
				insert into messaging.reads (channel_id, user_id, last_read_id, read_at) values (?, ?, ?, ?)
				on conflict (channel_id, user_id) do update set last_read_id = excluded.last_read_id, read_at = excluded.read_at
				 where messaging.reads.last_read_id < excluded.last_read_id""")
			.params(channelId, userId, messageId, Timestamp.from(now)).update();
	}

	void closeOperation(UUID operationId, Instant at) {
		jdbc.sql("update messaging.channels set closed_at = ? where operation_id = ? and closed_at is null")
			.params(Timestamp.from(at), operationId).update();
	}

	int purgeClosedBefore(Instant cutoff) {
		return jdbc.sql("delete from messaging.channels where closed_at is not null and closed_at < ?")
			.param(Timestamp.from(cutoff)).update();
	}

	private static ChannelRow channel(ResultSet rs) throws SQLException {
		Timestamp closed = rs.getTimestamp("closed_at");
		return new ChannelRow(rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
				rs.getObject("group_id", UUID.class), ChannelKind.valueOf(rs.getString("kind")), rs.getBoolean("admins_only"),
				closed == null ? null : closed.toInstant());
	}

	private static MessageRow message(ResultSet rs) throws SQLException {
		Timestamp deleted = rs.getTimestamp("deleted_at");
		Timestamp hidden = rs.getTimestamp("hidden_at");
		return new MessageRow(rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
				rs.getObject("sender_id", UUID.class), rs.getString("kind"), rs.getString("body"),
				rs.getTimestamp("created_at").toInstant(), deleted == null ? null : deleted.toInstant(),
				hidden == null ? null : hidden.toInstant());
	}

}
