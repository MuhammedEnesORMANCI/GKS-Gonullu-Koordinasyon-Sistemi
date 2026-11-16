package org.gks.backend.realtime;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.gks.backend.ApiProblems;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.SessionPrincipal;
import org.gks.backend.access.Decision;
import org.gks.backend.access.Subject;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RT-01 {@code GET /stream} (§12.1–12.2). Kimlik ve oturum iptali güvenlik filtresinde, her konunun yetkisi
 * <b>her bağlantı açılışında</b> denetlenir; konulardan biri yetkisizse akış açılmaz ({@code 403}, kısmi abonelik
 * yok). Hatalar {@code text/event-stream} isteyen istemciye de {@code application/problem+json} olarak yazılır.
 */
@RestController
@RequestMapping("/api/v1/stream")
class StreamController {

	static final int MAX_TOPICS = 10;

	private static final Pattern TOPIC = Pattern.compile(
			"organization|(operation|user):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final StreamHub hub;

	StreamController(StreamHub hub) {
		this.hub = hub;
	}

	@GetMapping
	SseEmitter stream(@RequestParam(required = false) String topics,
			@RequestParam(name = "lastEventId", required = false) String lastEventIdParam,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
			Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
		Subject subject = Subject.from(authentication);
		UUID sessionId = ((SessionPrincipal) authentication.getPrincipal()).sessionId();
		Set<String> requested = new LinkedHashSet<>();
		if (topics != null) {
			for (String raw : topics.split(",")) {
				String topic = raw.trim();
				if (topic.isEmpty()) {
					continue;
				}
				if (!TOPIC.matcher(topic).matches()) {
					return problem(request, response, Code.VALIDATION_FAILED);
				}
				requested.add(topic.toLowerCase(Locale.ROOT));
			}
		}
		if (requested.size() > MAX_TOPICS) {
			return problem(request, response, Code.VALIDATION_FAILED);
		}
		for (String topic : requested) {
			Decision decision = hub.authorize(subject, topic);
			if (!decision.allowed()) {
				return problem(request, response,
						decision == Decision.CLIENT_NOT_ALLOWED ? Code.CLIENT_NOT_ALLOWED : Code.FORBIDDEN);
			}
		}
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Accel-Buffering", "no");   // ters vekil arabelleğe almasın
		// Tarayıcının yerel yeniden bağlanması başlığı gönderir; ikisi birden gelirse başlık esastır (BOŞLUK-20).
		String lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;
		return hub.open(subject, sessionId, requested, lastEventId);
	}

	private static SseEmitter problem(HttpServletRequest request, HttpServletResponse response, Code code)
			throws IOException {
		ApiProblems.write(request, response, code);
		return null;
	}

}
