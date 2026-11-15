package org.gks.backend;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Her isteğe bir izleme kimliği atar. Kimlik hata yanıtlarının zorunlu {@code traceId} alanına (§4.1)
 * ve günlük kayıtlarına (MDC) yazılır; böylece bir hata yanıtı günlükle eşleştirilebilir.
 * Güvenlik filtrelerinden önce çalışır, çünkü 401/403 yanıtları da bu kimliği taşır.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {

	static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
	static final String MDC_KEY = "traceId";

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HexFormat HEX = HexFormat.of();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = newTraceId();
		request.setAttribute(ATTRIBUTE, traceId);
		MDC.put(MDC_KEY, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	static String currentTraceId(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE);
		return value != null ? value.toString() : newTraceId();
	}

	private static String newTraceId() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HEX.formatHex(bytes);
	}

}
