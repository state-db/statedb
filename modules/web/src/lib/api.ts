// Typed client for the console API (/api/*). Same origin in prod (embedded in
// the Quarkus app); proxied to :8080 by the bun dev server in local dev.
//
// The SPA is a pure OIDC backend-for-frontend client: it never sees or stores
// tokens. "Logged in" means GET /api/user returned 200; a 401 means the session
// is missing/expired and the browser must be sent to the provider login route.

const BASE = "/api";

/** Thrown when the console session is absent or expired (HTTP 401). */
export class UnauthorizedError extends Error {
  constructor() {
    super("unauthorized");
    this.name = "UnauthorizedError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = init?.signal ? undefined : new AbortController();
  const timeout = controller
    ? window.setTimeout(() => controller.abort(), 15_000)
    : undefined;
  const headers = new Headers(init?.headers);
  if (!headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  try {
    const res = await fetch(`${BASE}${path}`, {
      ...init,
      headers,
      signal: init?.signal ?? controller?.signal,
    });
    if (res.status === 401) {
      throw new UnauthorizedError();
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({ error: res.statusText }));
      throw new Error((body as { error?: string }).error ?? res.statusText);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  } finally {
    if (timeout) {
      window.clearTimeout(timeout);
    }
  }
}

export interface OrgMembership {
  name: string;
  displayName: string;
  role: string;
}

export interface CurrentUser {
  id: string;
  email: string | null;
  name: string | null;
  siteAdmin: boolean;
  organizations: OrgMembership[];
}

export const api = {
  user: {
    get: () => request<CurrentUser>("/user"),
  },
} as const;

/**
 * Sends the browser to the server-owned default OIDC login route. Explicit
 * provider links remain available at /api/auth/login/{provider}; the SPA does
 * not choose identity providers.
 */
export function redirectToLogin(): void {
  const returnTo = `${window.location.pathname}${window.location.search}`;
  window.location.href = `/console/login?returnTo=${encodeURIComponent(returnTo)}`;
}
