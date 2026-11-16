package org.gks.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.gks.android.R
import org.gks.android.domain.HomeKind
import org.gks.android.domain.managementAccess
import org.gks.android.domain.toHomeModel
import org.gks.android.ui.account.AccountScreen
import org.gks.android.ui.account.AccountViewModel
import org.gks.android.ui.account.PasswordChangeScreen
import org.gks.android.ui.account.PasswordChangeViewModel
import org.gks.android.ui.account.ProfileEditScreen
import org.gks.android.ui.account.ProfileEditViewModel
import org.gks.android.ui.common.Centered
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.containerViewModel
import org.gks.android.ui.groups.GroupDetailScreen
import org.gks.android.ui.groups.GroupDetailViewModel
import org.gks.android.ui.groups.GroupsScreen
import org.gks.android.ui.groups.GroupsViewModel
import org.gks.android.ui.home.HomeTab
import org.gks.android.ui.home.HomeViewModel
import org.gks.android.ui.home.OperationDetailScreen
import org.gks.android.ui.home.OperationDetailViewModel
import org.gks.android.ui.management.AnnouncementDetailScreen
import org.gks.android.ui.management.AnnouncementDetailViewModel
import org.gks.android.ui.management.AnnouncementFormViewModel
import org.gks.android.ui.management.AnnouncementHistoryViewModel
import org.gks.android.ui.management.AnnouncementsScreen
import org.gks.android.ui.management.ManagementOperationsViewModel
import org.gks.android.ui.management.ManagementScreen
import org.gks.android.ui.management.OperationFormScreen
import org.gks.android.ui.management.OperationFormViewModel
import org.gks.android.ui.management.OperationManageScreen
import org.gks.android.ui.management.OperationManageViewModel
import org.gks.android.ui.management.GroupCreateScreen
import org.gks.android.ui.management.GroupCreateViewModel
import org.gks.android.ui.management.ManagedGroupScreen
import org.gks.android.ui.management.ManagedGroupViewModel
import org.gks.android.ui.management.OperationGroupsScreen
import org.gks.android.ui.management.OperationGroupsViewModel
import org.gks.android.ui.management.ManagedTaskScreen
import org.gks.android.ui.management.ManagedTaskViewModel
import org.gks.android.ui.management.OperationTasksScreen
import org.gks.android.ui.management.OperationTasksViewModel
import org.gks.android.ui.management.TaskFormScreen
import org.gks.android.ui.management.TaskFormViewModel
import org.gks.android.ui.management.VolunteersScreen
import org.gks.android.ui.management.VolunteersViewModel
import org.gks.android.GksApplication
import org.gks.android.ui.alerts.AlertDetailScreen
import org.gks.android.ui.notify.AlarmPermissionPrompt
import org.gks.android.ui.notify.NotificationPermissionPrompt
import org.gks.android.ui.location.LocationSharingCard
import org.gks.android.ui.location.LocationSharingViewModel
import org.gks.android.ui.alerts.AlertDetailViewModel
import org.gks.android.ui.alerts.OpenAlertsBand
import org.gks.android.ui.alerts.OpenAlertsViewModel
import org.gks.android.ui.notifications.InboxScreen
import org.gks.android.ui.notifications.InboxViewModel
import org.gks.android.ui.notifications.NotificationDetailScreen
import org.gks.android.ui.notifications.NotificationDetailViewModel
import org.gks.android.ui.notifications.UnreadCountViewModel
import org.gks.android.ui.tasks.TaskDetailScreen
import org.gks.android.ui.tasks.TaskDetailViewModel
import org.gks.android.ui.tasks.TasksScreen
import org.gks.android.ui.tasks.TasksViewModel

/** Alt navigasyon sekmeleri. */
enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
	HOME("home", R.string.tab_home, Icons.Filled.Home),
	TASKS("tasks", R.string.tab_tasks, Icons.AutoMirrored.Filled.List),
	GROUPS("groups", R.string.tab_groups, Icons.Filled.Face),
	MANAGEMENT("management", R.string.tab_management, Icons.Filled.Settings),
	NOTIFICATIONS("notifications", R.string.tab_notifications, Icons.Filled.Notifications),
	ACCOUNT("account", R.string.tab_account, Icons.Filled.Person),
}

