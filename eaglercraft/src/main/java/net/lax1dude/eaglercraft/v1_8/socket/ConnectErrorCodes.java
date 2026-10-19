package net.lax1dude.eaglercraft.v1_8.socket;

/**
 * Human-readable connect error codes for every join path in this client.
 *
 * <p>Eagler-protocol servers report {@code EAG-001}..{@code EAG-009} (the raw
 * {@code SERVER_ERROR_*} id from the handshake). Vanilla-via-proxy reports
 * {@code MWC-*} (proxy/token/socket layer) and {@code MC-*} (vanilla login and
 * TCP framing). Each entry carries cause + fix so disconnect screens are
 * actionable instead of "Server Error Code 5".</p>
 */
public class ConnectErrorCodes {

	private ConnectErrorCodes() {
	}

	/**
	 * Detailed text for an Eaglercraft handshake server error.
	 *
	 * @param errCode raw SERVER_ERROR_* id
	 * @param serverMsg server-supplied message (may be empty)
	 */
	public static String describeEaglerError(int errCode, String serverMsg) {
		String msg = serverMsg == null ? "" : serverMsg.trim();
		String suffix = msg.isEmpty() ? "" : "\nServer said: " + msg;
		switch (errCode) {
			case HandshakePacketTypes.SERVER_ERROR_UNKNOWN_PACKET:
				return "EAG-001 Unknown packet.\n"
						+ "Your client sent something the server does not know.\n"
						+ "Fix: update the client, then retry." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_INVALID_PACKET:
				return "EAG-002 Invalid packet.\n"
						+ "Login data arrived corrupted (bad wifi/VPN/proxy).\n"
						+ "Fix: retry; disable VPN; try another relay." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_WRONG_PACKET:
				return "EAG-003 Wrong packet order.\n"
						+ "Handshake arrived out of order, usually a double-clicked Join.\n"
						+ "Fix: wait 10s, click Join once." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_EXCESSIVE_PROFILE_DATA:
				return "EAG-004 Profile data too large.\n"
						+ "Skin/cape packet exceeds the server limit.\n"
						+ "Fix: use a smaller/default skin, then retry." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_DUPLICATE_PROFILE_DATA:
				return "EAG-005 Duplicate login session.\n"
						+ "The server already has your login open (double Join,\n"
						+ "stale tab, or resent skin data).\n"
						+ "Fix: wait ~10s, join once from Multiplayer.\n"
						+ "Still failing? Close other tabs, restart, reset skin to default."
						+ suffix;
			case HandshakePacketTypes.SERVER_ERROR_RATELIMIT_BLOCKED:
				return "EAG-006 Rate limited (temporary).\n"
						+ "Too many joins in a short time.\n"
						+ "Fix: wait a minute, then retry." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_RATELIMIT_LOCKED:
				return "EAG-007 Rate limited (locked out).\n"
						+ "The server locked this address after repeated joins.\n"
						+ "Fix: wait it out or contact the server owner." + suffix;
			case HandshakePacketTypes.SERVER_ERROR_CUSTOM_MESSAGE:
				return msg;
			case HandshakePacketTypes.SERVER_ERROR_AUTHENTICATION_REQUIRED:
				return "EAG-009 Login required.\n"
						+ "This server needs a password/account.\n"
						+ "Fix: enter credentials on the auth screen." + suffix;
			default:
				return "EAG-000 Server error " + errCode + ".\n"
						+ "Unknown handshake failure.\n"
						+ "Fix: retry; update client and server." + suffix;
		}
	}

	/**
	 * One-line code prefix for MWC/vanilla failures, e.g.
	 * {@code [MWC-HTTP-429] Too many connections...}.
	 */
	public static String mwc(String code, String detail) {
		if (detail == null || detail.isEmpty()) {
			return "[" + code + "]";
		}
		return "[" + code + "] " + detail;
	}

	/** Token/HTTP/socket layer codes. */
	public static final class Token {
		private Token() {
		}

