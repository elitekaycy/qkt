// Finds the qkt executable: the `qkt.path` setting, then $QKT_HOME/bin, then PATH.
// Pure (no vscode import) so it is unit-testable with plain node.
const fs = require("fs");
const path = require("path");

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

function resolveQkt(configured, env, platform) {
  if (configured && configured.trim()) return configured.trim();
  const dirs = [];
  if (env.QKT_HOME) dirs.push(path.join(env.QKT_HOME, "bin"), env.QKT_HOME);
  const sep = platform === "win32" ? ";" : ":";
  for (const d of (env.PATH || env.Path || "").split(sep)) if (d) dirs.push(d);
  for (const dir of dirs) {
    for (const c of candidatesFor(dir, platform, env)) if (isFile(c)) return c;
  }
  return undefined;
}

module.exports = { resolveQkt };
