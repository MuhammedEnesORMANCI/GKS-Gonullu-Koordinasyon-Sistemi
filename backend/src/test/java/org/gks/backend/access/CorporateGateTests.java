package org.gks.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-014 istemci ve MFA kapısının tam tablosu ({@link Grants#corporate}). */
class CorporateGateTests {

	@ParameterizedTest(name = "{0} web={1} mfa={2} → {3}")
	@CsvSource({
			// Web (değişmedi): ADM/KOR MFA ister, TEK/UYM istemez
			"ADM, true, true, ALLOW", "ADM, true, false, CLIENT_NOT_ALLOWED",
			"KOR, true, true, ALLOW", "KOR, true, false, CLIENT_NOT_ALLOWED",
			"TEK, true, false, ALLOW", "UYM, true, false, ALLOW",
			// Android: yalnızca MFA'lı yönetim oturumu ve yalnızca ADM/KOR
			"ADM, false, true, ALLOW", "ADM, false, false, CLIENT_NOT_ALLOWED",
			"KOR, false, true, ALLOW", "KOR, false, false, CLIENT_NOT_ALLOWED",
			"TEK, false, true, CLIENT_NOT_ALLOWED", "TEK, false, false, CLIENT_NOT_ALLOWED",
			"UYM, false, true, CLIENT_NOT_ALLOWED", "UYM, false, false, CLIENT_NOT_ALLOWED",
	})
	void gate(Role role, boolean web, boolean mfa, Decision expected) {
		assertThat(Grants.corporate(new Subject(UUID.randomUUID(), web, mfa), true, role)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "true, true", "false, true", "false, false" })
	void withoutRoleAlwaysForbidden(boolean web, boolean mfa) {
		assertThat(Grants.corporate(new Subject(UUID.randomUUID(), web, mfa), false, Role.KOR)).isEqualTo(Decision.FORBIDDEN);
	}

}
