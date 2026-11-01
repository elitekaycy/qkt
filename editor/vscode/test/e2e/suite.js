// Runs inside VS Code: opens a .qkt file and checks that the bundled client reached `qkt lsp`
// (a diagnostic for a broken indicator, field completion after `btc.`, hover on a keyword).
const assert = require("assert");
const vscode = require("vscode");

const SOURCE = [
  "STRATEGY s VERSION 1",
  "",
  "SYMBOLS",
  "    btc = BACKTEST:BTCUSDT EVERY 1m",
  "",
  "RULES",
  "    WHEN btc.close > 100",
  "    THEN BUY btc SIZING 1",
  "",
].join("\n");

async function waitFor(what, fn, timeoutMs = 60000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 250));
  }
}

async function run() {
  const broken = await vscode.workspace.openTextDocument({
    language: "qkt",
    content: SOURCE.replace("btc.close > 100", "notanindicator(btc.close, 3) > 100"),
  });
  await vscode.window.showTextDocument(broken);
  const diags = await waitFor("diagnostics", () => {
    const d = vscode.languages.getDiagnostics(broken.uri);
    return d.length ? d : undefined;
  });
  assert.ok(diags.some((d) => d.range.start.line === 6), `expected a diagnostic on line 7, got ${JSON.stringify(diags)}`);

  const doc = await vscode.workspace.openTextDocument({ language: "qkt", content: SOURCE });
  await vscode.window.showTextDocument(doc);
  assert.strictEqual(doc.languageId, "qkt");
  const pos = new vscode.Position(6, 9);
  const completion = await waitFor("completion", async () => {
    const list = await vscode.commands.executeCommand("vscode.executeCompletionItemProvider", doc.uri, pos);
    const labels = list.items.map((i) => (typeof i.label === "string" ? i.label : i.label.label));
    return labels.includes("ema") ? labels : undefined;
  });
  assert.ok(completion.includes("btc"), "expected the declared stream alias in completion");

  const hovers = await vscode.commands.executeCommand("vscode.executeHoverProvider", doc.uri, new vscode.Position(0, 2));
  assert.ok(hovers.length > 0, "expected hover on STRATEGY");
}

module.exports = { run };
