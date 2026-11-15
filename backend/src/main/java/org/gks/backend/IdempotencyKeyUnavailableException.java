package org.gks.backend;

/**
 * Süresi dolmamış bir idempotency satırının özet anahtarı sürümü yüklü değil (§3.6 "Eksik anahtar (fail-closed)",
 * LD-42 (7)). Satır yok sayılmaz ve istek yeni istek olarak işlenmez; yanıt {@code 500} olur. Mesaj anahtar değeri
 * ve sürüm bilgisi içermez; sürüm yalnızca işletim günlüğüne yazılır.
 */
public class IdempotencyKeyUnavailableException extends RuntimeException {

	IdempotencyKeyUnavailableException() {
		super("Idempotency kaydı doğrulanamadı");
	}

}
