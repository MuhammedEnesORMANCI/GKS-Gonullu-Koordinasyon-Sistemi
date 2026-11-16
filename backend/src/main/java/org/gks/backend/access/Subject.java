package org.gks.backend.access;

import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.SessionPrincipal;
import org.springframework.security.core.Authentication;

/**
 * {@code can()} öznesi: kullanıcı ve oturumun istemci/MFA durumu (authorization.md §2, ADR-014). İstemci kapısı
 * (kurumsal izinler web'de; Android'de yalnızca ADM/KOR'un MFA'lı yönetim oturumunda) bu bilgiyle access modülünde
 * uygulanır.
 */
public record Subject(UUID userId, boolean webSession, boolean mfaVerified) {

	/** Güvenlik bağlamındaki oturumdan özne; oturum yoksa {@code 401 AUTH_UNAUTHENTICATED}. */
	public static Subject from(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal session) {
			return new Subject(session.userId(), session.webSession(), session.mfaVerified());
		}
		throw ApiException.of(Code.AUTH_UNAUTHENTICATED);
	}

	/**
	 * Yönetim uçlarının istemci kapısı (§2.4, §3.3 adım 4; ADR-014): web oturumu veya MFA'lı Android yönetim
	 * oturumu. Kaynak yüklenmeden önce, MFA'sız Android oturumu (gönüllü) {@code 403 CLIENT_NOT_ALLOWED} alır.
	 * Rol, kapsam ve izin kararı ayrıca {@code can()} ile verilir; bu kapı tek başına yetki vermez.
	 */
	public Subject requireManagementSession() {
		if (!webSession && !mfaVerified) {
			throw ApiException.of(Code.CLIENT_NOT_ALLOWED);
		}
		return this;
	}

	/** Denetim kaydının istemci alanı ({@code audit.audit_entries.client}). */
	public String client() {
		return webSession ? "WEB" : "ANDROID";
	}

	/** {@code x-client: [ANDROID]} uçları (görev yanıtı, kendi atamaları, davet kabulü). */
	public Subject requireAndroid() {
		if (webSession) {
			throw ApiException.of(Code.CLIENT_NOT_ALLOWED);
		}
		return this;
	}

}
