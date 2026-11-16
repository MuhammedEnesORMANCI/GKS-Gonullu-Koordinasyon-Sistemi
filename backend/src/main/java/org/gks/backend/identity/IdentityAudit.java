package org.gks.backend.identity;

import java.util.Map;
import java.util.UUID;

import org.gks.backend.AuditTrail;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kimlik olaylarının denetimi (ADR-020): başarılı/başarısız giriş, çıkış, MFA kurulumu, yenileme belirtecinin tekrar
 * kullanımı. Çağıranın işlemi varsa ona katılır, yoksa (giriş akışı bilinçli olarak işlemsizdir) kendi kısa işlemini
 * açar. Parola, belirteç, bilet, TOTP kodu ve e-posta <b>yazılmaz</b>; IP {@link AuditTrail} tarafından istekten,
 * istemci türü çağırandan gelir.
 */
@Component
class IdentityAudit {

	static final String RESOURCE = "SESSION";

	private final AuditTrail audit;
	private final TransactionTemplate transactions;

	IdentityAudit(AuditTrail audit, TransactionTemplate transactions) {
		this.audit = audit;
		this.transactions = transactions;
	}

	/**
	 * Başarısız işlemin kaydı: denetim yazılamazsa (ör. veritabanı erişilemez) asıl hata korunur, denetim hatası ona
	 * eklenir; kullanıcı yanlış hata kodu görmez. Başarılı işlemin kaydı ise {@link #record} ile zorunludur.
	 */
	void recordFailure(UUID userId, ClientType client, Map<String, Object> details, RuntimeException original) {
		try {
			record(userId, "SELF", client, "LOGIN_FAILED", null, details);
		}
		catch (RuntimeException auditError) {
			original.addSuppressed(auditError);
		}
	}

	/** @param userId tanınmayan hesapla başarısız girişte boş */
	void record(UUID userId, String basis, ClientType client, String action, UUID sessionId, Map<String, Object> details) {
		transactions.executeWithoutResult(status -> audit.record(userId, basis, client.name(), action, RESOURCE, sessionId,
				null, details));
	}

}
