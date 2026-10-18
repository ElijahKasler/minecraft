/**
 * Types + constants shared between the web app, the API server, the Eagler
 * WebSocket bridge and the Java 1.8 protocol proxy.
 *
 * Anything here must stay platform-independent (no Node, no DOM).
 */

// ---------------------------------------------------------------------------
// Eagler WebSocket handshake packet types (HandshakePacketTypes.java)
// ---------------------------------------------------------------------------
export const EAG = {
  VERSION: 3, // Eagler protocol version (v3 baseline)
  PROTOCOL_CLIENT_VERSION: 0x01,
  PROTOCOL_SERVER_VERSION: 0x02,
  CLIENT_VERSION: 0x01, // alias used by the bridge codec
  SERVER_VERSION: 0x02,
  PROTOCOL_VERSION_MISMATCH: 0x03,
  CLIENT_REQUEST_LOGIN: 0x04,
  SERVER_ALLOW_LOGIN: 0x05,
  SERVER_DENY_LOGIN: 0x06,
  CLIENT_PROFILE_DATA: 0x07,
  CLIENT_FINISH_LOGIN: 0x08,
  SERVER_FINISH_LOGIN: 0x09,
  SERVER_REDIRECT_TO: 0x0a,
  CLIENT_AUTHENTICATE: 0x0b,
  CLIENT_AUTHENTICATE_RESPONSE: 0x0c,
  SERVER_AUTHENTICATE: 0x0d,
  SERVER_ERROR: 0xff,

  // auth methods
  AUTH_METHOD_NONE: 0x00,
  AUTH_METHOD_EAGLER_SHA256: 0x01,
  AUTH_METHOD_AUTHME_SHA256: 0x02,
  AUTH_METHOD_PLAINTEXT: 0xff,

  // error codes (HandshakePacketTypes.java)
  SERVER_ERROR_UNKNOWN_PACKET: 0x01,
  SERVER_ERROR_INVALID_PACKET: 0x02,
  SERVER_ERROR_WRONG_PACKET: 0x03,
  SERVER_ERROR_EXCESSIVE_PROFILE_DATA: 0x04,
  SERVER_ERROR_DUPLICATE_PROFILE_DATA: 0x05,
  SERVER_ERROR_RATELIMIT_BLOCKED: 0x06,
  SERVER_ERROR_RATELIMIT_LOCKED: 0x07,
  SERVER_ERROR_CUSTOM_MESSAGE: 0x08,
  SERVER_ERROR_AUTHENTICATION_REQUIRED: 0x09,

  // valid usernames per client (1-16 chars, avoids & decorators)
  USERNAME_RE: /^[a-zA-Z0-9_]{1,16}$/,
} as const;

// Eagler "universal" channel names used toward real Java servers
export const EAG_CHANNELS = {
  skin: { legacy: "EAG|Skins-1.8", modern: "eagler:skins-1-8" },
  cape: { legacy: "EAG|Capes-1.8", modern: "eagler:capes-1-8" },
  voice: { legacy: "EAG|Voice-1.8", modern: "eagler:voice-1-8" },
  updateCert: { legacy: "EAG|UpdateCert-1.8", modern: "eagler:update-cert-1-8" },
  fnaws: { legacy: "EAG|FNAWSEn-1.8", modern: "eagler:fnaws-1-8" },
  general: { legacy: "EAG|1.8", modern: "eagler:1-8" },
} as const;

/** 0xEE "injected" EAG message marker used by Eagler v5 (InjectedMessageController). */
export const EAG_INJECTED_PACKET_ID = 0xee;

// ---------------------------------------------------------------------------
// Vanilla Minecraft 1.8 protocol constants
// ---------------------------------------------------------------------------
export const MC = {
  PROTOCOL_VERSION: 47,
  LEGACY_PING_MARKER: 0xfe,
  compressionThreshold: 256 as const,
} as const;

export interface PlayerInfoDto {
  id: string;
  username: string;
}

export interface ServerInfoDto {
  id: string;
  name: string;
  description: string;
  address: string;
  port: number;
  onlineMode: boolean;
  iconUrl?: string | null;
  maxPlayers?: number | null;
  maintenance: boolean;
  playerCount: number | null;
}

export interface AuthUserDto {
  id: string;
  email: string;
  username: string;
  role: "user" | "admin";
  linkedMicrosoft: boolean;
  mcUsername?: string | null;
}

export interface PlaySessionInfo {
  ticket: string;
  bridgeUrl: string; // wss://... with ticket
  clientAssets: string;
}