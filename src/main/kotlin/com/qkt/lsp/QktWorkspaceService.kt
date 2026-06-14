package com.qkt.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

/**
 * qkt has no workspace-level features yet (no settings, no file watching). LSP4J still
 * requires a workspace service, so these are the mandatory no-op handlers.
 */
class QktWorkspaceService : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
}