private object Routes {
	const val TASK = "task/{id}"
	const val OPERATION = "operation/{id}"
	const val GROUP = "group/{id}"
	const val PROFILE = "account/profile"
	const val PASSWORD = "account/password"
	const val NOTIFICATION = "notification/{id}"
	const val MANAGED_OPERATION = "management/operation/{id}"
	const val EDIT_OPERATION = "management/operation/{id}/edit"
	const val NEW_OPERATION = "management/operation/new"
	const val VOLUNTEERS = "management/operation/{id}/volunteers"
	fun volunteers(id: String) = "management/operation/$id/volunteers"
	const val OPERATION_GROUPS = "management/operation/{id}/groups"
	const val NEW_GROUP = "management/operation/{id}/groups/new"
	const val MANAGED_GROUP = "management/group/{id}"
	fun operationGroups(id: String) = "management/operation/$id/groups"
	fun newGroup(id: String) = "management/operation/$id/groups/new"
	fun managedGroup(id: String) = "management/group/$id"
	const val OPERATION_TASKS = "management/operation/{op}/tasks"
	const val NEW_TASK = "management/operation/{op}/tasks/new"
	const val MANAGED_TASK = "management/operation/{op}/task/{id}"
	const val EDIT_TASK = "management/operation/{op}/task/{id}/edit"
	fun operationTasks(op: String) = "management/operation/$op/tasks"
	fun newTask(op: String) = "management/operation/$op/tasks/new"
	fun managedTask(op: String, id: String) = "management/operation/$op/task/$id"
	fun editTask(op: String, id: String) = "management/operation/$op/task/$id/edit"
	const val OPERATION_ANNOUNCEMENTS = "management/operation/{op}/announcements"
	const val ORGANIZATION_ANNOUNCEMENTS = "management/announcements/organization"
	const val MANAGED_ANNOUNCEMENT = "management/announcement/{id}"
	fun operationAnnouncements(op: String) = "management/operation/$op/announcements"
	fun managedAnnouncement(id: String) = "management/announcement/$id"
	fun managedOperation(id: String) = "management/operation/$id"
	fun editOperation(id: String) = "management/operation/$id/edit"
	fun notification(id: String) = "notification/$id"
	const val ALERT = "alert/{id}"
	fun alert(id: String) = "alert/$id"
	fun task(id: String) = "task/$id"
	fun operation(id: String) = "operation/$id"
	fun group(id: String) = "group/$id"

	@StringRes
	fun title(route: String?): Int = when (route) {
		TASK -> R.string.title_task
		OPERATION -> R.string.title_operation
		GROUP -> R.string.title_group
		PROFILE -> R.string.profile_edit
		PASSWORD -> R.string.password_change
		NOTIFICATION -> R.string.title_notification
		ALERT -> R.string.title_alert
		MANAGED_OPERATION -> R.string.title_operation
		EDIT_OPERATION -> R.string.operation_edit
		NEW_OPERATION -> R.string.operation_create
		VOLUNTEERS -> R.string.volunteers_title
		OPERATION_GROUPS -> R.string.groups_management_title
		NEW_GROUP -> R.string.group_create
		MANAGED_GROUP -> R.string.title_group
		OPERATION_TASKS -> R.string.tasks_management_title
		NEW_TASK -> R.string.task_create
		MANAGED_TASK -> R.string.title_task
		EDIT_TASK -> R.string.task_edit
		OPERATION_ANNOUNCEMENTS -> R.string.announcements_title
		ORGANIZATION_ANNOUNCEMENTS -> R.string.announcements_organization_title
		MANAGED_ANNOUNCEMENT -> R.string.announcement_detail_title
		else -> Tab.entries.firstOrNull { it.route == route }?.label ?: R.string.app_name
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(app: AppViewModel, main: Screen.Main) {
	if (main.context.toHomeModel().kind == HomeKind.PASSWORD_CHANGE_REQUIRED) {
		// Sunucu diğer uçları 403 AUTH_PASSWORD_CHANGE_REQUIRED ile reddeder; sekmeler açılmaz. Başarıda bağlam
		// yeniden okunur ve `mustChangePassword=false` ile sekmeler açılır.
		PasswordChangeScreen(
			containerViewModel(key = "forced-password") { PasswordChangeViewModel(it.authRepository) },
			forced = true,
			onChanged = app::refreshContext,
			footer = { TextButton(onClick = app::logout, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_logout)) } },
		)
		return
	}

