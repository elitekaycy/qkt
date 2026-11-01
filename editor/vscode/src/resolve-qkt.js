// Finds the qkt executable: the `qkt.path` setting, then $QKT_HOME/bin, then PATH, then the `qkt-path`
// file that `qkt editor install` writes, which covers a qkt that is on no path at all.
// Pure (no vscode import) so it is unit-testable with plain node.
const fs = require("fs");
const path = require("path");
const os = require("os");

function candidatesFor(dir, platform, env) {
  if (platform !== "win32") return [path.join(dir, "qkt")];
  const exts = (env.PATHEXT || ".EXE;.CMD;.BAT").split(";").filter(Boolean);
  return exts.map((e) => path.join(dir, "qkt" + e.toLowerCase()));
}

function isFile(p) {
  try {
    return fs.statSync(p).isFile();
  } catch (_) {
    return false;
  }
}

// Mirrors qkt's own config directory, where `qkt editor install` records the launcher.
function recordedPathFile(env, platform, home) {
  if (platform === "win32" && env.APPDATA) return path.join(env.APPDATA, "qkt", "qkt-path");
  if (env.XDG_CONFIG_HOME) return path.join(env.XDG_CONFIG_HOME, "qkt", "qkt-path");
  return path.join(home, ".config", "qkt", "qkt-path");
}

function recordedQkt(env, platform, home) {
  try {
    const recorded = fs.readFileSync(recordedPathFile(env, platform, home), "utf8").trim();
    return isFile(recorded) ? recorded : undefined;
  } catch (_) {
    return undefined;
  }
}

function resolveQkt(configured, env, platform, home = os.homedir()) {
  if (configured && configured.trim()) return configured.trim();
  const dirs = [];
  if (env.QKT_HOME) dirs.push(path.join(env.QKT_HOME, "bin"), env.QKT_HOME);
  const sep = platform === "win32" ? ";" : ":";
  for (const d of (env.PATH || env.Path || "").split(sep)) if (d) dirs.push(d);
  for (const dir of dirs) {
    for (const c of candidatesFor(dir, platform, env)) if (isFile(c)) return c;
  }
  return recordedQkt(env, platform, home);
}

module.exports = { resolveQkt };
