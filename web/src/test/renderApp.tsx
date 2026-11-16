import { render } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AppProviders, AppRoutes } from '../app/App';
import { SessionProvider } from '../app/session';
import { MOCK_PRESET, type ServiceConfig } from '../config/services';
import type { Services } from '../services/contracts';
import { createServices } from '../services/registry';
import { markMockLoggedIn, setMockPersona } from '../services/mock/services';
import type { MockPersona } from '../services/mock/store';

export function renderApp({
  route = '/panel',
  persona = 'KOR',
  loggedIn = true,
  config = MOCK_PRESET,
  overrides = {},
}: {
  route?: string;
  persona?: MockPersona;
  loggedIn?: boolean;
  config?: ServiceConfig;
  overrides?: Partial<Services>;
} = {}) {
  setMockPersona(persona);
  markMockLoggedIn(loggedIn);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } },
  });
  const services: Services = { ...createServices(config), ...overrides };
  const result = render(
    <AppProviders queryClient={queryClient} config={config} services={services}>
      <MemoryRouter initialEntries={[route]}>
        <SessionProvider>
          <AppRoutes />
        </SessionProvider>
      </MemoryRouter>
    </AppProviders>,
  );
  return { ...result, queryClient, services };
}
