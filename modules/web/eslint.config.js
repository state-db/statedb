// Static analysis for the console SPA, complementing `tsc --noEmit`: React
// Hook dependency and call-order violations, and mishandled promises.
// Run with `make analyze-web`.
import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules", "scripts"] },
  {
    files: ["**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      // Type-aware, for no-floating-promises and no-misused-promises.
      ...tseslint.configs.recommendedTypeChecked,
      // v7 keeps the eslintrc-shaped configs at the top level; the flat-config
      // equivalents are under .flat.
      reactHooks.configs.flat["recommended-latest"],
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    // TanStack Router signals redirects by throwing a Redirect object from
    // beforeLoad — the framework's control flow, not an error being thrown.
    files: ["src/router.tsx"],
    rules: { "@typescript-eslint/only-throw-error": "off" },
  },
  {
    // These two declare `"succeeded" | "failed" | ... | string` unions, where
    // the literals document the values the server sends today while staying
    // open to new ones. TypeScript does collapse the union to `string`, which
    // is what the rule reports.
    files: ["src/lib/api.ts", "src/components/console/StatusBadge.tsx"],
    rules: { "@typescript-eslint/no-redundant-type-constituents": "off" },
  },
);
