import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    pool: "threads",
    setupFiles: "./src/test/setup.ts",
    include: ["src/**/*.test.{ts,tsx}"],
    environmentOptions: {
      jsdom: {
        url: "https://website.test/",
      },
    },
    css: true,
  },
});
