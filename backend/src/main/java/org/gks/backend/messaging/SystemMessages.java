package org.gks.backend.messaging;

import java.util.UUID;

/**
 * Diğer modüllerin kanallara sistem mesajı yazdığı açık arayüz (ADR-024; ör. genel acil alarm metni). Çağıranın
 * işleminde çalışır; telefon bildirimi üretmez (alarmın kendi bildirimi vardır). Kapalı operasyonda yazmaz.
 */
public interface SystemMessages {

	/** @param groupId {@code null} ise operasyon kanalı, değilse grubun kanalı */
	void post(UUID operationId, UUID groupId, String text);

}
