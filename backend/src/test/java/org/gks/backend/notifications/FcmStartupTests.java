package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import com.google.firebase.FirebaseApp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

/**
 * ADR-022: FCM'nin gerçek başlatma yolu (FirebaseOptions, FirebaseApp, FirebaseMessaging) gerçek sınıf yoluyla ve
 * sahte bir hizmet hesabı JSON'u ile kurulur; ağa çıkılmaz. 16.11.2026'daki açılış hatası
 * ({@code NoClassDefFoundError: JacksonFactory}) bu yolu hiçbir testin çalıştırmamasından kaçmıştı.
 * Başlatma başarısız olursa uygulama çökmez, push kapalıya düşer.
 */
class FcmStartupTests {

	@TempDir
	Path dir;

	@AfterEach
	void deleteApp() {
		FirebaseApp.getApps().stream().filter(a -> a.getName().equals("gks")).forEach(FirebaseApp::delete);
	}

	@Test
	void realFirebaseOptionsAndMessagingBuildFromServiceAccountJsonWithoutNetwork() throws Exception {
		Path key = fakeServiceAccount();
		FcmPushGateway gateway = FcmPushGateway.fromCredentialsFile(key.toString());
		assertThat(gateway.enabled()).isTrue();
		// FirebaseMessaging istemcisi proje kimliği (hizmet hesabından "gks-test") olmadan kurulamaz.
		assertThat(com.google.firebase.messaging.FirebaseMessaging.getInstance(FirebaseApp.getInstance("gks"))).isNotNull();
		assertThat(com.google.firebase.ImplFirebaseTrampolines.getProjectId(FirebaseApp.getInstance("gks"))).isEqualTo("gks-test");

		PushConfiguration.PushSetup setup = PushConfiguration.setup("fcm", key.toString(), FcmPushGateway::fromCredentialsFile);
		assertThat(setup.status()).isEqualTo(Status.UP);
		assertThat(setup.gateway().enabled()).isTrue();
	}

	@Test
	void missingClassOrUnreadableKeyFallsBackToDisabledInsteadOfCrashing() {
		PushConfiguration.PushSetup linkage = PushConfiguration.setup("fcm", "x", path -> {
			throw new NoClassDefFoundError("com/google/api/client/json/jackson2/JacksonFactory");
		});
		assertThat(linkage.status()).isEqualTo(PushConfiguration.DEGRADED);
		assertThat(linkage.gateway().enabled()).isFalse();
		assertThat(linkage.reason()).contains("NoClassDefFoundError").contains("JacksonFactory");

		PushConfiguration.PushSetup missing = PushConfiguration.setup("fcm", dir.resolve("yok.json").toString(),
				FcmPushGateway::fromCredentialsFile);
		assertThat(missing.status()).isEqualTo(PushConfiguration.DEGRADED);
		assertThat(missing.gateway().enabled()).isFalse();

		assertThat(PushConfiguration.setup("fcm", "", FcmPushGateway::fromCredentialsFile).status()).isEqualTo(PushConfiguration.DEGRADED);
		assertThat(PushConfiguration.setup("apns", "", FcmPushGateway::fromCredentialsFile).status()).isEqualTo(PushConfiguration.DEGRADED);
		assertThat(PushConfiguration.setup("none", "", FcmPushGateway::fromCredentialsFile).status()).isEqualTo(PushConfiguration.DISABLED);
	}

	@Test
	void healthShowsPushStateWithoutSecrets() throws Exception {
		Path key = fakeServiceAccount();
		var config = new PushConfiguration();
		var degraded = config.pushHealthIndicator(PushConfiguration.setup("fcm", dir.resolve("yok.json").toString(),
				FcmPushGateway::fromCredentialsFile)).health();
		assertThat(degraded.getStatus()).isEqualTo(PushConfiguration.DEGRADED);
		assertThat(degraded.getDetails()).containsEntry("provider", "fcm").containsKey("reason");
		var up = config.pushHealthIndicator(PushConfiguration.setup("fcm", key.toString(), FcmPushGateway::fromCredentialsFile)).health();
		assertThat(up.getStatus()).isEqualTo(Status.UP);
		assertThat(up.getDetails().toString()).doesNotContain("PRIVATE KEY").doesNotContain(key.toString());
	}

	private Path fakeServiceAccount() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String pem = "-----BEGIN PRIVATE KEY-----\\n"
				+ Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
				+ "\\n-----END PRIVATE KEY-----\\n";
		String json = """
				{"type":"service_account","project_id":"gks-test","private_key_id":"test-key-id","private_key":"%s",
				 "client_email":"fcm@gks-test.iam.gserviceaccount.com","client_id":"1",
				 "token_uri":"https://oauth2.googleapis.com/token"}""".formatted(pem);
		Path file = dir.resolve("sahte-hizmet-hesabi.json");
		Files.writeString(file, json);
		return file;
	}

}
