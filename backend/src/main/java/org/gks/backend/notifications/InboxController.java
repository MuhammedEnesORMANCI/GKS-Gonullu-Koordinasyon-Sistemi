package org.gks.backend.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.notifications.NotificationStore.InboxRow;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uygulama içi gelen kutusu (TD-13, communication.md §1.6). NT-04, NT-05 ve LD-60 ekleri (ayrıntı, okunmamış sayısı).
 * Kaynak kuralı {@code self}: her sorgu oturumun kullanıcısıyla sınırlıdır; başkasının kaydı {@code 404} döner
 * (varlığı açık edilmez). Web ve Android oturumu kullanabilir (LD-60): kişinin kendi gelen kutusu kurumsal izin
 * değildir, ADR-013 istemci kapısı etkilenmez.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
class InboxController {

	private static final Set<String> CATEGORIES = Set.of("ALERT", "TASK", "ANNOUNCEMENT", "MEETING_POINT",
			"OPERATION_INVITE", "SYSTEM");

	private final NotificationStore store;
	private final Clock clock;

	InboxController(NotificationStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	/** §11.3 {@code NotificationInboxItem}; {@code id} IN_APP teslim kaydının kimliğidir. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InboxItem(UUID id, String category, String kind, String priority, String title, String body,
			Instant createdAt, Instant readAt, Link link, UUID operationId) {
	}

	/** İstemci rotaya çevirir: {@code TASK}, {@code GROUP}, {@code INVITATION}, {@code OPERATION}, {@code ANNOUNCEMENT}. */
	record Link(String type, UUID id) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InboxPage(List<InboxItem> items, String nextCursor) {
	}

	record UnreadCount(int unreadCount) {
	}

	/** NT-04: en yeni önce; {@code unreadOnly}, {@code category}, {@code limit} (1..100), {@code cursor}. */
	@GetMapping
	@Transactional(readOnly = true)
	ResponseEntity<InboxPage> list(@RequestParam(required = false) String unreadOnly,
			@RequestParam(required = false) String category, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		UUID userId = Subject.from(authentication).userId();
		List<FieldError> errors = new ArrayList<>();
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		boolean unread = bool("unreadOnly", unreadOnly, errors);
		if (category != null && !CATEGORIES.contains(category)) {
			errors.add(new FieldError("category", "INVALID_ENUM", "Geçersiz kategori"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		PageCursor.Slice<InboxRow> slice = page.slice(store.inbox(userId, unread, category, page.after(), page.limit()),
				InboxRow::id);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new InboxPage(slice.items().stream().map(InboxController::item).toList(), slice.nextCursor()));
	}

	/** Bildirim ayrıntısı (LD-60 eki): okundu işaretlemez; istemci ayrıca NT-05 çağırır. */
	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	ResponseEntity<InboxItem> get(@PathVariable UUID id, Authentication authentication) {
		InboxRow row = store.inboxItem(Subject.from(authentication).userId(), id)
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(item(row));
	}

	/** NT-05: {@code 204}; {@code read_at} ilk okumada dolar, tekrar çağrı zararsızdır (doğal idempotent). */
	@PostMapping("/{id}/read")
	@Transactional
	ResponseEntity<Void> read(@PathVariable UUID id, Authentication authentication) {
		if (!store.markRead(Subject.from(authentication).userId(), id, clock.instant())) {
			throw ApiException.of(Code.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	/** Okunmamış sayısı (LD-60 eki): istemci yoklaması bu ucu kullanır (tek indeks sayımı). */
	@GetMapping("/unread-count")
	@Transactional(readOnly = true)
	ResponseEntity<UnreadCount> unreadCount(Authentication authentication) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new UnreadCount(store.unreadCount(Subject.from(authentication).userId())));
	}

	static InboxItem item(InboxRow row) {
		Link link = row.sourceType() != null ? new Link(row.sourceType(), row.sourceId())
				: "ANNOUNCEMENT".equals(row.category()) ? new Link("ANNOUNCEMENT", row.notificationId()) : null;
		return new InboxItem(row.id(), row.category(), row.kind(), row.priority(), row.title(), row.body(),
				row.createdAt(), row.readAt(), link, row.operationId());
	}

	private static boolean bool(String field, String value, List<FieldError> errors) {
		if (value == null) {
			return false;
		}
		if (!value.equals("true") && !value.equals("false")) {
			errors.add(new FieldError(field, "INVALID_FORMAT", field + " true veya false olmalıdır"));
			return false;
		}
		return Boolean.parseBoolean(value);
	}

}
