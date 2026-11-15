package org.gks.backend.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * ID-11 {@code MeContext.permissions}: <b>bu oturumun</b> etkin izinleri; istemci kapısı ve MFA kuralı uygulanmıştır
 * (backend §2.4, §5 {@code EffectivePermissions}; authorization.md §5). Yalnızca menü ve düğmeler içindir, yetki
 * kaynağı değildir: sunucu her istekte {@code can()} ile yeniden denetler. Kurumsal izinler ADM/KOR'da MFA'lı, TEK/UYM'de
 * MFA'sız web oturumu ister; Android oturumu kurumsal izin taşımaz (ADR-013).
 */
@Component
public class EffectivePermissions {

	/** ADM kurum kapsamı (yalnızca K1). {@code operation.manage}, {@code alert.view}, {@code group.view}: tüm operasyonlar. */
	static final List<String> ADM = List.of("operation.create", "operation.manage", "invitation.create", "role.assign",
			"user.status.view", "account.disable", "session.revoke", "audit.view", "mfa.reset", "password.reset",
			"announcement.send.organization", "alert.view", "group.view");

	static final List<String> TEK = List.of("user.status.view", "account.disable", "session.revoke");

	static final List<String> UYM = List.of("audit.view");

	/** KOR kendi operasyonunda. */
	static final List<String> KOR = List.of("operation.manage", "invitation.create", "task.manage", "location.view",
			"alert.send", "alert.cancel", "alert.close", "alert.resend", "alert.view", "announcement.send.group",
			"group.create.operational", "group.view", "group.update", "membership.add", "membership.remove",
			"membership.role.update", "meeting_point.update");

	/** Katılımcı (GON); her istemcide. */
	static final List<String> GON = List.of("task.respond", "location.share", "alert.respond");

	static final List<String> LEADER = List.of("group.view", "group.update", "meeting_point.update");

	static final List<String> MEMBER = List.of("group.view");

	private final RoleAssignmentStore store;

	EffectivePermissions(RoleAssignmentStore store) {
		this.store = store;
	}

	public record View(List<String> organization, Map<UUID, List<String>> operations, Map<UUID, List<String>> groups) {
	}

	/** @param groupRoles öznenin arşivlenmemiş gruplardaki aktif rolleri (K2; groups'tan) */
	public View of(Subject subject, Map<UUID, GroupRole> groupRoles) {
		Grants grants = store.grants(subject.userId());
		List<String> organization = new ArrayList<>(List.of("invitation.respond"));
		if (Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed()) {
			addAll(organization, ADM);
		}
		if (Grants.corporate(subject, grants.has(Role.TEK), Role.TEK).allowed()) {
			addAll(organization, TEK);
		}
		if (Grants.corporate(subject, grants.has(Role.UYM), Role.UYM).allowed()) {
			addAll(organization, UYM);
		}
		Map<UUID, List<String>> operations = new LinkedHashMap<>();
		boolean coordinatorSession = Grants.corporate(subject, true, Role.KOR).allowed();
		for (UUID operationId : grants.operations(Role.GON)) {
			addAll(operations.computeIfAbsent(operationId, k -> new ArrayList<>()), GON);
		}
		if (coordinatorSession) {
			for (UUID operationId : grants.operations(Role.KOR)) {
				addAll(operations.computeIfAbsent(operationId, k -> new ArrayList<>()), KOR);
			}
		}
		Map<UUID, List<String>> groups = new LinkedHashMap<>();
		groupRoles.forEach((groupId, role) -> groups.put(groupId, List.copyOf(role == GroupRole.LEADER ? LEADER : MEMBER)));
		return new View(List.copyOf(organization), operations, groups);
	}

	private static void addAll(List<String> target, List<String> permissions) {
		permissions.stream().filter(p -> !target.contains(p)).forEach(target::add);
	}

}
