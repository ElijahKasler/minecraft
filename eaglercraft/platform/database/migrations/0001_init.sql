-- 0001_init.sql - initial platform schema
-- Postgres 14+

create extension if not exists citext;

-- users: platform accounts (password login, later MS OAuth)
create table if not exists users (
    id            uuid primary key default gen_random_uuid(),
    email         citext not null unique,
    email_verified_at timestamptz,
    password_hash text not null,
    username      citext not null unique,
    role          text not null default 'user' check (role in ('user','admin')),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

-- sessions: HttpOnly cookie sessions (opaque token, stored hashed)
create table if not exists sessions (
    token_hash   char(64) primary key,
    user_id      uuid not null references users(id) on delete cascade,
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null
);
create index if not exists sessions_user_id_idx on sessions(user_id);

-- ms_oauth: linked Microsoft/Minecraft accounts (Phase 8)
create table if not exists ms_oauth (
    id            bigserial primary key,
    user_id       uuid not null unique references users(id) on delete cascade,
    ms_subject    text not null,
    ms_email      citext,
    ms_name       text,
    -- encrypted at rest (AES-256-GCM, key in env ENCRYPTION_KEY)
    ms_refresh_enc   bytea,
    mc_uuid       text,
    mc_username   text,
    mc_access_expires_at timestamptz,
    linked_at     timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
create index if not exists ms_oauth_ms_subject_idx on ms_oauth(ms_subject);

-- servers: registry of Minecraft Java 1.8 servers the platform can route to
create table if not exists servers (
    id            uuid primary key default gen_random_uuid(),
    name          text not null,
    description   text not null default '',
    address       text not null,          -- host only
    port          integer not null default 25565,
    online_mode   boolean not null default false,
    icon_url      text,
    max_players   integer,
    sort_order    integer not null default 0,
    enabled       boolean not null default true,
    maintenance   boolean not null default false,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    check (port between 1 and 65535)
);
create index if not exists servers_enabled_idx on servers(enabled);

-- play_tickets: one-time, short-lived, signed play launch authorizations
create table if not exists play_tickets (
    token_hash    char(64) primary key,
    user_id       uuid not null references users(id) on delete cascade,
    server_id     uuid not null references servers(id) on delete cascade,
    used_at       timestamptz,
    created_at    timestamptz not null default now(),
    expires_at    timestamptz not null
);
create index if not exists play_tickets_created_idx on play_tickets(created_at);

-- audit: admin actions
create table if not exists audit_log (
    id           bigserial primary key,
    actor_id     uuid references users(id) on delete set null,
    action       text not null,
    detail       jsonb not null default '{}'::jsonb,
    created_at   timestamptz not null default now()
);
create index if not exists audit_log_created_idx on audit_log(created_at);