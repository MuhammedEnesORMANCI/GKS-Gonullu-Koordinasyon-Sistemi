package org.gks.backend.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * ID-24 geçici parola e-postası (AC-03.5; geliştirmede Mailpit). Parola yalnızca e-postada bulunur; günlüğe ve API
 * yanıtına yazılmaz. Gönderim işlem commit edildikten sonra yapılır; başarısız olursa yönetici sıfırlamayı yineleyebilir.
 */
@Component
class UserAdminMailer {

	private static final Logger log = LoggerFactory.getLogger(UserAdminMailer.class);

	private final JavaMailSender sender;
	private final InvitationCodes codes;

	UserAdminMailer(JavaMailSender sender, InvitationCodes codes) {
		this.sender = sender;
		this.codes = codes;
	}

	void sendTemporaryPassword(String email, String displayName, String temporaryPassword) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(codes.mailFrom());
		message.setTo(email);
		message.setSubject("Gönüllü Koordinasyon Sistemi: parolanız sıfırlandı");
		message.setText("""
				Merhaba %s,

				Kurum yöneticiniz parolanızı sıfırladı. Tüm oturumlarınız kapatıldı.

				Geçici parolanız:

				    %s

				İlk girişte yeni bir parola belirlemeniz istenecek. Bu işlemi siz istemediyseniz kurum yöneticinize bildirin.
				""".formatted(displayName, temporaryPassword));
		try {
			sender.send(message);
		}
		catch (MailException ex) {
			// Alıcı ve içerik (parola) günlüğe yazılmaz.
			log.warn("Geçici parola e-postası gönderilemedi: {}", ex.getClass().getSimpleName());
		}
	}

}
