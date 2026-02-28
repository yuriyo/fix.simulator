import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Output to dist/ inside admin-ui/ – Javalin picks it up as external static files
    outDir: 'dist',
  },
  server: {
    // During development, proxy API + WS calls to the Javalin backend
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
