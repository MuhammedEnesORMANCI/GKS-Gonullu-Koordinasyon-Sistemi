import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { apiClient, type ApiClient } from '../api/client';
import { serviceConfig, type ServiceConfig, type ServiceResource } from '../config/services';
import type { Services } from './contracts';
import {
  httpAccountService,
  httpAnnouncementService,
  httpAlertService,
  httpAuditService,
  httpLocationService,
  httpPublicAnnouncementService,
  httpMessagingService,
  httpRealtimeService,
  httpAuthService,
  httpGroupService,
  httpInvitationService,
  httpMeContextService,
  httpNotificationService,
  httpOperationListService,
  httpOperationService,
  httpParticipantService,
  httpRoleAssignmentService,
  httpTaskService,
  httpUserAdminService,
  httpMapService,
} from './http';
import {
  mockAccountService,
  mockAnnouncementService,
  mockAlertService,
  mockAuditService,
  mockLocationService,
  mockRealtimeService,
  mockAuthService,
  mockGroupService,
  mockInvitationService,
  mockMeContextService,
  mockNotificationService,
  mockOperationListService,
  mockOperationService,
  mockParticipantService,
  mockPublicAnnouncementService,
  mockMessagingService,
  mockRoleAssignmentService,
  mockTaskService,
  mockUserAdminService,
  mockMapService,
} from './mock/services';

export function createServices(config: ServiceConfig, api: ApiClient = apiClient): Services {
  const pick = <K extends ServiceResource>(key: K, http: () => Services[K], mock: () => Services[K]) =>
    config[key] === 'http' ? http() : mock();
  return {
    auth: pick('auth', () => httpAuthService(api), mockAuthService),
    // Profil kimlik kaynağının modunu izler (ayrı yapılandırma anahtarı yok).
    account: config.auth === 'http' ? httpAccountService(api) : mockAccountService(),
    meContext: pick('meContext', () => httpMeContextService(api), mockMeContextService),
    operations: pick('operations', () => httpOperationService(api), mockOperationService),
    operationList: pick('operationList', () => httpOperationListService(api), mockOperationListService),
    participants: pick('participants', () => httpParticipantService(api), mockParticipantService),
    groups: pick('groups', () => httpGroupService(api), mockGroupService),
    tasks: pick('tasks', () => httpTaskService(api), mockTaskService),
    invitations: pick('invitations', () => httpInvitationService(api), mockInvitationService),
    notifications: pick('notifications', () => httpNotificationService(api), mockNotificationService),
    announcements: pick('announcements', () => httpAnnouncementService(api), mockAnnouncementService),
    roleAssignments: pick('roleAssignments', () => httpRoleAssignmentService(api), mockRoleAssignmentService),
    users: pick('users', () => httpUserAdminService(api), mockUserAdminService),
    audit: pick('audit', () => httpAuditService(api), mockAuditService),
    alerts: pick('alerts', () => httpAlertService(api), mockAlertService),
    locations: pick('locations', () => httpLocationService(api), mockLocationService),
    realtime: pick('realtime', () => httpRealtimeService(), mockRealtimeService),
    publicAnnouncements: pick('publicAnnouncements', () => httpPublicAnnouncementService(api), mockPublicAnnouncementService),
    messaging: pick('messaging', () => httpMessagingService(api), mockMessagingService),
    map: pick('map', () => httpMapService(api), mockMapService),
  };
}

interface ServicesValue {
  services: Services;
  config: ServiceConfig;
}

const ServicesContext = createContext<ServicesValue | null>(null);

export function ServicesProvider({
  children,
  config = serviceConfig,
  services,
}: {
  children: ReactNode;
  config?: ServiceConfig;
  services?: Services;
}) {
  const value = useMemo<ServicesValue>(
    () => ({ config, services: services ?? createServices(config) }),
    [config, services],
  );
  return <ServicesContext.Provider value={value}>{children}</ServicesContext.Provider>;
}

function useServicesValue(): ServicesValue {
  const value = useContext(ServicesContext);
  if (!value) throw new Error('ServicesProvider eksik');
  return value;
}

export function useServices(): Services {
  return useServicesValue().services;
}

/** Ekranın kullandığı kaynaklardan biri `mock` ise true (rozet için). */
export function useIsMock(...resources: ServiceResource[]): boolean {
  const { config } = useServicesValue();
  return resources.some((r) => config[r] === 'mock');
}
