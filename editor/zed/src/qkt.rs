//! Zed extension for qkt: starts `qkt lsp` for `.qkt` files, so completion, suggestions,
//! hover and diagnostics come from the same server every other editor uses.
use zed_extension_api::{self as zed, LanguageServerId, Result};

/// The language server only parses small files, so it runs in a small, quiet JVM.
const LEAN_JVM: &str =
    "-Xms16m -Xmx256m -Xss512k -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ParallelGCThreads=1";

struct QktExtension;

impl zed::Extension for QktExtension {
    fn new() -> Self {
        QktExtension
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let command = worktree.which("qkt").ok_or_else(|| {
            "qkt was not found on PATH. Install it from https://github.com/elitekaycy/qkt/releases/latest"
                .to_string()
        })?;
        let mut env = worktree.shell_env();
        let jvm = match env.iter().find(|(k, _)| k == "JAVA_TOOL_OPTIONS") {
            Some((_, existing)) => format!("{existing} {LEAN_JVM}"),
            None => LEAN_JVM.to_string(),
        };
        env.retain(|(k, _)| k != "JAVA_TOOL_OPTIONS");
        env.push(("JAVA_TOOL_OPTIONS".to_string(), jvm));
        Ok(zed::Command {
            command,
            args: vec!["lsp".to_string()],
            env,
        })
    }
}

zed::register_extension!(QktExtension);
