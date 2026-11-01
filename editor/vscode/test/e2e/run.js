// Launches a real VS Code with this extension and runs suite.js inside it.
// Needs `qkt` on PATH (or QKT_HOME) and, on Linux CI, a display (xvfb-run).
const path = require("path");
const { runTests } = require("@vscode/test-electron");

runTests({
  extensionDevelopmentPath: path.resolve(__dirname, "../.."),
  extensionTestsPath: path.resolve(__dirname, "suite.js"),
  launchArgs: [path.resolve(__dirname, "../../../nvim/test"), "--disable-extensions"],
}).catch((err) => {
  console.error(err);
  process.exit(1);
});
