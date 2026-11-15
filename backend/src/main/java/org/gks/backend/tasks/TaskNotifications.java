package org.gks.backend.tasks;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.notifications.Notifier;
import org.gks.backend.notifications.Notifier.Category;
import org.gks.backend.notifications.Notifier.Kind;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.gks.backend.notifications.Notifier.Priority;
import org.gks.backend.notifications.Notifier.SourceType;
import org.gks.backend.organization.Operations;
import org.gks.backend.tasks.TaskStore.TaskRow;
import org.springframework.stereotype.Component;

/**
 * Görev bildirimleri (communication.md §1.2: görev ataması {@code HIGH}; AC-13.5, AC-14.5, AC-15.3). Görev
 * servisinin işlemi içinde çağrılır; işlem geri alınırsa bildirim de oluşmaz. Alıcı yalnızca ilgili atananlardır;
 * işlemi yapan kişi kendi eylemi için bildirim almaz. Anahtarlar kaynak olaydan türer (DB §6.5).
 */
@Component
class TaskNotifications {

	private final Notifier notifier;
	private final Operations operations;

	TaskNotifications(Notifier notifier, Operations operations) {
		this.notifier = notifier;
		this.operations = operations;
	}

	/** TK-03 yayın: tüm anlık görüntü atananlarına tek bildirim. */
	void published(TaskRow task, Collection<UUID> assignees, UUID actor) {
		send("task:" + task.id() + ":PUBLISHED", Kind.TASK_ASSIGNED, Priority.HIGH, "Yeni görev: " + task.title(),
				operationName(task) + " operasyonunda size görev atandı. Kabul veya reddetmek için açın.", task, assignees, actor);
	}

	/** TK-05 elle atama (yeniden {@code PENDING} dahil; her atama anı ayrı olaydır). */
	void assigned(TaskRow task, UUID assignmentId, UUID userId, Instant assignedAt, UUID actor) {
		send("assignment:" + assignmentId + ":ADDED:" + assignedAt.toEpochMilli(), Kind.TASK_ASSIGNED, Priority.HIGH,
				"Yeni görev: " + task.title(),
				operationName(task) + " operasyonunda size görev atandı. Kabul veya reddetmek için açın.", task,
				Set.of(userId), actor);
	}

	/** TK-02 yayındaki görevde değişiklik (AC-13.5); anahtar yeni sürümden türer. */
	void updated(TaskRow task, int version, Collection<UUID> assignees, UUID actor) {
		send("task:" + task.id() + ":UPDATED:" + version, Kind.TASK_UPDATED, Priority.HIGH,
				"Görev güncellendi: " + task.title(), "Görevin ayrıntıları değişti. Güncel bilgileri görmek için açın.",
				task, assignees, actor);
	}

	/** TK-04: iptal ({@code TASK_CANCELLED}) veya ilerleme ({@code IN_PROGRESS}, {@code DONE}). */
	void stateChanged(TaskRow task, TaskState target, Collection<UUID> assignees, UUID actor) {
		switch (target) {
			case CANCELLED -> send("task:" + task.id() + ":CANCELLED", Kind.TASK_CANCELLED, Priority.HIGH,
					"Görev iptal edildi: " + task.title(), "Bu görev koordinatör tarafından iptal edildi.", task, assignees, actor);
			case IN_PROGRESS -> send("task:" + task.id() + ":IN_PROGRESS", Kind.TASK_UPDATED, Priority.HIGH,
					"Görev başladı: " + task.title(), "Kabul ettiğiniz görev başladı.", task, assignees, actor);
			case DONE -> send("task:" + task.id() + ":DONE", Kind.TASK_UPDATED, Priority.NORMAL,
					"Görev tamamlandı: " + task.title(), "Görev tamamlandı olarak işaretlendi.", task, assignees, actor);
			default -> {
			}
		}
	}

	/** TK-06: kişi görevden çıkarıldı. */
	void removed(TaskRow task, UUID assignmentId, UUID userId, Instant at, UUID actor) {
		send("assignment:" + assignmentId + ":REMOVED:" + at.toEpochMilli(), Kind.TASK_ASSIGNMENT_REMOVED, Priority.HIGH,
				"Görevden çıkarıldınız: " + task.title(), "Bu görevdeki atamanız kaldırıldı.", task, Set.of(userId), actor);
	}

	/** AC-14.5: kapasite dolunca yanıt bekleyen atamalar {@code CAPACITY_FULL} oldu. */
	void capacityFull(TaskRow task, Collection<UUID> users, Instant at) {
		send("task:" + task.id() + ":CAPACITY_FULL:" + at.toEpochMilli(), Kind.TASK_CAPACITY_FULL, Priority.NORMAL,
				"Görevin kapasitesi doldu: " + task.title(), "Yanıt bekleyen atamanız artık kabul edilemez.", task, users, null);
	}

	private void send(String key, Kind kind, Priority priority, String title, String body, TaskRow task,
			Collection<UUID> recipients, UUID actor) {
		Set<UUID> users = new LinkedHashSet<>(recipients);
		if (actor != null) {
			users.remove(actor);
		}
		notifier.notify(NotificationRequest.toUsers(key, Category.TASK, kind, priority, title, body, SourceType.TASK,
				task.id(), task.operationId(), users, actor));
	}

	private String operationName(TaskRow task) {
		return operations.find(task.operationId()).map(Operations.OperationRef::name).orElse("Bu");
	}

}
