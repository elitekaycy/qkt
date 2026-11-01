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
" The server runs in a small JVM (capped heap, one GC and one JIT thread); override the
" flags with `let g:qkt_lsp_jvm_options = '...'`.
let g:qkt_lsp_jvm_options = get(g:, 'qkt_lsp_jvm_options', '-Xms16m -Xmx256m -Xss512k -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ParallelGCThreads=1')
if has('nvim-0.8') && !get(g:, 'qkt_no_lsp', 0) && executable('qkt')
  lua vim.lsp.start({ name = 'qkt', cmd = { 'qkt', 'lsp' }, cmd_env = { JAVA_TOOL_OPTIONS = vim.trim((vim.env.JAVA_TOOL_OPTIONS or '') .. ' ' .. vim.g.qkt_lsp_jvm_options) }, root_dir = (function() local m = vim.fs.find({ 'qkt.config.yaml', '.git' }, { upward = true, path = vim.fn.expand('%:p:h') }); return m[1] and vim.fs.dirname(m[1]) or vim.fn.getcwd() end)() })
endif
