/**
 * Local dev server. Bundles the SPA with HMR and proxies backend traffic to
 * the Quarkus app on :8080. Only used in dev — in prod the bundle is embedded
 * in the Quarkus app and served from the same origin (no proxy).
 */
import homepage from "./index.html";

const BACKEND = "http://localhost:8080";
const PORT = Number(process.env.PORT ?? 5173);

function proxyBackend(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const headers = new Headers(req.headers);
  // Tell Quarkus the browser-facing origin so OIDC redirect URIs and the BFF session cookie are
  // built for this dev server (:5173), not the proxied backend (:8080) — otherwise the IdP would
  // redirect back to :8080 and the session cookie would land on the wrong origin. The Quarkus side
  // honors these only when proxy-address-forwarding is enabled (set in the workbench app target).
  headers.set("X-Forwarded-Host", url.host);
  headers.set("X-Forwarded-Proto", url.protocol.replace(":", ""));
  return fetch(BACKEND + url.pathname + url.search, {
    method: req.method,
    headers,
    body: req.method !== "GET" && req.method !== "HEAD" ? req.body : undefined,
    // Pass 3xx responses straight back to the browser instead of following them here. The OIDC
    // redirects (to the IdP and back) must happen in the browser so it visits the IdP directly and
    // carries the session/state cookies; following them in the proxy would break the login flow.
    redirect: "manual",
  });
}

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: PORT,

  routes: {
    // Backend proxies — matched before the SPA wildcard. The console only ever
    // calls /api, but /pulumi is proxied too so the dev origin behaves exactly
    // like the embedded prod origin.
    "/api/*": proxyBackend,
    "/pulumi/*": proxyBackend,
    // Native `pulumi login` entry points. Pulumi hardcodes these at the console
    // root; in prod Quarkus serves them directly, so the dev proxy must forward
    // them too or they'd fall through to the SPA and 404.
    "/cli-login": proxyBackend,
    "/welcome/cli": proxyBackend,

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
