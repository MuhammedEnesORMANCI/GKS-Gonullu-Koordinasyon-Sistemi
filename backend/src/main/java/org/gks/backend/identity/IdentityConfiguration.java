package org.gks.backend.identity;

import java.time.Clock;
import java.util.Map;

import org.gks.backend.SecurityContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class IdentityConfiguration {

	/**
	 * Argon2id (ADR-012). bcrypt seçilmedi: 72 baytı aşan parolayı reddeder, oysa parola kuralı 128 karaktere
	 * (Türkçe karakterlerle 256 bayta kadar) izin verir. Parametreler OWASP önerisi: 19 MiB, 2 tur, 1 iş parçacığı.
	 * Özet algoritma ön ekiyle saklanır ({@code {argon2}$argon2id$…}, DB §3.1).
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder("argon2",
				Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)));
	}

	/** İmza anahtarı JWK olarak, başlıktaki {@code kid} ile eşleşecek sürümle tanımlanır. */
	@Bean
	JwtEncoder jwtEncoder(JwtProperties properties) {
		OctetSequenceKey key = new OctetSequenceKey.Builder(properties.secretKey())
			.keyID(properties.keyId())
			.algorithm(JWSAlgorithm.HS256)
			.build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
	}

	/** Yalnızca HS256 kabul edilir; süre ve claim denetimi {@link AccessTokens} içinde yapılır. */
	@Bean
	JwtDecoder jwtDecoder(JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		decoder.setJwtValidator(AccessTokens.structureValidator(properties.keyId()));
		return decoder;
	}

	@Bean
	AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(AccessTokens tokens, Users users, Clock clock) {
		return new AccessTokenAuthenticationFilter(tokens, users, clock);
	}

	/** Filtre, ortak güvenlik zincirinde anonim kimlikten önce çalışır. */
	@Bean
	SecurityContributor accessTokenAuthentication(AccessTokenAuthenticationFilter filter) {
		return http -> http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
	}

	/** Filtre yalnızca güvenlik zincirinde çalışır; sunucu filtresi olarak ikinci kez kaydedilmez. */
	@Bean
	FilterRegistrationBean<AccessTokenAuthenticationFilter> accessTokenFilterRegistration(
			AccessTokenAuthenticationFilter filter) {
		FilterRegistrationBean<AccessTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

}
