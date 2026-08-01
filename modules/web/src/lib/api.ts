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
      const body: unknown = await res.json().catch(() => ({ error: res.statusText }));
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

export interface MintedCliToken {
  id: string;
  token: string;
}

export interface OidcProvider {
  id: string;
  name: string;
  issuerUrl: string;
  clientId: string;
  scopes: string[];
  enabled: boolean;
  envManaged: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface OidcProviderWrite {
  name?: string;
  issuerUrl?: string;
  clientId?: string;
  clientSecret?: string;
  scopes?: string[];
  enabled?: boolean;
}

export interface AdminOrganization {
  id: string;
  name: string;
  displayName: string;
  members: number;
  stacks: number;
  createdAt: string;
}

export interface AdminUserOrg {
  name: string;
  displayName: string;
  role: string;
}
export interface AdminUser {
  id: string;
  iss: string;
  sub: string;
  email: string | null;
  displayName: string | null;
  principalType: "human" | "service";
  siteAdmin: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  deactivatedAt: string | null;
  credentialStatus: "active" | "expired" | "revoked" | "no_credential" | null;
  organizations: AdminUserOrg[];
}
export interface AdminUsersPage {
  users: AdminUser[];
  page: number;
  pageSize: number;
  total: number;
}

export type HumanRole = "owner" | "member" | "viewer" | "guest";
export interface MemberGrant { stackId: string; project: string; stack: string; template: string; createdAt: string; }
export interface OrganizationMember {
  userId: string; email: string | null; displayName: string | null; role: HumanRole;
  joinedAt: string; grants: MemberGrant[];
}
export interface UserCandidate { id: string; email: string | null; displayName: string | null; }

export interface ApiToken {
  id: string;
  description: string;
  kind: "oidc" | "service_account";
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
}

export interface ServiceAccount {
  id: string;
  tokenId: string;
  name: string;
  org: string;
  orgDisplayName: string;
  createdAt: string;
  tokenExpiresAt: string;
  tokenLastUsedAt: string | null;
  tokenRevokedAt: string | null;
  accountDeactivatedAt: string | null;
  createdById: string | null;
  createdByName: string | null;
  createdByEmail: string | null;
  revokedById: string | null;
  revokedByName: string | null;
  revokedByEmail: string | null;
}

export interface MintedServiceAccount {
  id: string;
  name: string;
  token: string;
  expiresAt: string;
}

export interface WorkspaceLastUpdate {
  kind: string;
  status: "succeeded" | "failed" | "in-progress" | "cancelled" | string;
  startedAt: string;
  version: number;
  actor: {
    displayName: string | null;
    email: string | null;
    fallback: string;
  } | null;
}

export interface WorkspaceLock {
  lockId: string;
  lockedBy: string;
  lockedAt: string;
}

export interface AdminLockHolder {
  kind: "human" | "service";
  id: string | null;
  displayName: string | null;
  email: string | null;
  fallback: string;
}

export interface AdminLock {
  lockId: string;
  stackId: string;
  org: string;
  project: string;
  stack: string;
  holder: AdminLockHolder;
  acquiredAt: string;
  heartbeatAt: string;
  expiresAt: string;
  update: {
    id: string;
    kind: string;
    version: number | null;
    preview: boolean;
  };
}

export interface AdminCompactionJob {
  id: string;
  stackId: string;
  org: string;
  project: string;
  stack: string;
  status: "pending" | "running" | "completed" | "failed";
  attempts: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
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
  id: string;
  workspaceId: string;
  createdAt: string;
  org: string;
  project: string;
  name: string;
  version: number;
  resourceCount: number | null;
  lastUpdate: WorkspaceLastUpdate | null;
  lock: WorkspaceLock | null;
  resources: PulumiResource[];
  pulumiVersion: string | null;
  tags: Record<string, string>;
  baseSnapshotVersion: number;
  journalEntriesSinceCompaction: number;
}

export interface UpdateHistoryRow {
  id: string;
  version: number | null;
  kind: string;
  status: string;
  message: string | null;
  summary: { resourceChanges?: Record<string, number> } | null;
  startedAt: string;
  endedAt: string | null;
}

export interface UpdateHistoryPage {
  updates: UpdateHistoryRow[];
  page: number;
  pageSize: number;
  total: number;
}

export interface RunDetail {
  id: string;
  version: number | null;
  kind: string;
  status: string;
  message: string | null;
  runtime: string | null;
  pulumiVersion: string | null;
  startedAt: string;
  endedAt: string | null;
  summary: { resourceChanges?: Record<string, number> } | null;
  config: Record<string, unknown>;
  options: Record<string, unknown>;
  metadata: Record<string, unknown>;
}

export interface RunEvent {
  sequence: number;
  kind: string;
  severity: string | null;
  payload: Record<string, unknown>;
}

export interface RunEventsPage {
  events: RunEvent[];
  nextAfter: number | null;
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
  tokens: {
    list: () => request<ApiToken[]>("/tokens"),
    mintCli: () =>
      request<MintedCliToken>("/auth/cli-mint", {
        method: "POST",
        body: "{}",
      }),
    revoke: (id: string) =>
      request<void>(`/tokens/${encodeURIComponent(id)}`, { method: "DELETE" }),
  },
  serviceAccounts: {
    list: (status: "active" | "inactive" | "all") =>
      request<ServiceAccount[]>(`/service-accounts?status=${status}`),
    create: (org: string, input: { name: string; expiresInDays: 30 | 90 | 365 }) =>
      request<MintedServiceAccount>(
        `/orgs/${encodeURIComponent(org)}/service-accounts`,
        { method: "POST", body: JSON.stringify(input) },
      ),
    revoke: (org: string, id: string) =>
      request<void>(
        `/orgs/${encodeURIComponent(org)}/service-accounts/${encodeURIComponent(id)}`,
        { method: "DELETE" },
      ),
  },
  admin: {
    orgs: {
      list: () => request<AdminOrganization[]>("/admin/orgs"),
      create: (org: { name: string; displayName: string }) => request<AdminOrganization>("/admin/orgs", { method: "POST", body: JSON.stringify(org) }),
      delete: (id: string) => request<void>(`/admin/orgs/${encodeURIComponent(id)}`, { method: "DELETE" }),
    },
    users: {
      list: (page: number, includeService: boolean) =>
        request<AdminUsersPage>(
          `/admin/users?page=${page}&includeService=${includeService}`,
        ),
    },
    providers: {
      list: () => request<OidcProvider[]>("/admin/providers"),
      create: (provider: OidcProviderWrite) =>
        request<OidcProvider>("/admin/providers", {
          method: "POST",
          body: JSON.stringify(provider),
        }),
      update: (id: string, provider: OidcProviderWrite) =>
        request<OidcProvider>(`/admin/providers/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: JSON.stringify(provider),
        }),
      delete: (id: string) =>
        request<void>(`/admin/providers/${encodeURIComponent(id)}`, {
          method: "DELETE",
        }),
    },
    operations: {
      locks: () => request<AdminLock[]>("/admin/locks"),
      compactionJobs: () => request<AdminCompactionJob[]>("/admin/compaction-jobs"),
    },
  },
  orgs: {
    members: {
      list: (org: string) => request<OrganizationMember[]>(`/orgs/${encodeURIComponent(org)}/members`),
      candidates: (org: string) => request<UserCandidate[]>(`/orgs/${encodeURIComponent(org)}/members/candidates`),
      add: (org: string, userId: string, role: HumanRole) => request<OrganizationMember>(`/orgs/${encodeURIComponent(org)}/members`, { method: "POST", body: JSON.stringify({ userId, role }) }),
      update: (org: string, userId: string, role: HumanRole) => request<OrganizationMember>(`/orgs/${encodeURIComponent(org)}/members`, { method: "PATCH", body: JSON.stringify({ userId, role }) }),
      remove: (org: string, userId: string) => request<void>(`/orgs/${encodeURIComponent(org)}/members?userId=${encodeURIComponent(userId)}`, { method: "DELETE" }),
    },
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
    updates: (
      org: string,
      project: string,
      stack: string,
      filters: { page: number; kind?: string; status?: string },
    ) => {
      const search = new URLSearchParams({ page: String(filters.page) });
      if (filters.kind) search.set("kind", filters.kind);
      if (filters.status) search.set("status", filters.status);
      return request<UpdateHistoryPage>(
        `/workspaces/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/updates?${search}`,
      );
    },
    run: (
      org: string,
      project: string,
      stack: string,
      locator: { version: number } | { previewId: string },
      signal?: AbortSignal,
    ) => {
      const suffix = "version" in locator
        ? `updates/${locator.version}`
        : `previews/${encodeURIComponent(locator.previewId)}`;
      return request<RunDetail>(
        `/pulumi/stacks/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/${suffix}`,
        { signal },
      );
    },
    runEvents: (
      org: string,
      project: string,
      stack: string,
      updateId: string,
      after: number,
      signal?: AbortSignal,
    ) => request<RunEventsPage>(
      `/pulumi/stacks/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/updates/${encodeURIComponent(updateId)}/events?after=${after}`,
      { signal },
    ),
    forceUnlock: (org: string, project: string, stack: string, lockId: string) =>
      request<void>(
        `/admin/stacks/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/lock?lockId=${encodeURIComponent(lockId)}`,
        { method: "DELETE" },
      ),
    compact: (org: string, project: string, stack: string) =>
      request<void>(
        `/admin/stacks/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}/compact`,
        { method: "POST" },
      ),
    delete: (org: string, project: string, stack: string) =>
      request<void>(
        `/pulumi/stacks/${encodeURIComponent(org)}/${encodeURIComponent(project)}/${encodeURIComponent(stack)}`,
        { method: "DELETE" },
      ),
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
