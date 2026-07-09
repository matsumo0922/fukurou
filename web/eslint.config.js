import js from "@eslint/js";
import { defineConfig } from "eslint/config";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";

const typescriptRecommendedConfig = /** @type {import("eslint/config").Config[]} */ (tseslint.configs.recommended);

export default defineConfig(
  {
    ignores: [
      "dist",
      "src/api/openapi-types.ts",
    ],
  },
  js.configs.recommended,
  typescriptRecommendedConfig,
  {
    files: [
      "**/*.{ts,tsx}",
    ],
    extends: [
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      "react-refresh/only-export-components": [
        "warn",
        {
          allowConstantExport: true,
        },
      ],
    },
  },
);
