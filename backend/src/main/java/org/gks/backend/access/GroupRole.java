package org.gks.backend.access;

/**
 * K2 katmanı: öznenin kaynağın grubundaki aktif üyelik rolü (authorization.md §2). Üyelik verisi groups modülündedir;
 * groups bu rolü {@link GroupAccess}'e girdi olarak verir, böylece access groups'a bağımlı olmaz.
 */
public enum GroupRole {
	LEADER, MEMBER
}
