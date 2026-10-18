// global.MWC_PROXY_CONFIG_OVERRIDES — mount as env.js next to mwc-proxy
// (see mwc-proxy README "Config overrides"). Tuned for Eaglercraft+MWC:
// - Eaglercraft browsers open 1 socket per server; keep per-IP cap generous.
// - Allow both Eaglercraft site origin and web-client origin (or '*' for LAN test).
// - Keep metrics on; keep auth rate limits default (MSA is bursty).

global.MWC_PROXY_CONFIG_OVERRIDES = {
  // Where the WS bridge lives; must match js/eaglerMwcBridge.js urlRoot().
  urlRoot: '/api/vm/net',

  // Eaglercraft shell + web-client-next origins. '*' is fine for home/LAN;
  // lock to your domains in production.
  allowOrigin: '*',
  allowOriginApp: true,

  // Eaglercraft auto-touch reconnects + shared-world relays can fan out.
  maxConnectionsPerIp: 10,

  // Example: only allow your servers (recommended for public proxies).
  // to: [{ host: 'play.example.com', port: 25565 }, { host: 'localhost' }],

  // Auth (prismarine-auth MSA) limits: defaults 2/IP, 5 total are safe.
  authMaxConcurrentPerIp: 2,
  authMaxConcurrentTotal: 5,
  metricsEndpoint: true,
  debugUrlEndpoint: true,

  signal: {
    // serverUrl: 'wss://signal.mcraft.fun',
    // description: 'Eaglercraft+MWC relay (1.8 offline + web 1.8-1.21)',
    // domain: 'mc.example.com'
  }
};
