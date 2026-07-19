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

export class NotFoundError extends Error {
  constructor() {
    super("not found");
    this.name = "NotFoundError";
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
    if (res.status === 404) {
      throw new NotFoundError();
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

export interface AuthProvider {
  name: string;
  displayName: string;
}

export interface AuthProviders {
  providers: AuthProvider[];
  /**
   * True when the server would honor a break-glass login (operator password
   * configured and no OIDC provider enabled). The loopback gate is still
   * enforced server-side on the POST.
   */
  breakGlass: boolean;
}

export interface WorkspaceLastUpdate {
  kind: string;
  status: "succeeded" | "failed" | "in-progress" | "cancelled" | string;
  startedAt: string;
  version: number;
}

export interface WorkspaceLock {
  lockedBy: string;
  lockedAt: string;
}

export interface Workspace {
  id: string;
  kind: "pulumi" | "tofu" | string;
  org: string;
  project: string;
  projectCreatedAt: string;
  stack: string;
  version: number;
  resourceCount: number | null;
  lastUpdate: WorkspaceLastUpdate | null;
  lock: WorkspaceLock | null;
}

export type PulumiValue = null | boolean | number | string | PulumiValue[] | {
  [key: string]: PulumiValue;
};

export interface PulumiResource {
  urn: string;
  type?: string;
  custom?: boolean;
  parent?: string;
  provider?: string;
  dependencies?: string[];
  inputs?: Record<string, PulumiValue>;
  outputs?: Record<string, PulumiValue>;
  created?: string;
  modified?: string;
  [key: string]: unknown;
}

export interface StackDetail {
  org: string;
  project: string;
  name: string;
  version: number;
  resourceCount: number | null;
  lastUpdate: WorkspaceLastUpdate | null;
  lock: WorkspaceLock | null;
  resources: PulumiResource[];
  pulumiVersion: string | null;
}

/**
 * Break-glass login: posts the operator password to the Quarkus form-auth
 * endpoint (urlencoded, not JSON — the mechanism consumes it before JAX-RS).
 * 200 sets the encrypted session cookie; 401 covers wrong password and closed
 * gate (OIDC enabled / non-loopback source) alike.
 */
async function breakGlassLogin(password: string): Promise<void> {
  const res = await fetch("/api/auth/local", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username: "admin", password }),
  });
  if (res.status === 401) {
    throw new UnauthorizedError();
  }
  if (!res.ok) {
    throw new Error(res.statusText);
  }
}

export const api = {
  auth: {
    providers: {
      list: () => request<AuthProviders>("/auth/providers"),
    },
    breakGlass: breakGlassLogin,
  },
  user: {
    get: () => request<CurrentUser>("/user"),
  },
  workspaces: {
    list: (filters?: { org?: string; project?: string }) => {
      const search = new URLSearchParams();
      if (filters?.org) search.set("org", filters.org);
      if (filters?.project) search.set("project", filters.project);
      const encoded = search.toString();
      const query = encoded ? `?${encoded}` : "";
      return request<Workspace[]>(`/workspaces${query}`);
    },
    get: (org: string, project: string, stack: string) =>
      request<StackDetail>(
        `/workspaces/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}`,
      ),
    exportUrl: (org: string, project: string, stack: string) =>
      `${BASE}/workspaces/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/export`,
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
