// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
  site: "https://statedb.app",
  integrations: [
    starlight({
      title: "statedb",
      description:
        "Self-hosted state backend with a web console, compatible with the Pulumi CLI.",
      logo: {
        light: "./src/assets/statedb-logo.svg",
        dark: "./src/assets/statedb-logo-dark.svg",
        replacesTitle: true,
      },
      favicon: "/favicon.svg",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/state-db/state-db",
        },
      ],
      customCss: [
        "@fontsource-variable/bricolage-grotesque",
        "@fontsource-variable/jetbrains-mono",
        "./src/styles/ocean.css",
        "./src/styles/starlight.css",
      ],
      sidebar: [
        { label: "Overview", slug: "docs" },
        {
          label: "Getting started",
          items: [{ label: "Quickstart", slug: "docs/quickstart" }],
        },
        {
          label: "Operating",
          items: [
            { label: "Deployment", slug: "docs/deployment" },
            { label: "Authentication", slug: "docs/authentication" },
          ],
        },
        {
          label: "Compatibility",
          items: [
            { label: "How we test", slug: "docs/compatibility" },
            { label: "Migrating in and out", slug: "docs/migration" },
          ],
        },
        { label: "License", items: [{ label: "License & pricing", slug: "docs/license" }] },
      ],
    }),
  ],
});
