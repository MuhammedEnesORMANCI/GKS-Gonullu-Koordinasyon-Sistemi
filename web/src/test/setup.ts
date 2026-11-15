import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { resetMockStore } from '../services/mock/store';

afterEach(() => {
  cleanup();
  resetMockStore();
  try {
    localStorage.clear();
  } catch {
    // jsdom dışı ortam
  }
});
