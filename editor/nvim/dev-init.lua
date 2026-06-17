-- Curated Neovim experience for the qkt:dev container.
--
-- The bundled ftplugin (editor/nvim/ftplugin/qkt.vim) already autostarts the qkt
-- language server (`qkt lsp`) for .qkt buffers. This file layers an
-- editor-friendly completion experience on top: as-you-type suggestions and
-- snippet placeholder jumps. It ships in the dev image only (copied into
-- ~/.config/nvim/plugin/), so the shared plugin stays unopinionated about
-- completion and never fights a user's own setup.

-- Turn on the language server's as-you-type completion popup for qkt buffers.
-- Requires the built-in LSP completion API (Neovim 0.11+); a no-op on older
-- builds, which fall back to manual <C-x><C-o> omni-completion.
vim.api.nvim_create_autocmd("LspAttach", {
  callback = function(ev)
    local client = vim.lsp.get_client_by_id(ev.data.client_id)
    if not client or client.name ~= "qkt" then
      return
    end
    local completion = vim.lsp.completion
    if completion and completion.enable then
      completion.enable(true, ev.data.client_id, ev.buf, { autotrigger = true })
    end
  end,
})

-- Jump between snippet placeholders (e.g. the fields of a STRATEGY skeleton)
-- with Tab / Shift-Tab; fall through to a literal Tab when no snippet is active.
vim.keymap.set({ "i", "s" }, "<Tab>", function()
  if vim.snippet and vim.snippet.active({ direction = 1 }) then
    vim.snippet.jump(1)
    return ""
  end
  return "<Tab>"
end, { expr = true, replace_keycodes = true })

vim.keymap.set({ "i", "s" }, "<S-Tab>", function()
  if vim.snippet and vim.snippet.active({ direction = -1 }) then
    vim.snippet.jump(-1)
    return ""
  end
  return "<S-Tab>"
end, { expr = true, replace_keycodes = true })