	// ADR-014: Yönetim sekmesi yalnızca sunucunun bu oturuma kurumsal izin verdiği MFA'lı yönetim oturumunda görünür.
	val management = main.context.managementAccess()
	val tabs = if (management != null) {
		listOf(Tab.HOME, Tab.TASKS, Tab.MANAGEMENT, Tab.NOTIFICATIONS, Tab.ACCOUNT)
	} else {
		listOf(Tab.HOME, Tab.TASKS, Tab.GROUPS, Tab.NOTIFICATIONS, Tab.ACCOUNT)
	}
	val nav = rememberNavController()
	val entry by nav.currentBackStackEntryAsState()
	val route = entry?.destination?.route
	val topLevel = route == null || tabs.any { it.route == route }
	// Üst çubuktaki "Yenile": bağlam yeniden çekilir, açık sekme kendi verisini sayaç değişince yeniden yükler.
	var refreshTick by rememberSaveable { mutableIntStateOf(0) }

	// Okunmamış sayısı: FCM yokken ön plandayken yoklanır (NT-08). Ekran arka plandayken yoklama durur.
	val unreadVm = containerViewModel { UnreadCountViewModel(it.notificationRepository) }
	val unread by unreadVm.count.collectAsStateWithLifecycle()
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	LaunchedEffect(lifecycle) {
		lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (true) {
				unreadVm.refresh()
				delay(UnreadCountViewModel.POLL_MILLIS)
			}
		}
	}

	// Açık alarmlar (AL-08): FCM yokken ön plandayken yoklanır (ADR-018).
	val alertsVm = containerViewModel { c -> OpenAlertsViewModel(c.alertRepository) { open -> c.alarms.reconcile(open) } }
	val openAlerts by alertsVm.alerts.collectAsStateWithLifecycle()
	LaunchedEffect(lifecycle) {
		lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (true) {
				alertsVm.refresh()
				delay(OpenAlertsViewModel.POLL_MILLIS)
			}
		}
	}

	// RT-01 canlı akış (ADR-021): ekran görünürken açık; olay gelince sayaç, gelen kutusu ve alarm bandı yenilenir.
	// Yoklama yedek olarak sürer. Akıştan gelen acil alarm ve duyuru yerel bildirim olarak da gösterilir.
	val container = (LocalContext.current.applicationContext as GksApplication).container
	LaunchedEffect(lifecycle) {
		container.liveNotifier.start()
		lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			container.realtime.acquire()
			try {
				container.realtime.events.collect { event ->
					when (event.type) {
						"inbox.changed", "resync" -> { unreadVm.refresh(); alertsVm.refresh() }
						// Oturum sunucuda iptal edildi: bağlam yeniden okunur; 401 ile giriş ekranına dönülür.
						"session.revoked" -> app.refreshContext()
					}
				}
			}
			finally {
				container.realtime.release()
			}
		}
	}
	NotificationPermissionPrompt()
	AlarmPermissionPrompt()
	// ADR-022: FCM belirteci bu oturuma kaydedilir (girişten sonra / açılışta; belirteç yenilenince servis de yapar).
	LaunchedEffect(main.context.user.id) { container.devices.sync() }
	// Bildirime dokunma: ilgili ekran (alarm → alarm ekranı, duyuru → gelen kutusu ayrıntısı).
	val pendingOpen by container.pendingOpen.collectAsStateWithLifecycle()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(Routes.title(route))) },
				navigationIcon = {
					if (!topLevel) {
						IconButton(onClick = { nav.popBackStack() }) {
							Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
						}
					}
				},
				actions = {
					if (topLevel) {
						IconButton(onClick = { app.refreshContext(); unreadVm.refresh(); alertsVm.refresh(); refreshTick++ }) {
							Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
						}
					}
				},
			)
		},
		bottomBar = { if (topLevel) BottomBar(nav, route, unread, tabs) },
	) { padding ->
		Column(Modifier.padding(padding)) {
		// AND-03: açık alarm bandı her ekranın üstünde (alarm ekranının kendisinde gösterilmez).
		if (route != Routes.ALERT) OpenAlertsBand(openAlerts) { nav.navigate(Routes.alert(it)) }
		LaunchedEffect(pendingOpen) {
			val target = pendingOpen ?: return@LaunchedEffect
			container.pendingOpen.value = null
			when {
				target.startsWith("alert:") -> nav.navigate(Routes.alert(target.removePrefix("alert:")))
				target.startsWith("inbox:") -> nav.navigate(Routes.notification(target.removePrefix("inbox:")))
				target == "inbox" -> nav.navigate(Tab.NOTIFICATIONS.route)
			}
		}
		NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.weight(1f)) {
			composable(Tab.HOME.route) {
				val vm = containerViewModel { HomeViewModel(it.volunteerRepository, it.invitationRepository) }
				RefreshOn(refreshTick, vm::load)
				HomeTab(
					context = main.context,
					contextRefreshing = main.refreshing,
					contextError = main.error,
					viewModel = vm,
					onContextChanged = app::refreshContext,
					onOpenOperation = { nav.navigate(Routes.operation(it)) },
					onOpenGroup = { nav.navigate(Routes.group(it)) },
				)
			}
			composable(Tab.TASKS.route) {
				val vm = containerViewModel { TasksViewModel(it.volunteerRepository) }
				RefreshOn(refreshTick, vm::load)
				TasksScreen(vm, onOpenTask = { nav.navigate(Routes.task(it)) })
			}
			composable(Tab.GROUPS.route) {
				val vm = containerViewModel { GroupsViewModel(it.volunteerRepository) }
				RefreshOn(refreshTick, vm::load)
				GroupsScreen(vm, onOpenGroup = { nav.navigate(Routes.group(it)) })
			}
			composable(Tab.MANAGEMENT.route) {
				if (management == null) {
					// Oturum yönetim yetkisini yitirdi (rol kaldırıldı / bağlam yenilendi): yetkisiz durum.
					Centered { MessageCard(stringResource(R.string.management_session_required), error = true) }
				} else {
					val vm = containerViewModel { ManagementOperationsViewModel(it.managementRepository) }
					RefreshOn(refreshTick, vm::load)
					LaunchedEffect(Unit) { vm.load() }
					ManagementScreen(
						vm,
						management,
						sessionExpiresAt = main.context.session.expiresAt,
						onOpen = { nav.navigate(Routes.managedOperation(it)) },
						onCreate = { nav.navigate(Routes.NEW_OPERATION) },
						onOrganizationAnnouncement = { nav.navigate(Routes.ORGANIZATION_ANNOUNCEMENTS) },
					)
				}
			}
			composable(Routes.MANAGED_OPERATION) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				val access = management
				if (access == null) {
					Centered { MessageCard(stringResource(R.string.management_session_required), error = true) }
				} else {
					val vm = containerViewModel(key = "managed-$id") {
						OperationManageViewModel(it.managementRepository, id, access.canAssignRoles)
					}
					LaunchedEffect(Unit) { vm.load() }
					OperationManageScreen(
						vm,
						access,
						onEdit = { nav.navigate(Routes.editOperation(id)) },
						onVolunteers = { nav.navigate(Routes.volunteers(id)) },
						onGroups = { nav.navigate(Routes.operationGroups(id)) },
						onTasks = { nav.navigate(Routes.operationTasks(id)) },
						onChanged = app::refreshContext,
						onAnnouncements = { nav.navigate(Routes.operationAnnouncements(id)) },
					)
				}
			}
			composable(Routes.VOLUNTEERS) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				VolunteersScreen(containerViewModel(key = "volunteers-$id") { VolunteersViewModel(it.managementRepository, id) })
			}
			composable(Routes.OPERATION_GROUPS) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				val vm = containerViewModel(key = "op-groups-$id") { OperationGroupsViewModel(it.managementRepository, id) }
				LaunchedEffect(Unit) { vm.load() }
				OperationGroupsScreen(
					vm,
					canManageGroups = management?.canManageGroups(id) == true,
					onOpen = { nav.navigate(Routes.managedGroup(it)) },
					onCreate = { nav.navigate(Routes.newGroup(id)) },
				)
			}
			composable(Routes.NEW_GROUP) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				GroupCreateScreen(
					containerViewModel(key = "new-group-$id") { GroupCreateViewModel(it.managementRepository, id) },
					onCreated = { groupId -> nav.navigate(Routes.managedGroup(groupId)) { popUpTo(Routes.OPERATION_GROUPS) } },
				)
			}
			composable(Routes.MANAGED_GROUP) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				ManagedGroupScreen(
					containerViewModel(key = "managed-group-$id") { ManagedGroupViewModel(it.volunteerRepository, it.managementRepository, id) },
					access = management,
				)
			}
			composable(Routes.OPERATION_TASKS) { backStack ->
				val op = backStack.arguments?.getString("op").orEmpty()
				val vm = containerViewModel(key = "op-tasks-$op") { OperationTasksViewModel(it.taskManagementRepository, op) }
				LaunchedEffect(Unit) { vm.load() }
				OperationTasksScreen(
					vm,
					canManageTasks = management?.canManageTasks(op) == true,
					onOpen = { nav.navigate(Routes.managedTask(op, it)) },
					onCreate = { nav.navigate(Routes.newTask(op)) },
				)
			}
			composable(Routes.NEW_TASK) { backStack ->
				val op = backStack.arguments?.getString("op").orEmpty()
				TaskFormScreen(
					containerViewModel(key = "new-task-$op") { TaskFormViewModel(it.taskManagementRepository, it.managementRepository, op, null) },
					onSaved = { id -> nav.navigate(Routes.managedTask(op, id)) { popUpTo(Routes.OPERATION_TASKS) } },
				)
			}
			composable(Routes.MANAGED_TASK) { backStack ->
				val op = backStack.arguments?.getString("op").orEmpty()
				val id = backStack.arguments?.getString("id").orEmpty()
				val vm = containerViewModel(key = "managed-task-$id") { ManagedTaskViewModel(it.taskManagementRepository, it.managementRepository, op, id) }
				LaunchedEffect(Unit) { vm.load() }
				ManagedTaskScreen(vm, canManageTasks = management?.canManageTasks(op) == true, onEdit = { nav.navigate(Routes.editTask(op, id)) })
			}
			composable(Routes.EDIT_TASK) { backStack ->
				val op = backStack.arguments?.getString("op").orEmpty()
				val id = backStack.arguments?.getString("id").orEmpty()
				TaskFormScreen(
					containerViewModel(key = "edit-task-$id") { TaskFormViewModel(it.taskManagementRepository, it.managementRepository, op, id) },
					onSaved = { nav.popBackStack() },
				)
			}
			composable(Routes.OPERATION_ANNOUNCEMENTS) { backStack ->
				val op = backStack.arguments?.getString("op").orEmpty()
				AnnouncementsScreen(
					form = containerViewModel(key = "announce-form-$op") { AnnouncementFormViewModel(it.announcementRepository, it.managementRepository, op) },
					history = containerViewModel(key = "announce-history-$op") { AnnouncementHistoryViewModel(it.announcementRepository, op) },
					canSend = management?.canAnnounce(op) == true,
					onOpen = { nav.navigate(Routes.managedAnnouncement(it)) },
				)
			}
			composable(Routes.ORGANIZATION_ANNOUNCEMENTS) {
				AnnouncementsScreen(
					form = containerViewModel(key = "announce-form-org") { AnnouncementFormViewModel(it.announcementRepository, it.managementRepository, null) },
					history = containerViewModel(key = "announce-history-org") { AnnouncementHistoryViewModel(it.announcementRepository, null) },
					canSend = management?.canAnnounceOrganization == true,
					onOpen = { nav.navigate(Routes.managedAnnouncement(it)) },
				)
			}
			composable(Routes.MANAGED_ANNOUNCEMENT) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				AnnouncementDetailScreen(containerViewModel(key = "announcement-$id") { AnnouncementDetailViewModel(it.announcementRepository, id) })
			}
			composable(Routes.EDIT_OPERATION) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				OperationFormScreen(
					containerViewModel(key = "edit-$id") {
						OperationFormViewModel(it.managementRepository, id, main.context.user.id, main.context.user.displayName)
					},
					editing = true,
					onSaved = { nav.popBackStack() },
				)
			}
			composable(Routes.NEW_OPERATION) {
				OperationFormScreen(
					containerViewModel(key = "new-operation") {
						OperationFormViewModel(it.managementRepository, null, main.context.user.id, main.context.user.displayName)
					},
					editing = false,
					onSaved = { id ->
						// Oluşturan ADM koordinatör olduysa bağlam (operasyonlar, izinler) değişir.
						app.refreshContext()
						nav.navigate(Routes.managedOperation(id)) { popUpTo(Tab.MANAGEMENT.route) }
					},
				)
			}
			composable(Tab.NOTIFICATIONS.route) {
				val vm = containerViewModel { InboxViewModel(it.notificationRepository) }
				RefreshOn(refreshTick, vm::refresh)
				// Yoklama yeni bildirim bulursa (sayı arttıysa) liste de tazelenir.
				var seen by remember { mutableIntStateOf(unread) }
				LaunchedEffect(unread) {
					if (unread > seen) vm.refresh()
					seen = unread
				}
				InboxScreen(vm, onOpen = { nav.navigate(Routes.notification(it.id)) })
			}
			composable(Routes.NOTIFICATION) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				// Liste ekranının ViewModel'i: ayrıntıda okunan satır geri dönüşte okunmuş görünsün.
				val inboxVm = nav.previousBackStackEntry?.takeIf { it.destination.route == Tab.NOTIFICATIONS.route }
					?.let { entry -> containerViewModel(owner = entry) { InboxViewModel(it.notificationRepository) } }
				NotificationDetailScreen(
					containerViewModel(key = "notification-$id") { NotificationDetailViewModel(it.notificationRepository, id) },
					onRead = { readId ->
						unreadVm.refresh()
						inboxVm?.markedRead(readId)
					},
					linkAction = { link ->
						when (link.type) {
							"TASK" -> ({ nav.navigate(Routes.task(link.id)) })
							"GROUP" -> ({ nav.navigate(Routes.group(link.id)) })
							"OPERATION" -> ({ nav.navigate(Routes.operation(link.id)) })
							"ALERT" -> ({ nav.navigate(Routes.alert(link.id)) })
							// Operasyon daveti Ana sayfadaki "Bekleyen davetler" kartından yanıtlanır.
							"INVITATION" -> ({
								app.refreshContext()
								nav.navigate(Tab.HOME.route) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
							})
							else -> null
						}
					},
				)
			}
			composable(Tab.ACCOUNT.route) {
				val vm = containerViewModel { AccountViewModel(it.authRepository) }
				RefreshOn(refreshTick, vm::load)
				AccountScreen(
					vm,
					main.context,
					onEditProfile = { nav.navigate(Routes.PROFILE) },
					onChangePassword = { nav.navigate(Routes.PASSWORD) },
					onLogout = app::logout,
				)
			}
			composable(Routes.PROFILE) {
				ProfileEditScreen(containerViewModel { ProfileEditViewModel(it.authRepository) }, onSaved = app::refreshContext)
			}
			composable(Routes.PASSWORD) {
				PasswordChangeScreen(containerViewModel { PasswordChangeViewModel(it.authRepository) }, forced = false, onChanged = {})
			}
			composable(Routes.TASK) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				TaskDetailScreen(containerViewModel(key = "task-$id") { TaskDetailViewModel(it.volunteerRepository, id) })
			}
			composable(Routes.GROUP) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				GroupDetailScreen(containerViewModel(key = "group-$id") { GroupDetailViewModel(it.volunteerRepository, id) })
			}
			composable(Routes.OPERATION) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				val locationVm = containerViewModel(key = "location-$id") { LocationSharingViewModel(it.locationRepository, id) }
				OperationDetailScreen(
					containerViewModel(key = "operation-$id") { OperationDetailViewModel(it.volunteerRepository, id) },
					context = main.context,
					onOpenTask = { nav.navigate(Routes.task(it)) },
					onOpenGroup = { nav.navigate(Routes.group(it)) },
					locationCard = { LocationSharingCard(locationVm, id) },
				)
			}
			composable(Routes.ALERT) { backStack ->
				val id = backStack.arguments?.getString("id").orEmpty()
				AlertDetailScreen(
					containerViewModel(key = "alert-$id") { AlertDetailViewModel(it.alertRepository, id, it.alarms) },
					onResponded = alertsVm::refresh,
				)
			}
		}
		}
	}
}

/** Üst çubuktaki yenileme sayacı değişince (ilk değer hariç) veriyi yeniden yükler. */
@Composable
private fun RefreshOn(tick: Int, reload: () -> Unit) {
	val initial = remember { tick }
	LaunchedEffect(tick) { if (tick != initial) reload() }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?, unread: Int, tabs: List<Tab>) {
	NavigationBar {
		tabs.forEach { tab ->
			NavigationBarItem(
				selected = route == tab.route,
				onClick = {
					nav.navigate(tab.route) {
						popUpTo(nav.graph.findStartDestination().id) { saveState = true }
						launchSingleTop = true
						restoreState = true
					}
				},
				icon = {
					if (tab == Tab.NOTIFICATIONS && unread > 0) {
						val label = stringResource(R.string.inbox_unread_count, unread)
						BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } },
							modifier = Modifier.semantics { contentDescription = label }) {
							Icon(tab.icon, contentDescription = null)
						}
					} else {
						Icon(tab.icon, contentDescription = null)
					}
				},
				label = { Text(stringResource(tab.label)) },
			)
		}
	}
}
