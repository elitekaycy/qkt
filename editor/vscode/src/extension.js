// qkt language client: starts `qkt lsp` over stdio for .qkt files, so completion, suggestions,
// hover and live diagnostics work as soon as the extension is installed. Highlighting and snippets
// are declarative (package.json) and keep working when the qkt binary cannot be found.
const vscode = require("vscode");
const { LanguageClient } = require("vscode-languageclient/node");
const { resolveQkt } = require("./resolve-qkt");

let client;

async function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand("qkt.restartLanguageServer", async () => {
      await stop();
      await start(context);
    }),
    vscode.workspace.onDidChangeConfiguration(async (e) => {
      if (e.affectsConfiguration("qkt.path") || e.affectsConfiguration("qkt.languageServer")) {
        await stop();
        await start(context);
      }
    }),
  );
  await start(context);
}

async function start(context) {
  const config = vscode.workspace.getConfiguration("qkt");
  if (!config.get("languageServer.enabled", true)) return;
  const command = resolveQkt(config.get("path", ""), process.env, process.platform);
  if (!command) {
    const choice = await vscode.window.showWarningMessage(
      "qkt: the `qkt` command was not found, so completion and diagnostics are off. " +
        "Install qkt or set `qkt.path`. Highlighting and snippets still work.",
      "Install qkt",
      "Set qkt.path",
    );
    if (choice === "Install qkt") {
      vscode.env.openExternal(vscode.Uri.parse("https://github.com/elitekaycy/qkt/releases/latest"));
    } else if (choice === "Set qkt.path") {
      vscode.commands.executeCommand("workbench.action.openSettings", "qkt.path");
    }
    return;
  }
  // Node refuses to spawn .cmd/.bat files without a shell (CVE-2024-27980).
  const shell = /\.(cmd|bat)$/i.test(command);
  const serverOptions = {
    command: shell ? `"${command}"` : command,
    args: ["lsp"],
    options: { shell },
  };
  const clientOptions = {
    documentSelector: [
      { scheme: "file", language: "qkt" },
      { scheme: "untitled", language: "qkt" },
    ],
  };
  client = new LanguageClient("qkt", "qkt language server", serverOptions, clientOptions);
  context.subscriptions.push(client);
  try {
    await client.start();
  } catch (err) {
    vscode.window.showErrorMessage(`qkt: could not start \`${command} lsp\`: ${err.message || err}`);
    client = undefined;
  }
}

async function stop() {
  if (!client) return;
  const c = client;
  client = undefined;
  try {
    await c.stop();
  } catch (_) {
    // already stopped
  }
}

function deactivate() {
  return stop();
}

module.exports = { activate, deactivate };
