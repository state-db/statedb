// Typed client for the console API (/api/*). Same origin in prod (embedded in
// the Quarkus app); proxied to :8080 by the bun dev server in local dev.

const BASE = "/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15_000);
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { "Content-Type": "application/json", ...init?.headers },
      ...init,
      signal: init?.signal ?? controller.signal,
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({ error: res.statusText }));
      throw new Error((body as { error?: string }).error ?? res.statusText);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  } finally {
    clearTimeout(timeout);
  }
}

export interface Me {
  hello: string;
}

export const api = {
  me: {
    get: () => request<Me>("/me"),
  },
} as const;
