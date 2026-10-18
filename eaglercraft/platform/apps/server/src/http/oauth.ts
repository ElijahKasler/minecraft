/**
 * Microsoft identity / Minecraft services OAuth (Phase 8).
 *
 * Uses the public Microsoft "consumers" tenant by default (see MS_TENANT).
 * The callback path must match the registered redirect URI exactly
 * (`${origin}/api/oauth/callback`).
 */

import type { Config } from "../config.js";

export interface MsTokens {
  accessToken: string;
  refreshToken?: string;
  expiresInSeconds: number;
}

export interface MsProfile {
  id: string;
  name?: string;
  email?: string;
}

export interface McProfile {
  id: string; // 32-char hex uuid (no dashes)
  name: string;
}

const SCOPE = [
  "XboxLive.signin",
  "XboxLive.offline_access",
  "openid",
  "profile",
  "email",
  "user.read",
].join(" ");

export function createMsAuthUrl(config: Config, state: string, redirectUri: string): string {
  const params = new URLSearchParams({
    client_id: config.msClientId as string,
    response_type: "code",
    redirect_uri: redirectUri,
    scope: SCOPE,
    response_mode: "query",
  });
  params.set("state", state);
  return `https://login.microsoftonline.com/${config.msTenant}/oauth2/v2.0/authorize?${params.toString()}`;
}

export async function exchangeCodeForTokens(config: Config, code: string, redirectUri: string): Promise<MsTokens> {
  if (!config.msClientId || !config.msClientSecret) throw new Error("Microsoft OAuth is not configured");
  const body = new URLSearchParams({
    client_id: config.msClientId,
    grant_type: "authorization_code",
    code,
    redirect_uri: redirectUri,
    client_secret: config.msClientSecret,
    scope: SCOPE,
  });
  const res = await fetch(`https://login.microsoftonline.com/${config.msTenant}/oauth2/v2.0/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
    body,
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  const json = (await res.json()) as {
    access_token: string;
    refresh_token?: string;
    expires_in: number;
  };
  return { accessToken: json.access_token, refreshToken: json.refresh_token, expiresInSeconds: json.expires_in };
}

export async function refreshMsTokens(config: Config, refreshToken: string): Promise<MsTokens> {
  if (!config.msClientId || !config.msClientSecret) throw new Error("Microsoft OAuth is not configured");
  const body = new URLSearchParams({
    client_id: config.msClientId,
    grant_type: "refresh_token",
    refresh_token: refreshToken,
    client_secret: config.msClientSecret,
    scope: SCOPE,
  });
  const res = await fetch(`https://login.microsoftonline.com/${config.msTenant}/oauth2/v2.0/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
    body,
  });
  if (!res.ok) throw new Error(`refresh failed: ${res.status}`);
  const json = (await res.json()) as { access_token: string; refresh_token?: string; expires_in: number };
  return { accessToken: json.access_token, refreshToken: json.refresh_token ?? refreshToken, expiresInSeconds: json.expires_in };
}

export async function fetchMicrosoftProfile(accessToken: string): Promise<MsProfile> {
  const res = await fetch("https://graph.microsoft.com/v1.0/me", {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) throw new Error(`graph failed: ${res.status}`);
  const json = (await res.json()) as { id: string; displayName?: string; userPrincipalName?: string; mail?: string };
  return {
    id: json.id,
    name: json.displayName,
    email: json.mail ?? json.userPrincipalName ?? undefined,
  };
}

export async function fetchMinecraftProfile(accessToken: string): Promise<McProfile | null> {
  // 1. XBL auth
  const xblRes = await fetch("https://user.auth.xboxlive.com/user/authenticate", {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      Properties: { AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: `d=${accessToken}` },
      RelyingParty: "http://auth.xboxlive.com",
      TokenType: "JWT",
    }),
  });
  if (!xblRes.ok) throw new Error(`xbl failed: ${xblRes.status}`);
  const xbl = (await xblRes.json()) as { Token: string; DisplayClaims: { xui: { uhs: string }[] } };

  // 2. XSTS
  const xstsRes = await fetch("https://xsts.auth.xboxlive.com/xsts/authorize", {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      Properties: { SandboxId: "RETAIL", UserTokens: [xbl.Token] },
      RelyingParty: "rp://api.minecraftservices.com/",
      TokenType: "JWT",
    }),
  });
  if (!xstsRes.ok) {
    if (xstsRes.status === 401) return null; // no Xbox profile / child account
    throw new Error(`xsts failed: ${xstsRes.status}`);
  }
  const xsts = (await xstsRes.json()) as { Token: string; DisplayClaims: { xui: { uhs: string }[] } };
  const uhs = xsts.DisplayClaims.xui[0]?.uhs;
  if (!uhs) return null;

  // 3. Minecraft auth
  const mcRes = await fetch("https://api.minecraftservices.com/authentication/login_with_xbox", {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ identityToken: `XBL3.0 x=${uhs};${xsts.Token}` }),
  });
  if (!mcRes.ok) throw new Error(`mc auth failed: ${mcRes.status}`);
  const mc = (await mcRes.json()) as { access_token: string };

  // 4. Profile
  const profileRes = await fetch("https://api.minecraftservices.com/minecraft/profile", {
    headers: { authorization: `Bearer ${mc.access_token}` },
  });
  if (!profileRes.ok) return null; // account owns no Java profile
  const profile = (await profileRes.json()) as { id: string; name: string };
  return { id: profile.id, name: profile.name };
}