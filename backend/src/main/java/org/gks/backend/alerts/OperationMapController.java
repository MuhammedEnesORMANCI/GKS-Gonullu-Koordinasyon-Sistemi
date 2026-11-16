package org.gks.backend.alerts;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.access.Subject;
import org.gks.backend.alerts.OperationMapService.MapOperation;
import org.gks.backend.organization.OperationState;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADR-025: {@code GET /api/v1/map/operations?state=ACTIVE&state=PLANNED} (varsayılan yalnızca ACTIVE). */
@RestController
class OperationMapController {

	private final OperationMapService service;

	OperationMapController(OperationMapService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/map/operations/{operationId}")
	MapOperation operation(@PathVariable UUID operationId, Authentication authentication) {
		return service.operation(Subject.from(authentication), operationId);
	}

	@GetMapping("/api/v1/map/operations")
	List<MapOperation> operations(@RequestParam(name = "state", required = false) List<String> states,
			Authentication authentication) {
		Set<OperationState> wanted = EnumSet.noneOf(OperationState.class);
		if (states == null || states.isEmpty()) {
			wanted.add(OperationState.ACTIVE);
		}
		else {
			for (String value : states) {
				OperationState state = null;
				try {
					state = OperationState.valueOf(value);
				}
				catch (IllegalArgumentException ex) {
					// aşağıda
				}
				if (state == null || !OperationMapService.ALLOWED.contains(state)) {
					throw ApiException.validation(List.of(new FieldError("state", "NOT_ALLOWED_VALUE",
							"Haritada yalnızca ACTIVE, PLANNED ve SUSPENDED operasyonlar gösterilir")));
				}
				wanted.add(state);
			}
		}
		return service.operations(Subject.from(authentication), wanted);
	}

}
