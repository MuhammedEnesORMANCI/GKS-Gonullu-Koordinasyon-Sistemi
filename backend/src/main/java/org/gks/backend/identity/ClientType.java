package org.gks.backend.identity;

/** Oturumun istemci türü; JWT {@code cli} claim'i ve {@code refresh_tokens.client} (backend-api-design.md §2.2). */
public enum ClientType {
	WEB, ANDROID
}
