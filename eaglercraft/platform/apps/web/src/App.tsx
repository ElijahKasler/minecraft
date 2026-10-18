import { useCallback, useEffect, useState } from "react";
import type { AuthUserDto, ServerInfoDto } from "@eaglerplatform/shared";
import { api, type AdminServerDto, type AdminStatsDto, type AdminUserDto, type AuditRowDto } from "./api";
import { bootClient } from "./client";

function parseHash(): string {
  const h = window.location.hash.replace(/^#\/?/, "");
  return h === "" ? "home" : h.split("?")[0];
}

function goto(name: string): void {
  window.location.hash = name === "home" ? "/" : `/${name}`;
}

export default function App() {
  const [route, setRoute] = useState(parseHash());
  const [me, setMe] = useState<AuthUserDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const onHash = () => setRoute(parseHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const refreshMe = useCallback(async () => {
    const r = await api.me();
    setMe(r.ok ? r.data : null);
    setLoading(false);
  }, []);

  useEffect(() => {
    void refreshMe();
  }, [refreshMe]);

  const loggedOut = () => {
    setMe(null);
    goto("auth");
  };

  return (
    <div className="app">
      <header className="topbar">
        <a className="brand" href="#/" onClick={() => goto("home")}>
          <img src="/favicon.svg" alt="" width={22} height={22} />
          <span>Eagler Platform</span>
        </a>
        <nav>
          {me ? (
            <>
              <span className="who" title={me.email}>
                {me.username}
                {me.role === "admin" ? " · admin" : ""}
              </span>
              {me.role === "admin" && (
                <a className={route === "admin" ? "active" : ""} href="#/admin">
                  Admin
                </a>
              )}
              <button
                className="ghost"
                onClick={async () => {
                  await api.logout();
                  loggedOut();
                }}
              >
                Log out
              </button>
            </>
          ) : (
            <a className={route === "auth" ? "active" : ""} href="#/auth">
              Sign in
            </a>
          )}
        </nav>
      </header>
      <main>
        {loading ? (
          <p className="muted center">Loading…</p>
        ) : route === "admin" ? (
          <Admin onDemoteToUser={setMe} />
        ) : route === "auth" ? (
          <Auth />
        ) : me ? (
          <Home onLoggedOut={loggedOut} />
        ) : (
          <Auth />
        )}
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Home / launcher
// ---------------------------------------------------------------------------

function Home({ onLoggedOut }: { onLoggedOut: () => void }) {
  const [servers, setServers] = useState<ServerInfoDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [booted, setBooted] = useState(false);

  useEffect(() => {
    void api.servers().then((r) => {
      setServers(r.ok ? r.data : []);
      setLoading(false);
    });
  }, []);

  const play = async (s: ServerInfoDto) => {
    setStatus("");
    setBusyId(s.id);
    const r = await api.play(s.id);
    setBusyId(null);
    if (!r.ok) {
      if (r.status === 401 || r.status === 403) {
        onLoggedOut();
        return;
      }
      setStatus(r.error);
      return;
    }
    setBooted(true);
    bootClient({ bridgeUrl: r.data.bridgeUrl, serverName: s.name, container: "game" });
    setStatus(`Connecting to ${s.name} …`);
  };

  if (loading) return <p className="muted center">Loading servers…</p>;

  return (
    <section>
      <div className="pagehead">
        <h1>Select a server</h1>
      </div>
      {booted && <div id="game" className="game-frame" />}
      {servers.length === 0 ? (
        <div className="empty">
          <p>No servers are available yet.</p>
          <p className="muted">
            Ask your administrator to register a Minecraft 1.8 server in the Admin console.
          </p>
        </div>
      ) : (
        <div className="cards">
          {servers.map((s) => (
            <div className="card row" key={s.id}>
              <div>
                <div className="card-title">
                  {s.name} {s.maintenance && <span className="tag">maintenance</span>}
                </div>
                <div className="muted">
                  {s.description || `${s.address}:${s.port}`}
                </div>
              </div>
              <button
                disabled={s.maintenance || busyId !== null}
                onClick={() => void play(s)}
              >
                {busyId === s.id ? "Launching…" : "Play"}
              </button>
            </div>
          ))}
        </div>
      )}
      {status && <p className="status">{status}</p>}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

function Auth() {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [oauth, setOauth] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setBusy(true);
    const r =
      mode === "login"
        ? await api.login(email, password)
        : await api.register(email, username, password);
    setBusy(false);
    if (r.ok) goto("home");
    else setError(r.error);
  };

  const msStart = async () => {
    setError("");
    setOauth(true);
    const r = await api.oauthStart();
    if (!r.ok) {
      setOauth(false);
      setError(r.error);
    }
  };

  return (
    <section className="authcard">
      <h1>{mode === "login" ? "Sign in" : "Create account"}</h1>
      <div className="tabs">
        <button className={mode === "login" ? "active" : ""} onClick={() => setMode("login")}>
          Sign in
        </button>
        <button className={mode === "register" ? "active" : ""} onClick={() => setMode("register")}>
          Register
        </button>
      </div>
      <form onSubmit={submit}>
        <label>
          Email
          <input
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        {mode === "register" && (
          <label>
            Username
            <input
              required
              minLength={1}
              maxLength={16}
              pattern="[A-Za-z0-9_]+"
              autoComplete="nickname"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
        )}
        <label>
          Password
          <input
            type="password"
            required
            min={mode === "register" ? 8 : undefined}
            minLength={mode === "register" ? 8 : undefined}
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <button type="submit" disabled={busy || oauth}>
          {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Register"}
        </button>
      </form>
      <div className="divider">
        <span>or</span>
      </div>
      <button className="ms" disabled={oauth} onClick={() => void msStart()}>
        {oauth ? "Redirecting to Microsoft…" : "Continue with Microsoft"}
      </button>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

function Admin({ onDemoteToUser }: { onDemoteToUser: (u: AuthUserDto | null) => void }) {
  const [tab, setTab] = useState<"servers" | "users" | "audit">("servers");
  const refreshKey = navigationMarker();

  return (
    <section>
      <div className="pagehead">
        <h1>Admin</h1>
        <div className="tabs">
          <button className={tab === "servers" ? "active" : ""} onClick={() => setTab("servers")}>
            Servers
          </button>
          <button className={tab === "users" ? "active" : ""} onClick={() => setTab("users")}>
            Users
          </button>
          <button className={tab === "audit" ? "active" : ""} onClick={() => setTab("audit")}>
            Audit log
          </button>
        </div>
      </div>
      {tab === "servers" && <AdminServers onDemoteToUser={onDemoteToUser} refreshKey={refreshKey} />}
      {tab === "users" && <AdminUsers onDemoteToUser={onDemoteToUser} />}
      {tab === "audit" && <AdminAudit />}
    </section>
  );
}

function navigationMarker(): number {
  return Date.now() % 1e9;
}

function AdminServers({ onDemoteToUser, refreshKey }: { onDemoteToUser: (u: AuthUserDto | null) => void; refreshKey: number }) {
  const [rows, setRows] = useState<AdminServerDto[]>([]);
  const [loadError, setLoadError] = useState("");
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({ name: "", description: "", address: "", port: "25565", onlineMode: false, iconUrl: "", maxPlayers: "" });

  const load = useCallback(async () => {
    const r = await api.admin.servers();
    if (!r.ok) {
      if (r.status === 403 || r.status === 401) onDemoteToUser(null);
      setLoadError(r.error);
      return;
    }
    setRows(r.data);
  }, [onDemoteToUser]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    const port = Number(form.port);
    const body = {
      name: form.name.trim(),
      description: form.description.trim(),
      address: form.address.trim(),
      port,
      onlineMode: form.onlineMode,
      iconUrl: form.iconUrl.trim() || undefined,
      maxPlayers: form.maxPlayers.trim() ? Number(form.maxPlayers) : undefined,
    };
    const r = await api.admin.createServer(body);
    if (r.ok) {
      setEditing(false);
      setForm({ name: "", description: "", address: "", port: "25565", onlineMode: false, iconUrl: "", maxPlayers: "" });
      void load();
    } else {
      setLoadError(r.error);
    }
  };

  const toggle = async (id: string, key: "enabled" | "maintenance", value: boolean) => {
    await api.admin.patchServer(id, { [key]: value });
    void load();
  };

  return (
    <div>
      <div className="toolbar">
        <button onClick={() => setEditing(!editing)}>{editing ? "Cancel" : "Add server"}</button>
      </div>
      {editing && (
        <form className="card form" onSubmit={save}>
          <div className="grid2">
            <label>
              Name
              <input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </label>
            <label>
              Address (host only)
              <input required value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            </label>
            <label>
              Port
              <input type="number" min={1} max={65535} value={form.port} onChange={(e) => setForm({ ...form, port: e.target.value })} />
            </label>
            <label>
              Max players (optional)
              <input type="number" min={1} value={form.maxPlayers} onChange={(e) => setForm({ ...form, maxPlayers: e.target.value })} />
            </label>
            <label className="full">
              Description
              <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </label>
            <label>
              Icon URL (optional)
              <input value={form.iconUrl} onChange={(e) => setForm({ ...form, iconUrl: e.target.value })} />
            </label>
            <label className="check">
              <input type="checkbox" checked={form.onlineMode} onChange={(e) => setForm({ ...form, onlineMode: e.target.checked })} />
              Online mode (premium Java server)
            </label>
          </div>
          <div className="toolbar">
            <button type="submit">Create</button>
          </div>
        </form>
      )}
      {loadError && <p className="error">{loadError}</p>}
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Address</th>
            <th>Mode</th>
            <th>State</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.id}>
              <td>
                <strong>{s.name}</strong>
                <div className="muted">{s.description}</div>
              </td>
              <td>
                {s.address}:{s.port}
              </td>
              <td>{s.online_mode ? "online" : "offline"}</td>
              <td>
                <button className="ghost" onClick={() => void toggle(s.id, "enabled", !s.enabled)}>
                  {s.enabled ? "Disable" : "Enable"}
                </button>{" "}
                <button className="ghost" onClick={() => void toggle(s.id, "maintenance", !s.maintenance)}>
                  {s.maintenance ? "End maintenance" : "Set maintenance"}
                </button>
              </td>
              <td>
                <button
                  className="danger"
                  onClick={async () => {
                    if (!window.confirm(`Delete server "${s.name}"?`)) return;
                    await api.admin.deleteServer(s.id);
                    void load();
                  }}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && !loadError && <p className="muted">No servers registered.</p>}
    </div>
  );
}

function AdminUsers({ onDemoteToUser }: { onDemoteToUser: (u: AuthUserDto | null) => void }) {
  const [rows, setRows] = useState<AdminUserDto[]>([]);
  const [stats, setStats] = useState<AdminStatsDto | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const [u, s] = await Promise.all([api.admin.users(), api.admin.stats()]);
    if (!u.ok) {
      if (u.status === 403 || u.status === 401) onDemoteToUser(null);
      setError(u.error);
      return;
    }
    setRows(u.data);
    if (s.ok) setStats(s.data);
  }, [onDemoteToUser]);

  useEffect(() => {
    void load();
  }, [load]);

  const setRole = async (id: string, role: "user" | "admin") => {
    const r = await api.admin.setRole(id, role);
    if (!r.ok) setError(r.error);
    else void load();
  };

  return (
    <div>
      <p className="muted">{stats ? `${stats.users} account${stats.users === 1 ? "" : "s"}` : ""}</p>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>Username</th>
            <th>Email</th>
            <th>Role</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((u) => (
            <tr key={u.id}>
              <td>
                <strong>{u.username}</strong>
              </td>
              <td>{u.email}</td>
              <td>{u.role}</td>
              <td className="muted">{u.createdAt ? new Date(u.createdAt).toLocaleString() : "—"}</td>
              <td>
                {u.role === "admin" ? (
                  <button className="ghost" onClick={() => void setRole(u.id, "user")}>
                    Demote
                  </button>
                ) : (
                  <button className="ghost" onClick={() => void setRole(u.id, "admin")}>
                    Make admin
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AdminAudit() {
  const [rows, setRows] = useState<AuditRowDto[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    void api.admin.audit().then((r) => {
      if (r.ok) setRows(r.data);
      else setError(r.error);
    });
  }, []);

  return (
    <div>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Actor</th>
            <th>Action</th>
            <th>Detail</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={String(r.id)}>
              <td className="muted">{new Date(r.created_at).toLocaleString()}</td>
              <td>{r.actor_id ? r.actor_id.slice(0, 8) : "—"}</td>
              <td>{r.action}</td>
              <td className="muted">{JSON.stringify(r.detail)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && !error && <p className="muted">No audit entries yet.</p>}
    </div>
  );
}