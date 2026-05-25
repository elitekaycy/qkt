if exists("b:did_ftplugin") | finish | endif
let b:did_ftplugin = 1

setlocal commentstring=--\ %s
setlocal comments=:--,b:#,s1:/*,mb:*,ex:*/
setlocal suffixesadd+=.qkt
setlocal iskeyword+=_
