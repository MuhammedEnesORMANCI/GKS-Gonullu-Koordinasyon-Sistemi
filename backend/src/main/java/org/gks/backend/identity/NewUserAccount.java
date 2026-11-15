package org.gks.backend.identity;

/**
 * Yeni hesap bilgileri (davet kabulü ve tohum verisi tarafından kullanılır; doğrudan API'si yoktur).
 * Alan adları {@code InvitationAcceptRequest} ile aynıdır; parola metne dönüşmez.
 */
public record NewUserAccount(String email, String password, String fullName, String displayName, String phone) {

	@Override
	public String toString() {
		return "NewUserAccount[displayName=" + displayName + "]";
	}

}
