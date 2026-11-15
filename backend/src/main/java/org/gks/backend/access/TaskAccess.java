package org.gks.backend.access;

import java.util.UUID;

/**
 * Görev kaynakları için {@code can()} (ADR-002, authorization.md §5.4; backend-api-design.md §9.1). İstemci ve MFA
 * kapısı (§2.4) da burada uygulanır: {@code task.manage} web'de ve MFA'lı Android yönetim oturumunda (ADR-014),
 * {@code task.respond} Android'de.
 * <p>
 * Rol atamaları (K1) bu modülde henüz yoktur; o zamana kadar {@link DenyAllTaskAccess} her şeyi reddeder. Atama
 * verisi tasks modülündedir; tasks atama bilgisini girdi olarak verir, böylece access tasks'a bağımlı olmaz.
 */
public interface TaskAccess {

	/**
	 * @param operationId görevin operasyonu (kapsam)
	 * @param assignee    {@link TaskAction#RESPOND} için: atama istekte bulunan kişiye mi ait (AC-14.3); diğer
	 *                    eylemlerde {@code false}
	 */
	Decision can(Subject subject, TaskAction action, UUID operationId, boolean assignee);

	/** TK-07, TK-08, TK-09 görünüm kapsamı; filtre sorgu düzeyinde uygulanır (authorization.md §6). */
	ViewScope viewScope(Subject subject, UUID operationId);

	enum TaskAction {
		/** {@code task.manage}: oluşturma, düzenleme, yayın, durum, atama ekleme/kaldırma. K1: KOR kendi, ADM tümü (ADR-015). */
		MANAGE,
		/** {@code task.respond}: kendi atamasını kabul/ret (atanan kişi). */
		RESPOND,
		/** TK-10 {@code self}: kendi atamalarını listeleme (Android). */
		LIST_OWN_ASSIGNMENTS
	}

	enum ViewScope {
		/** KOR (kendi operasyonu) ve ADM (tüm operasyonlar): tüm görevler, hedefler, geçmiş ve sayaçlar. */
		ALL,
		/** GON/LDR (ve Android oturumundaki KOR, §2.4): yalnızca kendisine atanmış görevler, gönüllü izdüşümü. */
		ASSIGNED,
		/** Kapsam dışı: {@code 403 FORBIDDEN}. */
		NONE
	}

}
