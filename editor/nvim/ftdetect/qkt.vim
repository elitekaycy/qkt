au BufRead,BufNewFile *.qkt setfiletype qkt
" A `-- vim: set ft=haskell:` modeline gives Haskell colours wherever this plugin is not
" installed; where it is, take the buffer back so the real qkt syntax and language server win.
au FileType haskell nested if expand('<afile>:e') ==? 'qkt' | setlocal filetype=qkt | endif
au Syntax haskell nested if expand('<afile>:e') ==? 'qkt' | setlocal syntax=qkt | endif
