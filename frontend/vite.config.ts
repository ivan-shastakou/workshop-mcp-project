import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // In local dev auth-service runs on 8081, chat-service on 8082 and backend on 8080; in Docker nginx does the proxying.
    proxy: {
      '/api/auth': 'http://localhost:8081',
      '/api/chat': 'http://localhost:8082',
      '/api/mcp': 'http://localhost:8082',
      '/api': 'http://localhost:8080',
    },
  },
});
