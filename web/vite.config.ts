import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const backendTarget = process.env.VITE_FUKUROU_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [
    react(),
  ],
  server: {
    proxy: {
      "/evaluation": {
        target: backendTarget,
        changeOrigin: true,
      },
      "/health": {
        target: backendTarget,
        changeOrigin: true,
      },
      "/openapi.json": {
        target: backendTarget,
        changeOrigin: true,
      },
      "/ops": {
        target: backendTarget,
        changeOrigin: true,
      },
      "/revision": {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: [
      "./src/test/setup.ts",
    ],
  },
});
