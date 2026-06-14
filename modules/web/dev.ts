/**
 * Local dev server. Bundles the SPA with HMR and proxies backend traffic to
 * the Quarkus app on :8080. Only used in dev — in prod the bundle is embedded
 * in the Quarkus app and served from the same origin (no proxy).
 */
import homepage from "./index.html";

const BACKEND = "http://localhost:8080";

function proxyBackend(req: Request): Promise<Response> {
  const url = new URL(req.url);
  return fetch(BACKEND + url.pathname + url.search, {
    method: req.method,
    headers: req.headers,
    body: req.method !== "GET" && req.method !== "HEAD" ? req.body : undefined,
  });
}

const server = Bun.serve({
  port: 5173,

  routes: {
    // Backend proxies — matched before the SPA wildcard. The console only ever
    // calls /api, but /pulumi is proxied too so the dev origin behaves exactly
    // like the embedded prod origin.
    "/api/*": proxyBackend,
    "/pulumi/*": proxyBackend,

    // SPA catch-all: Bun bundles index.html + assets and enables HMR. Must be
    // "/*" (not "/") so direct navigation to a client route still loads the app.
    "/*": homepage,
  },

  development: {
    hmr: true,
    console: true,
  },
});

console.log(`state-db web dev server: ${server.url}`);
console.log(`backend proxy → ${BACKEND}`);
