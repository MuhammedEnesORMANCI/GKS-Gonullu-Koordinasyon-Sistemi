package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Uygulamanın gerçek bir PostgreSQL 16 (Testcontainers) ile ayağa kalktığını,
 * Flyway geçişlerinin uygulandığını ve sağlık uçlarının çalıştığını doğrular.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApplicationTests {

	private static final List<String> MODULE_SCHEMAS = List.of(
			"identity", "access", "organization", "groups", "tasks",
			"location", "alerts", "notifications", "audit", "platform");

	private final HttpClient http = HttpClient.newHttpClient();

	@Autowired
	private Environment environment;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void flywayCreatesAllModuleSchemas() {
		List<String> schemas = jdbc.queryForList(
				"select schema_name from information_schema.schemata", String.class);
		assertThat(schemas).containsAll(MODULE_SCHEMAS);

		Integer applied = jdbc.queryForObject(
				"select count(*) from platform.flyway_schema_history where success", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(1);
	}

	@Test
	void databaseRunsPostgres16() {
		String version = jdbc.queryForObject("show server_version", String.class);
		assertThat(version).startsWith("16.");
	}

	@Test
	void pingReturnsNoContentWithoutCaching() throws Exception {
		HttpResponse<String> response = get("/api/v1/ping");

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
		assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
				value -> assertThat(value).contains("no-store"));
	}

	@Test
	void actuatorHealthIsUpIncludingDatabase() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("\"status\":\"UP\"")
				.contains("\"db\"");
	}

	private HttpResponse<String> get(String path) throws Exception {
		String port = environment.getRequiredProperty("local.server.port");
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
