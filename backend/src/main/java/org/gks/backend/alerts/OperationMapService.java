package org.gks.backend.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.access.ChannelAccess;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.alerts.AlertStore.Counts;
import org.gks.backend.groups.GroupMembers;
import org.gks.backend.groups.GroupMembers.MeetingPointRef;
import org.gks.backend.organization.OperationState;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.Operations.AreaRef;
import org.gks.backend.organization.Operations.OperationRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ADR-025 "Operasyon Haritası": ADM tüm operasyonları, KOR yalnızca koordine ettiklerini görür (kurumsal oturum,
 * ADR-014/015). Gönüllü konumları burada yoktur (konum yalnızca operasyon sayfasında, görüntüleme başına denetimle).
 */
@Service
class OperationMapService {

	static final Set<OperationState> ALLOWED = EnumSet.of(OperationState.ACTIVE, OperationState.PLANNED,
			OperationState.SUSPENDED);

	private final ChannelAccess access;
	private final Operations operations;
	private final GroupMembers groups;
	private final Participation participation;
	private final AlertStore alerts;

	OperationMapService(ChannelAccess access, Operations operations, GroupMembers groups, Participation participation,
			AlertStore alerts) {
		this.access = access;
		this.operations = operations;
		this.groups = groups;
		this.participation = participation;
		this.alerts = alerts;
	}

	@Transactional(readOnly = true)
	List<MapOperation> operations(Subject subject, Set<OperationState> states) {
		List<OperationRef> scope;
		if (access.allOperations(subject)) {
			scope = operations.all();
		}
		else {
			Set<UUID> coordinated = access.coordinatedOperations(subject);
			if (coordinated.isEmpty()) {
				throw ApiException.of(Code.FORBIDDEN);
			}
			scope = operations.all().stream().filter(op -> coordinated.contains(op.id())).toList();
		}
		return build(scope.stream().filter(op -> states.contains(op.state())).toList());
	}

	/** Operasyon sayfası haritası: o operasyonun KOR'u veya ADM (kapalı operasyon da). */
	@Transactional(readOnly = true)
	MapOperation operation(Subject subject, UUID operationId) {
		OperationRef op = operations.find(operationId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (!access.coordinates(subject, operationId)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		return build(List.of(op)).getFirst();
	}

	private List<MapOperation> build(List<OperationRef> visible) {
		Collection<UUID> ids = visible.stream().map(OperationRef::id).toList();
		Map<UUID, AreaRef> areas = operations.areas(ids);
		Map<UUID, List<MapMeetingPoint>> points = groups.meetingPoints(ids).stream()
			.collect(Collectors.groupingBy(MeetingPointRef::operationId,
					Collectors.mapping(MapOperationsMapper::point, Collectors.toList())));
		Map<UUID, AlertStore.OpenAlertRef> open = alerts.latestOpenAlerts(ids);
		Map<UUID, Counts> counts = alerts.counts(open.values().stream().map(AlertStore.OpenAlertRef::id).toList());
		List<MapOperation> result = new ArrayList<>();
		for (OperationRef op : visible) {
			AlertStore.OpenAlertRef alert = open.get(op.id());
			AreaRef area = areas.get(op.id());
			result.add(new MapOperation(op.id(), op.name(), op.state().name(),
					area == null ? null : new MapArea(area.lat(), area.lon(), area.radiusM()),
					participation.participants(op.id()).size(), points.getOrDefault(op.id(), List.of()),
					alert == null ? null : new MapAlert(alert.id(), alert.sentAt(), counts.getOrDefault(alert.id(), Counts.EMPTY))));
		}
		return result;
	}

	private static final class MapOperationsMapper {
		static MapMeetingPoint point(MeetingPointRef ref) {
			return new MapMeetingPoint(ref.groupId(), ref.groupName(), ref.lat(), ref.lon(), ref.address());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MapOperation(UUID id, String name, String state, MapArea area, int participantCount,
			List<MapMeetingPoint> meetingPoints, MapAlert openAlert) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MapArea(double lat, double lon, Integer radiusM) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MapMeetingPoint(UUID groupId, String groupName, double lat, double lon, String address) {
	}

	record MapAlert(UUID id, Instant sentAt, Counts counts) {
	}

}