		public static final String BAD_URL = "MWC-TOKEN-BADURL";
		public static final String NO_SERVER = "MWC-TOKEN-NOSERVER";
		public static final String UNREACHABLE = "MWC-TOKEN-NET";
		public static final String TIMEOUT = "MWC-TOKEN-TIMEOUT";
		public static final String DNS = "MWC-TOKEN-DNS";
		public static final String REFUSED = "MWC-TOKEN-REFUSED";
		public static final String TLS = "MWC-TOKEN-TLS";
		public static final String SOCKET = "MWC-SOCKET";
		public static final String RATE_LIMITED = "MWC-HTTP-429";
		public static final String FORBIDDEN = "MWC-HTTP-403";
		public static final String HTTP = "MWC-HTTP";
		public static final String BAD_RESPONSE = "MWC-TOKEN-BADRESP";
	}

	/** Vanilla login/play layer codes. */
	public static final class Play {
		private Play() {
		}

		public static final String KICK = "MC-LOGIN-KICK";
		public static final String ONLINE_MODE = "MC-ONLINE-MODE";
		public static final String COMPRESSION = "MC-COMPRESS";
		public static final String FRAME = "MC-FRAME";
		public static final String ENCRYPTION = "MC-ENCRYPT";
		public static final String AUTH = "MC-AUTH";
		public static final String PROXY_SHUTDOWN = "MC-PROXY-DOWN";
		public static final String TIMEOUT = "MC-TIMEOUT";
		public static final String CLOSED = "MC-CLOSED";
	}

	/**
	 * Classify a transport-level failure (exception string from
	 * {@link net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime#postJSON}'s
	 * {@code -1\n<detail>} marker, or a browser "status 0" response) into a
	 * {@link Token} code.
	 *
	 * @param detail e.g. {@code "UnknownHostException: http"} or
	 *               {@code "ConnectException: Connection refused"}
	 */
	public static String classifyTransportError(String detail) {
		if (detail == null || detail.isEmpty()) {
			return Token.UNREACHABLE;
		}
		String d = detail.toLowerCase();
		if (d.contains("unknownhost") || d.contains("no such host") || d.contains("name or service not known")) {
			return Token.DNS;
		}
		if (d.contains("refused")) {
			return Token.REFUSED;
		}
		if (d.contains("timeout") || d.contains("timed out")) {
			return Token.TIMEOUT;
		}
		if (d.contains("ssl") || d.contains("certificate") || d.contains("handshake")) {
			return Token.TLS;
		}
		if (d.contains("broken pipe") || d.contains("connection reset") || d.contains("closed")) {
			return Token.SOCKET;
		}
		return Token.UNREACHABLE;
	}

	/**
	 * Full cause+fix disconnect text for a proxy that could not be reached at
	 * the HTTP layer.
	 *
	 * @param proxyBase normalized proxy base actually tried, e.g. {@code http://localhost:2344}
	 * @param code      a {@link Token} code, usually from {@link #classifyTransportError}
	 * @param cause     transport detail, may be null
	 */
	public static String describeUnreachableProxy(String proxyBase, String code, String cause) {
		String why = (cause == null || cause.isEmpty()) ? "no response (offline mode, request blocked, or proxy not running)"
				: cause;
		String fix;
		if (Token.DNS.equals(code)) {
			fix = "Fix: the proxy address does not resolve.\nOpen Proxy Settings and use a full address, e.g. http://localhost:2344";
		} else if (Token.REFUSED.equals(code)) {
			fix = "Fix: nothing is listening on that host/port.\nStart the proxy, or correct its port in Proxy Settings.";
		} else if (Token.TLS.equals(code)) {
			fix = "Fix: TLS/certificate problem.\nTry http:// instead of https://, or fix the proxy certificate.";
		} else if (Token.TIMEOUT.equals(code)) {
			fix = "Fix: the proxy accepted but never answered.\nCheck the proxy process or your network.";
		} else {
			fix = "Fix: start the proxy, or correct its address in Proxy Settings (e.g. http://localhost:2344).";
		}
		return mwc(code, "Could not reach proxy at " + proxyBase + ".\nCause: " + why + "\n" + fix);
	}
}
