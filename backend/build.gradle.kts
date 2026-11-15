plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.gks"
version = "0.0.1-SNAPSHOT"
description = "Gonullu Koordinasyon Sistemi backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// JWT (HS256) üretimi ve doğrulaması; yalnızca JOSE kütüphanesi, kaynak sunucusu otomatik yapılandırması yok.
	implementation("org.springframework.security:spring-security-oauth2-jose")
	// Argon2id parola özeti (Spring Security Argon2PasswordEncoder bu sağlayıcıyı ister).
	implementation("org.bouncycastle:bcprov-jdk18on:1.81")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

// Yerel çalıştırma: kök dizindeki .env (depoya girmez; şablon .env.example) bootRun ortamına yüklenir.
// compose.yaml aynı dosyayı kullanır. Ortamda zaten tanımlı değişken ezilmez.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	val envFile = rootProject.file("../.env")
	if (envFile.exists()) {
		envFile.readLines()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
			.forEach { line ->
				val key = line.substringBefore('=').trim()
				if (System.getenv(key) == null) {
					environment(key, line.substringAfter('=').trim())
				}
			}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}
