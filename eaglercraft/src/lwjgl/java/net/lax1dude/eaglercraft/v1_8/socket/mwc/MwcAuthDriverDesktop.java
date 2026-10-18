package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/** Desktop implementation of {@link MwcAuthDriver} using JCE for crypto. */
public final class MwcAuthDriverDesktop implements MwcAuthDriver {

	private static final MwcAuthDriverDesktop INSTANCE = new MwcAuthDriverDesktop();

	public static MwcAuthDriverDesktop getInstance() {
		return INSTANCE;
	}

	private MwcAuthDriverDesktop() {
	}

	@Override
	public void begin(MwcAuthState state, AuthCallback callback) {
		try {
			// Validate inputs
			if (state.publicKey == null || state.publicKey.length == 0) {
				throw new IllegalArgumentException("Server public key is empty");
			}
			if (state.verifyToken == null || state.verifyToken.length == 0) {
				throw new IllegalArgumentException("Server verify token is empty");
			}

			// Generate random 16-byte shared secret
			state.sharedSecret = MwcCrypto.get().newSharedSecret();

			// Encrypt shared secret with server's public key (RSA/ECB/PKCS1Padding)
			state.encryptedSharedSecret = MwcCrypto.get().rsaEncrypt(state.publicKey, state.sharedSecret);

			// Encrypt verify token with server's public key
			state.encryptedVerifyToken = MwcCrypto.get().rsaEncrypt(state.publicKey, state.verifyToken);

			// TODO: Real Microsoft OAuth2 device-code flow + yggdrasil session join.
			// For now, simulate success so the handshake reaches Login Success.
			// Online-mode servers will reject dummy credentials — use offline mode
			// (server.properties: online-mode=false) until real auth is implemented.
			state.accessToken = "dummy_access_token";
			state.selectedProfileId = "00000000-0000-0000-0000-000000000000";
			state.complete = true;

			callback.onSuccess(state);
		} catch (Exception e) {
			// Walk the cause chain to show the real underlying error
			StringBuilder sb = new StringBuilder("Desktop auth failed: ");
			Throwable t = e;
			while (t != null) {
				sb.append(t.getClass().getSimpleName());
				if (t.getMessage() != null) {
					sb.append(": ").append(t.getMessage());
				}
				sb.append(" <- ");
				t = t.getCause();
			}
			sb.setLength(sb.length() - 4); // remove trailing " <- "
			callback.onFailure(state, sb.toString());
		}
	}
}
