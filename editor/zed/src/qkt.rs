//! Zed extension for qkt: starts `qkt lsp` for `.qkt` files, so completion, suggestions,
//! hover and diagnostics come from the same server every other editor uses.
use zed_extension_api::{self as zed, LanguageServerId, Result};

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
        // The qkt launcher starts `qkt lsp` in a small JVM itself; nothing to configure here.
        Ok(zed::Command {
            command,
            args: vec!["lsp".to_string()],
            env: worktree.shell_env(),
        })
    }
}

zed::register_extension!(QktExtension);
