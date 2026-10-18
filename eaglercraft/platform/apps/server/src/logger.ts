export type Level = "debug" | "info" | "warn" | "error";

const ORDER: Record<Level, number> = { debug: 0, info: 1, warn: 2, error: 3 };

class Logger {
  constructor(private level: Level = (process.env.LOG_LEVEL as Level) || "info") {}

  private stamp(level: Level, args: unknown[]) {
    if (ORDER[level] < ORDER[this.level]) return;
    const parts = [new Date().toISOString(), level.toUpperCase().padEnd(5)].join(" ");
    console[level === "debug" ? "log" : level](`[${parts}]`, ...args);
  }

  debug(...args: unknown[]) {
    this.stamp("debug", args);
  }
  info(...args: unknown[]) {
    this.stamp("info", args);
  }
  warn(...args: unknown[]) {
    this.stamp("warn", args);
  }
  error(...args: unknown[]) {
    this.stamp("error", args);
  }
}

export const log = new Logger();