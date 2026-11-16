package org.gks.backend.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mesajlaşma kanalları için rol kararları (ADR-024). Grup üyeliği ve liderlik groups modülünden gelir; burada
 * yalnızca K1 rolleri (KOR/ADM, kurumsal oturum kuralıyla) ve katılım vardır. Her çağrıda veritabanından okunur (LD-55).
 */
public interface ChannelAccess {

	/** KOR kendi operasyonunda veya ADM (MFA'lı yönetim oturumu; ADR-014/015). */
	boolean coordinates(Subject subject, UUID operationId);

	/** ADM kurumsal oturumu: tüm operasyonların kanallarını okur ve yazar. */
	boolean allOperations(Subject subject);

	/** Kurumsal oturumda KOR olduğu operasyonlar. */
	Set<UUID> coordinatedOperations(Subject subject);

	/** Aktif katılımcısı (GON) olduğu operasyonlar. */
	Set<UUID> participatingOperations(UUID userId);

	/** Operasyonun aktif koordinatörleri (bildirim alıcıları). */
	List<UUID> coordinators(UUID operationId);

}
