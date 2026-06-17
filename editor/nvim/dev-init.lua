-- Curated Neovim experience for the qkt:dev container.
--
-- The bundled ftplugin (editor/nvim/ftplugin/qkt.vim) already autostarts the qkt
-- language server (`qkt lsp`) for .qkt buffers. This file layers an
-- editor-friendly completion experience on top: as-you-type suggestions and
-- snippet placeholder jumps. It ships in the dev image only (copied into
-- ~/.config/nvim/plugin/), so the shared plugin stays unopinionated about
-- completion and never fights a user's own setup.

local group = vim.api.nvim_create_augroup("qkt_dev", { clear = true })

vim.api.nvim_create_autocmd("LspAttach", {
  group = group,
  callback = function(ev)
    local client = vim.lsp.get_client_by_id(ev.data.client_id)
    if not client or client.name ~= "qkt" then
      return
    end

    -- Show the menu even for a single match, and highlight without inserting
    -- until the user confirms (Enter / Ctrl-y), so suggestions are unobtrusive.
    vim.bo[ev.buf].completeopt = "menu,menuone,noinsert"

    -- The server only advertises `.` and `(` as trigger characters, so the
    -- built-in autotrigger never opens the menu while you type a keyword or
    -- snippet prefix. Enable it for the trigger chars...
    if vim.lsp.completion and vim.lsp.completion.enable then
      vim.lsp.completion.enable(true, ev.data.client_id, ev.buf, { autotrigger = true })
    end

    -- ...then also open the menu as you type word characters.
    vim.api.nvim_create_autocmd("TextChangedI", {
      group = group,
      buffer = ev.buf,
      callback = function()
        if vim.fn.pumvisible() == 1 then
          return
        end
        if not (vim.lsp.completion and vim.lsp.completion.get) then
          return
        end
        local col = vim.fn.col(".") - 1
        if col < 1 then
          return
        end
        local prev = vim.api.nvim_get_current_line():sub(col, col)
        if prev:match("[%w_]") then
          vim.lsp.completion.get()
        end
      end,
    })
  end,
})

-- Tab / Shift-Tab: jump between snippet placeholders, else navigate an open
-- completion menu, else insert a literal Tab. Enter (or Ctrl-y) confirms the
-- highlighted item and expands a snippet.
vim.keymap.set({ "i", "s" }, "<Tab>", function()
  if vim.snippet and vim.snippet.active({ direction = 1 }) then
    vim.snippet.jump(1)
    return ""
  elseif vim.fn.pumvisible() == 1 then
    return "<C-n>"
  end
  return "<Tab>"
end, { expr = true, replace_keycodes = true })

vim.keymap.set({ "i", "s" }, "<S-Tab>", function()
  if vim.snippet and vim.snippet.active({ direction = -1 }) then
    vim.snippet.jump(-1)
    return ""
  elseif vim.fn.pumvisible() == 1 then
    return "<C-p>"
  end
  return "<S-Tab>"
end, { expr = true, replace_keycodes = true })
