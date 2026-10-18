import type { AuthUserDto, ServerInfoDto } from "@eaglerplatform/shared";

export type ApiResult<T> = { ok: true; data: T } | { ok: false; status: number; error: string };

async function request<T>(method: string, path: string, body?: unknown): Promise<ApiResult<T>> {
  try {
    const res = await fetch(path, {
      method,
      credentials: "same-origin",
      headers: body !== undefined ? { "content-type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data: unknown = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    }
    if (res.ok) return { ok: true, data: data as T };
    const error = (data as { error?: string } | null)?.error ?? `HTTP ${res.status}`;
    return { ok: false, status: res.status, error };
  } catch (err) {
    return { ok: false, status: 0, error: err instanceof Error ? err.message : String(err) };
  }
}

export interface PlayDto {
  ticket: string;
  bridgeUrl: string;
  serverId: string;
}

export interface AdminUserDto {
  id: string;
  email: string;
  username: string;
  role: "user" | "admin";
  createdAt: string | null;
}

export interface AuditRowDto {
  id: string | number;
  actor_id: string | null;
  action: string;
  detail: Record<string, unknown>;
  created_at: string;
}

export interface AdminStatsDto {
  users: number;
}

export interface AdminServerDto extends Omit<ServerInfoDto, "playerCount" | "onlineMode"> {
  online_mode: boolean;
  enabled: boolean;
  sort_order: number;
  created_at: string | null;
}

export const api = {
  me: () => request<AuthUserDto>("GET", "/api/me"),
  login: (email: string, password: string) =>
    request<AuthUserDto>("POST", "/api/login", { email, password }),
  register: (email: string, username: string, password: string) =>
    request<AuthUserDto>("POST", "/api/register", { email, username, password }),
  logout: () => request<{ ok: boolean }>("POST", "/api/logout", {}),
  servers: () => request<ServerInfoDto[]>("GET", "/api/servers"),
  play: (id: string) => request<PlayDto>("POST", `/api/servers/${encodeURIComponent(id)}/play`, {}),
  oauthStart: async (): Promise<ApiResult<{ url: string }>> => {
    const r = await request<{ url: string }>("GET", "/api/oauth/start");
    if (r.ok) window.location.assign(r.data.url);
    return r;
  },
  admin: {
    stats: () => request<AdminStatsDto>("GET", "/api/admin/stats"),
    users: () => request<AdminUserDto[]>("GET", "/api/admin/users"),
    setRole: (id: string, role: "user" | "admin") =>
      request<{ ok: boolean }>("PATCH", `/api/admin/users/${encodeURIComponent(id)}`, { role }),
    servers: () => request<AdminServerDto[]>("GET", "/api/admin/servers"),
    createServer: (body: Record<string, unknown>) =>
      request<{ id: string }>("POST", "/api/admin/servers", body),
    patchServer: (id: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>("PATCH", `/api/admin/servers/${encodeURIComponent(id)}`, body),
    deleteServer: (id: string) =>
      request<{ ok: boolean }>("DELETE", `/api/admin/servers/${encodeURIComponent(id)}`),
    audit: () => request<AuditRowDto[]>("GET", "/api/admin/audit"),
  },
};