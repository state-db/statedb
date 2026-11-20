// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import remarkReleaseVersion from "./src/remark-release-version.mjs";

export default defineConfig({
  site: "https://statedb.app",
  markdown: { remarkPlugins: [remarkReleaseVersion] },
  integrations: [
    starlight({
      title: "statedb",
      disable404Route: true,
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
          href: "https://github.com/state-db/statedb",
        },
      ],
      head: [
        {
          tag: "meta",
          attrs: { property: "og:image", content: "https://statedb.app/og.png" },
        },
        {
          tag: "meta",
          attrs: { property: "og:image:width", content: "1200" },
        },
        {
          tag: "meta",
          attrs: { property: "og:image:height", content: "630" },
        },
        {
          tag: "meta",
          attrs: { name: "twitter:image", content: "https://statedb.app/og.png" },
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
          items: [
            { label: "Quickstart", slug: "docs/quickstart" },
            { label: "Migrate stacks", slug: "docs/migration" },
          ],
        },
        {
          label: "Install",
          items: [
            { label: "Choose an installation", slug: "docs/install" },
            { label: "Kubernetes", slug: "docs/install/kubernetes" },
            { label: "Self-managed", slug: "docs/deployment" },
            { label: "AWS CloudFormation", slug: "docs/install/aws" },
            { label: "Google Cloud", slug: "docs/install/gcp" },
            { label: "Microsoft Azure", slug: "docs/install/azure" },
          ],
        },
        {
          label: "Operate",
          items: [
            { label: "Operate statedb", slug: "docs/operations" },
            { label: "Use the console", slug: "docs/management" },
            { label: "Authentication and access", slug: "docs/authentication" },
          ],
        },
        {
          label: "Reference",
          items: [
            { label: "Architecture", slug: "docs/architecture" },
            { label: "Pulumi CLI compatibility", slug: "docs/compatibility" },
            { label: "License and Pulumi", slug: "docs/license" },
          ],
        },
      ],
    }),
  ],
});
