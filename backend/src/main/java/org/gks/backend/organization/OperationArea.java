package org.gks.backend.organization;

import java.util.List;
import java.util.Optional;

import org.gks.backend.ApiException.FieldError;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** Operasyon bölgesi (ADR-025): merkez ve isteğe bağlı yarıçap (metre, 50..200000). */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OperationArea(double lat, double lon, Integer radiusM) {

	static final int RADIUS_MIN = 50;
	static final int RADIUS_MAX = 200_000;

	static OperationArea of(Double lat, Double lon, Integer radiusM) {
		return lat == null || lon == null ? null : new OperationArea(lat, lon, radiusM);
	}

	/** {@code null} gövde: bölge silinir ({@code Optional.empty()}). */
	static Optional<OperationArea> parse(JsonNode node, List<FieldError> errors) {
		if (node == null || node.isNull()) {
			return Optional.empty();
		}
		if (!node.isObject()) {
			errors.add(new FieldError("area", "INVALID_FORMAT", "Bölge nesne olmalıdır"));
			return Optional.empty();
		}
		for (String field : node.propertyNames()) {
			if (!List.of("lat", "lon", "radiusM").contains(field)) {
				errors.add(new FieldError("area." + field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		Double lat = number(node.get("lat"), "area.lat", -90, 90, "Enlem -90 ile 90 arasında olmalıdır", errors);
		Double lon = number(node.get("lon"), "area.lon", -180, 180, "Boylam -180 ile 180 arasında olmalıdır", errors);
		Integer radius = null;
		JsonNode r = node.get("radiusM");
		if (r != null && !r.isNull()) {
			if (!r.isIntegralNumber()) {
				errors.add(new FieldError("area.radiusM", "INVALID_FORMAT", "Yarıçap tam sayı (metre) olmalıdır"));
			}
			else if (r.asLong() < RADIUS_MIN || r.asLong() > RADIUS_MAX) {
				errors.add(new FieldError("area.radiusM", "OUT_OF_RANGE",
						"Yarıçap " + RADIUS_MIN + " ile " + RADIUS_MAX + " metre arasında olmalıdır"));
			}
			else {
				radius = (int) r.asLong();
			}
		}
		return lat == null || lon == null ? Optional.empty() : Optional.of(new OperationArea(lat, lon, radius));
	}

	private static Double number(JsonNode node, String field, double min, double max, String message,
			List<FieldError> errors) {
		if (node == null || node.isNull()) {
			errors.add(new FieldError(field, "REQUIRED", "Enlem ve boylam birlikte verilmelidir"));
			return null;
		}
		if (!node.isNumber() || Double.isNaN(node.asDouble()) || Double.isInfinite(node.asDouble())) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Koordinat sayı olmalıdır"));
			return null;
		}
		double value = node.asDouble();
		if (value < min || value > max) {
			errors.add(new FieldError(field, "OUT_OF_RANGE", message));
			return null;
		}
		return value;
	}

}
