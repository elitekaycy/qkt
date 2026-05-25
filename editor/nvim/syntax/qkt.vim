" Vim syntax file for qkt — trading strategy DSL
" Mirrors editor/textmate/qkt.tmLanguage.json. When that file changes, mirror
" the corresponding keyword bucket here.

if exists("b:current_syntax") | finish | endif

" Section keywords — file-level structure
syn keyword qktSection STRATEGY VERSION DEFAULTS SYMBOLS LET RULES PORTFOLIO IMPORT

" Flow keywords — rule structure, control flow
syn keyword qktFlow WHEN THEN FOR EACH IN DO AS RUN HOLD CASE ELSE END SINCE

" Action verbs, sizing, order/bracket vocabulary, and other domain keywords
syn keyword qktKeyword BUY SELL CLOSE_ALL CLOSE CANCEL_ALL CANCEL FLATTEN
syn keyword qktKeyword LOG WARN ERROR DEBUG
syn keyword qktKeyword STACK_AT STACK SPACING WITHIN MFE
syn keyword qktKeyword MARKET LIMIT STOP_LOSS STOP TRAILING BRACKET
syn keyword qktKeyword OCO_ENTRY OCO ORDER_TYPE
syn keyword qktKeyword TAKE_PROFIT TAKE PROFIT LOSS RR AT BY
syn keyword qktKeyword PCT SIZING RISK USD OF EQUITY BALANCE
syn keyword qktKeyword POSITION_AVG_PRICE POSITION OPEN_ORDERS ENTRY_QTY
syn keyword qktKeyword TIF GTC IOC FOK DAY GTD
syn keyword qktKeyword EVERY WARMUP BARS
syn keyword qktKeyword CROSSES ABOVE BELOW BETWEEN
syn keyword qktKeyword OPEN MAX MIN MEAN SUM ACCOUNT SYMBOL NOW

" Word-form operators
syn keyword qktOperator AND OR NOT IS NULL

" Symbol-form operators
syn match   qktOperator "==\|!=\|<=\|>=\|<\|>\|+\|-\|\*\|/\|%"

" Booleans
syn keyword qktBoolean TRUE FALSE

" Numeric durations (must come before plain numbers so they win)
syn match   qktDuration "\<\d\+[smhd]\>"

" Numeric literals (integers, decimals, scientific)
syn match   qktNumber "\<\d\+\(\.\d\+\)\?\([eE][+-]\?\d\+\)\?\>"

" Broker prefix in BROKER:SYMBOL
syn match   qktBroker "\<[A-Z][A-Z0-9_]*:"

" Strings — both single and double quoted, with backslash escapes
syn region  qktString start=+"+ skip=+\\.+ end=+"+
syn region  qktString start=+'+ skip=+\\.+ end=+'+

" Comments — line (--, #) and block (/* ... */)
syn match   qktComment "--.*$" contains=@Spell
syn match   qktComment "#.*$" contains=@Spell
syn region  qktComment start="/\*" end="\*/" contains=@Spell

" Link our groups to standard highlight groups so colorschemes pick them up
hi def link qktSection   PreProc
hi def link qktFlow      Conditional
hi def link qktKeyword   Keyword
hi def link qktOperator  Operator
hi def link qktBoolean   Boolean
hi def link qktDuration  Number
hi def link qktNumber    Number
hi def link qktBroker    Type
hi def link qktString    String
hi def link qktComment   Comment

let b:current_syntax = "qkt"
