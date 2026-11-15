package org.gks.backend.identity;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Davet e-postası (K-06; geliştirme ve demoda Mailpit). Kod yalnızca e-postada bulunur; günlüğe ve API yanıtına
 * yazılmaz. Gönderim davet işlemi commit edildikten sonra yapılır; başarısızlık daveti geri almaz, davet iptal edilip
 * yeniden oluşturulabilir.
 */
@Component
class InvitationMailer {

	private static final Logger log = LoggerFactory.getLogger(InvitationMailer.class);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
		.withZone(ZoneId.of("Europe/Istanbul"));

	private final JavaMailSender sender;
	private final InvitationCodes codes;

	InvitationMailer(JavaMailSender sender, InvitationCodes codes) {
		this.sender = sender;
		this.codes = codes;
	}

	/** Yeni kullanıcı daveti: Android uygulamasında kodla kayıt (ID-16). */
	void sendNewUser(String email, String scopeName, String code, Instant expiresAt) {
		String display = code.substring(0, 4) + "-" + code.substring(4);
		send(email, "Gönüllü Koordinasyon Sistemi daveti", """
				Merhaba,

				%s kapsamında Gönüllü Koordinasyon Sistemi'ne davet edildiniz.

				Android uygulamasında "Davet kodum var" adımına aşağıdaki kodu girerek hesabınızı oluşturun:

				    %s

				Bağlantı: %s#%s

				Davet %s tarihine kadar geçerlidir. Bu daveti beklemiyorsanız e-postayı yok sayın.
				""".formatted(scopeName, display, codes.linkBase(), code, DATE.format(expiresAt)));
	}

	/** Kayıtlı kullanıcıya operasyon daveti: uygulamada kabul veya ret (ID-17/18). */
	void sendOperation(String email, String scopeName, Instant expiresAt) {
		send(email, "Operasyon daveti: " + scopeName, """
				Merhaba,

				"%s" operasyonuna katılmaya davet edildiniz. Android uygulamasında giriş yaptıktan sonra davetler
				bölümünden kabul edebilir veya reddedebilirsiniz.

				Davet %s tarihine kadar geçerlidir.
				""".formatted(scopeName, DATE.format(expiresAt)));
	}

	private void send(String to, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(codes.mailFrom());
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		try {
			sender.send(message);
		}
		catch (MailException ex) {
			// Alıcı ve içerik (kod) günlüğe yazılmaz.
			log.warn("Davet e-postası gönderilemedi: {}", ex.getClass().getSimpleName());
		}
	}

}
