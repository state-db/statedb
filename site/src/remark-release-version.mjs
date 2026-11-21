import { readFileSync } from "node:fs";

// bin/release writes this file in the release commit, which also triggers the site deploy.
const version = readFileSync(new URL("../release-version", import.meta.url), "utf8").trim();
const PLACEHOLDER = /\{\{version\}\}/g;

export default function remarkReleaseVersion() {
  const visit = (node) => {
    for (const key of ["value", "url"]) {
      if (typeof node[key] === "string") {
        node[key] = node[key].replace(PLACEHOLDER, version);
      }
    }
    node.children?.forEach(visit);
  };
  return visit;
}
