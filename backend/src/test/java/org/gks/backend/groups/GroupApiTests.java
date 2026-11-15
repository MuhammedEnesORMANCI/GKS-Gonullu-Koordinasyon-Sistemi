package org.gks.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.gks.backend.TestcontainersConfiguration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import org.gks.backend.access.GroupAccess;
import org.gks.backend.access.GroupAccess.GroupAction;
import org.gks.backend.access.GroupAccess.ListScope;
import org.gks.backend.access.GroupRole;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/**
 * GR-01 – GR-08 (backend-api-design.md §8; AC-09, AC-10, AC-12). İstekler gerçek giriş belirteciyle yapılır;
 * yetki ({@link GroupAccess}) ve katılım ({@link Participation}) kararları access modülünde olduğundan taklit edilir.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupApiTests {

	private static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	private static final String PASSWORD = "Saha-Ekibi-2026!";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@MockitoBean
	GroupAccess access;

	@MockitoBean
	Participation participation;

	UUID coordinator;
	String coordinatorToken;
	UUID operationId;

	@BeforeEach
	void setUp() throws Exception {
		coordinator = registerUser("Koordinatör");
		coordinatorToken = sessions().web(coordinator, true);
		operationId = operation("ACTIVE");
		given(access.can(any(), any(), any(), any())).willReturn(Decision.ALLOW);
		given(access.listScope(any())).willReturn(new ListScope(false, Set.of(operationId), true));
		given(participation.isParticipant(any(), any())).willReturn(true);
	}

	@Nested
	class CreateGroup {

		@Test
		void createsGroupWithLeaderAndMembers() throws Exception {
			UUID leader = user("Lider Ayşe");
			UUID member1 = user("Mehmet");
			UUID member2 = user("Zeynep");

			String body = create(operationId, "Arama Kurtarma 1", leader, member1, member2, leader)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/v1/groups/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.name").value("Arama Kurtarma 1"))
				.andExpect(jsonPath("$.operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.operationName").value("Sel müdahalesi"))
				.andExpect(jsonPath("$.leader.id").value(leader.toString()))
				.andExpect(jsonPath("$.leader.displayName").value("Lider Ayşe"))
				.andExpect(jsonPath("$.memberCount").value(3))
				.andExpect(jsonPath("$.members[0].role").value("LEADER"))
				.andExpect(jsonPath("$.members[0].user.displayName").value("Lider Ayşe"))
				.andExpect(jsonPath("$.members[0].user.email").doesNotExist())
				.andExpect(jsonPath("$.type").value("OPERATIONAL"))
				.andExpect(jsonPath("$.joinPolicy").value("MANAGED"))
				.andExpect(jsonPath("$.archived").value(false))
				.andExpect(jsonPath("$.meetingPoint").doesNotExist())
				.andReturn().getResponse().getContentAsString();
			UUID groupId = UUID.fromString(JsonPath.read(body, "$.id"));

			assertThat(jdbc.sql("select created_by from groups.groups where id = ?").param(groupId).query(UUID.class)
				.single()).isEqualTo(coordinator);
			assertThat(roles(groupId)).containsExactlyInAnyOrderEntriesOf(
					Map.of(leader, "LEADER", member1, "MEMBER", member2, "MEMBER"));
			verify(access).can(new Subject(coordinator, true, true), GroupAction.CREATE, operationId, null);
		}

		@Test
		void outsideOwnOperationIsForbidden() throws Exception {
			given(access.can(any(), eq(GroupAction.CREATE), any(), any())).willReturn(Decision.FORBIDDEN);

			expectProblem(create(operationId, "Yetkisiz", user("Lider")), 403, "FORBIDDEN");
			assertThat(groupCount()).isZero();
		}

		@Test
		void unknownOperationIsNotFound() throws Exception {
			expectProblem(create(UUID.randomUUID(), "Yok", user("Lider")), 404, "NOT_FOUND");
		}

		@Test
		void leaderIsRequired() throws Exception {
			expectProblem(mvc.perform(json(post("/api/v1/operations/{id}/groups", operationId), coordinatorToken,
					"{\"name\": \"Lidersiz\", \"memberUserIds\": [\"" + user("Üye") + "\"]}")), 409, "LEADER_REQUIRED");
			assertThat(groupCount()).isZero();
		}

		@Test
		void leaderAndMembersMustBeParticipants() throws Exception {
			UUID outsider = user("Dışarıdan");
			given(participation.isParticipant(operationId, outsider)).willReturn(false);

			expectProblem(create(operationId, "Grup A", outsider), 409, "NOT_A_PARTICIPANT");
			expectProblem(create(operationId, "Grup B", user("Lider"), outsider), 409, "NOT_A_PARTICIPANT");
			assertThat(groupCount()).isZero();
		}

		@Test
		void moreThanFiftyMembersIsGroupFull() throws Exception {
			UUID[] members = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);

			expectProblem(create(operationId, "Kalabalık", UUID.randomUUID(), members), 409, "GROUP_FULL")
				.andExpect(jsonPath("$.memberCount").value(51))
				.andExpect(jsonPath("$.limit").value(50));
		}

		@Test
		void exactlyFiftyIncludingLeaderIsAllowed() throws Exception {
			UUID[] members = IntStream.range(0, 49).mapToObj(i -> user("Üye " + i)).toArray(UUID[]::new);

			create(operationId, "Tam kadro", user("Lider"), members)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.memberCount").value(50));
		}

		@ParameterizedTest
		@CsvSource({ "COMPLETED", "CANCELLED" })
		void closedOperationRejectsNewGroups(String state) throws Exception {
			expectProblem(create(operation(state), "Geç kalan", user("Lider")), 409, "OPERATION_CLOSED");
		}

		@Test
		void nameIsUniqueCaseInsensitivelyWithinOperation() throws Exception {
			create(operationId, "Lojistik", user("Lider 1")).andExpect(status().isCreated());

			expectProblem(create(operationId, "  LOJİSTİK  ".replace("İ", "I"), user("Lider 2")), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
			create(operation("ACTIVE"), "Lojistik", user("Lider 3")).andExpect(status().isCreated());
		}

		@ParameterizedTest(name = "{0}")
		@CsvSource(delimiter = '|', textBlock = """
				name too short    | {"name": "A", "leaderUserId": "%L"}                                         | name              | TOO_SHORT
				name too long     | {"name": "%81", "leaderUserId": "%L"}                                       | name              | TOO_LONG
				name missing      | {"leaderUserId": "%L"}                                                      | name              | REQUIRED
				description long  | {"name": "Grup", "description": "%1001", "leaderUserId": "%L"}               | description       | TOO_LONG
				duplicate member  | {"name": "Grup", "leaderUserId": "%L", "memberUserIds": ["%M", "%M"]}       | memberUserIds[1]  | NOT_ALLOWED_VALUE
				bad uuid          | {"name": "Grup", "leaderUserId": "kim"}                                     | leaderUserId      | INVALID_FORMAT
				unknown field     | {"name": "Grup", "leaderUserId": "%L", "type": "COMMUNITY"}                 | type              | UNKNOWN_FIELD
				""")
		void rejectsInvalidRequests(String caseName, String body, String field, String code) throws Exception {
			String json = body.replace("%L", UUID.randomUUID().toString()).replace("%M", UUID.randomUUID().toString())
				.replace("\"%81\"", "\"" + "a".repeat(81) + "\"").replace("\"%1001\"", "\"" + "d".repeat(1001) + "\"");

			expectProblem(mvc.perform(json(post("/api/v1/operations/{id}/groups", operationId), coordinatorToken, json)),
					400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].code").value(code));
		}

		@Test
		void requiresAuthentication() throws Exception {
			expectProblem(mvc.perform(post("/api/v1/operations/{id}/groups", operationId)
				.contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "AUTH_UNAUTHENTICATED");
		}

	}

	@Nested
	class ViewGroups {

		@Test
		void memberViewIsDecidedWithMembershipRole() throws Exception {
			UUID volunteer = registerUser("Gönüllü");
			UUID groupId = groupWith(user("Lider"), volunteer);
			given(access.can(any(), eq(GroupAction.VIEW), any(), any())).willAnswer(call -> call.getArgument(3) != null ? Decision.ALLOW : Decision.FORBIDDEN);

			mvc.perform(get("/api/v1/groups/{id}", groupId).with(TestSessions.authenticate(token(volunteer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.myRole").value("MEMBER"))
				.andExpect(jsonPath("$.members.length()").value(2));
			verify(access).can(new Subject(volunteer, false, false), GroupAction.VIEW, operationId, GroupRole.MEMBER);
		}

		/** AC-10.2: üye olmayan gönüllü 403 alır ve hiçbir grup verisi dönmez. */
		@Test
		void nonMemberGetsForbiddenWithoutData() throws Exception {
			UUID groupId = groupWith(user("Lider"));
			UUID stranger = registerUser("Yabancı");
			given(access.can(any(), eq(GroupAction.VIEW), any(), isNull())).willReturn(Decision.FORBIDDEN);

			String body = expectProblem(mvc.perform(get("/api/v1/groups/{id}", groupId)
				.with(TestSessions.authenticate(token(stranger)))), 403, "FORBIDDEN")
				.andReturn().getResponse().getContentAsString();
			// instance yalnızca istemcinin gönderdiği yolu yansıtır; grup verisi (ad, üyeler, lider) dönmez.
			assertThat(body).doesNotContain("Lider").doesNotContain("members").doesNotContain("\"name\"");
		}

		@Test
		void unknownGroupIsNotFound() throws Exception {
			expectProblem(mvc.perform(get("/api/v1/groups/{id}", UUID.randomUUID())
				.with(TestSessions.authenticate(coordinatorToken))), 404, "NOT_FOUND");
		}

		@Test
		void archivedGroupMembershipGivesNoRole() throws Exception {
			UUID volunteer = registerUser("Gönüllü");
			UUID groupId = groupWith(user("Lider"), volunteer);
			jdbc.sql("update groups.groups set archived_at = now() where id = ?").param(groupId).update();

			mvc.perform(get("/api/v1/groups/{id}", groupId).with(TestSessions.authenticate(token(volunteer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.archived").value(true))
				.andExpect(jsonPath("$.myRole").doesNotExist());
			verify(access).can(new Subject(volunteer, false, false), GroupAction.VIEW, operationId, null);
		}

		/** AC-10.1: gönüllü yalnızca üyesi olduğu grupları listeler. */
		@Test
		void volunteerListsOnlyOwnGroups() throws Exception {
			UUID volunteer = registerUser("Gönüllü");
			UUID mine = groupWith(user("Lider 1"), volunteer);
			groupWith(user("Lider 2"));
			given(access.listScope(any())).willReturn(new ListScope(false, Set.of(), true));

			mvc.perform(get("/api/v1/groups").with(TestSessions.authenticate(token(volunteer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(mine.toString()))
				.andExpect(jsonPath("$.items[0].myRole").value("MEMBER"))
				.andExpect(jsonPath("$.items[0].operationName").value("Sel müdahalesi"))
				.andExpect(jsonPath("$.items[0].leader.displayName").value("Lider 1"))
				.andExpect(jsonPath("$.items[0].memberCount").value(2))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
		}

		@Test
		void coordinatorScopeFiltersByOperationAndArchive() throws Exception {
			UUID active = groupWith(user("Lider 1"));
			UUID archived = groupWith(user("Lider 2"));
			jdbc.sql("update groups.groups set archived_at = now() where id = ?").param(archived).update();
			UUID otherOperation = operation("ACTIVE");
			UUID foreign = groupIn(otherOperation, user("Lider 3"));
			given(access.listScope(any())).willReturn(new ListScope(false, Set.of(operationId), false));

			assertThat(listIds("")).containsExactly(active);
			assertThat(listIds("?includeArchived=true")).containsExactly(archived, active);
			assertThat(listIds("?operationId=" + otherOperation)).isEmpty();
			assertThat(listIds("?includeArchived=true")).doesNotContain(foreign);
		}

		@Test
		void noScopeListsNothing() throws Exception {
			groupWith(user("Lider"));
			given(access.listScope(any())).willReturn(ListScope.NONE);

			assertThat(listIds("")).isEmpty();
		}

		@Test
		void pagesWithOpaqueCursor() throws Exception {
			UUID first = groupWith(user("Lider 1"));
			UUID second = groupWith(user("Lider 2"));
			UUID third = groupWith(user("Lider 3"));

			String page1 = list("?limit=2");
			assertThat(ids(page1)).containsExactly(third, second);
			String cursor = JsonPath.read(page1, "$.nextCursor");
			String page2 = list("?limit=2&cursor=" + cursor);
			assertThat(ids(page2)).containsExactly(first);
			assertThat(page2).doesNotContain("nextCursor");
		}

		@ParameterizedTest
		@CsvSource({ "limit=0, limit", "limit=101, limit", "limit=abc, limit", "cursor=!!, cursor",
				"operationId=xyz, operationId", "includeArchived=evet, includeArchived" })
		void rejectsInvalidQuery(String query, String field) throws Exception {
			expectProblem(mvc.perform(get("/api/v1/groups?" + query).with(TestSessions.authenticate(coordinatorToken))),
					400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value(field));
		}

	}

	@Nested
	class Membership {

		@Test
		void addsParticipantAsMember() throws Exception {
			UUID groupId = groupWith(user("Lider"));
			UUID volunteer = user("Yeni Üye");

			addMember(groupId, volunteer, coordinatorToken)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/groups/" + groupId + "/members/" + volunteer))
				.andExpect(jsonPath("$.user.id").value(volunteer.toString()))
				.andExpect(jsonPath("$.user.displayName").value("Yeni Üye"))
				.andExpect(jsonPath("$.role").value("MEMBER"));
			assertThat(roles(groupId)).containsEntry(volunteer, "MEMBER").hasSize(2);
		}

		@Test
		void existingMemberNonParticipantAndClosedOperationAreConflicts() throws Exception {
			UUID member = user("Üye");
			UUID groupId = groupWith(user("Lider"), member);
			UUID outsider = user("Dışarıdan");
			given(participation.isParticipant(operationId, outsider)).willReturn(false);

			expectProblem(addMember(groupId, member, coordinatorToken), 409, "ALREADY_EXISTS")
				.andExpect(jsonPath("$.conflict").value("ALREADY_MEMBER"));
			expectProblem(addMember(groupId, outsider, coordinatorToken), 409, "NOT_A_PARTICIPANT");
			jdbc.sql("update organization.operations set state = 'COMPLETED', closed_at = now() where id = ?")
				.param(operationId).update();
			expectProblem(addMember(groupId, user("Geç"), coordinatorToken), 409, "OPERATION_CLOSED");
		}

		/** AC-09.6: grup lideri üye ekleyemez ve çıkaramaz; karar lider rolüyle access'e sorulur. */
		@Test
		void leaderCannotAddOrRemoveMembers() throws Exception {
			UUID leader = registerUser("Lider");
			UUID member = user("Üye");
			UUID groupId = groupWith(leader, member);
			String leaderToken = token(leader);
			given(access.can(any(), eq(GroupAction.ADD_MEMBER), any(), eq(GroupRole.LEADER))).willReturn(Decision.FORBIDDEN);
			given(access.can(any(), eq(GroupAction.REMOVE_MEMBER), any(), eq(GroupRole.LEADER))).willReturn(Decision.FORBIDDEN);

			// Android oturumu: GR-04/GR-05 yalnızca web (x-client: [WEB]) → uç düzeyi kapı (§3.3 adım 4).
			expectProblem(addMember(groupId, user("Aday"), leaderToken), 403, "CLIENT_NOT_ALLOWED");
			// Web oturumu olsa bile lider rolü üyelik izni vermez (yetki kararı, AC-09.6).
			String leaderWebToken = sessions().web(leader, false);
			expectProblem(addMember(groupId, user("Aday 2"), leaderWebToken), 403, "FORBIDDEN");
			expectProblem(mvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, member)
				.with(TestSessions.authenticate(leaderWebToken))), 403, "FORBIDDEN");
			assertThat(roles(groupId)).hasSize(2);
		}

		@Test
		void fullGroupRejectsNewMember() throws Exception {
			UUID[] members = IntStream.range(0, 49).mapToObj(i -> user("Üye " + i)).toArray(UUID[]::new);
			UUID groupId = groupWith(user("Lider"), members);

			expectProblem(addMember(groupId, user("Fazla"), coordinatorToken), 409, "GROUP_FULL")
				.andExpect(jsonPath("$.memberCount").value(50))
				.andExpect(jsonPath("$.limit").value(50));
		}

		/** DB E-6: sayım grup kilidi altında; son yer için yarışan iki istekten yalnızca biri kazanır. */
		@Test
		void concurrentAddsCannotExceedLimit() throws Exception {
			UUID[] members = IntStream.range(0, 48).mapToObj(i -> user("Üye " + i)).toArray(UUID[]::new);
			UUID groupId = groupWith(user("Lider"), members);
			List<Callable<Integer>> adds = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				UUID candidate = user("Aday " + i);
				adds.add(() -> addMember(groupId, candidate, coordinatorToken).andReturn().getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			try (var executor = Executors.newFixedThreadPool(4)) {
				for (Future<Integer> result : executor.invokeAll(adds)) {
					statuses.add(result.get());
				}
			}

			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
			assertThat(roles(groupId)).hasSize(50);
		}

		/** AC-09.3, D-01: çıkarılan üyenin erişimi bir sonraki istekte biter. */
		@Test
		void removedMemberLosesMembershipOnNextRequest() throws Exception {
			UUID volunteer = registerUser("Gönüllü");
			UUID groupId = groupWith(user("Lider"), volunteer);
			String volunteerToken = token(volunteer);
			given(access.can(any(), eq(GroupAction.VIEW), any(), any())).willAnswer(call -> call.getArgument(3) != null ? Decision.ALLOW : Decision.FORBIDDEN);
			mvc.perform(get("/api/v1/groups/{id}", groupId).with(TestSessions.authenticate(volunteerToken)))
				.andExpect(status().isOk());

			mvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, volunteer)
				.with(TestSessions.authenticate(coordinatorToken))).andExpect(status().isNoContent());

			Map<String, Object> row = jdbc.sql("""
					select status, ended_by, end_reason, ended_at from groups.group_memberships
					 where group_id = ? and user_id = ?""").params(groupId, volunteer).query().singleRow();
			assertThat(row.get("status")).isEqualTo("REMOVED");
			assertThat(row.get("ended_by")).isEqualTo(coordinator);
			assertThat(row.get("end_reason")).isEqualTo("REMOVED_BY_COORDINATOR");
			assertThat(row.get("ended_at")).isNotNull();
			expectProblem(mvc.perform(get("/api/v1/groups/{id}", groupId)
				.with(TestSessions.authenticate(volunteerToken))), 403, "FORBIDDEN");
		}

		@Test
		void leaderCannotBeRemovedAndNonMemberIsNotFound() throws Exception {
			UUID leader = user("Lider");
			UUID groupId = groupWith(leader);

			expectProblem(mvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, leader)
				.with(TestSessions.authenticate(coordinatorToken))), 409, "LEADER_REQUIRED");
			expectProblem(mvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, UUID.randomUUID())
				.with(TestSessions.authenticate(coordinatorToken))), 404, "NOT_FOUND");
			assertThat(roles(groupId)).containsEntry(leader, "LEADER");
		}

	}

	@Nested
	class Leadership {

		@Test
		void promotesMemberAndDemotesOldLeader() throws Exception {
			UUID oldLeader = user("Eski Lider");
			UUID member = user("Yeni Lider");
			UUID groupId = groupWith(oldLeader, member);

			changeLeader(groupId, member)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leader.id").value(member.toString()));
			assertThat(roles(groupId)).containsExactlyInAnyOrderEntriesOf(Map.of(oldLeader, "MEMBER", member, "LEADER"));
		}

		@Test
		void nonMemberLeaderIsAddedWhenParticipant() throws Exception {
			UUID groupId = groupWith(user("Eski Lider"));
			UUID newcomer = user("Yeni Lider");
			UUID outsider = user("Dışarıdan");
			given(participation.isParticipant(operationId, outsider)).willReturn(false);

			changeLeader(groupId, newcomer).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(2));
			assertThat(roles(groupId)).containsEntry(newcomer, "LEADER");
			expectProblem(changeLeader(groupId, outsider), 409, "NOT_A_PARTICIPANT");
		}

		@Test
		void currentLeaderIsNoChange() throws Exception {
			UUID leader = user("Lider");
			UUID groupId = groupWith(leader);

			changeLeader(groupId, leader).andExpect(status().isOk()).andExpect(jsonPath("$.leader.id").value(leader.toString()));
			assertThat(roles(groupId)).containsExactlyEntriesOf(Map.of(leader, "LEADER"));
		}

		/** K-32: eşzamanlı lider değişikliklerinden sonra tam olarak bir aktif lider kalır. */
		@Test
		void concurrentLeaderChangesKeepExactlyOneLeader() throws Exception {
			UUID[] members = IntStream.range(0, 4).mapToObj(i -> user("Üye " + i)).toArray(UUID[]::new);
			UUID groupId = groupWith(user("Lider"), members);
			List<Callable<Integer>> changes = new ArrayList<>();
			for (UUID member : members) {
				changes.add(() -> changeLeader(groupId, member).andReturn().getResponse().getStatus());
			}
			try (var executor = Executors.newFixedThreadPool(4)) {
				for (Future<Integer> result : executor.invokeAll(changes)) {
					assertThat(result.get()).isEqualTo(200);
				}
			}

			assertThat(roles(groupId).values()).filteredOn("LEADER"::equals).hasSize(1);
		}

		@Test
		void schemaAllowsAtMostOneActiveLeader() {
			UUID leader = user("Lider");
			UUID groupId = groupWith(leader);

			assertThatThrownBy(() -> jdbc.sql("""
					insert into groups.group_memberships (id, group_id, user_id, role, added_by)
					values (?, ?, ?, 'LEADER', ?)""").params(UUID.randomUUID(), groupId, user("İkinci"), coordinator).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ux_group_memberships_active_leader");
		}

	}

	@Nested
	class UpdateGroup {

		@Test
		void coordinatorUpdatesNameAndDescription() throws Exception {
			UUID groupId = groupWith(user("Lider"));

			patchGroup(groupId, coordinatorToken, "{\"name\": \"Yeni ad\", \"description\": \"Kuzey sektörü\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Yeni ad"))
				.andExpect(jsonPath("$.description").value("Kuzey sektörü"));
			patchGroup(groupId, coordinatorToken, "{\"description\": null}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Yeni ad"))
				.andExpect(jsonPath("$.description").doesNotExist());
		}

		/** K-08, AC-09.6: lider açıklamayı günceller; adı yalnızca KOR değiştirir (§8.2 öneri: 400 READ_ONLY). */
		@Test
		void leaderUpdatesDescriptionButNotName() throws Exception {
			UUID leader = registerUser("Lider");
			UUID groupId = groupWith(leader);
			String leaderToken = token(leader);
			given(access.can(any(), eq(GroupAction.RENAME), any(), eq(GroupRole.LEADER))).willReturn(Decision.FORBIDDEN);

			Subject subject = new Subject(leader, false, false);

			// 1. istek: yalnızca açıklama → UPDATE denetlenir, ad yok → RENAME sorulmaz.
			patchGroup(groupId, leaderToken, "{\"description\": \"Toplanma: okul bahçesi\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Toplanma: okul bahçesi"));
			verify(access, times(1)).can(subject, GroupAction.UPDATE, operationId, GroupRole.LEADER);
			verify(access, never()).can(subject, GroupAction.RENAME, operationId, GroupRole.LEADER);

			// 2. istek: ad → UPDATE yeniden ve ayrıca RENAME denetlenir; önceki kararın sonucu kullanılmaz.
			expectProblem(patchGroup(groupId, leaderToken, "{\"name\": \"Lider adı\"}"), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].code").value("READ_ONLY"));
			verify(access, times(2)).can(subject, GroupAction.UPDATE, operationId, GroupRole.LEADER);
			verify(access, times(1)).can(subject, GroupAction.RENAME, operationId, GroupRole.LEADER);
			assertThat(jdbc.sql("select name from groups.groups where id = ?").param(groupId).query(String.class).single())
				.isNotEqualTo("Lider adı");
		}

		@Test
		void withoutUpdatePermissionIsForbidden() throws Exception {
			UUID groupId = groupWith(user("Lider"));
			given(access.can(any(), eq(GroupAction.UPDATE), any(), any())).willReturn(Decision.FORBIDDEN);

			expectProblem(patchGroup(groupId, coordinatorToken, "{\"description\": \"x\"}"), 403, "FORBIDDEN");
		}

		@ParameterizedTest
		@CsvSource(delimiter = '|', textBlock = """
				{"members": []}         | members | READ_ONLY
				{"joinPolicy": "OPEN"}  | joinPolicy | READ_ONLY
				{"colour": "red"}       | colour  | UNKNOWN_FIELD
				{"name": null}          | name    | REQUIRED
				""")
		void rejectsInvalidPatch(String body, String field, String code) throws Exception {
			expectProblem(patchGroup(groupWith(user("Lider")), coordinatorToken, body), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].code").value(code));
		}

		@Test
		void archivedGroupAndDuplicateNameAreRejected() throws Exception {
			UUID taken = groupWith(user("Lider 1"));
			UUID groupId = groupWith(user("Lider 2"));
			String takenName = jdbc.sql("select name from groups.groups where id = ?").param(taken).query(String.class).single();

			expectProblem(patchGroup(groupId, coordinatorToken, "{\"name\": \"" + takenName.toUpperCase(java.util.Locale.ROOT) + "\"}"),
					400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
			jdbc.sql("update groups.groups set archived_at = now() where id = ?").param(groupId).update();
			expectProblem(patchGroup(groupId, coordinatorToken, "{\"description\": \"x\"}"), 409, "OPERATION_CLOSED");
		}

	}

	@Nested
	class MeetingPoints {

		/** AC-12.1, AC-12.3: adres, harita noktası, açıklama, zaman; son güncelleyen ve zaman görünür. */
		@Test
		void setsMeetingPointWithAuditFields() throws Exception {
			UUID groupId = groupWith(user("Lider"));

			putMeetingPoint(groupId, coordinatorToken, """
					{"address": "Atatürk İlkokulu bahçesi", "lat": 41.0151, "lon": 28.9795,
					 "description": "Ana kapı önü", "time": "2026-11-16T07:30:00+03:00"}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.address").value("Atatürk İlkokulu bahçesi"))
				.andExpect(jsonPath("$.lat").value(41.0151))
				.andExpect(jsonPath("$.lon").value(28.9795))
				.andExpect(jsonPath("$.time").value("2026-11-16T04:30:00Z"))
				.andExpect(jsonPath("$.updatedBy.id").value(coordinator.toString()))
				.andExpect(jsonPath("$.updatedBy.displayName").value("Koordinatör"))
				.andExpect(jsonPath("$.updatedAt").isNotEmpty());
			mvc.perform(get("/api/v1/groups/{id}", groupId).with(TestSessions.authenticate(coordinatorToken)))
				.andExpect(jsonPath("$.meetingPoint.address").value("Atatürk İlkokulu bahçesi"))
				.andExpect(jsonPath("$.meetingPoint.updatedBy.displayName").value("Koordinatör"));
		}

		@Test
		void leaderMaySetOwnGroupsMeetingPointWithCoordinatesOnly() throws Exception {
			UUID leader = registerUser("Lider");
			UUID groupId = groupWith(leader);

			putMeetingPoint(groupId, token(leader), "{\"lat\": 39.92, \"lon\": 32.85, \"time\": \"2026-11-16T05:00:00Z\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.address").doesNotExist())
				.andExpect(jsonPath("$.updatedBy.displayName").value("Lider"));
			verify(access).can(new Subject(leader, false, false), GroupAction.UPDATE_MEETING_POINT, operationId,
					GroupRole.LEADER);
		}

		@ParameterizedTest(name = "{0}")
		@CsvSource(delimiter = '|', textBlock = """
				no place          | {"time": "2026-11-16T05:00:00Z"}                                  | address | REQUIRED
				lat without lon   | {"lat": 39.9, "time": "2026-11-16T05:00:00Z"}                     | lon     | REQUIRED
				lat out of range  | {"lat": 91, "lon": 32.8, "time": "2026-11-16T05:00:00Z"}          | lat     | OUT_OF_RANGE
				missing time      | {"address": "Meydan"}                                             | time    | REQUIRED
				bad time          | {"address": "Meydan", "time": "yarın sabah"}                      | time    | INVALID_FORMAT
				address too long  | {"address": "%301", "time": "2026-11-16T05:00:00Z"}               | address | TOO_LONG
				""")
		void rejectsInvalidMeetingPoint(String caseName, String body, String field, String code) throws Exception {
			String json = body.replace("\"%301\"", "\"" + "a".repeat(301) + "\"");

			expectProblem(putMeetingPoint(groupWith(user("Lider")), coordinatorToken, json), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].code").value(code));
		}

		@Test
		void forbiddenAndClosedOperationAreRejected() throws Exception {
			UUID groupId = groupWith(user("Lider"));
			String body = "{\"address\": \"Meydan\", \"time\": \"2026-11-16T05:00:00Z\"}";
			given(access.can(any(), eq(GroupAction.UPDATE_MEETING_POINT), any(), any())).willReturn(Decision.FORBIDDEN);
			expectProblem(putMeetingPoint(groupId, coordinatorToken, body), 403, "FORBIDDEN");

			given(access.can(any(), eq(GroupAction.UPDATE_MEETING_POINT), any(), any())).willReturn(Decision.ALLOW);
			jdbc.sql("update organization.operations set state = 'CANCELLED', closed_at = now() where id = ?")
				.param(operationId).update();
			expectProblem(putMeetingPoint(groupId, coordinatorToken, body), 409, "OPERATION_CLOSED");
		}

		@Test
		void schemaRequiresBothCoordinates() {
			UUID groupId = groupWith(user("Lider"));

			assertThatThrownBy(() -> jdbc.sql("update groups.groups set mp_lat = 40 where id = ?").param(groupId).update())
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_groups_mp_coords");
		}

	}

	// --- yardımcılar ---

	private TestSessions sessions() {
		return new TestSessions(jdbc, jwtEncoder);
	}

	private UUID registerUser(String displayName) {
		return accounts.register(new NewUserAccount("kisi-" + UUID.randomUUID() + "@example.org", PASSWORD,
				displayName + " Soyadı", displayName, null));
	}

	/** Giriş yapmayacak kişiler için hızlı kayıt (parola özeti kullanılmaz). */
	private UUID user(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	private String token(UUID userId) throws Exception {
		String email = jdbc.sql("select email from identity.users where id = ?").param(userId).query(String.class).single();
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"ANDROID\", \"installationId\": \"grup-testi-01\"}"
				.formatted(email, PASSWORD)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private UUID operation(String state) {
		UUID id = UUID.randomUUID();
		boolean activated = state.equals("ACTIVE") || state.equals("COMPLETED");
		boolean closed = state.equals("COMPLETED") || state.equals("CANCELLED");
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, closed_at, created_by)
				values (?, ?, 'Sel müdahalesi', ?, ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, state, activated ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
					closed ? java.sql.Timestamp.from(java.time.Instant.now()) : null, coordinator)
			.update();
		return id;
	}

	private ResultActions create(UUID operation, String name, UUID leader, UUID... members) throws Exception {
		String memberList = java.util.Arrays.stream(members).map(m -> "\"" + m + "\"").collect(Collectors.joining(", "));
		return mvc.perform(json(post("/api/v1/operations/{id}/groups", operation), coordinatorToken,
				"{\"name\": \"%s\", \"leaderUserId\": \"%s\", \"memberUserIds\": [%s]}".formatted(name, leader, memberList)));
	}

	private UUID groupWith(UUID leader, UUID... members) {
		return groupIn(operationId, leader, members);
	}

	private UUID groupIn(UUID operation, UUID leader, UUID... members) {
		try {
			String body = create(operation, "Grup " + UUID.randomUUID().toString().substring(0, 8), leader, members)
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			return UUID.fromString(JsonPath.read(body, "$.id"));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private ResultActions addMember(UUID groupId, UUID userId, String token) throws Exception {
		return mvc.perform(json(post("/api/v1/groups/{id}/members", groupId), token, "{\"userId\": \"" + userId + "\"}"));
	}

	private ResultActions changeLeader(UUID groupId, UUID userId) throws Exception {
		return mvc.perform(json(put("/api/v1/groups/{id}/leader", groupId), coordinatorToken,
				"{\"userId\": \"" + userId + "\"}"));
	}

	private ResultActions patchGroup(UUID groupId, String token, String body) throws Exception {
		return mvc.perform(json(patch("/api/v1/groups/{id}", groupId), token, body));
	}

	private ResultActions putMeetingPoint(UUID groupId, String token, String body) throws Exception {
		return mvc.perform(json(put("/api/v1/groups/{id}/meeting-point", groupId), token, body));
	}

	private String list(String query) throws Exception {
		return mvc.perform(get("/api/v1/groups" + query).with(TestSessions.authenticate(coordinatorToken)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private List<UUID> listIds(String query) throws Exception {
		return ids(list(query));
	}

	private static List<UUID> ids(String page) {
		List<String> ids = JsonPath.read(page, "$.items[*].id");
		return ids.stream().map(UUID::fromString).toList();
	}

	private Map<UUID, String> roles(UUID groupId) {
		return jdbc.sql("select user_id, role from groups.group_memberships where group_id = ? and status = 'ACTIVE'")
			.param(groupId)
			.query((rs, row) -> Map.entry(rs.getObject("user_id", UUID.class), rs.getString("role")))
			.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private int groupCount() {
		return jdbc.sql("select count(*) from groups.groups where operation_id = ?").param(operationId)
			.query(Integer.class).single();
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
		return request.with(TestSessions.authenticate(token)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

}
