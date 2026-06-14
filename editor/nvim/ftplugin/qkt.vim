if exists("b:did_ftplugin") | finish | endif
let b:did_ftplugin = 1

setlocal commentstring=--\ %s
setlocal comments=:--,b:#,s1:/*,mb:*,ex:*/
setlocal suffixesadd+=.qkt
setlocal iskeyword+=_

" Language server: live diagnostics, completion, and hover, served by the `qkt`
" CLI itself (`qkt lsp`). Autostarts on Neovim 0.8+ when `qkt` is on PATH; the
" client is keyed on the project root so all .qkt buffers share one server.
" Opt out with `let g:qkt_no_lsp = 1` (e.g. if you configure qkt via lspconfig).
if has('nvim-0.8') && !get(g:, 'qkt_no_lsp', 0) && executable('qkt')
  lua vim.lsp.start({ name = 'qkt', cmd = { 'qkt', 'lsp' }, root_dir = (function() local m = vim.fs.find({ 'qkt.config.yaml', '.git' }, { upward = true, path = vim.fn.expand('%:p:h') }); return m[1] and vim.fs.dirname(m[1]) or vim.fn.getcwd() end)() })
endif
