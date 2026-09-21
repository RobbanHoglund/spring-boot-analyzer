import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom'
  },
  server: {
    fs: {
      // Test-only: lets the category parity test read the backend FindingCategory enum,
      // which lives outside the frontend root. Does not affect the dev server (vite.config.ts).
      allow: ['..']
    }
  }
});
