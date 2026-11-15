package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Temel güvenlik yapılandırmasını gerçek HTTP istekleriyle doğrular (backend-api-design.md §2, §4.1).
 * Kimlik doğrulama mekanizması henüz olmadığından korunan her uç 401 döner.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigurationTests {

	private static final String PROBLEM_JSON = "application/problem+json";

	private final HttpClient http = HttpClient.newHttpClient();

	@Autowired
	private Environment environment;

	@Nested
	class PublicEndpoints {

		@Test
		void pingIsPublicAndNotCached() throws Exception {
			HttpResponse<String> response = send(get("/api/v1/ping"));

			assertThat(response.statusCode()).isEqualTo(204);
			assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
					value -> assertThat(value).contains("no-store"));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
		void healthEndpointsArePublic(String path) throws Exception {
			HttpResponse<String> response = send(get(path));

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}

	}

	@Nested
	class ProtectedEndpoints {

		@ParameterizedTest
		@ValueSource(strings = { "/actuator/info", "/actuator/modulith", "/api/v1/operations", "/api/v1/me", "/unknown" })
		void requireAuthenticationWithProblemResponse(String path) throws Exception {
			HttpResponse<String> response = send(get(path));

			assertUnauthenticated(response);
		}

		@Test
		void pingAcceptsOnlyGetWithoutAuthentication() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/ping").build());

			assertUnauthenticated(response);
		}

		@Test
		void eachErrorResponseHasItsOwnTraceId() throws Exception {
			String first = traceId(send(get("/api/v1/me")).body());
			String second = traceId(send(get("/api/v1/me")).body());

			assertThat(first).matches("[0-9a-f]{32}");
			assertThat(second).matches("[0-9a-f]{32}").isNotEqualTo(first);
		}

	}

	@Nested
	class CsrfProtection {

		@Test
		void cookieRequestWithoutTokenIsRejected() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/operations")
					.header("Cookie", "XSRF-TOKEN=abc123").build());

			assertProblem(response, 403, "CSRF_TOKEN_INVALID");
		}

		@Test
		void cookieRequestWithMismatchedTokenIsRejected() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/operations")
					.header("Cookie", "XSRF-TOKEN=abc123")
					.header("X-CSRF-Token", "different").build());

			assertProblem(response, 403, "CSRF_TOKEN_INVALID");
		}

		@Test
		void matchingDoubleSubmitTokenPassesCsrfAndReachesAuthentication() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/operations")
					.header("Cookie", "XSRF-TOKEN=abc123")
					.header("X-CSRF-Token", "abc123").build());

			assertUnauthenticated(response);
		}

		@Test
		void bearerRequestDoesNotNeedCsrfToken() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/operations")
					.header("Authorization", "Bearer not-a-real-token")
					.header("Cookie", "XSRF-TOKEN=abc123").build());

			assertUnauthenticated(response);
		}

		@Test
		void cookielessRequestDoesNotNeedCsrfToken() throws Exception {
			HttpResponse<String> response = send(post("/api/v1/operations").build());

			assertUnauthenticated(response);
		}

	}

	@Nested
	class ResponseHardening {

		@Test
		void noServerSessionIsCreated() throws Exception {
			HttpResponse<String> response = send(get("/api/v1/me"));

			assertThat(response.headers().allValues("Set-Cookie"))
					.noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
		}

		@Test
		void defaultSecurityHeadersArePresent() throws Exception {
			HttpResponse<String> response = send(get("/api/v1/ping"));

			assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
			assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
		}

		@Test
		void crossOriginRequestsGetNoCorsHeaders() throws Exception {
			HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/ping"))
					.header("Origin", "https://evil.example").GET().build());

			assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
		}

	}

	private void assertUnauthenticated(HttpResponse<String> response) {
		assertProblem(response, 401, "AUTH_UNAUTHENTICATED");
	}

	private void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				value -> assertThat(value).startsWith(PROBLEM_JSON));
		assertThat(response.body())
				.contains("\"status\":" + status)
				.contains("\"code\":\"" + code + "\"")
				.contains("\"type\":\"/problems/")
				.contains("\"title\":\"")
				.containsPattern("\"traceId\":\"[0-9a-f]{32}\"");
	}

	private static String traceId(String body) {
		return body.replaceAll(".*\"traceId\":\"([0-9a-f]+)\".*", "$1");
	}

	private HttpRequest get(String path) {
		return HttpRequest.newBuilder(uri(path)).GET().build();
	}

	private HttpRequest.Builder post(String path) {
		return HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path);
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
