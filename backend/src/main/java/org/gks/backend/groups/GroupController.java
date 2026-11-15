package org.gks.backend.groups;

import java.net.URI;
import java.util.UUID;

import org.gks.backend.access.Subject;
import org.gks.backend.groups.GroupRequests.GroupCreateRequest;
import org.gks.backend.groups.GroupRequests.GroupLeaderRequest;
import org.gks.backend.groups.GroupRequests.GroupMemberAddRequest;
import org.gks.backend.groups.GroupRequests.MeetingPointRequest;
import org.gks.backend.groups.GroupViews.GroupMemberView;
import org.gks.backend.groups.GroupViews.GroupPage;
import org.gks.backend.groups.GroupViews.GroupView;
import org.gks.backend.groups.GroupViews.MeetingPoint;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/** Grup uçları GR-01 – GR-08 (backend-api-design.md §8.1). GR-09/GR-10 Genişletilmiş halkadadır. */
@RestController
class GroupController {

	private final GroupService service;

	GroupController(GroupService service) {
		this.service = service;
	}

	/** GR-01. */
	@PostMapping("/api/v1/operations/{operationId}/groups")
	ResponseEntity<GroupView> create(@PathVariable UUID operationId, @RequestBody GroupCreateRequest request,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();   // x-client: [WEB]
		GroupView view = service.create(subject, operationId, GroupValidation.create(request));
		return ResponseEntity.created(URI.create("/api/v1/groups/" + view.id())).body(view);
	}

	/** GR-02. */
	@GetMapping("/api/v1/groups")
	GroupPage list(@RequestParam(required = false) String operationId,
			@RequestParam(required = false) String includeArchived, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		return service.list(Subject.from(authentication), GroupValidation.page(operationId, includeArchived, limit, cursor));
	}

	/** GR-03. */
	@GetMapping("/api/v1/groups/{groupId}")
	GroupView get(@PathVariable UUID groupId, Authentication authentication) {
		return service.get(Subject.from(authentication), groupId);
	}

	/** GR-04. */
	@PostMapping("/api/v1/groups/{groupId}/members")
	ResponseEntity<GroupMemberView> addMember(@PathVariable UUID groupId, @RequestBody GroupMemberAddRequest request,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();   // x-client: [WEB]
		GroupValidation.requireUser("userId", request == null ? null : request.userId());
		GroupMemberView member = service.addMember(subject, groupId, request.userId());
		return ResponseEntity.created(URI.create("/api/v1/groups/" + groupId + "/members/" + request.userId()))
			.body(member);
	}

	/** GR-05. */
	@DeleteMapping("/api/v1/groups/{groupId}/members/{userId}")
	ResponseEntity<Void> removeMember(@PathVariable UUID groupId, @PathVariable UUID userId,
			Authentication authentication) {
		service.removeMember(Subject.from(authentication).requireManagementSession(), groupId, userId);
		return ResponseEntity.noContent().build();
	}

	/** GR-06. */
	@PutMapping("/api/v1/groups/{groupId}/leader")
	GroupView changeLeader(@PathVariable UUID groupId, @RequestBody GroupLeaderRequest request,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();   // x-client: [WEB]
		GroupValidation.requireUser("userId", request == null ? null : request.userId());
		return service.changeLeader(subject, groupId, request.userId());
	}

	/** GR-07. */
	@PatchMapping("/api/v1/groups/{groupId}")
	GroupView update(@PathVariable UUID groupId, @RequestBody JsonNode body, Authentication authentication) {
		return service.update(Subject.from(authentication), groupId, GroupValidation.update(body));
	}

	/** GR-08. */
	@PutMapping("/api/v1/groups/{groupId}/meeting-point")
	MeetingPoint updateMeetingPoint(@PathVariable UUID groupId, @RequestBody MeetingPointRequest request,
			Authentication authentication) {
		return service.updateMeetingPoint(Subject.from(authentication), groupId, GroupValidation.meetingPoint(request));
	}


}
