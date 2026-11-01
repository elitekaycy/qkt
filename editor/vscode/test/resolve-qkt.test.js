const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { resolveQkt } = require("../src/resolve-qkt");

function tempDirWith(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "qkt-resolve-"));
  fs.writeFileSync(path.join(dir, name), "");
  return dir;
}

test("the qkt.path setting wins over everything else", () => {
  const dir = tempDirWith("qkt");
  assert.strictEqual(resolveQkt("/opt/qkt/bin/qkt", { PATH: dir }, "linux"), "/opt/qkt/bin/qkt");
});

test("QKT_HOME/bin is searched before PATH", () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "qkt-home-"));
  fs.mkdirSync(path.join(home, "bin"));
  fs.writeFileSync(path.join(home, "bin", "qkt"), "");
  const onPath = tempDirWith("qkt");
  assert.strictEqual(resolveQkt("", { QKT_HOME: home, PATH: onPath }, "linux"), path.join(home, "bin", "qkt"));
});

test("finds qkt on PATH", () => {
  const dir = tempDirWith("qkt");
  assert.strictEqual(resolveQkt("", { PATH: `/nonexistent:${dir}` }, "linux"), path.join(dir, "qkt"));
});

test("on Windows finds qkt.exe or qkt.cmd through PATHEXT, not the extensionless name", () => {
  const dir = tempDirWith("qkt.cmd");
  fs.writeFileSync(path.join(dir, "qkt"), "");
  const found = resolveQkt("", { Path: dir, PATHEXT: ".EXE;.CMD" }, "win32");
  assert.strictEqual(found, path.join(dir, "qkt.cmd"));
});

test("returns undefined when qkt is nowhere", () => {
  assert.strictEqual(resolveQkt("", { PATH: "/nonexistent" }, "linux"), undefined);
});
