export interface LaunchOptions {
  bridgeUrl: string;
  serverName: string;
  container: string;
}

interface OptsWithRefs {
  demoMode: boolean;
  container: string;
  assetsURI: string;
  localesURI: string;
  worldsDB: string;
  logInvalidCerts: boolean;
  crashOnUncaughtExceptions: boolean;
  servers: { addr: string; name: string }[];
  relays: unknown[];
  joinServer: string;
}

declare global {
  interface Window {
    eaglercraftXOpts?: OptsWithRefs;
    main?: () => void;
  }
}

/**
 * Boot the prebuilt EaglercraftX client (classes.js served under /client/)
 * into the given container element. Mirrors the launcher contract used by
 * the built-in fallback page in the server's static handler.
 */
export function bootClient(opts: LaunchOptions): () => void {
  const el = document.getElementById(opts.container);
  if (!el) return () => undefined;
  el.style.display = "block";
  el.style.height = "100vh";
  window.scrollTo(0, 0);

  const holder: OptsWithRefs = {
    demoMode: false,
    container: opts.container,
    assetsURI: "/client/assets.epk",
    localesURI: "/client/lang/",
    worldsDB: "worlds",
    logInvalidCerts: true,
    crashOnUncaughtExceptions: true,
    servers: [{ addr: opts.bridgeUrl, name: opts.serverName }],
    relays: [],
    joinServer: opts.bridgeUrl,
  };
  window.eaglercraftXOpts = holder;

  const script = document.createElement("script");
  script.src = "/client/classes.js";
  script.async = true;
  script.onload = () => window.main?.();
  document.body.appendChild(script);

  return () => {
    void holder;
    el.style.display = "none";
  };
}