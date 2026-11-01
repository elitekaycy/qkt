#include "tree_sitter/parser.h"

#if defined(__GNUC__) || defined(__clang__)
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#endif

#define LANGUAGE_VERSION 14
#define STATE_COUNT 26
#define LARGE_STATE_COUNT 25
#define SYMBOL_COUNT 209
#define ALIAS_COUNT 0
#define TOKEN_COUNT 187
#define EXTERNAL_TOKEN_COUNT 0
#define FIELD_COUNT 1
#define MAX_ALIAS_SEQUENCE_LENGTH 2
#define PRODUCTION_ID_COUNT 3

enum ts_symbol_identifiers {
  sym_identifier = 1,
  aux_sym_keyword_section_token1 = 2,
  aux_sym_keyword_section_token2 = 3,
  aux_sym_keyword_section_token3 = 4,
  aux_sym_keyword_section_token4 = 5,
  aux_sym_keyword_section_token5 = 6,
  aux_sym_keyword_section_token6 = 7,
  aux_sym_keyword_section_token7 = 8,
  aux_sym_keyword_section_token8 = 9,
  aux_sym_keyword_section_token9 = 10,
  aux_sym_keyword_section_token10 = 11,
  aux_sym_keyword_section_token11 = 12,
  aux_sym_keyword_section_token12 = 13,
  aux_sym_keyword_section_token13 = 14,
  aux_sym_keyword_section_token14 = 15,
  aux_sym_keyword_section_token15 = 16,
  aux_sym_keyword_flow_token1 = 17,
  aux_sym_keyword_flow_token2 = 18,
  aux_sym_keyword_flow_token3 = 19,
  aux_sym_keyword_flow_token4 = 20,
  aux_sym_keyword_flow_token5 = 21,
  aux_sym_keyword_flow_token6 = 22,
  aux_sym_keyword_flow_token7 = 23,
  aux_sym_keyword_flow_token8 = 24,
  aux_sym_keyword_flow_token9 = 25,
  aux_sym_keyword_flow_token10 = 26,
  aux_sym_keyword_flow_token11 = 27,
  aux_sym_keyword_action_token1 = 28,
  aux_sym_keyword_action_token2 = 29,
  aux_sym_keyword_action_token3 = 30,
  aux_sym_keyword_action_token4 = 31,
  aux_sym_keyword_action_token5 = 32,
  aux_sym_keyword_action_token6 = 33,
  aux_sym_keyword_action_token7 = 34,
  aux_sym_keyword_action_token8 = 35,
  aux_sym_keyword_action_token9 = 36,
  aux_sym_keyword_action_token10 = 37,
  aux_sym_keyword_action_token11 = 38,
  aux_sym_keyword_action_token12 = 39,
  aux_sym_keyword_order_token1 = 40,
  aux_sym_keyword_order_token2 = 41,
  aux_sym_keyword_order_token3 = 42,
  aux_sym_keyword_order_token4 = 43,
  aux_sym_keyword_order_token5 = 44,
  aux_sym_keyword_order_token6 = 45,
  aux_sym_keyword_order_token7 = 46,
  aux_sym_keyword_order_token8 = 47,
  aux_sym_keyword_order_token9 = 48,
  aux_sym_keyword_order_token10 = 49,
  aux_sym_keyword_order_token11 = 50,
  aux_sym_keyword_order_token12 = 51,
  aux_sym_keyword_order_token13 = 52,
  aux_sym_keyword_order_token14 = 53,
  aux_sym_keyword_order_token15 = 54,
  aux_sym_keyword_order_token16 = 55,
  aux_sym_keyword_order_token17 = 56,
  aux_sym_keyword_order_token18 = 57,
  aux_sym_keyword_order_token19 = 58,
  aux_sym_keyword_order_token20 = 59,
  aux_sym_keyword_order_token21 = 60,
  aux_sym_keyword_sizing_token1 = 61,
  aux_sym_keyword_sizing_token2 = 62,
  aux_sym_keyword_sizing_token3 = 63,
  aux_sym_keyword_sizing_token4 = 64,
  aux_sym_keyword_sizing_token5 = 65,
  aux_sym_keyword_sizing_token6 = 66,
  aux_sym_keyword_sizing_token7 = 67,
  aux_sym_keyword_bracket_token1 = 68,
  aux_sym_keyword_bracket_token2 = 69,
  aux_sym_keyword_bracket_token3 = 70,
  aux_sym_keyword_bracket_token4 = 71,
  aux_sym_keyword_bracket_token5 = 72,
  aux_sym_keyword_bracket_token6 = 73,
  aux_sym_keyword_bracket_token7 = 74,
  aux_sym_keyword_bracket_token8 = 75,
  aux_sym_keyword_bracket_token9 = 76,
  aux_sym_keyword_bracket_token10 = 77,
  aux_sym_keyword_bracket_token11 = 78,
  aux_sym_keyword_bracket_token12 = 79,
  aux_sym_keyword_bracket_token13 = 80,
  aux_sym_keyword_bracket_token14 = 81,
  aux_sym_keyword_bracket_token15 = 82,
  aux_sym_keyword_bracket_token16 = 83,
  aux_sym_keyword_bracket_token17 = 84,
  aux_sym_keyword_bracket_token18 = 85,
  aux_sym_keyword_bracket_token19 = 86,
  aux_sym_keyword_bracket_token20 = 87,
  aux_sym_keyword_bracket_token21 = 88,
  aux_sym_keyword_stacking_token1 = 89,
  aux_sym_keyword_stacking_token2 = 90,
  aux_sym_keyword_stacking_token3 = 91,
  aux_sym_keyword_stacking_token4 = 92,
  aux_sym_keyword_stacking_token5 = 93,
  aux_sym_keyword_stacking_token6 = 94,
  aux_sym_keyword_stacking_token7 = 95,
  aux_sym_keyword_stacking_token8 = 96,
  aux_sym_keyword_stacking_token9 = 97,
  aux_sym_keyword_stacking_token10 = 98,
  aux_sym_keyword_portfolio_token1 = 99,
  aux_sym_keyword_portfolio_token2 = 100,
  aux_sym_keyword_portfolio_token3 = 101,
  aux_sym_keyword_portfolio_token4 = 102,
  aux_sym_keyword_portfolio_token5 = 103,
  aux_sym_keyword_portfolio_token6 = 104,
  aux_sym_keyword_portfolio_token7 = 105,
  aux_sym_keyword_portfolio_token8 = 106,
  aux_sym_keyword_portfolio_token9 = 107,
  aux_sym_keyword_portfolio_token10 = 108,
  aux_sym_keyword_portfolio_token11 = 109,
  aux_sym_keyword_portfolio_token12 = 110,
  aux_sym_keyword_portfolio_token13 = 111,
  aux_sym_keyword_portfolio_token14 = 112,
  aux_sym_keyword_session_token1 = 113,
  aux_sym_keyword_session_token2 = 114,
  aux_sym_keyword_session_token3 = 115,
  aux_sym_keyword_session_token4 = 116,
  aux_sym_keyword_session_token5 = 117,
  aux_sym_keyword_session_token6 = 118,
  aux_sym_keyword_session_token7 = 119,
  aux_sym_keyword_session_token8 = 120,
  aux_sym_keyword_session_token9 = 121,
  aux_sym_keyword_session_token10 = 122,
  aux_sym_keyword_session_token11 = 123,
  aux_sym_keyword_hook_token1 = 124,
  aux_sym_keyword_hook_token2 = 125,
  aux_sym_keyword_hook_token3 = 126,
  aux_sym_keyword_hook_token4 = 127,
  aux_sym_keyword_state_token1 = 128,
  aux_sym_keyword_state_token2 = 129,
  aux_sym_keyword_state_token3 = 130,
  aux_sym_keyword_state_token4 = 131,
  aux_sym_keyword_state_token5 = 132,
  aux_sym_keyword_state_token6 = 133,
  aux_sym_keyword_state_token7 = 134,
  aux_sym_keyword_state_token8 = 135,
  aux_sym_keyword_state_token9 = 136,
  aux_sym_keyword_state_token10 = 137,
  aux_sym_keyword_state_token11 = 138,
  aux_sym_keyword_operator_word_token1 = 139,
  aux_sym_keyword_operator_word_token2 = 140,
  aux_sym_keyword_operator_word_token3 = 141,
  aux_sym_keyword_operator_word_token4 = 142,
  aux_sym_keyword_operator_word_token5 = 143,
  aux_sym_keyword_operator_word_token6 = 144,
  aux_sym_keyword_operator_word_token7 = 145,
  aux_sym_keyword_operator_word_token8 = 146,
  aux_sym_keyword_operator_word_token9 = 147,
  aux_sym_keyword_aggregate_token1 = 148,
  aux_sym_keyword_aggregate_token2 = 149,
  aux_sym_keyword_aggregate_token3 = 150,
  aux_sym_keyword_aggregate_token4 = 151,
  aux_sym_keyword_aggregate_token5 = 152,
  aux_sym_keyword_literal_token1 = 153,
  aux_sym_keyword_literal_token2 = 154,
  anon_sym_LPAREN = 155,
  anon_sym_DOT = 156,
  sym_broker_symbol = 157,
  sym_duration = 158,
  sym_number = 159,
  aux_sym_string_token1 = 160,
  aux_sym_string_token2 = 161,
  sym_comment = 162,
  anon_sym_DASH_GT = 163,
  anon_sym_EQ_EQ = 164,
  anon_sym_LT_GT = 165,
  anon_sym_BANG_EQ = 166,
  anon_sym_LT_EQ = 167,
  anon_sym_GT_EQ = 168,
  anon_sym_LT = 169,
  anon_sym_GT = 170,
  anon_sym_EQ = 171,
  anon_sym_PLUS = 172,
  anon_sym_DASH = 173,
  anon_sym_STAR = 174,
  anon_sym_SLASH = 175,
  anon_sym_PERCENT = 176,
  anon_sym_RPAREN = 177,
  anon_sym_LBRACK = 178,
  anon_sym_RBRACK = 179,
  anon_sym_LBRACE = 180,
  anon_sym_RBRACE = 181,
  anon_sym_COMMA = 182,
  anon_sym_COLON = 183,
  anon_sym_SEMI = 184,
  anon_sym_AT = 185,
  anon_sym_DOLLAR = 186,
  sym_source_file = 187,
  sym__item = 188,
  sym_keyword_section = 189,
  sym_keyword_flow = 190,
  sym_keyword_action = 191,
  sym_keyword_order = 192,
  sym_keyword_sizing = 193,
  sym_keyword_bracket = 194,
  sym_keyword_stacking = 195,
  sym_keyword_portfolio = 196,
  sym_keyword_session = 197,
  sym_keyword_hook = 198,
  sym_keyword_state = 199,
  sym_keyword_operator_word = 200,
  sym_keyword_aggregate = 201,
  sym_keyword_literal = 202,
  sym_call = 203,
  sym_member = 204,
  sym_string = 205,
  sym_operator = 206,
  sym_punctuation = 207,
  aux_sym_source_file_repeat1 = 208,
};

static const char * const ts_symbol_names[] = {
  [ts_builtin_sym_end] = "end",
  [sym_identifier] = "identifier",
  [aux_sym_keyword_section_token1] = "keyword_section_token1",
  [aux_sym_keyword_section_token2] = "keyword_section_token2",
  [aux_sym_keyword_section_token3] = "keyword_section_token3",
  [aux_sym_keyword_section_token4] = "keyword_section_token4",
  [aux_sym_keyword_section_token5] = "keyword_section_token5",
  [aux_sym_keyword_section_token6] = "keyword_section_token6",
  [aux_sym_keyword_section_token7] = "keyword_section_token7",
  [aux_sym_keyword_section_token8] = "keyword_section_token8",
  [aux_sym_keyword_section_token9] = "keyword_section_token9",
  [aux_sym_keyword_section_token10] = "keyword_section_token10",
  [aux_sym_keyword_section_token11] = "keyword_section_token11",
  [aux_sym_keyword_section_token12] = "keyword_section_token12",
  [aux_sym_keyword_section_token13] = "keyword_section_token13",
  [aux_sym_keyword_section_token14] = "keyword_section_token14",
  [aux_sym_keyword_section_token15] = "keyword_section_token15",
  [aux_sym_keyword_flow_token1] = "keyword_flow_token1",
  [aux_sym_keyword_flow_token2] = "keyword_flow_token2",
  [aux_sym_keyword_flow_token3] = "keyword_flow_token3",
  [aux_sym_keyword_flow_token4] = "keyword_flow_token4",
  [aux_sym_keyword_flow_token5] = "keyword_flow_token5",
  [aux_sym_keyword_flow_token6] = "keyword_flow_token6",
  [aux_sym_keyword_flow_token7] = "keyword_flow_token7",
  [aux_sym_keyword_flow_token8] = "keyword_flow_token8",
  [aux_sym_keyword_flow_token9] = "keyword_flow_token9",
  [aux_sym_keyword_flow_token10] = "keyword_flow_token10",
  [aux_sym_keyword_flow_token11] = "keyword_flow_token11",
  [aux_sym_keyword_action_token1] = "keyword_action_token1",
  [aux_sym_keyword_action_token2] = "keyword_action_token2",
  [aux_sym_keyword_action_token3] = "keyword_action_token3",
  [aux_sym_keyword_action_token4] = "keyword_action_token4",
  [aux_sym_keyword_action_token5] = "keyword_action_token5",
  [aux_sym_keyword_action_token6] = "keyword_action_token6",
  [aux_sym_keyword_action_token7] = "keyword_action_token7",
  [aux_sym_keyword_action_token8] = "keyword_action_token8",
  [aux_sym_keyword_action_token9] = "keyword_action_token9",
  [aux_sym_keyword_action_token10] = "keyword_action_token10",
  [aux_sym_keyword_action_token11] = "keyword_action_token11",
  [aux_sym_keyword_action_token12] = "keyword_action_token12",
  [aux_sym_keyword_order_token1] = "keyword_order_token1",
  [aux_sym_keyword_order_token2] = "keyword_order_token2",
  [aux_sym_keyword_order_token3] = "keyword_order_token3",
  [aux_sym_keyword_order_token4] = "keyword_order_token4",
  [aux_sym_keyword_order_token5] = "keyword_order_token5",
  [aux_sym_keyword_order_token6] = "keyword_order_token6",
  [aux_sym_keyword_order_token7] = "keyword_order_token7",
  [aux_sym_keyword_order_token8] = "keyword_order_token8",
  [aux_sym_keyword_order_token9] = "keyword_order_token9",
  [aux_sym_keyword_order_token10] = "keyword_order_token10",
  [aux_sym_keyword_order_token11] = "keyword_order_token11",
  [aux_sym_keyword_order_token12] = "keyword_order_token12",
  [aux_sym_keyword_order_token13] = "keyword_order_token13",
  [aux_sym_keyword_order_token14] = "keyword_order_token14",
  [aux_sym_keyword_order_token15] = "keyword_order_token15",
  [aux_sym_keyword_order_token16] = "keyword_order_token16",
  [aux_sym_keyword_order_token17] = "keyword_order_token17",
  [aux_sym_keyword_order_token18] = "keyword_order_token18",
  [aux_sym_keyword_order_token19] = "keyword_order_token19",
  [aux_sym_keyword_order_token20] = "keyword_order_token20",
  [aux_sym_keyword_order_token21] = "keyword_order_token21",
  [aux_sym_keyword_sizing_token1] = "keyword_sizing_token1",
  [aux_sym_keyword_sizing_token2] = "keyword_sizing_token2",
  [aux_sym_keyword_sizing_token3] = "keyword_sizing_token3",
  [aux_sym_keyword_sizing_token4] = "keyword_sizing_token4",
  [aux_sym_keyword_sizing_token5] = "keyword_sizing_token5",
  [aux_sym_keyword_sizing_token6] = "keyword_sizing_token6",
  [aux_sym_keyword_sizing_token7] = "keyword_sizing_token7",
  [aux_sym_keyword_bracket_token1] = "keyword_bracket_token1",
  [aux_sym_keyword_bracket_token2] = "keyword_bracket_token2",
  [aux_sym_keyword_bracket_token3] = "keyword_bracket_token3",
  [aux_sym_keyword_bracket_token4] = "keyword_bracket_token4",
  [aux_sym_keyword_bracket_token5] = "keyword_bracket_token5",
  [aux_sym_keyword_bracket_token6] = "keyword_bracket_token6",
  [aux_sym_keyword_bracket_token7] = "keyword_bracket_token7",
  [aux_sym_keyword_bracket_token8] = "keyword_bracket_token8",
  [aux_sym_keyword_bracket_token9] = "keyword_bracket_token9",
  [aux_sym_keyword_bracket_token10] = "keyword_bracket_token10",
  [aux_sym_keyword_bracket_token11] = "keyword_bracket_token11",
  [aux_sym_keyword_bracket_token12] = "keyword_bracket_token12",
  [aux_sym_keyword_bracket_token13] = "keyword_bracket_token13",
  [aux_sym_keyword_bracket_token14] = "keyword_bracket_token14",
  [aux_sym_keyword_bracket_token15] = "keyword_bracket_token15",
  [aux_sym_keyword_bracket_token16] = "keyword_bracket_token16",
  [aux_sym_keyword_bracket_token17] = "keyword_bracket_token17",
  [aux_sym_keyword_bracket_token18] = "keyword_bracket_token18",
  [aux_sym_keyword_bracket_token19] = "keyword_bracket_token19",
  [aux_sym_keyword_bracket_token20] = "keyword_bracket_token20",
  [aux_sym_keyword_bracket_token21] = "keyword_bracket_token21",
  [aux_sym_keyword_stacking_token1] = "keyword_stacking_token1",
  [aux_sym_keyword_stacking_token2] = "keyword_stacking_token2",
  [aux_sym_keyword_stacking_token3] = "keyword_stacking_token3",
  [aux_sym_keyword_stacking_token4] = "keyword_stacking_token4",
  [aux_sym_keyword_stacking_token5] = "keyword_stacking_token5",
  [aux_sym_keyword_stacking_token6] = "keyword_stacking_token6",
  [aux_sym_keyword_stacking_token7] = "keyword_stacking_token7",
  [aux_sym_keyword_stacking_token8] = "keyword_stacking_token8",
  [aux_sym_keyword_stacking_token9] = "keyword_stacking_token9",
  [aux_sym_keyword_stacking_token10] = "keyword_stacking_token10",
  [aux_sym_keyword_portfolio_token1] = "keyword_portfolio_token1",
  [aux_sym_keyword_portfolio_token2] = "keyword_portfolio_token2",
  [aux_sym_keyword_portfolio_token3] = "keyword_portfolio_token3",
  [aux_sym_keyword_portfolio_token4] = "keyword_portfolio_token4",
  [aux_sym_keyword_portfolio_token5] = "keyword_portfolio_token5",
  [aux_sym_keyword_portfolio_token6] = "keyword_portfolio_token6",
  [aux_sym_keyword_portfolio_token7] = "keyword_portfolio_token7",
  [aux_sym_keyword_portfolio_token8] = "keyword_portfolio_token8",
  [aux_sym_keyword_portfolio_token9] = "keyword_portfolio_token9",
  [aux_sym_keyword_portfolio_token10] = "keyword_portfolio_token10",
  [aux_sym_keyword_portfolio_token11] = "keyword_portfolio_token11",
  [aux_sym_keyword_portfolio_token12] = "keyword_portfolio_token12",
  [aux_sym_keyword_portfolio_token13] = "keyword_portfolio_token13",
  [aux_sym_keyword_portfolio_token14] = "keyword_portfolio_token14",
  [aux_sym_keyword_session_token1] = "keyword_session_token1",
  [aux_sym_keyword_session_token2] = "keyword_session_token2",
  [aux_sym_keyword_session_token3] = "keyword_session_token3",
  [aux_sym_keyword_session_token4] = "keyword_session_token4",
  [aux_sym_keyword_session_token5] = "keyword_session_token5",
  [aux_sym_keyword_session_token6] = "keyword_session_token6",
  [aux_sym_keyword_session_token7] = "keyword_session_token7",
  [aux_sym_keyword_session_token8] = "keyword_session_token8",
  [aux_sym_keyword_session_token9] = "keyword_session_token9",
  [aux_sym_keyword_session_token10] = "keyword_session_token10",
  [aux_sym_keyword_session_token11] = "keyword_session_token11",
  [aux_sym_keyword_hook_token1] = "keyword_hook_token1",
  [aux_sym_keyword_hook_token2] = "keyword_hook_token2",
  [aux_sym_keyword_hook_token3] = "keyword_hook_token3",
  [aux_sym_keyword_hook_token4] = "keyword_hook_token4",
  [aux_sym_keyword_state_token1] = "keyword_state_token1",
  [aux_sym_keyword_state_token2] = "keyword_state_token2",
  [aux_sym_keyword_state_token3] = "keyword_state_token3",
  [aux_sym_keyword_state_token4] = "keyword_state_token4",
  [aux_sym_keyword_state_token5] = "keyword_state_token5",
  [aux_sym_keyword_state_token6] = "keyword_state_token6",
  [aux_sym_keyword_state_token7] = "keyword_state_token7",
  [aux_sym_keyword_state_token8] = "keyword_state_token8",
  [aux_sym_keyword_state_token9] = "keyword_state_token9",
  [aux_sym_keyword_state_token10] = "keyword_state_token10",
  [aux_sym_keyword_state_token11] = "keyword_state_token11",
  [aux_sym_keyword_operator_word_token1] = "keyword_operator_word_token1",
  [aux_sym_keyword_operator_word_token2] = "keyword_operator_word_token2",
  [aux_sym_keyword_operator_word_token3] = "keyword_operator_word_token3",
  [aux_sym_keyword_operator_word_token4] = "keyword_operator_word_token4",
  [aux_sym_keyword_operator_word_token5] = "keyword_operator_word_token5",
  [aux_sym_keyword_operator_word_token6] = "keyword_operator_word_token6",
  [aux_sym_keyword_operator_word_token7] = "keyword_operator_word_token7",
  [aux_sym_keyword_operator_word_token8] = "keyword_operator_word_token8",
  [aux_sym_keyword_operator_word_token9] = "keyword_operator_word_token9",
  [aux_sym_keyword_aggregate_token1] = "keyword_aggregate_token1",
  [aux_sym_keyword_aggregate_token2] = "keyword_aggregate_token2",
  [aux_sym_keyword_aggregate_token3] = "keyword_aggregate_token3",
  [aux_sym_keyword_aggregate_token4] = "keyword_aggregate_token4",
  [aux_sym_keyword_aggregate_token5] = "keyword_aggregate_token5",
  [aux_sym_keyword_literal_token1] = "keyword_literal_token1",
  [aux_sym_keyword_literal_token2] = "keyword_literal_token2",
  [anon_sym_LPAREN] = "(",
  [anon_sym_DOT] = ".",
  [sym_broker_symbol] = "broker_symbol",
  [sym_duration] = "duration",
  [sym_number] = "number",
  [aux_sym_string_token1] = "string_token1",
  [aux_sym_string_token2] = "string_token2",
  [sym_comment] = "comment",
  [anon_sym_DASH_GT] = "->",
  [anon_sym_EQ_EQ] = "==",
  [anon_sym_LT_GT] = "<>",
  [anon_sym_BANG_EQ] = "!=",
  [anon_sym_LT_EQ] = "<=",
  [anon_sym_GT_EQ] = ">=",
  [anon_sym_LT] = "<",
  [anon_sym_GT] = ">",
  [anon_sym_EQ] = "=",
  [anon_sym_PLUS] = "+",
  [anon_sym_DASH] = "-",
  [anon_sym_STAR] = "*",
  [anon_sym_SLASH] = "/",
  [anon_sym_PERCENT] = "%",
  [anon_sym_RPAREN] = ")",
  [anon_sym_LBRACK] = "[",
  [anon_sym_RBRACK] = "]",
  [anon_sym_LBRACE] = "{",
  [anon_sym_RBRACE] = "}",
  [anon_sym_COMMA] = ",",
  [anon_sym_COLON] = ":",
  [anon_sym_SEMI] = ";",
  [anon_sym_AT] = "@",
  [anon_sym_DOLLAR] = "$",
  [sym_source_file] = "source_file",
  [sym__item] = "_item",
  [sym_keyword_section] = "keyword_section",
  [sym_keyword_flow] = "keyword_flow",
  [sym_keyword_action] = "keyword_action",
  [sym_keyword_order] = "keyword_order",
  [sym_keyword_sizing] = "keyword_sizing",
  [sym_keyword_bracket] = "keyword_bracket",
  [sym_keyword_stacking] = "keyword_stacking",
  [sym_keyword_portfolio] = "keyword_portfolio",
  [sym_keyword_session] = "keyword_session",
  [sym_keyword_hook] = "keyword_hook",
  [sym_keyword_state] = "keyword_state",
  [sym_keyword_operator_word] = "keyword_operator_word",
  [sym_keyword_aggregate] = "keyword_aggregate",
  [sym_keyword_literal] = "keyword_literal",
  [sym_call] = "call",
  [sym_member] = "member",
  [sym_string] = "string",
  [sym_operator] = "operator",
  [sym_punctuation] = "punctuation",
  [aux_sym_source_file_repeat1] = "source_file_repeat1",
};

static const TSSymbol ts_symbol_map[] = {
  [ts_builtin_sym_end] = ts_builtin_sym_end,
  [sym_identifier] = sym_identifier,
  [aux_sym_keyword_section_token1] = aux_sym_keyword_section_token1,
  [aux_sym_keyword_section_token2] = aux_sym_keyword_section_token2,
  [aux_sym_keyword_section_token3] = aux_sym_keyword_section_token3,
  [aux_sym_keyword_section_token4] = aux_sym_keyword_section_token4,
  [aux_sym_keyword_section_token5] = aux_sym_keyword_section_token5,
  [aux_sym_keyword_section_token6] = aux_sym_keyword_section_token6,
  [aux_sym_keyword_section_token7] = aux_sym_keyword_section_token7,
  [aux_sym_keyword_section_token8] = aux_sym_keyword_section_token8,
  [aux_sym_keyword_section_token9] = aux_sym_keyword_section_token9,
  [aux_sym_keyword_section_token10] = aux_sym_keyword_section_token10,
  [aux_sym_keyword_section_token11] = aux_sym_keyword_section_token11,
  [aux_sym_keyword_section_token12] = aux_sym_keyword_section_token12,
  [aux_sym_keyword_section_token13] = aux_sym_keyword_section_token13,
  [aux_sym_keyword_section_token14] = aux_sym_keyword_section_token14,
  [aux_sym_keyword_section_token15] = aux_sym_keyword_section_token15,
  [aux_sym_keyword_flow_token1] = aux_sym_keyword_flow_token1,
  [aux_sym_keyword_flow_token2] = aux_sym_keyword_flow_token2,
  [aux_sym_keyword_flow_token3] = aux_sym_keyword_flow_token3,
  [aux_sym_keyword_flow_token4] = aux_sym_keyword_flow_token4,
  [aux_sym_keyword_flow_token5] = aux_sym_keyword_flow_token5,
  [aux_sym_keyword_flow_token6] = aux_sym_keyword_flow_token6,
  [aux_sym_keyword_flow_token7] = aux_sym_keyword_flow_token7,
  [aux_sym_keyword_flow_token8] = aux_sym_keyword_flow_token8,
  [aux_sym_keyword_flow_token9] = aux_sym_keyword_flow_token9,
  [aux_sym_keyword_flow_token10] = aux_sym_keyword_flow_token10,
  [aux_sym_keyword_flow_token11] = aux_sym_keyword_flow_token11,
  [aux_sym_keyword_action_token1] = aux_sym_keyword_action_token1,
  [aux_sym_keyword_action_token2] = aux_sym_keyword_action_token2,
  [aux_sym_keyword_action_token3] = aux_sym_keyword_action_token3,
  [aux_sym_keyword_action_token4] = aux_sym_keyword_action_token4,
  [aux_sym_keyword_action_token5] = aux_sym_keyword_action_token5,
  [aux_sym_keyword_action_token6] = aux_sym_keyword_action_token6,
  [aux_sym_keyword_action_token7] = aux_sym_keyword_action_token7,
  [aux_sym_keyword_action_token8] = aux_sym_keyword_action_token8,
  [aux_sym_keyword_action_token9] = aux_sym_keyword_action_token9,
  [aux_sym_keyword_action_token10] = aux_sym_keyword_action_token10,
  [aux_sym_keyword_action_token11] = aux_sym_keyword_action_token11,
  [aux_sym_keyword_action_token12] = aux_sym_keyword_action_token12,
  [aux_sym_keyword_order_token1] = aux_sym_keyword_order_token1,
  [aux_sym_keyword_order_token2] = aux_sym_keyword_order_token2,
  [aux_sym_keyword_order_token3] = aux_sym_keyword_order_token3,
  [aux_sym_keyword_order_token4] = aux_sym_keyword_order_token4,
  [aux_sym_keyword_order_token5] = aux_sym_keyword_order_token5,
  [aux_sym_keyword_order_token6] = aux_sym_keyword_order_token6,
  [aux_sym_keyword_order_token7] = aux_sym_keyword_order_token7,
  [aux_sym_keyword_order_token8] = aux_sym_keyword_order_token8,
  [aux_sym_keyword_order_token9] = aux_sym_keyword_order_token9,
  [aux_sym_keyword_order_token10] = aux_sym_keyword_order_token10,
  [aux_sym_keyword_order_token11] = aux_sym_keyword_order_token11,
  [aux_sym_keyword_order_token12] = aux_sym_keyword_order_token12,
  [aux_sym_keyword_order_token13] = aux_sym_keyword_order_token13,
  [aux_sym_keyword_order_token14] = aux_sym_keyword_order_token14,
  [aux_sym_keyword_order_token15] = aux_sym_keyword_order_token15,
  [aux_sym_keyword_order_token16] = aux_sym_keyword_order_token16,
  [aux_sym_keyword_order_token17] = aux_sym_keyword_order_token17,
  [aux_sym_keyword_order_token18] = aux_sym_keyword_order_token18,
  [aux_sym_keyword_order_token19] = aux_sym_keyword_order_token19,
  [aux_sym_keyword_order_token20] = aux_sym_keyword_order_token20,
  [aux_sym_keyword_order_token21] = aux_sym_keyword_order_token21,
  [aux_sym_keyword_sizing_token1] = aux_sym_keyword_sizing_token1,
  [aux_sym_keyword_sizing_token2] = aux_sym_keyword_sizing_token2,
  [aux_sym_keyword_sizing_token3] = aux_sym_keyword_sizing_token3,
  [aux_sym_keyword_sizing_token4] = aux_sym_keyword_sizing_token4,
  [aux_sym_keyword_sizing_token5] = aux_sym_keyword_sizing_token5,
  [aux_sym_keyword_sizing_token6] = aux_sym_keyword_sizing_token6,
  [aux_sym_keyword_sizing_token7] = aux_sym_keyword_sizing_token7,
  [aux_sym_keyword_bracket_token1] = aux_sym_keyword_bracket_token1,
  [aux_sym_keyword_bracket_token2] = aux_sym_keyword_bracket_token2,
  [aux_sym_keyword_bracket_token3] = aux_sym_keyword_bracket_token3,
  [aux_sym_keyword_bracket_token4] = aux_sym_keyword_bracket_token4,
  [aux_sym_keyword_bracket_token5] = aux_sym_keyword_bracket_token5,
  [aux_sym_keyword_bracket_token6] = aux_sym_keyword_bracket_token6,
  [aux_sym_keyword_bracket_token7] = aux_sym_keyword_bracket_token7,
  [aux_sym_keyword_bracket_token8] = aux_sym_keyword_bracket_token8,
  [aux_sym_keyword_bracket_token9] = aux_sym_keyword_bracket_token9,
  [aux_sym_keyword_bracket_token10] = aux_sym_keyword_bracket_token10,
  [aux_sym_keyword_bracket_token11] = aux_sym_keyword_bracket_token11,
  [aux_sym_keyword_bracket_token12] = aux_sym_keyword_bracket_token12,
  [aux_sym_keyword_bracket_token13] = aux_sym_keyword_bracket_token13,
  [aux_sym_keyword_bracket_token14] = aux_sym_keyword_bracket_token14,
  [aux_sym_keyword_bracket_token15] = aux_sym_keyword_bracket_token15,
  [aux_sym_keyword_bracket_token16] = aux_sym_keyword_bracket_token16,
  [aux_sym_keyword_bracket_token17] = aux_sym_keyword_bracket_token17,
  [aux_sym_keyword_bracket_token18] = aux_sym_keyword_bracket_token18,
  [aux_sym_keyword_bracket_token19] = aux_sym_keyword_bracket_token19,
  [aux_sym_keyword_bracket_token20] = aux_sym_keyword_bracket_token20,
  [aux_sym_keyword_bracket_token21] = aux_sym_keyword_bracket_token21,
  [aux_sym_keyword_stacking_token1] = aux_sym_keyword_stacking_token1,
  [aux_sym_keyword_stacking_token2] = aux_sym_keyword_stacking_token2,
  [aux_sym_keyword_stacking_token3] = aux_sym_keyword_stacking_token3,
  [aux_sym_keyword_stacking_token4] = aux_sym_keyword_stacking_token4,
  [aux_sym_keyword_stacking_token5] = aux_sym_keyword_stacking_token5,
  [aux_sym_keyword_stacking_token6] = aux_sym_keyword_stacking_token6,
  [aux_sym_keyword_stacking_token7] = aux_sym_keyword_stacking_token7,
  [aux_sym_keyword_stacking_token8] = aux_sym_keyword_stacking_token8,
  [aux_sym_keyword_stacking_token9] = aux_sym_keyword_stacking_token9,
  [aux_sym_keyword_stacking_token10] = aux_sym_keyword_stacking_token10,
  [aux_sym_keyword_portfolio_token1] = aux_sym_keyword_portfolio_token1,
  [aux_sym_keyword_portfolio_token2] = aux_sym_keyword_portfolio_token2,
  [aux_sym_keyword_portfolio_token3] = aux_sym_keyword_portfolio_token3,
  [aux_sym_keyword_portfolio_token4] = aux_sym_keyword_portfolio_token4,
  [aux_sym_keyword_portfolio_token5] = aux_sym_keyword_portfolio_token5,
  [aux_sym_keyword_portfolio_token6] = aux_sym_keyword_portfolio_token6,
  [aux_sym_keyword_portfolio_token7] = aux_sym_keyword_portfolio_token7,
  [aux_sym_keyword_portfolio_token8] = aux_sym_keyword_portfolio_token8,
  [aux_sym_keyword_portfolio_token9] = aux_sym_keyword_portfolio_token9,
  [aux_sym_keyword_portfolio_token10] = aux_sym_keyword_portfolio_token10,
  [aux_sym_keyword_portfolio_token11] = aux_sym_keyword_portfolio_token11,
  [aux_sym_keyword_portfolio_token12] = aux_sym_keyword_portfolio_token12,
  [aux_sym_keyword_portfolio_token13] = aux_sym_keyword_portfolio_token13,
  [aux_sym_keyword_portfolio_token14] = aux_sym_keyword_portfolio_token14,
  [aux_sym_keyword_session_token1] = aux_sym_keyword_session_token1,
  [aux_sym_keyword_session_token2] = aux_sym_keyword_session_token2,
  [aux_sym_keyword_session_token3] = aux_sym_keyword_session_token3,
  [aux_sym_keyword_session_token4] = aux_sym_keyword_session_token4,
  [aux_sym_keyword_session_token5] = aux_sym_keyword_session_token5,
  [aux_sym_keyword_session_token6] = aux_sym_keyword_session_token6,
  [aux_sym_keyword_session_token7] = aux_sym_keyword_session_token7,
  [aux_sym_keyword_session_token8] = aux_sym_keyword_session_token8,
  [aux_sym_keyword_session_token9] = aux_sym_keyword_session_token9,
  [aux_sym_keyword_session_token10] = aux_sym_keyword_session_token10,
  [aux_sym_keyword_session_token11] = aux_sym_keyword_session_token11,
  [aux_sym_keyword_hook_token1] = aux_sym_keyword_hook_token1,
  [aux_sym_keyword_hook_token2] = aux_sym_keyword_hook_token2,
  [aux_sym_keyword_hook_token3] = aux_sym_keyword_hook_token3,
  [aux_sym_keyword_hook_token4] = aux_sym_keyword_hook_token4,
  [aux_sym_keyword_state_token1] = aux_sym_keyword_state_token1,
  [aux_sym_keyword_state_token2] = aux_sym_keyword_state_token2,
  [aux_sym_keyword_state_token3] = aux_sym_keyword_state_token3,
  [aux_sym_keyword_state_token4] = aux_sym_keyword_state_token4,
  [aux_sym_keyword_state_token5] = aux_sym_keyword_state_token5,
  [aux_sym_keyword_state_token6] = aux_sym_keyword_state_token6,
  [aux_sym_keyword_state_token7] = aux_sym_keyword_state_token7,
  [aux_sym_keyword_state_token8] = aux_sym_keyword_state_token8,
  [aux_sym_keyword_state_token9] = aux_sym_keyword_state_token9,
  [aux_sym_keyword_state_token10] = aux_sym_keyword_state_token10,
  [aux_sym_keyword_state_token11] = aux_sym_keyword_state_token11,
  [aux_sym_keyword_operator_word_token1] = aux_sym_keyword_operator_word_token1,
  [aux_sym_keyword_operator_word_token2] = aux_sym_keyword_operator_word_token2,
  [aux_sym_keyword_operator_word_token3] = aux_sym_keyword_operator_word_token3,
  [aux_sym_keyword_operator_word_token4] = aux_sym_keyword_operator_word_token4,
  [aux_sym_keyword_operator_word_token5] = aux_sym_keyword_operator_word_token5,
  [aux_sym_keyword_operator_word_token6] = aux_sym_keyword_operator_word_token6,
  [aux_sym_keyword_operator_word_token7] = aux_sym_keyword_operator_word_token7,
  [aux_sym_keyword_operator_word_token8] = aux_sym_keyword_operator_word_token8,
  [aux_sym_keyword_operator_word_token9] = aux_sym_keyword_operator_word_token9,
  [aux_sym_keyword_aggregate_token1] = aux_sym_keyword_aggregate_token1,
  [aux_sym_keyword_aggregate_token2] = aux_sym_keyword_aggregate_token2,
  [aux_sym_keyword_aggregate_token3] = aux_sym_keyword_aggregate_token3,
  [aux_sym_keyword_aggregate_token4] = aux_sym_keyword_aggregate_token4,
  [aux_sym_keyword_aggregate_token5] = aux_sym_keyword_aggregate_token5,
  [aux_sym_keyword_literal_token1] = aux_sym_keyword_literal_token1,
  [aux_sym_keyword_literal_token2] = aux_sym_keyword_literal_token2,
  [anon_sym_LPAREN] = anon_sym_LPAREN,
  [anon_sym_DOT] = anon_sym_DOT,
  [sym_broker_symbol] = sym_broker_symbol,
  [sym_duration] = sym_duration,
  [sym_number] = sym_number,
  [aux_sym_string_token1] = aux_sym_string_token1,
  [aux_sym_string_token2] = aux_sym_string_token2,
  [sym_comment] = sym_comment,
  [anon_sym_DASH_GT] = anon_sym_DASH_GT,
  [anon_sym_EQ_EQ] = anon_sym_EQ_EQ,
  [anon_sym_LT_GT] = anon_sym_LT_GT,
  [anon_sym_BANG_EQ] = anon_sym_BANG_EQ,
  [anon_sym_LT_EQ] = anon_sym_LT_EQ,
  [anon_sym_GT_EQ] = anon_sym_GT_EQ,
  [anon_sym_LT] = anon_sym_LT,
  [anon_sym_GT] = anon_sym_GT,
  [anon_sym_EQ] = anon_sym_EQ,
  [anon_sym_PLUS] = anon_sym_PLUS,
  [anon_sym_DASH] = anon_sym_DASH,
  [anon_sym_STAR] = anon_sym_STAR,
  [anon_sym_SLASH] = anon_sym_SLASH,
  [anon_sym_PERCENT] = anon_sym_PERCENT,
  [anon_sym_RPAREN] = anon_sym_RPAREN,
  [anon_sym_LBRACK] = anon_sym_LBRACK,
  [anon_sym_RBRACK] = anon_sym_RBRACK,
  [anon_sym_LBRACE] = anon_sym_LBRACE,
  [anon_sym_RBRACE] = anon_sym_RBRACE,
  [anon_sym_COMMA] = anon_sym_COMMA,
  [anon_sym_COLON] = anon_sym_COLON,
  [anon_sym_SEMI] = anon_sym_SEMI,
  [anon_sym_AT] = anon_sym_AT,
  [anon_sym_DOLLAR] = anon_sym_DOLLAR,
  [sym_source_file] = sym_source_file,
  [sym__item] = sym__item,
  [sym_keyword_section] = sym_keyword_section,
  [sym_keyword_flow] = sym_keyword_flow,
  [sym_keyword_action] = sym_keyword_action,
  [sym_keyword_order] = sym_keyword_order,
  [sym_keyword_sizing] = sym_keyword_sizing,
  [sym_keyword_bracket] = sym_keyword_bracket,
  [sym_keyword_stacking] = sym_keyword_stacking,
  [sym_keyword_portfolio] = sym_keyword_portfolio,
  [sym_keyword_session] = sym_keyword_session,
  [sym_keyword_hook] = sym_keyword_hook,
  [sym_keyword_state] = sym_keyword_state,
  [sym_keyword_operator_word] = sym_keyword_operator_word,
  [sym_keyword_aggregate] = sym_keyword_aggregate,
  [sym_keyword_literal] = sym_keyword_literal,
  [sym_call] = sym_call,
  [sym_member] = sym_member,
  [sym_string] = sym_string,
  [sym_operator] = sym_operator,
  [sym_punctuation] = sym_punctuation,
  [aux_sym_source_file_repeat1] = aux_sym_source_file_repeat1,
};

static const TSSymbolMetadata ts_symbol_metadata[] = {
  [ts_builtin_sym_end] = {
    .visible = false,
    .named = true,
  },
  [sym_identifier] = {
    .visible = true,
    .named = true,
  },
  [aux_sym_keyword_section_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token12] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token13] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token14] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_section_token15] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_flow_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_action_token12] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token12] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token13] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token14] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token15] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token16] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token17] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token18] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token19] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token20] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_order_token21] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_sizing_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token12] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token13] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token14] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token15] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token16] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token17] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token18] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token19] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token20] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_bracket_token21] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_stacking_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token12] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token13] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_portfolio_token14] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_session_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_hook_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_hook_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_hook_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_hook_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token10] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_state_token11] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token6] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token7] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token8] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_operator_word_token9] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_aggregate_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_aggregate_token2] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_aggregate_token3] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_aggregate_token4] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_aggregate_token5] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_literal_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_keyword_literal_token2] = {
    .visible = false,
    .named = false,
  },
  [anon_sym_LPAREN] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_DOT] = {
    .visible = true,
    .named = false,
  },
  [sym_broker_symbol] = {
    .visible = true,
    .named = true,
  },
  [sym_duration] = {
    .visible = true,
    .named = true,
  },
  [sym_number] = {
    .visible = true,
    .named = true,
  },
  [aux_sym_string_token1] = {
    .visible = false,
    .named = false,
  },
  [aux_sym_string_token2] = {
    .visible = false,
    .named = false,
  },
  [sym_comment] = {
    .visible = true,
    .named = true,
  },
  [anon_sym_DASH_GT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_EQ_EQ] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_LT_GT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_BANG_EQ] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_LT_EQ] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_GT_EQ] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_LT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_GT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_EQ] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_PLUS] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_DASH] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_STAR] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_SLASH] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_PERCENT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_RPAREN] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_LBRACK] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_RBRACK] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_LBRACE] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_RBRACE] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_COMMA] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_COLON] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_SEMI] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_AT] = {
    .visible = true,
    .named = false,
  },
  [anon_sym_DOLLAR] = {
    .visible = true,
    .named = false,
  },
  [sym_source_file] = {
    .visible = true,
    .named = true,
  },
  [sym__item] = {
    .visible = false,
    .named = true,
  },
  [sym_keyword_section] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_flow] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_action] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_order] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_sizing] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_bracket] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_stacking] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_portfolio] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_session] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_hook] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_state] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_operator_word] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_aggregate] = {
    .visible = true,
    .named = true,
  },
  [sym_keyword_literal] = {
    .visible = true,
    .named = true,
  },
  [sym_call] = {
    .visible = true,
    .named = true,
  },
  [sym_member] = {
    .visible = true,
    .named = true,
  },
  [sym_string] = {
    .visible = true,
    .named = true,
  },
  [sym_operator] = {
    .visible = true,
    .named = true,
  },
  [sym_punctuation] = {
    .visible = true,
    .named = true,
  },
  [aux_sym_source_file_repeat1] = {
    .visible = false,
    .named = false,
  },
};

enum ts_field_identifiers {
  field_name = 1,
};

static const char * const ts_field_names[] = {
  [0] = NULL,
  [field_name] = "name",
};

static const TSFieldMapSlice ts_field_map_slices[PRODUCTION_ID_COUNT] = {
  [1] = {.index = 0, .length = 1},
  [2] = {.index = 1, .length = 1},
};

static const TSFieldMapEntry ts_field_map_entries[] = {
  [0] =
    {field_name, 1},
  [1] =
    {field_name, 0},
};

static const TSSymbol ts_alias_sequences[PRODUCTION_ID_COUNT][MAX_ALIAS_SEQUENCE_LENGTH] = {
  [0] = {0},
};

static const uint16_t ts_non_terminal_alias_map[] = {
  0,
};

static const TSStateId ts_primary_state_ids[STATE_COUNT] = {
  [0] = 0,
  [1] = 1,
  [2] = 2,
  [3] = 3,
  [4] = 4,
  [5] = 5,
  [6] = 6,
  [7] = 7,
  [8] = 8,
  [9] = 9,
  [10] = 10,
  [11] = 11,
  [12] = 12,
  [13] = 13,
  [14] = 14,
  [15] = 15,
  [16] = 16,
  [17] = 17,
  [18] = 18,
  [19] = 19,
  [20] = 20,
  [21] = 21,
  [22] = 22,
  [23] = 23,
  [24] = 24,
  [25] = 25,
};

static bool ts_lex(TSLexer *lexer, TSStateId state) {
  START_LEXER();
  eof = lexer->eof(lexer);
  switch (state) {
    case 0:
      if (eof) ADVANCE(12);
      ADVANCE_MAP(
        '!', 5,
        '"', 1,
        '#', 23,
        '$', 47,
        '%', 37,
        '\'', 2,
        '(', 13,
        ')', 38,
        '*', 35,
        '+', 33,
        ',', 43,
        '-', 34,
        '.', 14,
        '/', 36,
        ':', 44,
        ';', 45,
        '<', 30,
        '=', 32,
        '>', 31,
        '@', 46,
        '[', 39,
        ']', 40,
        '{', 41,
        '}', 42,
      );
      if (('\t' <= lookahead && lookahead <= '\r') ||
          lookahead == ' ') SKIP(0);
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(17);
      if (('A' <= lookahead && lookahead <= 'Z') ||
          lookahead == '_' ||
          ('a' <= lookahead && lookahead <= 'z')) ADVANCE(48);
      END_STATE();
    case 1:
      if (lookahead == '"') ADVANCE(20);
      if (lookahead == '\\') ADVANCE(10);
      if (lookahead != 0 &&
          lookahead != '\n') ADVANCE(1);
      END_STATE();
    case 2:
      if (lookahead == '\'') ADVANCE(21);
      if (lookahead == '\\') ADVANCE(11);
      if (lookahead != 0 &&
          lookahead != '\n') ADVANCE(2);
      END_STATE();
    case 3:
      if (lookahead == '*') ADVANCE(3);
      if (lookahead == '/') ADVANCE(22);
      if (lookahead != 0) ADVANCE(4);
      END_STATE();
    case 4:
      if (lookahead == '*') ADVANCE(3);
      if (lookahead != 0) ADVANCE(4);
      END_STATE();
    case 5:
      if (lookahead == '=') ADVANCE(27);
      END_STATE();
    case 6:
      if (lookahead == '+' ||
          lookahead == '-') ADVANCE(8);
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(19);
      END_STATE();
    case 7:
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(18);
      END_STATE();
    case 8:
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(19);
      END_STATE();
    case 9:
      if (('0' <= lookahead && lookahead <= '9') ||
          ('A' <= lookahead && lookahead <= 'Z') ||
          lookahead == '_' ||
          ('a' <= lookahead && lookahead <= 'z')) ADVANCE(15);
      END_STATE();
    case 10:
      if (lookahead != 0 &&
          lookahead != '\n') ADVANCE(1);
      END_STATE();
    case 11:
      if (lookahead != 0 &&
          lookahead != '\n') ADVANCE(2);
      END_STATE();
    case 12:
      ACCEPT_TOKEN(ts_builtin_sym_end);
      END_STATE();
    case 13:
      ACCEPT_TOKEN(anon_sym_LPAREN);
      END_STATE();
    case 14:
      ACCEPT_TOKEN(anon_sym_DOT);
      END_STATE();
    case 15:
      ACCEPT_TOKEN(sym_broker_symbol);
      if (lookahead == '-' ||
          lookahead == '.' ||
          ('0' <= lookahead && lookahead <= '9') ||
          ('A' <= lookahead && lookahead <= 'Z') ||
          lookahead == '_' ||
          ('a' <= lookahead && lookahead <= 'z')) ADVANCE(15);
      END_STATE();
    case 16:
      ACCEPT_TOKEN(sym_duration);
      END_STATE();
    case 17:
      ACCEPT_TOKEN(sym_number);
      if (lookahead == '.') ADVANCE(7);
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(6);
      if (lookahead == 'd' ||
          lookahead == 'h' ||
          lookahead == 'm' ||
          lookahead == 's') ADVANCE(16);
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(17);
      END_STATE();
    case 18:
      ACCEPT_TOKEN(sym_number);
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(6);
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(18);
      END_STATE();
    case 19:
      ACCEPT_TOKEN(sym_number);
      if (('0' <= lookahead && lookahead <= '9')) ADVANCE(19);
      END_STATE();
    case 20:
      ACCEPT_TOKEN(aux_sym_string_token1);
      END_STATE();
    case 21:
      ACCEPT_TOKEN(aux_sym_string_token2);
      END_STATE();
    case 22:
      ACCEPT_TOKEN(sym_comment);
      END_STATE();
    case 23:
      ACCEPT_TOKEN(sym_comment);
      if (lookahead != 0 &&
          lookahead != '\n') ADVANCE(23);
      END_STATE();
    case 24:
      ACCEPT_TOKEN(anon_sym_DASH_GT);
      END_STATE();
    case 25:
      ACCEPT_TOKEN(anon_sym_EQ_EQ);
      END_STATE();
    case 26:
      ACCEPT_TOKEN(anon_sym_LT_GT);
      END_STATE();
    case 27:
      ACCEPT_TOKEN(anon_sym_BANG_EQ);
      END_STATE();
    case 28:
      ACCEPT_TOKEN(anon_sym_LT_EQ);
      END_STATE();
    case 29:
      ACCEPT_TOKEN(anon_sym_GT_EQ);
      END_STATE();
    case 30:
      ACCEPT_TOKEN(anon_sym_LT);
      if (lookahead == '=') ADVANCE(28);
      if (lookahead == '>') ADVANCE(26);
      END_STATE();
    case 31:
      ACCEPT_TOKEN(anon_sym_GT);
      if (lookahead == '=') ADVANCE(29);
      END_STATE();
    case 32:
      ACCEPT_TOKEN(anon_sym_EQ);
      if (lookahead == '=') ADVANCE(25);
      END_STATE();
    case 33:
      ACCEPT_TOKEN(anon_sym_PLUS);
      END_STATE();
    case 34:
      ACCEPT_TOKEN(anon_sym_DASH);
      if (lookahead == '-') ADVANCE(23);
      if (lookahead == '>') ADVANCE(24);
      END_STATE();
    case 35:
      ACCEPT_TOKEN(anon_sym_STAR);
      END_STATE();
    case 36:
      ACCEPT_TOKEN(anon_sym_SLASH);
      if (lookahead == '*') ADVANCE(4);
      END_STATE();
    case 37:
      ACCEPT_TOKEN(anon_sym_PERCENT);
      END_STATE();
    case 38:
      ACCEPT_TOKEN(anon_sym_RPAREN);
      END_STATE();
    case 39:
      ACCEPT_TOKEN(anon_sym_LBRACK);
      END_STATE();
    case 40:
      ACCEPT_TOKEN(anon_sym_RBRACK);
      END_STATE();
    case 41:
      ACCEPT_TOKEN(anon_sym_LBRACE);
      END_STATE();
    case 42:
      ACCEPT_TOKEN(anon_sym_RBRACE);
      END_STATE();
    case 43:
      ACCEPT_TOKEN(anon_sym_COMMA);
      END_STATE();
    case 44:
      ACCEPT_TOKEN(anon_sym_COLON);
      END_STATE();
    case 45:
      ACCEPT_TOKEN(anon_sym_SEMI);
      END_STATE();
    case 46:
      ACCEPT_TOKEN(anon_sym_AT);
      END_STATE();
    case 47:
      ACCEPT_TOKEN(anon_sym_DOLLAR);
      END_STATE();
    case 48:
      ACCEPT_TOKEN(sym_identifier);
      if (lookahead == ':') ADVANCE(9);
      if (('0' <= lookahead && lookahead <= '9') ||
          ('A' <= lookahead && lookahead <= 'Z') ||
          lookahead == '_' ||
          ('a' <= lookahead && lookahead <= 'z')) ADVANCE(48);
      END_STATE();
    default:
      return false;
  }
}

static bool ts_lex_keywords(TSLexer *lexer, TSStateId state) {
  START_LEXER();
  eof = lexer->eof(lexer);
  switch (state) {
    case 0:
      ADVANCE_MAP(
        'A', 1,
        'a', 1,
        'B', 2,
        'b', 2,
        'C', 3,
        'c', 3,
        'D', 4,
        'd', 4,
        'E', 5,
        'e', 5,
        'F', 6,
        'f', 6,
        'G', 7,
        'g', 7,
        'H', 8,
        'h', 8,
        'I', 9,
        'i', 9,
        'L', 10,
        'l', 10,
        'M', 11,
        'm', 11,
        'N', 12,
        'n', 12,
        'O', 13,
        'o', 13,
        'P', 14,
        'p', 14,
        'R', 15,
        'r', 15,
        'S', 16,
        's', 16,
        'T', 17,
        't', 17,
        'U', 18,
        'u', 18,
        'V', 19,
        'v', 19,
        'W', 20,
        'w', 20,
      );
      if (('\t' <= lookahead && lookahead <= '\r') ||
          lookahead == ' ') SKIP(0);
      END_STATE();
    case 1:
      ADVANCE_MAP(
        'B', 21,
        'b', 21,
        'C', 22,
        'c', 22,
        'F', 23,
        'f', 23,
        'G', 24,
        'g', 24,
        'L', 25,
        'l', 25,
        'N', 26,
        'n', 26,
        'R', 27,
        'r', 27,
        'S', 28,
        's', 28,
        'T', 29,
        't', 29,
      );
      END_STATE();
    case 2:
      ADVANCE_MAP(
        'A', 30,
        'a', 30,
        'E', 31,
        'e', 31,
        'R', 32,
        'r', 32,
        'U', 33,
        'u', 33,
        'Y', 34,
        'y', 34,
      );
      END_STATE();
    case 3:
      ADVANCE_MAP(
        'A', 35,
        'a', 35,
        'H', 36,
        'h', 36,
        'L', 37,
        'l', 37,
        'O', 38,
        'o', 38,
        'R', 39,
        'r', 39,
      );
      END_STATE();
    case 4:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(40);
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(41);
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(42);
      END_STATE();
    case 5:
      ADVANCE_MAP(
        'A', 43,
        'a', 43,
        'L', 44,
        'l', 44,
        'N', 45,
        'n', 45,
        'Q', 46,
        'q', 46,
        'R', 47,
        'r', 47,
        'V', 48,
        'v', 48,
        'X', 49,
        'x', 49,
      );
      END_STATE();
    case 6:
      ADVANCE_MAP(
        'A', 50,
        'a', 50,
        'L', 51,
        'l', 51,
        'O', 52,
        'o', 52,
        'R', 53,
        'r', 53,
      );
      END_STATE();
    case 7:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(54);
      END_STATE();
    case 8:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(55);
      END_STATE();
    case 9:
      ADVANCE_MAP(
        'M', 56,
        'm', 56,
        'N', 57,
        'n', 57,
        'O', 58,
        'o', 58,
        'S', 59,
        's', 59,
      );
      END_STATE();
    case 10:
      ADVANCE_MAP(
        'A', 60,
        'a', 60,
        'E', 61,
        'e', 61,
        'I', 62,
        'i', 62,
        'O', 63,
        'o', 63,
      );
      END_STATE();
    case 11:
      ADVANCE_MAP(
        'A', 64,
        'a', 64,
        'E', 65,
        'e', 65,
        'F', 66,
        'f', 66,
        'I', 67,
        'i', 67,
      );
      END_STATE();
    case 12:
      ADVANCE_MAP(
        'A', 68,
        'a', 68,
        'O', 69,
        'o', 69,
        'U', 70,
        'u', 70,
        'Y', 71,
        'y', 71,
      );
      END_STATE();
    case 13:
      ADVANCE_MAP(
        'C', 72,
        'c', 72,
        'F', 73,
        'f', 73,
        'N', 74,
        'n', 74,
        'P', 75,
        'p', 75,
        'R', 76,
        'r', 76,
        'V', 77,
        'v', 77,
      );
      END_STATE();
    case 14:
      ADVANCE_MAP(
        'A', 78,
        'a', 78,
        'C', 79,
        'c', 79,
        'O', 80,
        'o', 80,
        'R', 81,
        'r', 81,
      );
      END_STATE();
    case 15:
      ADVANCE_MAP(
        'E', 82,
        'e', 82,
        'I', 83,
        'i', 83,
        'R', 84,
        'r', 84,
        'U', 85,
        'u', 85,
      );
      END_STATE();
    case 16:
      ADVANCE_MAP(
        'C', 86,
        'c', 86,
        'E', 87,
        'e', 87,
        'I', 88,
        'i', 88,
        'P', 89,
        'p', 89,
        'T', 90,
        't', 90,
        'U', 91,
        'u', 91,
        'Y', 92,
        'y', 92,
      );
      END_STATE();
    case 17:
      ADVANCE_MAP(
        'A', 93,
        'a', 93,
        'H', 94,
        'h', 94,
        'I', 95,
        'i', 95,
        'O', 96,
        'o', 96,
        'R', 97,
        'r', 97,
      );
      END_STATE();
    case 18:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(98);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(99);
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(100);
      END_STATE();
    case 19:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(101);
      END_STATE();
    case 20:
      ADVANCE_MAP(
        'A', 102,
        'a', 102,
        'E', 103,
        'e', 103,
        'H', 104,
        'h', 104,
        'I', 105,
        'i', 105,
      );
      END_STATE();
    case 21:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(106);
      END_STATE();
    case 22:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(107);
      END_STATE();
    case 23:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(108);
      END_STATE();
    case 24:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(109);
      END_STATE();
    case 25:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(110);
      END_STATE();
    case 26:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(111);
      END_STATE();
    case 27:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(112);
      END_STATE();
    case 28:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token2);
      END_STATE();
    case 29:
      ACCEPT_TOKEN(aux_sym_keyword_order_token1);
      END_STATE();
    case 30:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(113);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(114);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(115);
      END_STATE();
    case 31:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(116);
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(117);
      END_STATE();
    case 32:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(118);
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(119);
      END_STATE();
    case 33:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(120);
      END_STATE();
    case 34:
      ACCEPT_TOKEN(aux_sym_keyword_order_token2);
      END_STATE();
    case 35:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(121);
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(122);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(123);
      END_STATE();
    case 36:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(124);
      END_STATE();
    case 37:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(125);
      END_STATE();
    case 38:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(126);
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(127);
      END_STATE();
    case 39:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(128);
      END_STATE();
    case 40:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(129);
      END_STATE();
    case 41:
      if (lookahead == 'B' ||
          lookahead == 'b') ADVANCE(130);
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(131);
      END_STATE();
    case 42:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token2);
      END_STATE();
    case 43:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(132);
      END_STATE();
    case 44:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(133);
      END_STATE();
    case 45:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(134);
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(135);
      END_STATE();
    case 46:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(136);
      END_STATE();
    case 47:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(137);
      END_STATE();
    case 48:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(138);
      END_STATE();
    case 49:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(139);
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(140);
      END_STATE();
    case 50:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(141);
      END_STATE();
    case 51:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(142);
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(143);
      END_STATE();
    case 52:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(144);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(145);
      END_STATE();
    case 53:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(146);
      END_STATE();
    case 54:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(147);
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(148);
      END_STATE();
    case 55:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(149);
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(150);
      END_STATE();
    case 56:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(151);
      END_STATE();
    case 57:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token8);
      END_STATE();
    case 58:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(152);
      END_STATE();
    case 59:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token6);
      END_STATE();
    case 60:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(153);
      END_STATE();
    case 61:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(154);
      END_STATE();
    case 62:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(155);
      END_STATE();
    case 63:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(156);
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(157);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(158);
      END_STATE();
    case 64:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(159);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(160);
      if (lookahead == 'X' ||
          lookahead == 'x') ADVANCE(161);
      END_STATE();
    case 65:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(162);
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(163);
      END_STATE();
    case 66:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(164);
      END_STATE();
    case 67:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(165);
      END_STATE();
    case 68:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(166);
      END_STATE();
    case 69:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(167);
      if (lookahead == 'W' ||
          lookahead == 'w') ADVANCE(168);
      END_STATE();
    case 70:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(169);
      END_STATE();
    case 71:
      ACCEPT_TOKEN(aux_sym_keyword_session_token6);
      END_STATE();
    case 72:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(170);
      END_STATE();
    case 73:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token4);
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(171);
      END_STATE();
    case 74:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token14);
      if (lookahead == '_') ADVANCE(172);
      END_STATE();
    case 75:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(173);
      END_STATE();
    case 76:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token9);
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(174);
      END_STATE();
    case 77:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(175);
      END_STATE();
    case 78:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(176);
      END_STATE();
    case 79:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(177);
      END_STATE();
    case 80:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(178);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(179);
      END_STATE();
    case 81:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(180);
      END_STATE();
    case 82:
      ADVANCE_MAP(
        'B', 181,
        'b', 181,
        'C', 182,
        'c', 182,
        'G', 183,
        'g', 183,
        'S', 184,
        's', 184,
        'T', 185,
        't', 185,
      );
      END_STATE();
    case 83:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(186);
      END_STATE();
    case 84:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token18);
      END_STATE();
    case 85:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(187);
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(188);
      END_STATE();
    case 86:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(189);
      END_STATE();
    case 87:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(190);
      if (lookahead == 'Q' ||
          lookahead == 'q') ADVANCE(191);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(192);
      END_STATE();
    case 88:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(193);
      if (lookahead == 'Z' ||
          lookahead == 'z') ADVANCE(194);
      END_STATE();
    case 89:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(195);
      END_STATE();
    case 90:
      ADVANCE_MAP(
        'A', 196,
        'a', 196,
        'E', 197,
        'e', 197,
        'O', 198,
        'o', 198,
        'R', 199,
        'r', 199,
      );
      END_STATE();
    case 91:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(200);
      END_STATE();
    case 92:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(201);
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(202);
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(203);
      END_STATE();
    case 93:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(204);
      END_STATE();
    case 94:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(205);
      END_STATE();
    case 95:
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(206);
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(207);
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(208);
      END_STATE();
    case 96:
      ACCEPT_TOKEN(aux_sym_keyword_order_token19);
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(209);
      END_STATE();
    case 97:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(210);
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(211);
      END_STATE();
    case 98:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(212);
      END_STATE();
    case 99:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(213);
      END_STATE();
    case 100:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(214);
      END_STATE();
    case 101:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(215);
      END_STATE();
    case 102:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(216);
      END_STATE();
    case 103:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(217);
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(218);
      END_STATE();
    case 104:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(219);
      END_STATE();
    case 105:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(220);
      END_STATE();
    case 106:
      if (lookahead == 'V' ||
          lookahead == 'v') ADVANCE(221);
      END_STATE();
    case 107:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(222);
      END_STATE();
    case 108:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(223);
      END_STATE();
    case 109:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(224);
      END_STATE();
    case 110:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(225);
      END_STATE();
    case 111:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token2);
      END_STATE();
    case 112:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token2);
      END_STATE();
    case 113:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(226);
      END_STATE();
    case 114:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(227);
      END_STATE();
    case 115:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(228);
      END_STATE();
    case 116:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(229);
      END_STATE();
    case 117:
      if (lookahead == 'W' ||
          lookahead == 'w') ADVANCE(230);
      END_STATE();
    case 118:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(231);
      END_STATE();
    case 119:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(232);
      END_STATE();
    case 120:
      ACCEPT_TOKEN(aux_sym_keyword_action_token1);
      END_STATE();
    case 121:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(233);
      END_STATE();
    case 122:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(234);
      END_STATE();
    case 123:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(235);
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(236);
      END_STATE();
    case 124:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(237);
      END_STATE();
    case 125:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(238);
      END_STATE();
    case 126:
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(239);
      END_STATE();
    case 127:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(240);
      END_STATE();
    case 128:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(241);
      END_STATE();
    case 129:
      ACCEPT_TOKEN(aux_sym_keyword_order_token3);
      END_STATE();
    case 130:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(242);
      END_STATE();
    case 131:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(243);
      END_STATE();
    case 132:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(244);
      END_STATE();
    case 133:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(245);
      END_STATE();
    case 134:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token5);
      END_STATE();
    case 135:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(246);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(247);
      END_STATE();
    case 136:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(248);
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(249);
      END_STATE();
    case 137:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(250);
      END_STATE();
    case 138:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(251);
      END_STATE();
    case 139:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(252);
      END_STATE();
    case 140:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(253);
      END_STATE();
    case 141:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(254);
      END_STATE();
    case 142:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(255);
      END_STATE();
    case 143:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(256);
      END_STATE();
    case 144:
      ACCEPT_TOKEN(aux_sym_keyword_order_token5);
      END_STATE();
    case 145:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token7);
      END_STATE();
    case 146:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(257);
      END_STATE();
    case 147:
      ACCEPT_TOKEN(aux_sym_keyword_order_token6);
      END_STATE();
    case 148:
      ACCEPT_TOKEN(aux_sym_keyword_order_token7);
      END_STATE();
    case 149:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(258);
      END_STATE();
    case 150:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(259);
      END_STATE();
    case 151:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(260);
      END_STATE();
    case 152:
      ACCEPT_TOKEN(aux_sym_keyword_order_token8);
      END_STATE();
    case 153:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(261);
      END_STATE();
    case 154:
      ACCEPT_TOKEN(aux_sym_keyword_section_token4);
      END_STATE();
    case 155:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(262);
      END_STATE();
    case 156:
      ACCEPT_TOKEN(aux_sym_keyword_action_token9);
      END_STATE();
    case 157:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(263);
      END_STATE();
    case 158:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(264);
      END_STATE();
    case 159:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token2);
      END_STATE();
    case 160:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(265);
      END_STATE();
    case 161:
      ACCEPT_TOKEN(aux_sym_keyword_aggregate_token1);
      END_STATE();
    case 162:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(266);
      END_STATE();
    case 163:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(267);
      END_STATE();
    case 164:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token3);
      END_STATE();
    case 165:
      ACCEPT_TOKEN(aux_sym_keyword_aggregate_token3);
      if (lookahead == '_') ADVANCE(268);
      END_STATE();
    case 166:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(269);
      END_STATE();
    case 167:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token7);
      END_STATE();
    case 168:
      ACCEPT_TOKEN(aux_sym_keyword_state_token5);
      END_STATE();
    case 169:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(270);
      END_STATE();
    case 170:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token11);
      if (lookahead == '_') ADVANCE(271);
      END_STATE();
    case 171:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(272);
      END_STATE();
    case 172:
      ADVANCE_MAP(
        'C', 273,
        'c', 273,
        'F', 274,
        'f', 274,
        'S', 275,
        's', 275,
        'T', 276,
        't', 276,
      );
      END_STATE();
    case 173:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(277);
      END_STATE();
    case 174:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(278);
      END_STATE();
    case 175:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(279);
      END_STATE();
    case 176:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(280);
      END_STATE();
    case 177:
      ACCEPT_TOKEN(aux_sym_keyword_order_token12);
      END_STATE();
    case 178:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(281);
      END_STATE();
    case 179:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(282);
      END_STATE();
    case 180:
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(283);
      END_STATE();
    case 181:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(284);
      END_STATE();
    case 182:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(285);
      END_STATE();
    case 183:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(286);
      END_STATE();
    case 184:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(287);
      END_STATE();
    case 185:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(288);
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(289);
      END_STATE();
    case 186:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(290);
      END_STATE();
    case 187:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(291);
      END_STATE();
    case 188:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token12);
      END_STATE();
    case 189:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(292);
      END_STATE();
    case 190:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(293);
      END_STATE();
    case 191:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(294);
      END_STATE();
    case 192:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(295);
      END_STATE();
    case 193:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(296);
      END_STATE();
    case 194:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(297);
      END_STATE();
    case 195:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(298);
      END_STATE();
    case 196:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(299);
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(300);
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(301);
      END_STATE();
    case 197:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(302);
      END_STATE();
    case 198:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(303);
      END_STATE();
    case 199:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(304);
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(305);
      END_STATE();
    case 200:
      ACCEPT_TOKEN(aux_sym_keyword_aggregate_token5);
      END_STATE();
    case 201:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(306);
      END_STATE();
    case 202:
      if (lookahead == 'B' ||
          lookahead == 'b') ADVANCE(307);
      END_STATE();
    case 203:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(308);
      END_STATE();
    case 204:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(309);
      END_STATE();
    case 205:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(310);
      END_STATE();
    case 206:
      ACCEPT_TOKEN(aux_sym_keyword_order_token17);
      END_STATE();
    case 207:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(311);
      END_STATE();
    case 208:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(312);
      END_STATE();
    case 209:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(313);
      END_STATE();
    case 210:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(314);
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(315);
      END_STATE();
    case 211:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(316);
      END_STATE();
    case 212:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(317);
      END_STATE();
    case 213:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token7);
      END_STATE();
    case 214:
      ACCEPT_TOKEN(aux_sym_keyword_session_token9);
      END_STATE();
    case 215:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(318);
      END_STATE();
    case 216:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(319);
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(320);
      END_STATE();
    case 217:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(321);
      END_STATE();
    case 218:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(322);
      END_STATE();
    case 219:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(323);
      END_STATE();
    case 220:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(324);
      END_STATE();
    case 221:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(325);
      END_STATE();
    case 222:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(326);
      END_STATE();
    case 223:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(327);
      END_STATE();
    case 224:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(328);
      END_STATE();
    case 225:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(329);
      END_STATE();
    case 226:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(330);
      END_STATE();
    case 227:
      ACCEPT_TOKEN(aux_sym_keyword_session_token1);
      END_STATE();
    case 228:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(331);
      END_STATE();
    case 229:
      if (lookahead == 'W' ||
          lookahead == 'w') ADVANCE(332);
      END_STATE();
    case 230:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(333);
      END_STATE();
    case 231:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(334);
      END_STATE();
    case 232:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(335);
      END_STATE();
    case 233:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(336);
      END_STATE();
    case 234:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(337);
      END_STATE();
    case 235:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token1);
      END_STATE();
    case 236:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token4);
      END_STATE();
    case 237:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(338);
      END_STATE();
    case 238:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(339);
      END_STATE();
    case 239:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(340);
      END_STATE();
    case 240:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(341);
      END_STATE();
    case 241:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(342);
      END_STATE();
    case 242:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(343);
      END_STATE();
    case 243:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(344);
      END_STATE();
    case 244:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token3);
      END_STATE();
    case 245:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token4);
      END_STATE();
    case 246:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(345);
      END_STATE();
    case 247:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(346);
      END_STATE();
    case 248:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(347);
      END_STATE();
    case 249:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(348);
      END_STATE();
    case 250:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(349);
      END_STATE();
    case 251:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(350);
      END_STATE();
    case 252:
      ACCEPT_TOKEN(aux_sym_keyword_state_token4);
      END_STATE();
    case 253:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(351);
      END_STATE();
    case 254:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(352);
      END_STATE();
    case 255:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(353);
      END_STATE();
    case 256:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(354);
      END_STATE();
    case 257:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token8);
      END_STATE();
    case 258:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token7);
      END_STATE();
    case 259:
      ACCEPT_TOKEN(aux_sym_keyword_session_token4);
      END_STATE();
    case 260:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(355);
      END_STATE();
    case 261:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(356);
      END_STATE();
    case 262:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(357);
      END_STATE();
    case 263:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(358);
      END_STATE();
    case 264:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token10);
      END_STATE();
    case 265:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(359);
      END_STATE();
    case 266:
      ACCEPT_TOKEN(aux_sym_keyword_aggregate_token2);
      END_STATE();
    case 267:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(360);
      END_STATE();
    case 268:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(361);
      END_STATE();
    case 269:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token9);
      END_STATE();
    case 270:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token8);
      END_STATE();
    case 271:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(362);
      END_STATE();
    case 272:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(363);
      END_STATE();
    case 273:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(364);
      END_STATE();
    case 274:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(365);
      END_STATE();
    case 275:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(366);
      END_STATE();
    case 276:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(367);
      END_STATE();
    case 277:
      ACCEPT_TOKEN(aux_sym_keyword_aggregate_token4);
      if (lookahead == '_') ADVANCE(368);
      END_STATE();
    case 278:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(369);
      END_STATE();
    case 279:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(370);
      END_STATE();
    case 280:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(371);
      END_STATE();
    case 281:
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(372);
      END_STATE();
    case 282:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(373);
      END_STATE();
    case 283:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(374);
      END_STATE();
    case 284:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(375);
      END_STATE();
    case 285:
      if (lookahead == 'V' ||
          lookahead == 'v') ADVANCE(376);
      END_STATE();
    case 286:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(377);
      END_STATE();
    case 287:
      if (lookahead == 'Z' ||
          lookahead == 'z') ADVANCE(378);
      END_STATE();
    case 288:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(379);
      END_STATE();
    case 289:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(380);
      END_STATE();
    case 290:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token5);
      END_STATE();
    case 291:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(381);
      END_STATE();
    case 292:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(382);
      END_STATE();
    case 293:
      ACCEPT_TOKEN(aux_sym_keyword_action_token11);
      END_STATE();
    case 294:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(383);
      END_STATE();
    case 295:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(384);
      END_STATE();
    case 296:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(385);
      END_STATE();
    case 297:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(386);
      END_STATE();
    case 298:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(387);
      END_STATE();
    case 299:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(388);
      END_STATE();
    case 300:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(389);
      END_STATE();
    case 301:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(390);
      END_STATE();
    case 302:
      ACCEPT_TOKEN(aux_sym_keyword_order_token13);
      END_STATE();
    case 303:
      ACCEPT_TOKEN(aux_sym_keyword_order_token14);
      if (lookahead == '_') ADVANCE(391);
      END_STATE();
    case 304:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(392);
      END_STATE();
    case 305:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(393);
      END_STATE();
    case 306:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(394);
      END_STATE();
    case 307:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(395);
      END_STATE();
    case 308:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(396);
      END_STATE();
    case 309:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token19);
      if (lookahead == '_') ADVANCE(397);
      END_STATE();
    case 310:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token10);
      END_STATE();
    case 311:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(398);
      END_STATE();
    case 312:
      if (lookahead == '_') ADVANCE(399);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(400);
      END_STATE();
    case 313:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(401);
      END_STATE();
    case 314:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(402);
      END_STATE();
    case 315:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(403);
      END_STATE();
    case 316:
      ACCEPT_TOKEN(aux_sym_keyword_literal_token2);
      END_STATE();
    case 317:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(404);
      END_STATE();
    case 318:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(405);
      END_STATE();
    case 319:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(406);
      END_STATE();
    case 320:
      ACCEPT_TOKEN(aux_sym_keyword_action_token12);
      END_STATE();
    case 321:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(407);
      END_STATE();
    case 322:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(408);
      END_STATE();
    case 323:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token11);
      END_STATE();
    case 324:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token21);
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(409);
      END_STATE();
    case 325:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token1);
      END_STATE();
    case 326:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(410);
      END_STATE();
    case 327:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token1);
      END_STATE();
    case 328:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(411);
      END_STATE();
    case 329:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(412);
      END_STATE();
    case 330:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(413);
      END_STATE();
    case 331:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(414);
      END_STATE();
    case 332:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token3);
      END_STATE();
    case 333:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(415);
      END_STATE();
    case 334:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(416);
      END_STATE();
    case 335:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(417);
      END_STATE();
    case 336:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(418);
      END_STATE();
    case 337:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(419);
      END_STATE();
    case 338:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(420);
      END_STATE();
    case 339:
      ACCEPT_TOKEN(aux_sym_keyword_action_token4);
      if (lookahead == '_') ADVANCE(421);
      END_STATE();
    case 340:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(422);
      END_STATE();
    case 341:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(423);
      END_STATE();
    case 342:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(424);
      END_STATE();
    case 343:
      ACCEPT_TOKEN(aux_sym_keyword_action_token6);
      END_STATE();
    case 344:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(425);
      END_STATE();
    case 345:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token6);
      END_STATE();
    case 346:
      if (lookahead == '_') ADVANCE(426);
      END_STATE();
    case 347:
      if (lookahead == '_') ADVANCE(427);
      END_STATE();
    case 348:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(428);
      END_STATE();
    case 349:
      ACCEPT_TOKEN(aux_sym_keyword_action_token7);
      END_STATE();
    case 350:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token6);
      END_STATE();
    case 351:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(429);
      END_STATE();
    case 352:
      ACCEPT_TOKEN(aux_sym_keyword_literal_token1);
      END_STATE();
    case 353:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(430);
      END_STATE();
    case 354:
      ACCEPT_TOKEN(aux_sym_keyword_order_token4);
      END_STATE();
    case 355:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(431);
      END_STATE();
    case 356:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token9);
      END_STATE();
    case 357:
      ACCEPT_TOKEN(aux_sym_keyword_order_token9);
      END_STATE();
    case 358:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(432);
      END_STATE();
    case 359:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(433);
      END_STATE();
    case 360:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(434);
      END_STATE();
    case 361:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(435);
      END_STATE();
    case 362:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(436);
      END_STATE();
    case 363:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(437);
      END_STATE();
    case 364:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(438);
      END_STATE();
    case 365:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(439);
      END_STATE();
    case 366:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(440);
      END_STATE();
    case 367:
      ACCEPT_TOKEN(aux_sym_keyword_hook_token4);
      END_STATE();
    case 368:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(441);
      END_STATE();
    case 369:
      if (lookahead == '_') ADVANCE(442);
      END_STATE();
    case 370:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(443);
      END_STATE();
    case 371:
      ACCEPT_TOKEN(aux_sym_keyword_section_token5);
      END_STATE();
    case 372:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(444);
      END_STATE();
    case 373:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(445);
      END_STATE();
    case 374:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(446);
      END_STATE();
    case 375:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(447);
      END_STATE();
    case 376:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(448);
      END_STATE();
    case 377:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(449);
      END_STATE();
    case 378:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(450);
      END_STATE();
    case 379:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(451);
      END_STATE();
    case 380:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(452);
      END_STATE();
    case 381:
      ACCEPT_TOKEN(aux_sym_keyword_section_token8);
      END_STATE();
    case 382:
      if (lookahead == 'U' ||
          lookahead == 'u') ADVANCE(453);
      END_STATE();
    case 383:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(454);
      END_STATE();
    case 384:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(455);
      END_STATE();
    case 385:
      ACCEPT_TOKEN(aux_sym_keyword_flow_token9);
      END_STATE();
    case 386:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(456);
      END_STATE();
    case 387:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(457);
      END_STATE();
    case 388:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token6);
      if (lookahead == '_') ADVANCE(458);
      END_STATE();
    case 389:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token8);
      END_STATE();
    case 390:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token13);
      END_STATE();
    case 391:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(459);
      END_STATE();
    case 392:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(460);
      END_STATE();
    case 393:
      if (lookahead == 'K' ||
          lookahead == 'k') ADVANCE(461);
      END_STATE();
    case 394:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(462);
      END_STATE();
    case 395:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(463);
      END_STATE();
    case 396:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(464);
      END_STATE();
    case 397:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(465);
      END_STATE();
    case 398:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(466);
      END_STATE();
    case 399:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(467);
      END_STATE();
    case 400:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token9);
      END_STATE();
    case 401:
      ACCEPT_TOKEN(aux_sym_keyword_session_token8);
      END_STATE();
    case 402:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(468);
      END_STATE();
    case 403:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(469);
      END_STATE();
    case 404:
      ACCEPT_TOKEN(aux_sym_keyword_order_token21);
      END_STATE();
    case 405:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(470);
      END_STATE();
    case 406:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(471);
      END_STATE();
    case 407:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(472);
      END_STATE();
    case 408:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(473);
      END_STATE();
    case 409:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(474);
      END_STATE();
    case 410:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(475);
      END_STATE();
    case 411:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(476);
      END_STATE();
    case 412:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(477);
      END_STATE();
    case 413:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(478);
      END_STATE();
    case 414:
      ACCEPT_TOKEN(aux_sym_keyword_section_token1);
      END_STATE();
    case 415:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(479);
      END_STATE();
    case 416:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(480);
      END_STATE();
    case 417:
      ACCEPT_TOKEN(aux_sym_keyword_session_token2);
      END_STATE();
    case 418:
      ACCEPT_TOKEN(aux_sym_keyword_action_token2);
      if (lookahead == '_') ADVANCE(481);
      END_STATE();
    case 419:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(482);
      END_STATE();
    case 420:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(483);
      END_STATE();
    case 421:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(484);
      if (lookahead == 'B' ||
          lookahead == 'b') ADVANCE(485);
      END_STATE();
    case 422:
      if (lookahead == 'M' ||
          lookahead == 'm') ADVANCE(486);
      END_STATE();
    case 423:
      if (lookahead == 'W' ||
          lookahead == 'w') ADVANCE(487);
      END_STATE();
    case 424:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(488);
      END_STATE();
    case 425:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(489);
      END_STATE();
    case 426:
      if (lookahead == 'Q' ||
          lookahead == 'q') ADVANCE(490);
      END_STATE();
    case 427:
      if (lookahead == 'W' ||
          lookahead == 'w') ADVANCE(491);
      END_STATE();
    case 428:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token2);
      END_STATE();
    case 429:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token7);
      END_STATE();
    case 430:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(492);
      END_STATE();
    case 431:
      ACCEPT_TOKEN(aux_sym_keyword_section_token3);
      END_STATE();
    case 432:
      ACCEPT_TOKEN(aux_sym_keyword_session_token5);
      END_STATE();
    case 433:
      ACCEPT_TOKEN(aux_sym_keyword_order_token10);
      END_STATE();
    case 434:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token8);
      END_STATE();
    case 435:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(493);
      END_STATE();
    case 436:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(494);
      END_STATE();
    case 437:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token13);
      END_STATE();
    case 438:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(495);
      END_STATE();
    case 439:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(496);
      END_STATE();
    case 440:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(497);
      END_STATE();
    case 441:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(498);
      END_STATE();
    case 442:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(499);
      END_STATE();
    case 443:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(500);
      END_STATE();
    case 444:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(501);
      END_STATE();
    case 445:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(502);
      END_STATE();
    case 446:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token15);
      END_STATE();
    case 447:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(503);
      END_STATE();
    case 448:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(504);
      END_STATE();
    case 449:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(505);
      END_STATE();
    case 450:
      ACCEPT_TOKEN(aux_sym_keyword_action_token10);
      END_STATE();
    case 451:
      if (lookahead == '_') ADVANCE(506);
      END_STATE();
    case 452:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(507);
      END_STATE();
    case 453:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(508);
      END_STATE();
    case 454:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(509);
      END_STATE();
    case 455:
      ACCEPT_TOKEN(aux_sym_keyword_section_token11);
      END_STATE();
    case 456:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token6);
      END_STATE();
    case 457:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(510);
      END_STATE();
    case 458:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(511);
      END_STATE();
    case 459:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(512);
      END_STATE();
    case 460:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(513);
      END_STATE();
    case 461:
      ACCEPT_TOKEN(aux_sym_keyword_state_token9);
      END_STATE();
    case 462:
      ACCEPT_TOKEN(aux_sym_keyword_session_token7);
      END_STATE();
    case 463:
      ACCEPT_TOKEN(aux_sym_keyword_state_token10);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(514);
      END_STATE();
    case 464:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(515);
      END_STATE();
    case 465:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(516);
      END_STATE();
    case 466:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(517);
      END_STATE();
    case 467:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(518);
      END_STATE();
    case 468:
      ACCEPT_TOKEN(aux_sym_keyword_state_token11);
      END_STATE();
    case 469:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(519);
      END_STATE();
    case 470:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(520);
      END_STATE();
    case 471:
      ACCEPT_TOKEN(aux_sym_keyword_session_token10);
      END_STATE();
    case 472:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(521);
      END_STATE();
    case 473:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token14);
      END_STATE();
    case 474:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token10);
      END_STATE();
    case 475:
      ACCEPT_TOKEN(aux_sym_keyword_state_token1);
      END_STATE();
    case 476:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token1);
      END_STATE();
    case 477:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(522);
      END_STATE();
    case 478:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token1);
      END_STATE();
    case 479:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token4);
      END_STATE();
    case 480:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token3);
      END_STATE();
    case 481:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(523);
      END_STATE();
    case 482:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token3);
      END_STATE();
    case 483:
      ACCEPT_TOKEN(aux_sym_keyword_session_token3);
      END_STATE();
    case 484:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(524);
      END_STATE();
    case 485:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(525);
      END_STATE();
    case 486:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token5);
      END_STATE();
    case 487:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(526);
      END_STATE();
    case 488:
      ACCEPT_TOKEN(aux_sym_keyword_operator_word_token5);
      END_STATE();
    case 489:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token5);
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(527);
      END_STATE();
    case 490:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(528);
      END_STATE();
    case 491:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(529);
      END_STATE();
    case 492:
      ACCEPT_TOKEN(aux_sym_keyword_action_token8);
      END_STATE();
    case 493:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(530);
      END_STATE();
    case 494:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(531);
      END_STATE();
    case 495:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(532);
      END_STATE();
    case 496:
      ACCEPT_TOKEN(aux_sym_keyword_hook_token2);
      END_STATE();
    case 497:
      ACCEPT_TOKEN(aux_sym_keyword_hook_token3);
      END_STATE();
    case 498:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(533);
      END_STATE();
    case 499:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(534);
      END_STATE();
    case 500:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(535);
      END_STATE();
    case 501:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(536);
      END_STATE();
    case 502:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(537);
      END_STATE();
    case 503:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(538);
      END_STATE();
    case 504:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token4);
      END_STATE();
    case 505:
      ACCEPT_TOKEN(aux_sym_keyword_section_token7);
      END_STATE();
    case 506:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(539);
      END_STATE();
    case 507:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token17);
      END_STATE();
    case 508:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(540);
      END_STATE();
    case 509:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(541);
      END_STATE();
    case 510:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token5);
      END_STATE();
    case 511:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(542);
      END_STATE();
    case 512:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(543);
      END_STATE();
    case 513:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(544);
      END_STATE();
    case 514:
      ACCEPT_TOKEN(aux_sym_keyword_section_token13);
      END_STATE();
    case 515:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(545);
      END_STATE();
    case 516:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(546);
      END_STATE();
    case 517:
      ACCEPT_TOKEN(aux_sym_keyword_order_token18);
      END_STATE();
    case 518:
      if (lookahead == '_') ADVANCE(547);
      END_STATE();
    case 519:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(548);
      END_STATE();
    case 520:
      ACCEPT_TOKEN(aux_sym_keyword_section_token15);
      END_STATE();
    case 521:
      ACCEPT_TOKEN(aux_sym_keyword_session_token11);
      END_STATE();
    case 522:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token1);
      END_STATE();
    case 523:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(549);
      END_STATE();
    case 524:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(550);
      END_STATE();
    case 525:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(551);
      END_STATE();
    case 526:
      ACCEPT_TOKEN(aux_sym_keyword_state_token2);
      END_STATE();
    case 527:
      ACCEPT_TOKEN(aux_sym_keyword_section_token2);
      END_STATE();
    case 528:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(552);
      END_STATE();
    case 529:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(553);
      END_STATE();
    case 530:
      ACCEPT_TOKEN(aux_sym_keyword_sizing_token3);
      END_STATE();
    case 531:
      if (lookahead == 'Y' ||
          lookahead == 'y') ADVANCE(554);
      END_STATE();
    case 532:
      ACCEPT_TOKEN(aux_sym_keyword_hook_token1);
      END_STATE();
    case 533:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(555);
      END_STATE();
    case 534:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(556);
      END_STATE();
    case 535:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token10);
      END_STATE();
    case 536:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(557);
      END_STATE();
    case 537:
      ACCEPT_TOKEN(aux_sym_keyword_state_token7);
      if (lookahead == '_') ADVANCE(558);
      END_STATE();
    case 538:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(559);
      END_STATE();
    case 539:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(560);
      END_STATE();
    case 540:
      ACCEPT_TOKEN(aux_sym_keyword_section_token9);
      END_STATE();
    case 541:
      ACCEPT_TOKEN(aux_sym_keyword_section_token10);
      END_STATE();
    case 542:
      ACCEPT_TOKEN(aux_sym_keyword_stacking_token7);
      END_STATE();
    case 543:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(561);
      END_STATE();
    case 544:
      ACCEPT_TOKEN(aux_sym_keyword_section_token12);
      END_STATE();
    case 545:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(562);
      END_STATE();
    case 546:
      if (lookahead == 'F' ||
          lookahead == 'f') ADVANCE(563);
      END_STATE();
    case 547:
      if (lookahead == 'B' ||
          lookahead == 'b') ADVANCE(564);
      END_STATE();
    case 548:
      ACCEPT_TOKEN(aux_sym_keyword_order_token20);
      END_STATE();
    case 549:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(565);
      END_STATE();
    case 550:
      ACCEPT_TOKEN(aux_sym_keyword_action_token5);
      END_STATE();
    case 551:
      if (lookahead == 'O' ||
          lookahead == 'o') ADVANCE(566);
      END_STATE();
    case 552:
      ACCEPT_TOKEN(aux_sym_keyword_state_token3);
      END_STATE();
    case 553:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(567);
      END_STATE();
    case 554:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token12);
      END_STATE();
    case 555:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(568);
      END_STATE();
    case 556:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(569);
      END_STATE();
    case 557:
      ACCEPT_TOKEN(aux_sym_keyword_section_token6);
      END_STATE();
    case 558:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(570);
      END_STATE();
    case 559:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token11);
      END_STATE();
    case 560:
      if (lookahead == 'L' ||
          lookahead == 'l') ADVANCE(571);
      END_STATE();
    case 561:
      ACCEPT_TOKEN(aux_sym_keyword_order_token15);
      END_STATE();
    case 562:
      if (lookahead == 'Z' ||
          lookahead == 'z') ADVANCE(572);
      END_STATE();
    case 563:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(573);
      END_STATE();
    case 564:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(574);
      END_STATE();
    case 565:
      ACCEPT_TOKEN(aux_sym_keyword_action_token3);
      END_STATE();
    case 566:
      if (lookahead == 'N' ||
          lookahead == 'n') ADVANCE(575);
      END_STATE();
    case 567:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(576);
      END_STATE();
    case 568:
      if (lookahead == 'S' ||
          lookahead == 's') ADVANCE(577);
      END_STATE();
    case 569:
      ACCEPT_TOKEN(aux_sym_keyword_order_token11);
      END_STATE();
    case 570:
      if (lookahead == 'V' ||
          lookahead == 'v') ADVANCE(578);
      END_STATE();
    case 571:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(579);
      END_STATE();
    case 572:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(580);
      END_STATE();
    case 573:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(581);
      END_STATE();
    case 574:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(582);
      END_STATE();
    case 575:
      if (lookahead == 'D' ||
          lookahead == 'd') ADVANCE(583);
      END_STATE();
    case 576:
      if (lookahead == 'T' ||
          lookahead == 't') ADVANCE(584);
      END_STATE();
    case 577:
      ACCEPT_TOKEN(aux_sym_keyword_state_token6);
      END_STATE();
    case 578:
      if (lookahead == 'G' ||
          lookahead == 'g') ADVANCE(585);
      END_STATE();
    case 579:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token16);
      END_STATE();
    case 580:
      ACCEPT_TOKEN(aux_sym_keyword_section_token14);
      END_STATE();
    case 581:
      ACCEPT_TOKEN(aux_sym_keyword_order_token16);
      END_STATE();
    case 582:
      if (lookahead == 'A' ||
          lookahead == 'a') ADVANCE(586);
      END_STATE();
    case 583:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token4);
      END_STATE();
    case 584:
      ACCEPT_TOKEN(aux_sym_keyword_portfolio_token6);
      END_STATE();
    case 585:
      if (lookahead == '_') ADVANCE(587);
      END_STATE();
    case 586:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(588);
      END_STATE();
    case 587:
      if (lookahead == 'P' ||
          lookahead == 'p') ADVANCE(589);
      END_STATE();
    case 588:
      if (lookahead == 'H' ||
          lookahead == 'h') ADVANCE(590);
      END_STATE();
    case 589:
      if (lookahead == 'R' ||
          lookahead == 'r') ADVANCE(591);
      END_STATE();
    case 590:
      ACCEPT_TOKEN(aux_sym_keyword_bracket_token20);
      END_STATE();
    case 591:
      if (lookahead == 'I' ||
          lookahead == 'i') ADVANCE(592);
      END_STATE();
    case 592:
      if (lookahead == 'C' ||
          lookahead == 'c') ADVANCE(593);
      END_STATE();
    case 593:
      if (lookahead == 'E' ||
          lookahead == 'e') ADVANCE(594);
      END_STATE();
    case 594:
      ACCEPT_TOKEN(aux_sym_keyword_state_token8);
      END_STATE();
    default:
      return false;
  }
}

static const TSLexMode ts_lex_modes[STATE_COUNT] = {
  [0] = {.lex_state = 0},
  [1] = {.lex_state = 0},
  [2] = {.lex_state = 0},
  [3] = {.lex_state = 0},
  [4] = {.lex_state = 0},
  [5] = {.lex_state = 0},
  [6] = {.lex_state = 0},
  [7] = {.lex_state = 0},
  [8] = {.lex_state = 0},
  [9] = {.lex_state = 0},
  [10] = {.lex_state = 0},
  [11] = {.lex_state = 0},
  [12] = {.lex_state = 0},
  [13] = {.lex_state = 0},
  [14] = {.lex_state = 0},
  [15] = {.lex_state = 0},
  [16] = {.lex_state = 0},
  [17] = {.lex_state = 0},
  [18] = {.lex_state = 0},
  [19] = {.lex_state = 0},
  [20] = {.lex_state = 0},
  [21] = {.lex_state = 0},
  [22] = {.lex_state = 0},
  [23] = {.lex_state = 0},
  [24] = {.lex_state = 0},
  [25] = {.lex_state = 0},
};

static const uint16_t ts_parse_table[LARGE_STATE_COUNT][SYMBOL_COUNT] = {
  [0] = {
    [ts_builtin_sym_end] = ACTIONS(1),
    [sym_identifier] = ACTIONS(1),
    [aux_sym_keyword_section_token1] = ACTIONS(1),
    [aux_sym_keyword_section_token2] = ACTIONS(1),
    [aux_sym_keyword_section_token3] = ACTIONS(1),
    [aux_sym_keyword_section_token4] = ACTIONS(1),
    [aux_sym_keyword_section_token5] = ACTIONS(1),
    [aux_sym_keyword_section_token6] = ACTIONS(1),
    [aux_sym_keyword_section_token7] = ACTIONS(1),
    [aux_sym_keyword_section_token8] = ACTIONS(1),
    [aux_sym_keyword_section_token9] = ACTIONS(1),
    [aux_sym_keyword_section_token10] = ACTIONS(1),
    [aux_sym_keyword_section_token11] = ACTIONS(1),
    [aux_sym_keyword_section_token12] = ACTIONS(1),
    [aux_sym_keyword_section_token13] = ACTIONS(1),
    [aux_sym_keyword_section_token14] = ACTIONS(1),
    [aux_sym_keyword_section_token15] = ACTIONS(1),
    [aux_sym_keyword_flow_token1] = ACTIONS(1),
    [aux_sym_keyword_flow_token2] = ACTIONS(1),
    [aux_sym_keyword_flow_token3] = ACTIONS(1),
    [aux_sym_keyword_flow_token4] = ACTIONS(1),
    [aux_sym_keyword_flow_token5] = ACTIONS(1),
    [aux_sym_keyword_flow_token6] = ACTIONS(1),
    [aux_sym_keyword_flow_token7] = ACTIONS(1),
    [aux_sym_keyword_flow_token8] = ACTIONS(1),
    [aux_sym_keyword_flow_token9] = ACTIONS(1),
    [aux_sym_keyword_flow_token10] = ACTIONS(1),
    [aux_sym_keyword_flow_token11] = ACTIONS(1),
    [aux_sym_keyword_action_token1] = ACTIONS(1),
    [aux_sym_keyword_action_token2] = ACTIONS(1),
    [aux_sym_keyword_action_token3] = ACTIONS(1),
    [aux_sym_keyword_action_token4] = ACTIONS(1),
    [aux_sym_keyword_action_token5] = ACTIONS(1),
    [aux_sym_keyword_action_token6] = ACTIONS(1),
    [aux_sym_keyword_action_token7] = ACTIONS(1),
    [aux_sym_keyword_action_token8] = ACTIONS(1),
    [aux_sym_keyword_action_token9] = ACTIONS(1),
    [aux_sym_keyword_action_token10] = ACTIONS(1),
    [aux_sym_keyword_action_token11] = ACTIONS(1),
    [aux_sym_keyword_action_token12] = ACTIONS(1),
    [aux_sym_keyword_order_token1] = ACTIONS(1),
    [aux_sym_keyword_order_token2] = ACTIONS(1),
    [aux_sym_keyword_order_token3] = ACTIONS(1),
    [aux_sym_keyword_order_token4] = ACTIONS(1),
    [aux_sym_keyword_order_token5] = ACTIONS(1),
    [aux_sym_keyword_order_token6] = ACTIONS(1),
    [aux_sym_keyword_order_token7] = ACTIONS(1),
    [aux_sym_keyword_order_token8] = ACTIONS(1),
    [aux_sym_keyword_order_token9] = ACTIONS(1),
    [aux_sym_keyword_order_token10] = ACTIONS(1),
    [aux_sym_keyword_order_token11] = ACTIONS(1),
    [aux_sym_keyword_order_token12] = ACTIONS(1),
    [aux_sym_keyword_order_token13] = ACTIONS(1),
    [aux_sym_keyword_order_token14] = ACTIONS(1),
    [aux_sym_keyword_order_token15] = ACTIONS(1),
    [aux_sym_keyword_order_token16] = ACTIONS(1),
    [aux_sym_keyword_order_token17] = ACTIONS(1),
    [aux_sym_keyword_order_token18] = ACTIONS(1),
    [aux_sym_keyword_order_token19] = ACTIONS(1),
    [aux_sym_keyword_order_token20] = ACTIONS(1),
    [aux_sym_keyword_order_token21] = ACTIONS(1),
    [aux_sym_keyword_sizing_token1] = ACTIONS(1),
    [aux_sym_keyword_sizing_token2] = ACTIONS(1),
    [aux_sym_keyword_sizing_token3] = ACTIONS(1),
    [aux_sym_keyword_sizing_token4] = ACTIONS(1),
    [aux_sym_keyword_sizing_token5] = ACTIONS(1),
    [aux_sym_keyword_sizing_token6] = ACTIONS(1),
    [aux_sym_keyword_sizing_token7] = ACTIONS(1),
    [aux_sym_keyword_bracket_token1] = ACTIONS(1),
    [aux_sym_keyword_bracket_token2] = ACTIONS(1),
    [aux_sym_keyword_bracket_token3] = ACTIONS(1),
    [aux_sym_keyword_bracket_token4] = ACTIONS(1),
    [aux_sym_keyword_bracket_token5] = ACTIONS(1),
    [aux_sym_keyword_bracket_token6] = ACTIONS(1),
    [aux_sym_keyword_bracket_token7] = ACTIONS(1),
    [aux_sym_keyword_bracket_token8] = ACTIONS(1),
    [aux_sym_keyword_bracket_token9] = ACTIONS(1),
    [aux_sym_keyword_bracket_token10] = ACTIONS(1),
    [aux_sym_keyword_bracket_token11] = ACTIONS(1),
    [aux_sym_keyword_bracket_token12] = ACTIONS(1),
    [aux_sym_keyword_bracket_token13] = ACTIONS(1),
    [aux_sym_keyword_bracket_token14] = ACTIONS(1),
    [aux_sym_keyword_bracket_token15] = ACTIONS(1),
    [aux_sym_keyword_bracket_token16] = ACTIONS(1),
    [aux_sym_keyword_bracket_token17] = ACTIONS(1),
    [aux_sym_keyword_bracket_token18] = ACTIONS(1),
    [aux_sym_keyword_bracket_token19] = ACTIONS(1),
    [aux_sym_keyword_bracket_token20] = ACTIONS(1),
    [aux_sym_keyword_bracket_token21] = ACTIONS(1),
    [aux_sym_keyword_stacking_token1] = ACTIONS(1),
    [aux_sym_keyword_stacking_token2] = ACTIONS(1),
    [aux_sym_keyword_stacking_token3] = ACTIONS(1),
    [aux_sym_keyword_stacking_token4] = ACTIONS(1),
    [aux_sym_keyword_stacking_token5] = ACTIONS(1),
    [aux_sym_keyword_stacking_token6] = ACTIONS(1),
    [aux_sym_keyword_stacking_token7] = ACTIONS(1),
    [aux_sym_keyword_stacking_token8] = ACTIONS(1),
    [aux_sym_keyword_stacking_token9] = ACTIONS(1),
    [aux_sym_keyword_stacking_token10] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(1),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(1),
    [aux_sym_keyword_session_token1] = ACTIONS(1),
    [aux_sym_keyword_session_token2] = ACTIONS(1),
    [aux_sym_keyword_session_token3] = ACTIONS(1),
    [aux_sym_keyword_session_token4] = ACTIONS(1),
    [aux_sym_keyword_session_token5] = ACTIONS(1),
    [aux_sym_keyword_session_token6] = ACTIONS(1),
    [aux_sym_keyword_session_token7] = ACTIONS(1),
    [aux_sym_keyword_session_token8] = ACTIONS(1),
    [aux_sym_keyword_session_token9] = ACTIONS(1),
    [aux_sym_keyword_session_token10] = ACTIONS(1),
    [aux_sym_keyword_session_token11] = ACTIONS(1),
    [aux_sym_keyword_hook_token1] = ACTIONS(1),
    [aux_sym_keyword_hook_token2] = ACTIONS(1),
    [aux_sym_keyword_hook_token3] = ACTIONS(1),
    [aux_sym_keyword_hook_token4] = ACTIONS(1),
    [aux_sym_keyword_state_token1] = ACTIONS(1),
    [aux_sym_keyword_state_token2] = ACTIONS(1),
    [aux_sym_keyword_state_token3] = ACTIONS(1),
    [aux_sym_keyword_state_token4] = ACTIONS(1),
    [aux_sym_keyword_state_token5] = ACTIONS(1),
    [aux_sym_keyword_state_token6] = ACTIONS(1),
    [aux_sym_keyword_state_token7] = ACTIONS(1),
    [aux_sym_keyword_state_token8] = ACTIONS(1),
    [aux_sym_keyword_state_token9] = ACTIONS(1),
    [aux_sym_keyword_state_token10] = ACTIONS(1),
    [aux_sym_keyword_state_token11] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(1),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(1),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(1),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(1),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(1),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(1),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(1),
    [aux_sym_keyword_literal_token1] = ACTIONS(1),
    [aux_sym_keyword_literal_token2] = ACTIONS(1),
    [anon_sym_LPAREN] = ACTIONS(1),
    [anon_sym_DOT] = ACTIONS(1),
    [sym_broker_symbol] = ACTIONS(1),
    [sym_duration] = ACTIONS(1),
    [sym_number] = ACTIONS(1),
    [aux_sym_string_token1] = ACTIONS(1),
    [aux_sym_string_token2] = ACTIONS(1),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(1),
    [anon_sym_EQ_EQ] = ACTIONS(1),
    [anon_sym_LT_GT] = ACTIONS(1),
    [anon_sym_BANG_EQ] = ACTIONS(1),
    [anon_sym_LT_EQ] = ACTIONS(1),
    [anon_sym_GT_EQ] = ACTIONS(1),
    [anon_sym_LT] = ACTIONS(1),
    [anon_sym_GT] = ACTIONS(1),
    [anon_sym_EQ] = ACTIONS(1),
    [anon_sym_PLUS] = ACTIONS(1),
    [anon_sym_DASH] = ACTIONS(1),
    [anon_sym_STAR] = ACTIONS(1),
    [anon_sym_SLASH] = ACTIONS(1),
    [anon_sym_PERCENT] = ACTIONS(1),
    [anon_sym_RPAREN] = ACTIONS(1),
    [anon_sym_LBRACK] = ACTIONS(1),
    [anon_sym_RBRACK] = ACTIONS(1),
    [anon_sym_LBRACE] = ACTIONS(1),
    [anon_sym_RBRACE] = ACTIONS(1),
    [anon_sym_COMMA] = ACTIONS(1),
    [anon_sym_COLON] = ACTIONS(1),
    [anon_sym_SEMI] = ACTIONS(1),
    [anon_sym_AT] = ACTIONS(1),
    [anon_sym_DOLLAR] = ACTIONS(1),
  },
  [1] = {
    [sym_source_file] = STATE(25),
    [sym__item] = STATE(3),
    [sym_keyword_section] = STATE(3),
    [sym_keyword_flow] = STATE(3),
    [sym_keyword_action] = STATE(3),
    [sym_keyword_order] = STATE(3),
    [sym_keyword_sizing] = STATE(3),
    [sym_keyword_bracket] = STATE(3),
    [sym_keyword_stacking] = STATE(3),
    [sym_keyword_portfolio] = STATE(3),
    [sym_keyword_session] = STATE(3),
    [sym_keyword_hook] = STATE(3),
    [sym_keyword_state] = STATE(3),
    [sym_keyword_operator_word] = STATE(3),
    [sym_keyword_aggregate] = STATE(3),
    [sym_keyword_literal] = STATE(3),
    [sym_call] = STATE(3),
    [sym_member] = STATE(3),
    [sym_string] = STATE(3),
    [sym_operator] = STATE(3),
    [sym_punctuation] = STATE(3),
    [aux_sym_source_file_repeat1] = STATE(3),
    [ts_builtin_sym_end] = ACTIONS(5),
    [sym_identifier] = ACTIONS(7),
    [aux_sym_keyword_section_token1] = ACTIONS(9),
    [aux_sym_keyword_section_token2] = ACTIONS(9),
    [aux_sym_keyword_section_token3] = ACTIONS(9),
    [aux_sym_keyword_section_token4] = ACTIONS(9),
    [aux_sym_keyword_section_token5] = ACTIONS(9),
    [aux_sym_keyword_section_token6] = ACTIONS(9),
    [aux_sym_keyword_section_token7] = ACTIONS(9),
    [aux_sym_keyword_section_token8] = ACTIONS(9),
    [aux_sym_keyword_section_token9] = ACTIONS(9),
    [aux_sym_keyword_section_token10] = ACTIONS(9),
    [aux_sym_keyword_section_token11] = ACTIONS(9),
    [aux_sym_keyword_section_token12] = ACTIONS(9),
    [aux_sym_keyword_section_token13] = ACTIONS(9),
    [aux_sym_keyword_section_token14] = ACTIONS(9),
    [aux_sym_keyword_section_token15] = ACTIONS(9),
    [aux_sym_keyword_flow_token1] = ACTIONS(11),
    [aux_sym_keyword_flow_token2] = ACTIONS(11),
    [aux_sym_keyword_flow_token3] = ACTIONS(11),
    [aux_sym_keyword_flow_token4] = ACTIONS(11),
    [aux_sym_keyword_flow_token5] = ACTIONS(11),
    [aux_sym_keyword_flow_token6] = ACTIONS(11),
    [aux_sym_keyword_flow_token7] = ACTIONS(11),
    [aux_sym_keyword_flow_token8] = ACTIONS(11),
    [aux_sym_keyword_flow_token9] = ACTIONS(11),
    [aux_sym_keyword_flow_token10] = ACTIONS(11),
    [aux_sym_keyword_flow_token11] = ACTIONS(11),
    [aux_sym_keyword_action_token1] = ACTIONS(13),
    [aux_sym_keyword_action_token2] = ACTIONS(13),
    [aux_sym_keyword_action_token3] = ACTIONS(13),
    [aux_sym_keyword_action_token4] = ACTIONS(13),
    [aux_sym_keyword_action_token5] = ACTIONS(13),
    [aux_sym_keyword_action_token6] = ACTIONS(13),
    [aux_sym_keyword_action_token7] = ACTIONS(13),
    [aux_sym_keyword_action_token8] = ACTIONS(13),
    [aux_sym_keyword_action_token9] = ACTIONS(13),
    [aux_sym_keyword_action_token10] = ACTIONS(13),
    [aux_sym_keyword_action_token11] = ACTIONS(13),
    [aux_sym_keyword_action_token12] = ACTIONS(13),
    [aux_sym_keyword_order_token1] = ACTIONS(15),
    [aux_sym_keyword_order_token2] = ACTIONS(15),
    [aux_sym_keyword_order_token3] = ACTIONS(15),
    [aux_sym_keyword_order_token4] = ACTIONS(15),
    [aux_sym_keyword_order_token5] = ACTIONS(15),
    [aux_sym_keyword_order_token6] = ACTIONS(15),
    [aux_sym_keyword_order_token7] = ACTIONS(15),
    [aux_sym_keyword_order_token8] = ACTIONS(15),
    [aux_sym_keyword_order_token9] = ACTIONS(15),
    [aux_sym_keyword_order_token10] = ACTIONS(15),
    [aux_sym_keyword_order_token11] = ACTIONS(15),
    [aux_sym_keyword_order_token12] = ACTIONS(15),
    [aux_sym_keyword_order_token13] = ACTIONS(15),
    [aux_sym_keyword_order_token14] = ACTIONS(15),
    [aux_sym_keyword_order_token15] = ACTIONS(15),
    [aux_sym_keyword_order_token16] = ACTIONS(15),
    [aux_sym_keyword_order_token17] = ACTIONS(15),
    [aux_sym_keyword_order_token18] = ACTIONS(15),
    [aux_sym_keyword_order_token19] = ACTIONS(15),
    [aux_sym_keyword_order_token20] = ACTIONS(15),
    [aux_sym_keyword_order_token21] = ACTIONS(15),
    [aux_sym_keyword_sizing_token1] = ACTIONS(17),
    [aux_sym_keyword_sizing_token2] = ACTIONS(17),
    [aux_sym_keyword_sizing_token3] = ACTIONS(17),
    [aux_sym_keyword_sizing_token4] = ACTIONS(17),
    [aux_sym_keyword_sizing_token5] = ACTIONS(17),
    [aux_sym_keyword_sizing_token6] = ACTIONS(17),
    [aux_sym_keyword_sizing_token7] = ACTIONS(17),
    [aux_sym_keyword_bracket_token1] = ACTIONS(19),
    [aux_sym_keyword_bracket_token2] = ACTIONS(19),
    [aux_sym_keyword_bracket_token3] = ACTIONS(19),
    [aux_sym_keyword_bracket_token4] = ACTIONS(19),
    [aux_sym_keyword_bracket_token5] = ACTIONS(19),
    [aux_sym_keyword_bracket_token6] = ACTIONS(19),
    [aux_sym_keyword_bracket_token7] = ACTIONS(19),
    [aux_sym_keyword_bracket_token8] = ACTIONS(19),
    [aux_sym_keyword_bracket_token9] = ACTIONS(19),
    [aux_sym_keyword_bracket_token10] = ACTIONS(19),
    [aux_sym_keyword_bracket_token11] = ACTIONS(19),
    [aux_sym_keyword_bracket_token12] = ACTIONS(19),
    [aux_sym_keyword_bracket_token13] = ACTIONS(19),
    [aux_sym_keyword_bracket_token14] = ACTIONS(19),
    [aux_sym_keyword_bracket_token15] = ACTIONS(19),
    [aux_sym_keyword_bracket_token16] = ACTIONS(19),
    [aux_sym_keyword_bracket_token17] = ACTIONS(19),
    [aux_sym_keyword_bracket_token18] = ACTIONS(19),
    [aux_sym_keyword_bracket_token19] = ACTIONS(19),
    [aux_sym_keyword_bracket_token20] = ACTIONS(19),
    [aux_sym_keyword_bracket_token21] = ACTIONS(19),
    [aux_sym_keyword_stacking_token1] = ACTIONS(21),
    [aux_sym_keyword_stacking_token2] = ACTIONS(21),
    [aux_sym_keyword_stacking_token3] = ACTIONS(21),
    [aux_sym_keyword_stacking_token4] = ACTIONS(21),
    [aux_sym_keyword_stacking_token5] = ACTIONS(21),
    [aux_sym_keyword_stacking_token6] = ACTIONS(21),
    [aux_sym_keyword_stacking_token7] = ACTIONS(21),
    [aux_sym_keyword_stacking_token8] = ACTIONS(21),
    [aux_sym_keyword_stacking_token9] = ACTIONS(21),
    [aux_sym_keyword_stacking_token10] = ACTIONS(21),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(23),
    [aux_sym_keyword_session_token1] = ACTIONS(25),
    [aux_sym_keyword_session_token2] = ACTIONS(25),
    [aux_sym_keyword_session_token3] = ACTIONS(25),
    [aux_sym_keyword_session_token4] = ACTIONS(25),
    [aux_sym_keyword_session_token5] = ACTIONS(25),
    [aux_sym_keyword_session_token6] = ACTIONS(25),
    [aux_sym_keyword_session_token7] = ACTIONS(25),
    [aux_sym_keyword_session_token8] = ACTIONS(25),
    [aux_sym_keyword_session_token9] = ACTIONS(25),
    [aux_sym_keyword_session_token10] = ACTIONS(25),
    [aux_sym_keyword_session_token11] = ACTIONS(25),
    [aux_sym_keyword_hook_token1] = ACTIONS(27),
    [aux_sym_keyword_hook_token2] = ACTIONS(27),
    [aux_sym_keyword_hook_token3] = ACTIONS(27),
    [aux_sym_keyword_hook_token4] = ACTIONS(27),
    [aux_sym_keyword_state_token1] = ACTIONS(29),
    [aux_sym_keyword_state_token2] = ACTIONS(29),
    [aux_sym_keyword_state_token3] = ACTIONS(29),
    [aux_sym_keyword_state_token4] = ACTIONS(29),
    [aux_sym_keyword_state_token5] = ACTIONS(29),
    [aux_sym_keyword_state_token6] = ACTIONS(29),
    [aux_sym_keyword_state_token7] = ACTIONS(29),
    [aux_sym_keyword_state_token8] = ACTIONS(29),
    [aux_sym_keyword_state_token9] = ACTIONS(29),
    [aux_sym_keyword_state_token10] = ACTIONS(29),
    [aux_sym_keyword_state_token11] = ACTIONS(29),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(31),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(33),
    [aux_sym_keyword_literal_token1] = ACTIONS(35),
    [aux_sym_keyword_literal_token2] = ACTIONS(35),
    [anon_sym_LPAREN] = ACTIONS(37),
    [anon_sym_DOT] = ACTIONS(39),
    [sym_broker_symbol] = ACTIONS(41),
    [sym_duration] = ACTIONS(41),
    [sym_number] = ACTIONS(43),
    [aux_sym_string_token1] = ACTIONS(45),
    [aux_sym_string_token2] = ACTIONS(45),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(47),
    [anon_sym_EQ_EQ] = ACTIONS(47),
    [anon_sym_LT_GT] = ACTIONS(47),
    [anon_sym_BANG_EQ] = ACTIONS(47),
    [anon_sym_LT_EQ] = ACTIONS(47),
    [anon_sym_GT_EQ] = ACTIONS(47),
    [anon_sym_LT] = ACTIONS(49),
    [anon_sym_GT] = ACTIONS(49),
    [anon_sym_EQ] = ACTIONS(49),
    [anon_sym_PLUS] = ACTIONS(47),
    [anon_sym_DASH] = ACTIONS(49),
    [anon_sym_STAR] = ACTIONS(47),
    [anon_sym_SLASH] = ACTIONS(49),
    [anon_sym_PERCENT] = ACTIONS(47),
    [anon_sym_RPAREN] = ACTIONS(37),
    [anon_sym_LBRACK] = ACTIONS(37),
    [anon_sym_RBRACK] = ACTIONS(37),
    [anon_sym_LBRACE] = ACTIONS(37),
    [anon_sym_RBRACE] = ACTIONS(37),
    [anon_sym_COMMA] = ACTIONS(37),
    [anon_sym_COLON] = ACTIONS(37),
    [anon_sym_SEMI] = ACTIONS(37),
    [anon_sym_AT] = ACTIONS(37),
    [anon_sym_DOLLAR] = ACTIONS(37),
  },
  [2] = {
    [sym__item] = STATE(2),
    [sym_keyword_section] = STATE(2),
    [sym_keyword_flow] = STATE(2),
    [sym_keyword_action] = STATE(2),
    [sym_keyword_order] = STATE(2),
    [sym_keyword_sizing] = STATE(2),
    [sym_keyword_bracket] = STATE(2),
    [sym_keyword_stacking] = STATE(2),
    [sym_keyword_portfolio] = STATE(2),
    [sym_keyword_session] = STATE(2),
    [sym_keyword_hook] = STATE(2),
    [sym_keyword_state] = STATE(2),
    [sym_keyword_operator_word] = STATE(2),
    [sym_keyword_aggregate] = STATE(2),
    [sym_keyword_literal] = STATE(2),
    [sym_call] = STATE(2),
    [sym_member] = STATE(2),
    [sym_string] = STATE(2),
    [sym_operator] = STATE(2),
    [sym_punctuation] = STATE(2),
    [aux_sym_source_file_repeat1] = STATE(2),
    [ts_builtin_sym_end] = ACTIONS(51),
    [sym_identifier] = ACTIONS(53),
    [aux_sym_keyword_section_token1] = ACTIONS(56),
    [aux_sym_keyword_section_token2] = ACTIONS(56),
    [aux_sym_keyword_section_token3] = ACTIONS(56),
    [aux_sym_keyword_section_token4] = ACTIONS(56),
    [aux_sym_keyword_section_token5] = ACTIONS(56),
    [aux_sym_keyword_section_token6] = ACTIONS(56),
    [aux_sym_keyword_section_token7] = ACTIONS(56),
    [aux_sym_keyword_section_token8] = ACTIONS(56),
    [aux_sym_keyword_section_token9] = ACTIONS(56),
    [aux_sym_keyword_section_token10] = ACTIONS(56),
    [aux_sym_keyword_section_token11] = ACTIONS(56),
    [aux_sym_keyword_section_token12] = ACTIONS(56),
    [aux_sym_keyword_section_token13] = ACTIONS(56),
    [aux_sym_keyword_section_token14] = ACTIONS(56),
    [aux_sym_keyword_section_token15] = ACTIONS(56),
    [aux_sym_keyword_flow_token1] = ACTIONS(59),
    [aux_sym_keyword_flow_token2] = ACTIONS(59),
    [aux_sym_keyword_flow_token3] = ACTIONS(59),
    [aux_sym_keyword_flow_token4] = ACTIONS(59),
    [aux_sym_keyword_flow_token5] = ACTIONS(59),
    [aux_sym_keyword_flow_token6] = ACTIONS(59),
    [aux_sym_keyword_flow_token7] = ACTIONS(59),
    [aux_sym_keyword_flow_token8] = ACTIONS(59),
    [aux_sym_keyword_flow_token9] = ACTIONS(59),
    [aux_sym_keyword_flow_token10] = ACTIONS(59),
    [aux_sym_keyword_flow_token11] = ACTIONS(59),
    [aux_sym_keyword_action_token1] = ACTIONS(62),
    [aux_sym_keyword_action_token2] = ACTIONS(62),
    [aux_sym_keyword_action_token3] = ACTIONS(62),
    [aux_sym_keyword_action_token4] = ACTIONS(62),
    [aux_sym_keyword_action_token5] = ACTIONS(62),
    [aux_sym_keyword_action_token6] = ACTIONS(62),
    [aux_sym_keyword_action_token7] = ACTIONS(62),
    [aux_sym_keyword_action_token8] = ACTIONS(62),
    [aux_sym_keyword_action_token9] = ACTIONS(62),
    [aux_sym_keyword_action_token10] = ACTIONS(62),
    [aux_sym_keyword_action_token11] = ACTIONS(62),
    [aux_sym_keyword_action_token12] = ACTIONS(62),
    [aux_sym_keyword_order_token1] = ACTIONS(65),
    [aux_sym_keyword_order_token2] = ACTIONS(65),
    [aux_sym_keyword_order_token3] = ACTIONS(65),
    [aux_sym_keyword_order_token4] = ACTIONS(65),
    [aux_sym_keyword_order_token5] = ACTIONS(65),
    [aux_sym_keyword_order_token6] = ACTIONS(65),
    [aux_sym_keyword_order_token7] = ACTIONS(65),
    [aux_sym_keyword_order_token8] = ACTIONS(65),
    [aux_sym_keyword_order_token9] = ACTIONS(65),
    [aux_sym_keyword_order_token10] = ACTIONS(65),
    [aux_sym_keyword_order_token11] = ACTIONS(65),
    [aux_sym_keyword_order_token12] = ACTIONS(65),
    [aux_sym_keyword_order_token13] = ACTIONS(65),
    [aux_sym_keyword_order_token14] = ACTIONS(65),
    [aux_sym_keyword_order_token15] = ACTIONS(65),
    [aux_sym_keyword_order_token16] = ACTIONS(65),
    [aux_sym_keyword_order_token17] = ACTIONS(65),
    [aux_sym_keyword_order_token18] = ACTIONS(65),
    [aux_sym_keyword_order_token19] = ACTIONS(65),
    [aux_sym_keyword_order_token20] = ACTIONS(65),
    [aux_sym_keyword_order_token21] = ACTIONS(65),
    [aux_sym_keyword_sizing_token1] = ACTIONS(68),
    [aux_sym_keyword_sizing_token2] = ACTIONS(68),
    [aux_sym_keyword_sizing_token3] = ACTIONS(68),
    [aux_sym_keyword_sizing_token4] = ACTIONS(68),
    [aux_sym_keyword_sizing_token5] = ACTIONS(68),
    [aux_sym_keyword_sizing_token6] = ACTIONS(68),
    [aux_sym_keyword_sizing_token7] = ACTIONS(68),
    [aux_sym_keyword_bracket_token1] = ACTIONS(71),
    [aux_sym_keyword_bracket_token2] = ACTIONS(71),
    [aux_sym_keyword_bracket_token3] = ACTIONS(71),
    [aux_sym_keyword_bracket_token4] = ACTIONS(71),
    [aux_sym_keyword_bracket_token5] = ACTIONS(71),
    [aux_sym_keyword_bracket_token6] = ACTIONS(71),
    [aux_sym_keyword_bracket_token7] = ACTIONS(71),
    [aux_sym_keyword_bracket_token8] = ACTIONS(71),
    [aux_sym_keyword_bracket_token9] = ACTIONS(71),
    [aux_sym_keyword_bracket_token10] = ACTIONS(71),
    [aux_sym_keyword_bracket_token11] = ACTIONS(71),
    [aux_sym_keyword_bracket_token12] = ACTIONS(71),
    [aux_sym_keyword_bracket_token13] = ACTIONS(71),
    [aux_sym_keyword_bracket_token14] = ACTIONS(71),
    [aux_sym_keyword_bracket_token15] = ACTIONS(71),
    [aux_sym_keyword_bracket_token16] = ACTIONS(71),
    [aux_sym_keyword_bracket_token17] = ACTIONS(71),
    [aux_sym_keyword_bracket_token18] = ACTIONS(71),
    [aux_sym_keyword_bracket_token19] = ACTIONS(71),
    [aux_sym_keyword_bracket_token20] = ACTIONS(71),
    [aux_sym_keyword_bracket_token21] = ACTIONS(71),
    [aux_sym_keyword_stacking_token1] = ACTIONS(74),
    [aux_sym_keyword_stacking_token2] = ACTIONS(74),
    [aux_sym_keyword_stacking_token3] = ACTIONS(74),
    [aux_sym_keyword_stacking_token4] = ACTIONS(74),
    [aux_sym_keyword_stacking_token5] = ACTIONS(74),
    [aux_sym_keyword_stacking_token6] = ACTIONS(74),
    [aux_sym_keyword_stacking_token7] = ACTIONS(74),
    [aux_sym_keyword_stacking_token8] = ACTIONS(74),
    [aux_sym_keyword_stacking_token9] = ACTIONS(74),
    [aux_sym_keyword_stacking_token10] = ACTIONS(74),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(77),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(77),
    [aux_sym_keyword_session_token1] = ACTIONS(80),
    [aux_sym_keyword_session_token2] = ACTIONS(80),
    [aux_sym_keyword_session_token3] = ACTIONS(80),
    [aux_sym_keyword_session_token4] = ACTIONS(80),
    [aux_sym_keyword_session_token5] = ACTIONS(80),
    [aux_sym_keyword_session_token6] = ACTIONS(80),
    [aux_sym_keyword_session_token7] = ACTIONS(80),
    [aux_sym_keyword_session_token8] = ACTIONS(80),
    [aux_sym_keyword_session_token9] = ACTIONS(80),
    [aux_sym_keyword_session_token10] = ACTIONS(80),
    [aux_sym_keyword_session_token11] = ACTIONS(80),
    [aux_sym_keyword_hook_token1] = ACTIONS(83),
    [aux_sym_keyword_hook_token2] = ACTIONS(83),
    [aux_sym_keyword_hook_token3] = ACTIONS(83),
    [aux_sym_keyword_hook_token4] = ACTIONS(83),
    [aux_sym_keyword_state_token1] = ACTIONS(86),
    [aux_sym_keyword_state_token2] = ACTIONS(86),
    [aux_sym_keyword_state_token3] = ACTIONS(86),
    [aux_sym_keyword_state_token4] = ACTIONS(86),
    [aux_sym_keyword_state_token5] = ACTIONS(86),
    [aux_sym_keyword_state_token6] = ACTIONS(86),
    [aux_sym_keyword_state_token7] = ACTIONS(86),
    [aux_sym_keyword_state_token8] = ACTIONS(86),
    [aux_sym_keyword_state_token9] = ACTIONS(86),
    [aux_sym_keyword_state_token10] = ACTIONS(86),
    [aux_sym_keyword_state_token11] = ACTIONS(86),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(89),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(89),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(92),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(92),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(92),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(92),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(92),
    [aux_sym_keyword_literal_token1] = ACTIONS(95),
    [aux_sym_keyword_literal_token2] = ACTIONS(95),
    [anon_sym_LPAREN] = ACTIONS(98),
    [anon_sym_DOT] = ACTIONS(101),
    [sym_broker_symbol] = ACTIONS(104),
    [sym_duration] = ACTIONS(104),
    [sym_number] = ACTIONS(107),
    [aux_sym_string_token1] = ACTIONS(110),
    [aux_sym_string_token2] = ACTIONS(110),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(113),
    [anon_sym_EQ_EQ] = ACTIONS(113),
    [anon_sym_LT_GT] = ACTIONS(113),
    [anon_sym_BANG_EQ] = ACTIONS(113),
    [anon_sym_LT_EQ] = ACTIONS(113),
    [anon_sym_GT_EQ] = ACTIONS(113),
    [anon_sym_LT] = ACTIONS(116),
    [anon_sym_GT] = ACTIONS(116),
    [anon_sym_EQ] = ACTIONS(116),
    [anon_sym_PLUS] = ACTIONS(113),
    [anon_sym_DASH] = ACTIONS(116),
    [anon_sym_STAR] = ACTIONS(113),
    [anon_sym_SLASH] = ACTIONS(116),
    [anon_sym_PERCENT] = ACTIONS(113),
    [anon_sym_RPAREN] = ACTIONS(98),
    [anon_sym_LBRACK] = ACTIONS(98),
    [anon_sym_RBRACK] = ACTIONS(98),
    [anon_sym_LBRACE] = ACTIONS(98),
    [anon_sym_RBRACE] = ACTIONS(98),
    [anon_sym_COMMA] = ACTIONS(98),
    [anon_sym_COLON] = ACTIONS(98),
    [anon_sym_SEMI] = ACTIONS(98),
    [anon_sym_AT] = ACTIONS(98),
    [anon_sym_DOLLAR] = ACTIONS(98),
  },
  [3] = {
    [sym__item] = STATE(2),
    [sym_keyword_section] = STATE(2),
    [sym_keyword_flow] = STATE(2),
    [sym_keyword_action] = STATE(2),
    [sym_keyword_order] = STATE(2),
    [sym_keyword_sizing] = STATE(2),
    [sym_keyword_bracket] = STATE(2),
    [sym_keyword_stacking] = STATE(2),
    [sym_keyword_portfolio] = STATE(2),
    [sym_keyword_session] = STATE(2),
    [sym_keyword_hook] = STATE(2),
    [sym_keyword_state] = STATE(2),
    [sym_keyword_operator_word] = STATE(2),
    [sym_keyword_aggregate] = STATE(2),
    [sym_keyword_literal] = STATE(2),
    [sym_call] = STATE(2),
    [sym_member] = STATE(2),
    [sym_string] = STATE(2),
    [sym_operator] = STATE(2),
    [sym_punctuation] = STATE(2),
    [aux_sym_source_file_repeat1] = STATE(2),
    [ts_builtin_sym_end] = ACTIONS(119),
    [sym_identifier] = ACTIONS(7),
    [aux_sym_keyword_section_token1] = ACTIONS(9),
    [aux_sym_keyword_section_token2] = ACTIONS(9),
    [aux_sym_keyword_section_token3] = ACTIONS(9),
    [aux_sym_keyword_section_token4] = ACTIONS(9),
    [aux_sym_keyword_section_token5] = ACTIONS(9),
    [aux_sym_keyword_section_token6] = ACTIONS(9),
    [aux_sym_keyword_section_token7] = ACTIONS(9),
    [aux_sym_keyword_section_token8] = ACTIONS(9),
    [aux_sym_keyword_section_token9] = ACTIONS(9),
    [aux_sym_keyword_section_token10] = ACTIONS(9),
    [aux_sym_keyword_section_token11] = ACTIONS(9),
    [aux_sym_keyword_section_token12] = ACTIONS(9),
    [aux_sym_keyword_section_token13] = ACTIONS(9),
    [aux_sym_keyword_section_token14] = ACTIONS(9),
    [aux_sym_keyword_section_token15] = ACTIONS(9),
    [aux_sym_keyword_flow_token1] = ACTIONS(11),
    [aux_sym_keyword_flow_token2] = ACTIONS(11),
    [aux_sym_keyword_flow_token3] = ACTIONS(11),
    [aux_sym_keyword_flow_token4] = ACTIONS(11),
    [aux_sym_keyword_flow_token5] = ACTIONS(11),
    [aux_sym_keyword_flow_token6] = ACTIONS(11),
    [aux_sym_keyword_flow_token7] = ACTIONS(11),
    [aux_sym_keyword_flow_token8] = ACTIONS(11),
    [aux_sym_keyword_flow_token9] = ACTIONS(11),
    [aux_sym_keyword_flow_token10] = ACTIONS(11),
    [aux_sym_keyword_flow_token11] = ACTIONS(11),
    [aux_sym_keyword_action_token1] = ACTIONS(13),
    [aux_sym_keyword_action_token2] = ACTIONS(13),
    [aux_sym_keyword_action_token3] = ACTIONS(13),
    [aux_sym_keyword_action_token4] = ACTIONS(13),
    [aux_sym_keyword_action_token5] = ACTIONS(13),
    [aux_sym_keyword_action_token6] = ACTIONS(13),
    [aux_sym_keyword_action_token7] = ACTIONS(13),
    [aux_sym_keyword_action_token8] = ACTIONS(13),
    [aux_sym_keyword_action_token9] = ACTIONS(13),
    [aux_sym_keyword_action_token10] = ACTIONS(13),
    [aux_sym_keyword_action_token11] = ACTIONS(13),
    [aux_sym_keyword_action_token12] = ACTIONS(13),
    [aux_sym_keyword_order_token1] = ACTIONS(15),
    [aux_sym_keyword_order_token2] = ACTIONS(15),
    [aux_sym_keyword_order_token3] = ACTIONS(15),
    [aux_sym_keyword_order_token4] = ACTIONS(15),
    [aux_sym_keyword_order_token5] = ACTIONS(15),
    [aux_sym_keyword_order_token6] = ACTIONS(15),
    [aux_sym_keyword_order_token7] = ACTIONS(15),
    [aux_sym_keyword_order_token8] = ACTIONS(15),
    [aux_sym_keyword_order_token9] = ACTIONS(15),
    [aux_sym_keyword_order_token10] = ACTIONS(15),
    [aux_sym_keyword_order_token11] = ACTIONS(15),
    [aux_sym_keyword_order_token12] = ACTIONS(15),
    [aux_sym_keyword_order_token13] = ACTIONS(15),
    [aux_sym_keyword_order_token14] = ACTIONS(15),
    [aux_sym_keyword_order_token15] = ACTIONS(15),
    [aux_sym_keyword_order_token16] = ACTIONS(15),
    [aux_sym_keyword_order_token17] = ACTIONS(15),
    [aux_sym_keyword_order_token18] = ACTIONS(15),
    [aux_sym_keyword_order_token19] = ACTIONS(15),
    [aux_sym_keyword_order_token20] = ACTIONS(15),
    [aux_sym_keyword_order_token21] = ACTIONS(15),
    [aux_sym_keyword_sizing_token1] = ACTIONS(17),
    [aux_sym_keyword_sizing_token2] = ACTIONS(17),
    [aux_sym_keyword_sizing_token3] = ACTIONS(17),
    [aux_sym_keyword_sizing_token4] = ACTIONS(17),
    [aux_sym_keyword_sizing_token5] = ACTIONS(17),
    [aux_sym_keyword_sizing_token6] = ACTIONS(17),
    [aux_sym_keyword_sizing_token7] = ACTIONS(17),
    [aux_sym_keyword_bracket_token1] = ACTIONS(19),
    [aux_sym_keyword_bracket_token2] = ACTIONS(19),
    [aux_sym_keyword_bracket_token3] = ACTIONS(19),
    [aux_sym_keyword_bracket_token4] = ACTIONS(19),
    [aux_sym_keyword_bracket_token5] = ACTIONS(19),
    [aux_sym_keyword_bracket_token6] = ACTIONS(19),
    [aux_sym_keyword_bracket_token7] = ACTIONS(19),
    [aux_sym_keyword_bracket_token8] = ACTIONS(19),
    [aux_sym_keyword_bracket_token9] = ACTIONS(19),
    [aux_sym_keyword_bracket_token10] = ACTIONS(19),
    [aux_sym_keyword_bracket_token11] = ACTIONS(19),
    [aux_sym_keyword_bracket_token12] = ACTIONS(19),
    [aux_sym_keyword_bracket_token13] = ACTIONS(19),
    [aux_sym_keyword_bracket_token14] = ACTIONS(19),
    [aux_sym_keyword_bracket_token15] = ACTIONS(19),
    [aux_sym_keyword_bracket_token16] = ACTIONS(19),
    [aux_sym_keyword_bracket_token17] = ACTIONS(19),
    [aux_sym_keyword_bracket_token18] = ACTIONS(19),
    [aux_sym_keyword_bracket_token19] = ACTIONS(19),
    [aux_sym_keyword_bracket_token20] = ACTIONS(19),
    [aux_sym_keyword_bracket_token21] = ACTIONS(19),
    [aux_sym_keyword_stacking_token1] = ACTIONS(21),
    [aux_sym_keyword_stacking_token2] = ACTIONS(21),
    [aux_sym_keyword_stacking_token3] = ACTIONS(21),
    [aux_sym_keyword_stacking_token4] = ACTIONS(21),
    [aux_sym_keyword_stacking_token5] = ACTIONS(21),
    [aux_sym_keyword_stacking_token6] = ACTIONS(21),
    [aux_sym_keyword_stacking_token7] = ACTIONS(21),
    [aux_sym_keyword_stacking_token8] = ACTIONS(21),
    [aux_sym_keyword_stacking_token9] = ACTIONS(21),
    [aux_sym_keyword_stacking_token10] = ACTIONS(21),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(23),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(23),
    [aux_sym_keyword_session_token1] = ACTIONS(25),
    [aux_sym_keyword_session_token2] = ACTIONS(25),
    [aux_sym_keyword_session_token3] = ACTIONS(25),
    [aux_sym_keyword_session_token4] = ACTIONS(25),
    [aux_sym_keyword_session_token5] = ACTIONS(25),
    [aux_sym_keyword_session_token6] = ACTIONS(25),
    [aux_sym_keyword_session_token7] = ACTIONS(25),
    [aux_sym_keyword_session_token8] = ACTIONS(25),
    [aux_sym_keyword_session_token9] = ACTIONS(25),
    [aux_sym_keyword_session_token10] = ACTIONS(25),
    [aux_sym_keyword_session_token11] = ACTIONS(25),
    [aux_sym_keyword_hook_token1] = ACTIONS(27),
    [aux_sym_keyword_hook_token2] = ACTIONS(27),
    [aux_sym_keyword_hook_token3] = ACTIONS(27),
    [aux_sym_keyword_hook_token4] = ACTIONS(27),
    [aux_sym_keyword_state_token1] = ACTIONS(29),
    [aux_sym_keyword_state_token2] = ACTIONS(29),
    [aux_sym_keyword_state_token3] = ACTIONS(29),
    [aux_sym_keyword_state_token4] = ACTIONS(29),
    [aux_sym_keyword_state_token5] = ACTIONS(29),
    [aux_sym_keyword_state_token6] = ACTIONS(29),
    [aux_sym_keyword_state_token7] = ACTIONS(29),
    [aux_sym_keyword_state_token8] = ACTIONS(29),
    [aux_sym_keyword_state_token9] = ACTIONS(29),
    [aux_sym_keyword_state_token10] = ACTIONS(29),
    [aux_sym_keyword_state_token11] = ACTIONS(29),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(31),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(31),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(33),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(33),
    [aux_sym_keyword_literal_token1] = ACTIONS(35),
    [aux_sym_keyword_literal_token2] = ACTIONS(35),
    [anon_sym_LPAREN] = ACTIONS(37),
    [anon_sym_DOT] = ACTIONS(39),
    [sym_broker_symbol] = ACTIONS(121),
    [sym_duration] = ACTIONS(121),
    [sym_number] = ACTIONS(123),
    [aux_sym_string_token1] = ACTIONS(45),
    [aux_sym_string_token2] = ACTIONS(45),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(47),
    [anon_sym_EQ_EQ] = ACTIONS(47),
    [anon_sym_LT_GT] = ACTIONS(47),
    [anon_sym_BANG_EQ] = ACTIONS(47),
    [anon_sym_LT_EQ] = ACTIONS(47),
    [anon_sym_GT_EQ] = ACTIONS(47),
    [anon_sym_LT] = ACTIONS(49),
    [anon_sym_GT] = ACTIONS(49),
    [anon_sym_EQ] = ACTIONS(49),
    [anon_sym_PLUS] = ACTIONS(47),
    [anon_sym_DASH] = ACTIONS(49),
    [anon_sym_STAR] = ACTIONS(47),
    [anon_sym_SLASH] = ACTIONS(49),
    [anon_sym_PERCENT] = ACTIONS(47),
    [anon_sym_RPAREN] = ACTIONS(37),
    [anon_sym_LBRACK] = ACTIONS(37),
    [anon_sym_RBRACK] = ACTIONS(37),
    [anon_sym_LBRACE] = ACTIONS(37),
    [anon_sym_RBRACE] = ACTIONS(37),
    [anon_sym_COMMA] = ACTIONS(37),
    [anon_sym_COLON] = ACTIONS(37),
    [anon_sym_SEMI] = ACTIONS(37),
    [anon_sym_AT] = ACTIONS(37),
    [anon_sym_DOLLAR] = ACTIONS(37),
  },
  [4] = {
    [ts_builtin_sym_end] = ACTIONS(125),
    [sym_identifier] = ACTIONS(127),
    [aux_sym_keyword_section_token1] = ACTIONS(127),
    [aux_sym_keyword_section_token2] = ACTIONS(127),
    [aux_sym_keyword_section_token3] = ACTIONS(127),
    [aux_sym_keyword_section_token4] = ACTIONS(127),
    [aux_sym_keyword_section_token5] = ACTIONS(127),
    [aux_sym_keyword_section_token6] = ACTIONS(127),
    [aux_sym_keyword_section_token7] = ACTIONS(127),
    [aux_sym_keyword_section_token8] = ACTIONS(127),
    [aux_sym_keyword_section_token9] = ACTIONS(127),
    [aux_sym_keyword_section_token10] = ACTIONS(127),
    [aux_sym_keyword_section_token11] = ACTIONS(127),
    [aux_sym_keyword_section_token12] = ACTIONS(127),
    [aux_sym_keyword_section_token13] = ACTIONS(127),
    [aux_sym_keyword_section_token14] = ACTIONS(127),
    [aux_sym_keyword_section_token15] = ACTIONS(127),
    [aux_sym_keyword_flow_token1] = ACTIONS(127),
    [aux_sym_keyword_flow_token2] = ACTIONS(127),
    [aux_sym_keyword_flow_token3] = ACTIONS(127),
    [aux_sym_keyword_flow_token4] = ACTIONS(127),
    [aux_sym_keyword_flow_token5] = ACTIONS(127),
    [aux_sym_keyword_flow_token6] = ACTIONS(127),
    [aux_sym_keyword_flow_token7] = ACTIONS(127),
    [aux_sym_keyword_flow_token8] = ACTIONS(127),
    [aux_sym_keyword_flow_token9] = ACTIONS(127),
    [aux_sym_keyword_flow_token10] = ACTIONS(127),
    [aux_sym_keyword_flow_token11] = ACTIONS(127),
    [aux_sym_keyword_action_token1] = ACTIONS(127),
    [aux_sym_keyword_action_token2] = ACTIONS(127),
    [aux_sym_keyword_action_token3] = ACTIONS(127),
    [aux_sym_keyword_action_token4] = ACTIONS(127),
    [aux_sym_keyword_action_token5] = ACTIONS(127),
    [aux_sym_keyword_action_token6] = ACTIONS(127),
    [aux_sym_keyword_action_token7] = ACTIONS(127),
    [aux_sym_keyword_action_token8] = ACTIONS(127),
    [aux_sym_keyword_action_token9] = ACTIONS(127),
    [aux_sym_keyword_action_token10] = ACTIONS(127),
    [aux_sym_keyword_action_token11] = ACTIONS(127),
    [aux_sym_keyword_action_token12] = ACTIONS(127),
    [aux_sym_keyword_order_token1] = ACTIONS(127),
    [aux_sym_keyword_order_token2] = ACTIONS(127),
    [aux_sym_keyword_order_token3] = ACTIONS(127),
    [aux_sym_keyword_order_token4] = ACTIONS(127),
    [aux_sym_keyword_order_token5] = ACTIONS(127),
    [aux_sym_keyword_order_token6] = ACTIONS(127),
    [aux_sym_keyword_order_token7] = ACTIONS(127),
    [aux_sym_keyword_order_token8] = ACTIONS(127),
    [aux_sym_keyword_order_token9] = ACTIONS(127),
    [aux_sym_keyword_order_token10] = ACTIONS(127),
    [aux_sym_keyword_order_token11] = ACTIONS(127),
    [aux_sym_keyword_order_token12] = ACTIONS(127),
    [aux_sym_keyword_order_token13] = ACTIONS(127),
    [aux_sym_keyword_order_token14] = ACTIONS(127),
    [aux_sym_keyword_order_token15] = ACTIONS(127),
    [aux_sym_keyword_order_token16] = ACTIONS(127),
    [aux_sym_keyword_order_token17] = ACTIONS(127),
    [aux_sym_keyword_order_token18] = ACTIONS(127),
    [aux_sym_keyword_order_token19] = ACTIONS(127),
    [aux_sym_keyword_order_token20] = ACTIONS(127),
    [aux_sym_keyword_order_token21] = ACTIONS(127),
    [aux_sym_keyword_sizing_token1] = ACTIONS(127),
    [aux_sym_keyword_sizing_token2] = ACTIONS(127),
    [aux_sym_keyword_sizing_token3] = ACTIONS(127),
    [aux_sym_keyword_sizing_token4] = ACTIONS(127),
    [aux_sym_keyword_sizing_token5] = ACTIONS(127),
    [aux_sym_keyword_sizing_token6] = ACTIONS(127),
    [aux_sym_keyword_sizing_token7] = ACTIONS(127),
    [aux_sym_keyword_bracket_token1] = ACTIONS(127),
    [aux_sym_keyword_bracket_token2] = ACTIONS(127),
    [aux_sym_keyword_bracket_token3] = ACTIONS(127),
    [aux_sym_keyword_bracket_token4] = ACTIONS(127),
    [aux_sym_keyword_bracket_token5] = ACTIONS(127),
    [aux_sym_keyword_bracket_token6] = ACTIONS(127),
    [aux_sym_keyword_bracket_token7] = ACTIONS(127),
    [aux_sym_keyword_bracket_token8] = ACTIONS(127),
    [aux_sym_keyword_bracket_token9] = ACTIONS(127),
    [aux_sym_keyword_bracket_token10] = ACTIONS(127),
    [aux_sym_keyword_bracket_token11] = ACTIONS(127),
    [aux_sym_keyword_bracket_token12] = ACTIONS(127),
    [aux_sym_keyword_bracket_token13] = ACTIONS(127),
    [aux_sym_keyword_bracket_token14] = ACTIONS(127),
    [aux_sym_keyword_bracket_token15] = ACTIONS(127),
    [aux_sym_keyword_bracket_token16] = ACTIONS(127),
    [aux_sym_keyword_bracket_token17] = ACTIONS(127),
    [aux_sym_keyword_bracket_token18] = ACTIONS(127),
    [aux_sym_keyword_bracket_token19] = ACTIONS(127),
    [aux_sym_keyword_bracket_token20] = ACTIONS(127),
    [aux_sym_keyword_bracket_token21] = ACTIONS(127),
    [aux_sym_keyword_stacking_token1] = ACTIONS(127),
    [aux_sym_keyword_stacking_token2] = ACTIONS(127),
    [aux_sym_keyword_stacking_token3] = ACTIONS(127),
    [aux_sym_keyword_stacking_token4] = ACTIONS(127),
    [aux_sym_keyword_stacking_token5] = ACTIONS(127),
    [aux_sym_keyword_stacking_token6] = ACTIONS(127),
    [aux_sym_keyword_stacking_token7] = ACTIONS(127),
    [aux_sym_keyword_stacking_token8] = ACTIONS(127),
    [aux_sym_keyword_stacking_token9] = ACTIONS(127),
    [aux_sym_keyword_stacking_token10] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(127),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(127),
    [aux_sym_keyword_session_token1] = ACTIONS(127),
    [aux_sym_keyword_session_token2] = ACTIONS(127),
    [aux_sym_keyword_session_token3] = ACTIONS(127),
    [aux_sym_keyword_session_token4] = ACTIONS(127),
    [aux_sym_keyword_session_token5] = ACTIONS(127),
    [aux_sym_keyword_session_token6] = ACTIONS(127),
    [aux_sym_keyword_session_token7] = ACTIONS(127),
    [aux_sym_keyword_session_token8] = ACTIONS(127),
    [aux_sym_keyword_session_token9] = ACTIONS(127),
    [aux_sym_keyword_session_token10] = ACTIONS(127),
    [aux_sym_keyword_session_token11] = ACTIONS(127),
    [aux_sym_keyword_hook_token1] = ACTIONS(127),
    [aux_sym_keyword_hook_token2] = ACTIONS(127),
    [aux_sym_keyword_hook_token3] = ACTIONS(127),
    [aux_sym_keyword_hook_token4] = ACTIONS(127),
    [aux_sym_keyword_state_token1] = ACTIONS(127),
    [aux_sym_keyword_state_token2] = ACTIONS(127),
    [aux_sym_keyword_state_token3] = ACTIONS(127),
    [aux_sym_keyword_state_token4] = ACTIONS(127),
    [aux_sym_keyword_state_token5] = ACTIONS(127),
    [aux_sym_keyword_state_token6] = ACTIONS(127),
    [aux_sym_keyword_state_token7] = ACTIONS(127),
    [aux_sym_keyword_state_token8] = ACTIONS(127),
    [aux_sym_keyword_state_token9] = ACTIONS(127),
    [aux_sym_keyword_state_token10] = ACTIONS(127),
    [aux_sym_keyword_state_token11] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(127),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(127),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(127),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(127),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(127),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(127),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(127),
    [aux_sym_keyword_literal_token1] = ACTIONS(127),
    [aux_sym_keyword_literal_token2] = ACTIONS(127),
    [anon_sym_LPAREN] = ACTIONS(125),
    [anon_sym_DOT] = ACTIONS(125),
    [sym_broker_symbol] = ACTIONS(125),
    [sym_duration] = ACTIONS(125),
    [sym_number] = ACTIONS(127),
    [aux_sym_string_token1] = ACTIONS(125),
    [aux_sym_string_token2] = ACTIONS(125),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(125),
    [anon_sym_EQ_EQ] = ACTIONS(125),
    [anon_sym_LT_GT] = ACTIONS(125),
    [anon_sym_BANG_EQ] = ACTIONS(125),
    [anon_sym_LT_EQ] = ACTIONS(125),
    [anon_sym_GT_EQ] = ACTIONS(125),
    [anon_sym_LT] = ACTIONS(127),
    [anon_sym_GT] = ACTIONS(127),
    [anon_sym_EQ] = ACTIONS(127),
    [anon_sym_PLUS] = ACTIONS(125),
    [anon_sym_DASH] = ACTIONS(127),
    [anon_sym_STAR] = ACTIONS(125),
    [anon_sym_SLASH] = ACTIONS(127),
    [anon_sym_PERCENT] = ACTIONS(125),
    [anon_sym_RPAREN] = ACTIONS(125),
    [anon_sym_LBRACK] = ACTIONS(125),
    [anon_sym_RBRACK] = ACTIONS(125),
    [anon_sym_LBRACE] = ACTIONS(125),
    [anon_sym_RBRACE] = ACTIONS(125),
    [anon_sym_COMMA] = ACTIONS(125),
    [anon_sym_COLON] = ACTIONS(125),
    [anon_sym_SEMI] = ACTIONS(125),
    [anon_sym_AT] = ACTIONS(125),
    [anon_sym_DOLLAR] = ACTIONS(125),
  },
  [5] = {
    [ts_builtin_sym_end] = ACTIONS(129),
    [sym_identifier] = ACTIONS(131),
    [aux_sym_keyword_section_token1] = ACTIONS(131),
    [aux_sym_keyword_section_token2] = ACTIONS(131),
    [aux_sym_keyword_section_token3] = ACTIONS(131),
    [aux_sym_keyword_section_token4] = ACTIONS(131),
    [aux_sym_keyword_section_token5] = ACTIONS(131),
    [aux_sym_keyword_section_token6] = ACTIONS(131),
    [aux_sym_keyword_section_token7] = ACTIONS(131),
    [aux_sym_keyword_section_token8] = ACTIONS(131),
    [aux_sym_keyword_section_token9] = ACTIONS(131),
    [aux_sym_keyword_section_token10] = ACTIONS(131),
    [aux_sym_keyword_section_token11] = ACTIONS(131),
    [aux_sym_keyword_section_token12] = ACTIONS(131),
    [aux_sym_keyword_section_token13] = ACTIONS(131),
    [aux_sym_keyword_section_token14] = ACTIONS(131),
    [aux_sym_keyword_section_token15] = ACTIONS(131),
    [aux_sym_keyword_flow_token1] = ACTIONS(131),
    [aux_sym_keyword_flow_token2] = ACTIONS(131),
    [aux_sym_keyword_flow_token3] = ACTIONS(131),
    [aux_sym_keyword_flow_token4] = ACTIONS(131),
    [aux_sym_keyword_flow_token5] = ACTIONS(131),
    [aux_sym_keyword_flow_token6] = ACTIONS(131),
    [aux_sym_keyword_flow_token7] = ACTIONS(131),
    [aux_sym_keyword_flow_token8] = ACTIONS(131),
    [aux_sym_keyword_flow_token9] = ACTIONS(131),
    [aux_sym_keyword_flow_token10] = ACTIONS(131),
    [aux_sym_keyword_flow_token11] = ACTIONS(131),
    [aux_sym_keyword_action_token1] = ACTIONS(131),
    [aux_sym_keyword_action_token2] = ACTIONS(131),
    [aux_sym_keyword_action_token3] = ACTIONS(131),
    [aux_sym_keyword_action_token4] = ACTIONS(131),
    [aux_sym_keyword_action_token5] = ACTIONS(131),
    [aux_sym_keyword_action_token6] = ACTIONS(131),
    [aux_sym_keyword_action_token7] = ACTIONS(131),
    [aux_sym_keyword_action_token8] = ACTIONS(131),
    [aux_sym_keyword_action_token9] = ACTIONS(131),
    [aux_sym_keyword_action_token10] = ACTIONS(131),
    [aux_sym_keyword_action_token11] = ACTIONS(131),
    [aux_sym_keyword_action_token12] = ACTIONS(131),
    [aux_sym_keyword_order_token1] = ACTIONS(131),
    [aux_sym_keyword_order_token2] = ACTIONS(131),
    [aux_sym_keyword_order_token3] = ACTIONS(131),
    [aux_sym_keyword_order_token4] = ACTIONS(131),
    [aux_sym_keyword_order_token5] = ACTIONS(131),
    [aux_sym_keyword_order_token6] = ACTIONS(131),
    [aux_sym_keyword_order_token7] = ACTIONS(131),
    [aux_sym_keyword_order_token8] = ACTIONS(131),
    [aux_sym_keyword_order_token9] = ACTIONS(131),
    [aux_sym_keyword_order_token10] = ACTIONS(131),
    [aux_sym_keyword_order_token11] = ACTIONS(131),
    [aux_sym_keyword_order_token12] = ACTIONS(131),
    [aux_sym_keyword_order_token13] = ACTIONS(131),
    [aux_sym_keyword_order_token14] = ACTIONS(131),
    [aux_sym_keyword_order_token15] = ACTIONS(131),
    [aux_sym_keyword_order_token16] = ACTIONS(131),
    [aux_sym_keyword_order_token17] = ACTIONS(131),
    [aux_sym_keyword_order_token18] = ACTIONS(131),
    [aux_sym_keyword_order_token19] = ACTIONS(131),
    [aux_sym_keyword_order_token20] = ACTIONS(131),
    [aux_sym_keyword_order_token21] = ACTIONS(131),
    [aux_sym_keyword_sizing_token1] = ACTIONS(131),
    [aux_sym_keyword_sizing_token2] = ACTIONS(131),
    [aux_sym_keyword_sizing_token3] = ACTIONS(131),
    [aux_sym_keyword_sizing_token4] = ACTIONS(131),
    [aux_sym_keyword_sizing_token5] = ACTIONS(131),
    [aux_sym_keyword_sizing_token6] = ACTIONS(131),
    [aux_sym_keyword_sizing_token7] = ACTIONS(131),
    [aux_sym_keyword_bracket_token1] = ACTIONS(131),
    [aux_sym_keyword_bracket_token2] = ACTIONS(131),
    [aux_sym_keyword_bracket_token3] = ACTIONS(131),
    [aux_sym_keyword_bracket_token4] = ACTIONS(131),
    [aux_sym_keyword_bracket_token5] = ACTIONS(131),
    [aux_sym_keyword_bracket_token6] = ACTIONS(131),
    [aux_sym_keyword_bracket_token7] = ACTIONS(131),
    [aux_sym_keyword_bracket_token8] = ACTIONS(131),
    [aux_sym_keyword_bracket_token9] = ACTIONS(131),
    [aux_sym_keyword_bracket_token10] = ACTIONS(131),
    [aux_sym_keyword_bracket_token11] = ACTIONS(131),
    [aux_sym_keyword_bracket_token12] = ACTIONS(131),
    [aux_sym_keyword_bracket_token13] = ACTIONS(131),
    [aux_sym_keyword_bracket_token14] = ACTIONS(131),
    [aux_sym_keyword_bracket_token15] = ACTIONS(131),
    [aux_sym_keyword_bracket_token16] = ACTIONS(131),
    [aux_sym_keyword_bracket_token17] = ACTIONS(131),
    [aux_sym_keyword_bracket_token18] = ACTIONS(131),
    [aux_sym_keyword_bracket_token19] = ACTIONS(131),
    [aux_sym_keyword_bracket_token20] = ACTIONS(131),
    [aux_sym_keyword_bracket_token21] = ACTIONS(131),
    [aux_sym_keyword_stacking_token1] = ACTIONS(131),
    [aux_sym_keyword_stacking_token2] = ACTIONS(131),
    [aux_sym_keyword_stacking_token3] = ACTIONS(131),
    [aux_sym_keyword_stacking_token4] = ACTIONS(131),
    [aux_sym_keyword_stacking_token5] = ACTIONS(131),
    [aux_sym_keyword_stacking_token6] = ACTIONS(131),
    [aux_sym_keyword_stacking_token7] = ACTIONS(131),
    [aux_sym_keyword_stacking_token8] = ACTIONS(131),
    [aux_sym_keyword_stacking_token9] = ACTIONS(131),
    [aux_sym_keyword_stacking_token10] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(131),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(131),
    [aux_sym_keyword_session_token1] = ACTIONS(131),
    [aux_sym_keyword_session_token2] = ACTIONS(131),
    [aux_sym_keyword_session_token3] = ACTIONS(131),
    [aux_sym_keyword_session_token4] = ACTIONS(131),
    [aux_sym_keyword_session_token5] = ACTIONS(131),
    [aux_sym_keyword_session_token6] = ACTIONS(131),
    [aux_sym_keyword_session_token7] = ACTIONS(131),
    [aux_sym_keyword_session_token8] = ACTIONS(131),
    [aux_sym_keyword_session_token9] = ACTIONS(131),
    [aux_sym_keyword_session_token10] = ACTIONS(131),
    [aux_sym_keyword_session_token11] = ACTIONS(131),
    [aux_sym_keyword_hook_token1] = ACTIONS(131),
    [aux_sym_keyword_hook_token2] = ACTIONS(131),
    [aux_sym_keyword_hook_token3] = ACTIONS(131),
    [aux_sym_keyword_hook_token4] = ACTIONS(131),
    [aux_sym_keyword_state_token1] = ACTIONS(131),
    [aux_sym_keyword_state_token2] = ACTIONS(131),
    [aux_sym_keyword_state_token3] = ACTIONS(131),
    [aux_sym_keyword_state_token4] = ACTIONS(131),
    [aux_sym_keyword_state_token5] = ACTIONS(131),
    [aux_sym_keyword_state_token6] = ACTIONS(131),
    [aux_sym_keyword_state_token7] = ACTIONS(131),
    [aux_sym_keyword_state_token8] = ACTIONS(131),
    [aux_sym_keyword_state_token9] = ACTIONS(131),
    [aux_sym_keyword_state_token10] = ACTIONS(131),
    [aux_sym_keyword_state_token11] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(131),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(131),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(131),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(131),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(131),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(131),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(131),
    [aux_sym_keyword_literal_token1] = ACTIONS(131),
    [aux_sym_keyword_literal_token2] = ACTIONS(131),
    [anon_sym_LPAREN] = ACTIONS(129),
    [anon_sym_DOT] = ACTIONS(129),
    [sym_broker_symbol] = ACTIONS(129),
    [sym_duration] = ACTIONS(129),
    [sym_number] = ACTIONS(131),
    [aux_sym_string_token1] = ACTIONS(129),
    [aux_sym_string_token2] = ACTIONS(129),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(129),
    [anon_sym_EQ_EQ] = ACTIONS(129),
    [anon_sym_LT_GT] = ACTIONS(129),
    [anon_sym_BANG_EQ] = ACTIONS(129),
    [anon_sym_LT_EQ] = ACTIONS(129),
    [anon_sym_GT_EQ] = ACTIONS(129),
    [anon_sym_LT] = ACTIONS(131),
    [anon_sym_GT] = ACTIONS(131),
    [anon_sym_EQ] = ACTIONS(131),
    [anon_sym_PLUS] = ACTIONS(129),
    [anon_sym_DASH] = ACTIONS(131),
    [anon_sym_STAR] = ACTIONS(129),
    [anon_sym_SLASH] = ACTIONS(131),
    [anon_sym_PERCENT] = ACTIONS(129),
    [anon_sym_RPAREN] = ACTIONS(129),
    [anon_sym_LBRACK] = ACTIONS(129),
    [anon_sym_RBRACK] = ACTIONS(129),
    [anon_sym_LBRACE] = ACTIONS(129),
    [anon_sym_RBRACE] = ACTIONS(129),
    [anon_sym_COMMA] = ACTIONS(129),
    [anon_sym_COLON] = ACTIONS(129),
    [anon_sym_SEMI] = ACTIONS(129),
    [anon_sym_AT] = ACTIONS(129),
    [anon_sym_DOLLAR] = ACTIONS(129),
  },
  [6] = {
    [ts_builtin_sym_end] = ACTIONS(133),
    [sym_identifier] = ACTIONS(135),
    [aux_sym_keyword_section_token1] = ACTIONS(135),
    [aux_sym_keyword_section_token2] = ACTIONS(135),
    [aux_sym_keyword_section_token3] = ACTIONS(135),
    [aux_sym_keyword_section_token4] = ACTIONS(135),
    [aux_sym_keyword_section_token5] = ACTIONS(135),
    [aux_sym_keyword_section_token6] = ACTIONS(135),
    [aux_sym_keyword_section_token7] = ACTIONS(135),
    [aux_sym_keyword_section_token8] = ACTIONS(135),
    [aux_sym_keyword_section_token9] = ACTIONS(135),
    [aux_sym_keyword_section_token10] = ACTIONS(135),
    [aux_sym_keyword_section_token11] = ACTIONS(135),
    [aux_sym_keyword_section_token12] = ACTIONS(135),
    [aux_sym_keyword_section_token13] = ACTIONS(135),
    [aux_sym_keyword_section_token14] = ACTIONS(135),
    [aux_sym_keyword_section_token15] = ACTIONS(135),
    [aux_sym_keyword_flow_token1] = ACTIONS(135),
    [aux_sym_keyword_flow_token2] = ACTIONS(135),
    [aux_sym_keyword_flow_token3] = ACTIONS(135),
    [aux_sym_keyword_flow_token4] = ACTIONS(135),
    [aux_sym_keyword_flow_token5] = ACTIONS(135),
    [aux_sym_keyword_flow_token6] = ACTIONS(135),
    [aux_sym_keyword_flow_token7] = ACTIONS(135),
    [aux_sym_keyword_flow_token8] = ACTIONS(135),
    [aux_sym_keyword_flow_token9] = ACTIONS(135),
    [aux_sym_keyword_flow_token10] = ACTIONS(135),
    [aux_sym_keyword_flow_token11] = ACTIONS(135),
    [aux_sym_keyword_action_token1] = ACTIONS(135),
    [aux_sym_keyword_action_token2] = ACTIONS(135),
    [aux_sym_keyword_action_token3] = ACTIONS(135),
    [aux_sym_keyword_action_token4] = ACTIONS(135),
    [aux_sym_keyword_action_token5] = ACTIONS(135),
    [aux_sym_keyword_action_token6] = ACTIONS(135),
    [aux_sym_keyword_action_token7] = ACTIONS(135),
    [aux_sym_keyword_action_token8] = ACTIONS(135),
    [aux_sym_keyword_action_token9] = ACTIONS(135),
    [aux_sym_keyword_action_token10] = ACTIONS(135),
    [aux_sym_keyword_action_token11] = ACTIONS(135),
    [aux_sym_keyword_action_token12] = ACTIONS(135),
    [aux_sym_keyword_order_token1] = ACTIONS(135),
    [aux_sym_keyword_order_token2] = ACTIONS(135),
    [aux_sym_keyword_order_token3] = ACTIONS(135),
    [aux_sym_keyword_order_token4] = ACTIONS(135),
    [aux_sym_keyword_order_token5] = ACTIONS(135),
    [aux_sym_keyword_order_token6] = ACTIONS(135),
    [aux_sym_keyword_order_token7] = ACTIONS(135),
    [aux_sym_keyword_order_token8] = ACTIONS(135),
    [aux_sym_keyword_order_token9] = ACTIONS(135),
    [aux_sym_keyword_order_token10] = ACTIONS(135),
    [aux_sym_keyword_order_token11] = ACTIONS(135),
    [aux_sym_keyword_order_token12] = ACTIONS(135),
    [aux_sym_keyword_order_token13] = ACTIONS(135),
    [aux_sym_keyword_order_token14] = ACTIONS(135),
    [aux_sym_keyword_order_token15] = ACTIONS(135),
    [aux_sym_keyword_order_token16] = ACTIONS(135),
    [aux_sym_keyword_order_token17] = ACTIONS(135),
    [aux_sym_keyword_order_token18] = ACTIONS(135),
    [aux_sym_keyword_order_token19] = ACTIONS(135),
    [aux_sym_keyword_order_token20] = ACTIONS(135),
    [aux_sym_keyword_order_token21] = ACTIONS(135),
    [aux_sym_keyword_sizing_token1] = ACTIONS(135),
    [aux_sym_keyword_sizing_token2] = ACTIONS(135),
    [aux_sym_keyword_sizing_token3] = ACTIONS(135),
    [aux_sym_keyword_sizing_token4] = ACTIONS(135),
    [aux_sym_keyword_sizing_token5] = ACTIONS(135),
    [aux_sym_keyword_sizing_token6] = ACTIONS(135),
    [aux_sym_keyword_sizing_token7] = ACTIONS(135),
    [aux_sym_keyword_bracket_token1] = ACTIONS(135),
    [aux_sym_keyword_bracket_token2] = ACTIONS(135),
    [aux_sym_keyword_bracket_token3] = ACTIONS(135),
    [aux_sym_keyword_bracket_token4] = ACTIONS(135),
    [aux_sym_keyword_bracket_token5] = ACTIONS(135),
    [aux_sym_keyword_bracket_token6] = ACTIONS(135),
    [aux_sym_keyword_bracket_token7] = ACTIONS(135),
    [aux_sym_keyword_bracket_token8] = ACTIONS(135),
    [aux_sym_keyword_bracket_token9] = ACTIONS(135),
    [aux_sym_keyword_bracket_token10] = ACTIONS(135),
    [aux_sym_keyword_bracket_token11] = ACTIONS(135),
    [aux_sym_keyword_bracket_token12] = ACTIONS(135),
    [aux_sym_keyword_bracket_token13] = ACTIONS(135),
    [aux_sym_keyword_bracket_token14] = ACTIONS(135),
    [aux_sym_keyword_bracket_token15] = ACTIONS(135),
    [aux_sym_keyword_bracket_token16] = ACTIONS(135),
    [aux_sym_keyword_bracket_token17] = ACTIONS(135),
    [aux_sym_keyword_bracket_token18] = ACTIONS(135),
    [aux_sym_keyword_bracket_token19] = ACTIONS(135),
    [aux_sym_keyword_bracket_token20] = ACTIONS(135),
    [aux_sym_keyword_bracket_token21] = ACTIONS(135),
    [aux_sym_keyword_stacking_token1] = ACTIONS(135),
    [aux_sym_keyword_stacking_token2] = ACTIONS(135),
    [aux_sym_keyword_stacking_token3] = ACTIONS(135),
    [aux_sym_keyword_stacking_token4] = ACTIONS(135),
    [aux_sym_keyword_stacking_token5] = ACTIONS(135),
    [aux_sym_keyword_stacking_token6] = ACTIONS(135),
    [aux_sym_keyword_stacking_token7] = ACTIONS(135),
    [aux_sym_keyword_stacking_token8] = ACTIONS(135),
    [aux_sym_keyword_stacking_token9] = ACTIONS(135),
    [aux_sym_keyword_stacking_token10] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(135),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(135),
    [aux_sym_keyword_session_token1] = ACTIONS(135),
    [aux_sym_keyword_session_token2] = ACTIONS(135),
    [aux_sym_keyword_session_token3] = ACTIONS(135),
    [aux_sym_keyword_session_token4] = ACTIONS(135),
    [aux_sym_keyword_session_token5] = ACTIONS(135),
    [aux_sym_keyword_session_token6] = ACTIONS(135),
    [aux_sym_keyword_session_token7] = ACTIONS(135),
    [aux_sym_keyword_session_token8] = ACTIONS(135),
    [aux_sym_keyword_session_token9] = ACTIONS(135),
    [aux_sym_keyword_session_token10] = ACTIONS(135),
    [aux_sym_keyword_session_token11] = ACTIONS(135),
    [aux_sym_keyword_hook_token1] = ACTIONS(135),
    [aux_sym_keyword_hook_token2] = ACTIONS(135),
    [aux_sym_keyword_hook_token3] = ACTIONS(135),
    [aux_sym_keyword_hook_token4] = ACTIONS(135),
    [aux_sym_keyword_state_token1] = ACTIONS(135),
    [aux_sym_keyword_state_token2] = ACTIONS(135),
    [aux_sym_keyword_state_token3] = ACTIONS(135),
    [aux_sym_keyword_state_token4] = ACTIONS(135),
    [aux_sym_keyword_state_token5] = ACTIONS(135),
    [aux_sym_keyword_state_token6] = ACTIONS(135),
    [aux_sym_keyword_state_token7] = ACTIONS(135),
    [aux_sym_keyword_state_token8] = ACTIONS(135),
    [aux_sym_keyword_state_token9] = ACTIONS(135),
    [aux_sym_keyword_state_token10] = ACTIONS(135),
    [aux_sym_keyword_state_token11] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(135),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(135),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(135),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(135),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(135),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(135),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(135),
    [aux_sym_keyword_literal_token1] = ACTIONS(135),
    [aux_sym_keyword_literal_token2] = ACTIONS(135),
    [anon_sym_LPAREN] = ACTIONS(133),
    [anon_sym_DOT] = ACTIONS(133),
    [sym_broker_symbol] = ACTIONS(133),
    [sym_duration] = ACTIONS(133),
    [sym_number] = ACTIONS(135),
    [aux_sym_string_token1] = ACTIONS(133),
    [aux_sym_string_token2] = ACTIONS(133),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(133),
    [anon_sym_EQ_EQ] = ACTIONS(133),
    [anon_sym_LT_GT] = ACTIONS(133),
    [anon_sym_BANG_EQ] = ACTIONS(133),
    [anon_sym_LT_EQ] = ACTIONS(133),
    [anon_sym_GT_EQ] = ACTIONS(133),
    [anon_sym_LT] = ACTIONS(135),
    [anon_sym_GT] = ACTIONS(135),
    [anon_sym_EQ] = ACTIONS(135),
    [anon_sym_PLUS] = ACTIONS(133),
    [anon_sym_DASH] = ACTIONS(135),
    [anon_sym_STAR] = ACTIONS(133),
    [anon_sym_SLASH] = ACTIONS(135),
    [anon_sym_PERCENT] = ACTIONS(133),
    [anon_sym_RPAREN] = ACTIONS(133),
    [anon_sym_LBRACK] = ACTIONS(133),
    [anon_sym_RBRACK] = ACTIONS(133),
    [anon_sym_LBRACE] = ACTIONS(133),
    [anon_sym_RBRACE] = ACTIONS(133),
    [anon_sym_COMMA] = ACTIONS(133),
    [anon_sym_COLON] = ACTIONS(133),
    [anon_sym_SEMI] = ACTIONS(133),
    [anon_sym_AT] = ACTIONS(133),
    [anon_sym_DOLLAR] = ACTIONS(133),
  },
  [7] = {
    [ts_builtin_sym_end] = ACTIONS(137),
    [sym_identifier] = ACTIONS(139),
    [aux_sym_keyword_section_token1] = ACTIONS(139),
    [aux_sym_keyword_section_token2] = ACTIONS(139),
    [aux_sym_keyword_section_token3] = ACTIONS(139),
    [aux_sym_keyword_section_token4] = ACTIONS(139),
    [aux_sym_keyword_section_token5] = ACTIONS(139),
    [aux_sym_keyword_section_token6] = ACTIONS(139),
    [aux_sym_keyword_section_token7] = ACTIONS(139),
    [aux_sym_keyword_section_token8] = ACTIONS(139),
    [aux_sym_keyword_section_token9] = ACTIONS(139),
    [aux_sym_keyword_section_token10] = ACTIONS(139),
    [aux_sym_keyword_section_token11] = ACTIONS(139),
    [aux_sym_keyword_section_token12] = ACTIONS(139),
    [aux_sym_keyword_section_token13] = ACTIONS(139),
    [aux_sym_keyword_section_token14] = ACTIONS(139),
    [aux_sym_keyword_section_token15] = ACTIONS(139),
    [aux_sym_keyword_flow_token1] = ACTIONS(139),
    [aux_sym_keyword_flow_token2] = ACTIONS(139),
    [aux_sym_keyword_flow_token3] = ACTIONS(139),
    [aux_sym_keyword_flow_token4] = ACTIONS(139),
    [aux_sym_keyword_flow_token5] = ACTIONS(139),
    [aux_sym_keyword_flow_token6] = ACTIONS(139),
    [aux_sym_keyword_flow_token7] = ACTIONS(139),
    [aux_sym_keyword_flow_token8] = ACTIONS(139),
    [aux_sym_keyword_flow_token9] = ACTIONS(139),
    [aux_sym_keyword_flow_token10] = ACTIONS(139),
    [aux_sym_keyword_flow_token11] = ACTIONS(139),
    [aux_sym_keyword_action_token1] = ACTIONS(139),
    [aux_sym_keyword_action_token2] = ACTIONS(139),
    [aux_sym_keyword_action_token3] = ACTIONS(139),
    [aux_sym_keyword_action_token4] = ACTIONS(139),
    [aux_sym_keyword_action_token5] = ACTIONS(139),
    [aux_sym_keyword_action_token6] = ACTIONS(139),
    [aux_sym_keyword_action_token7] = ACTIONS(139),
    [aux_sym_keyword_action_token8] = ACTIONS(139),
    [aux_sym_keyword_action_token9] = ACTIONS(139),
    [aux_sym_keyword_action_token10] = ACTIONS(139),
    [aux_sym_keyword_action_token11] = ACTIONS(139),
    [aux_sym_keyword_action_token12] = ACTIONS(139),
    [aux_sym_keyword_order_token1] = ACTIONS(139),
    [aux_sym_keyword_order_token2] = ACTIONS(139),
    [aux_sym_keyword_order_token3] = ACTIONS(139),
    [aux_sym_keyword_order_token4] = ACTIONS(139),
    [aux_sym_keyword_order_token5] = ACTIONS(139),
    [aux_sym_keyword_order_token6] = ACTIONS(139),
    [aux_sym_keyword_order_token7] = ACTIONS(139),
    [aux_sym_keyword_order_token8] = ACTIONS(139),
    [aux_sym_keyword_order_token9] = ACTIONS(139),
    [aux_sym_keyword_order_token10] = ACTIONS(139),
    [aux_sym_keyword_order_token11] = ACTIONS(139),
    [aux_sym_keyword_order_token12] = ACTIONS(139),
    [aux_sym_keyword_order_token13] = ACTIONS(139),
    [aux_sym_keyword_order_token14] = ACTIONS(139),
    [aux_sym_keyword_order_token15] = ACTIONS(139),
    [aux_sym_keyword_order_token16] = ACTIONS(139),
    [aux_sym_keyword_order_token17] = ACTIONS(139),
    [aux_sym_keyword_order_token18] = ACTIONS(139),
    [aux_sym_keyword_order_token19] = ACTIONS(139),
    [aux_sym_keyword_order_token20] = ACTIONS(139),
    [aux_sym_keyword_order_token21] = ACTIONS(139),
    [aux_sym_keyword_sizing_token1] = ACTIONS(139),
    [aux_sym_keyword_sizing_token2] = ACTIONS(139),
    [aux_sym_keyword_sizing_token3] = ACTIONS(139),
    [aux_sym_keyword_sizing_token4] = ACTIONS(139),
    [aux_sym_keyword_sizing_token5] = ACTIONS(139),
    [aux_sym_keyword_sizing_token6] = ACTIONS(139),
    [aux_sym_keyword_sizing_token7] = ACTIONS(139),
    [aux_sym_keyword_bracket_token1] = ACTIONS(139),
    [aux_sym_keyword_bracket_token2] = ACTIONS(139),
    [aux_sym_keyword_bracket_token3] = ACTIONS(139),
    [aux_sym_keyword_bracket_token4] = ACTIONS(139),
    [aux_sym_keyword_bracket_token5] = ACTIONS(139),
    [aux_sym_keyword_bracket_token6] = ACTIONS(139),
    [aux_sym_keyword_bracket_token7] = ACTIONS(139),
    [aux_sym_keyword_bracket_token8] = ACTIONS(139),
    [aux_sym_keyword_bracket_token9] = ACTIONS(139),
    [aux_sym_keyword_bracket_token10] = ACTIONS(139),
    [aux_sym_keyword_bracket_token11] = ACTIONS(139),
    [aux_sym_keyword_bracket_token12] = ACTIONS(139),
    [aux_sym_keyword_bracket_token13] = ACTIONS(139),
    [aux_sym_keyword_bracket_token14] = ACTIONS(139),
    [aux_sym_keyword_bracket_token15] = ACTIONS(139),
    [aux_sym_keyword_bracket_token16] = ACTIONS(139),
    [aux_sym_keyword_bracket_token17] = ACTIONS(139),
    [aux_sym_keyword_bracket_token18] = ACTIONS(139),
    [aux_sym_keyword_bracket_token19] = ACTIONS(139),
    [aux_sym_keyword_bracket_token20] = ACTIONS(139),
    [aux_sym_keyword_bracket_token21] = ACTIONS(139),
    [aux_sym_keyword_stacking_token1] = ACTIONS(139),
    [aux_sym_keyword_stacking_token2] = ACTIONS(139),
    [aux_sym_keyword_stacking_token3] = ACTIONS(139),
    [aux_sym_keyword_stacking_token4] = ACTIONS(139),
    [aux_sym_keyword_stacking_token5] = ACTIONS(139),
    [aux_sym_keyword_stacking_token6] = ACTIONS(139),
    [aux_sym_keyword_stacking_token7] = ACTIONS(139),
    [aux_sym_keyword_stacking_token8] = ACTIONS(139),
    [aux_sym_keyword_stacking_token9] = ACTIONS(139),
    [aux_sym_keyword_stacking_token10] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(139),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(139),
    [aux_sym_keyword_session_token1] = ACTIONS(139),
    [aux_sym_keyword_session_token2] = ACTIONS(139),
    [aux_sym_keyword_session_token3] = ACTIONS(139),
    [aux_sym_keyword_session_token4] = ACTIONS(139),
    [aux_sym_keyword_session_token5] = ACTIONS(139),
    [aux_sym_keyword_session_token6] = ACTIONS(139),
    [aux_sym_keyword_session_token7] = ACTIONS(139),
    [aux_sym_keyword_session_token8] = ACTIONS(139),
    [aux_sym_keyword_session_token9] = ACTIONS(139),
    [aux_sym_keyword_session_token10] = ACTIONS(139),
    [aux_sym_keyword_session_token11] = ACTIONS(139),
    [aux_sym_keyword_hook_token1] = ACTIONS(139),
    [aux_sym_keyword_hook_token2] = ACTIONS(139),
    [aux_sym_keyword_hook_token3] = ACTIONS(139),
    [aux_sym_keyword_hook_token4] = ACTIONS(139),
    [aux_sym_keyword_state_token1] = ACTIONS(139),
    [aux_sym_keyword_state_token2] = ACTIONS(139),
    [aux_sym_keyword_state_token3] = ACTIONS(139),
    [aux_sym_keyword_state_token4] = ACTIONS(139),
    [aux_sym_keyword_state_token5] = ACTIONS(139),
    [aux_sym_keyword_state_token6] = ACTIONS(139),
    [aux_sym_keyword_state_token7] = ACTIONS(139),
    [aux_sym_keyword_state_token8] = ACTIONS(139),
    [aux_sym_keyword_state_token9] = ACTIONS(139),
    [aux_sym_keyword_state_token10] = ACTIONS(139),
    [aux_sym_keyword_state_token11] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(139),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(139),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(139),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(139),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(139),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(139),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(139),
    [aux_sym_keyword_literal_token1] = ACTIONS(139),
    [aux_sym_keyword_literal_token2] = ACTIONS(139),
    [anon_sym_LPAREN] = ACTIONS(137),
    [anon_sym_DOT] = ACTIONS(137),
    [sym_broker_symbol] = ACTIONS(137),
    [sym_duration] = ACTIONS(137),
    [sym_number] = ACTIONS(139),
    [aux_sym_string_token1] = ACTIONS(137),
    [aux_sym_string_token2] = ACTIONS(137),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(137),
    [anon_sym_EQ_EQ] = ACTIONS(137),
    [anon_sym_LT_GT] = ACTIONS(137),
    [anon_sym_BANG_EQ] = ACTIONS(137),
    [anon_sym_LT_EQ] = ACTIONS(137),
    [anon_sym_GT_EQ] = ACTIONS(137),
    [anon_sym_LT] = ACTIONS(139),
    [anon_sym_GT] = ACTIONS(139),
    [anon_sym_EQ] = ACTIONS(139),
    [anon_sym_PLUS] = ACTIONS(137),
    [anon_sym_DASH] = ACTIONS(139),
    [anon_sym_STAR] = ACTIONS(137),
    [anon_sym_SLASH] = ACTIONS(139),
    [anon_sym_PERCENT] = ACTIONS(137),
    [anon_sym_RPAREN] = ACTIONS(137),
    [anon_sym_LBRACK] = ACTIONS(137),
    [anon_sym_RBRACK] = ACTIONS(137),
    [anon_sym_LBRACE] = ACTIONS(137),
    [anon_sym_RBRACE] = ACTIONS(137),
    [anon_sym_COMMA] = ACTIONS(137),
    [anon_sym_COLON] = ACTIONS(137),
    [anon_sym_SEMI] = ACTIONS(137),
    [anon_sym_AT] = ACTIONS(137),
    [anon_sym_DOLLAR] = ACTIONS(137),
  },
  [8] = {
    [ts_builtin_sym_end] = ACTIONS(141),
    [sym_identifier] = ACTIONS(143),
    [aux_sym_keyword_section_token1] = ACTIONS(143),
    [aux_sym_keyword_section_token2] = ACTIONS(143),
    [aux_sym_keyword_section_token3] = ACTIONS(143),
    [aux_sym_keyword_section_token4] = ACTIONS(143),
    [aux_sym_keyword_section_token5] = ACTIONS(143),
    [aux_sym_keyword_section_token6] = ACTIONS(143),
    [aux_sym_keyword_section_token7] = ACTIONS(143),
    [aux_sym_keyword_section_token8] = ACTIONS(143),
    [aux_sym_keyword_section_token9] = ACTIONS(143),
    [aux_sym_keyword_section_token10] = ACTIONS(143),
    [aux_sym_keyword_section_token11] = ACTIONS(143),
    [aux_sym_keyword_section_token12] = ACTIONS(143),
    [aux_sym_keyword_section_token13] = ACTIONS(143),
    [aux_sym_keyword_section_token14] = ACTIONS(143),
    [aux_sym_keyword_section_token15] = ACTIONS(143),
    [aux_sym_keyword_flow_token1] = ACTIONS(143),
    [aux_sym_keyword_flow_token2] = ACTIONS(143),
    [aux_sym_keyword_flow_token3] = ACTIONS(143),
    [aux_sym_keyword_flow_token4] = ACTIONS(143),
    [aux_sym_keyword_flow_token5] = ACTIONS(143),
    [aux_sym_keyword_flow_token6] = ACTIONS(143),
    [aux_sym_keyword_flow_token7] = ACTIONS(143),
    [aux_sym_keyword_flow_token8] = ACTIONS(143),
    [aux_sym_keyword_flow_token9] = ACTIONS(143),
    [aux_sym_keyword_flow_token10] = ACTIONS(143),
    [aux_sym_keyword_flow_token11] = ACTIONS(143),
    [aux_sym_keyword_action_token1] = ACTIONS(143),
    [aux_sym_keyword_action_token2] = ACTIONS(143),
    [aux_sym_keyword_action_token3] = ACTIONS(143),
    [aux_sym_keyword_action_token4] = ACTIONS(143),
    [aux_sym_keyword_action_token5] = ACTIONS(143),
    [aux_sym_keyword_action_token6] = ACTIONS(143),
    [aux_sym_keyword_action_token7] = ACTIONS(143),
    [aux_sym_keyword_action_token8] = ACTIONS(143),
    [aux_sym_keyword_action_token9] = ACTIONS(143),
    [aux_sym_keyword_action_token10] = ACTIONS(143),
    [aux_sym_keyword_action_token11] = ACTIONS(143),
    [aux_sym_keyword_action_token12] = ACTIONS(143),
    [aux_sym_keyword_order_token1] = ACTIONS(143),
    [aux_sym_keyword_order_token2] = ACTIONS(143),
    [aux_sym_keyword_order_token3] = ACTIONS(143),
    [aux_sym_keyword_order_token4] = ACTIONS(143),
    [aux_sym_keyword_order_token5] = ACTIONS(143),
    [aux_sym_keyword_order_token6] = ACTIONS(143),
    [aux_sym_keyword_order_token7] = ACTIONS(143),
    [aux_sym_keyword_order_token8] = ACTIONS(143),
    [aux_sym_keyword_order_token9] = ACTIONS(143),
    [aux_sym_keyword_order_token10] = ACTIONS(143),
    [aux_sym_keyword_order_token11] = ACTIONS(143),
    [aux_sym_keyword_order_token12] = ACTIONS(143),
    [aux_sym_keyword_order_token13] = ACTIONS(143),
    [aux_sym_keyword_order_token14] = ACTIONS(143),
    [aux_sym_keyword_order_token15] = ACTIONS(143),
    [aux_sym_keyword_order_token16] = ACTIONS(143),
    [aux_sym_keyword_order_token17] = ACTIONS(143),
    [aux_sym_keyword_order_token18] = ACTIONS(143),
    [aux_sym_keyword_order_token19] = ACTIONS(143),
    [aux_sym_keyword_order_token20] = ACTIONS(143),
    [aux_sym_keyword_order_token21] = ACTIONS(143),
    [aux_sym_keyword_sizing_token1] = ACTIONS(143),
    [aux_sym_keyword_sizing_token2] = ACTIONS(143),
    [aux_sym_keyword_sizing_token3] = ACTIONS(143),
    [aux_sym_keyword_sizing_token4] = ACTIONS(143),
    [aux_sym_keyword_sizing_token5] = ACTIONS(143),
    [aux_sym_keyword_sizing_token6] = ACTIONS(143),
    [aux_sym_keyword_sizing_token7] = ACTIONS(143),
    [aux_sym_keyword_bracket_token1] = ACTIONS(143),
    [aux_sym_keyword_bracket_token2] = ACTIONS(143),
    [aux_sym_keyword_bracket_token3] = ACTIONS(143),
    [aux_sym_keyword_bracket_token4] = ACTIONS(143),
    [aux_sym_keyword_bracket_token5] = ACTIONS(143),
    [aux_sym_keyword_bracket_token6] = ACTIONS(143),
    [aux_sym_keyword_bracket_token7] = ACTIONS(143),
    [aux_sym_keyword_bracket_token8] = ACTIONS(143),
    [aux_sym_keyword_bracket_token9] = ACTIONS(143),
    [aux_sym_keyword_bracket_token10] = ACTIONS(143),
    [aux_sym_keyword_bracket_token11] = ACTIONS(143),
    [aux_sym_keyword_bracket_token12] = ACTIONS(143),
    [aux_sym_keyword_bracket_token13] = ACTIONS(143),
    [aux_sym_keyword_bracket_token14] = ACTIONS(143),
    [aux_sym_keyword_bracket_token15] = ACTIONS(143),
    [aux_sym_keyword_bracket_token16] = ACTIONS(143),
    [aux_sym_keyword_bracket_token17] = ACTIONS(143),
    [aux_sym_keyword_bracket_token18] = ACTIONS(143),
    [aux_sym_keyword_bracket_token19] = ACTIONS(143),
    [aux_sym_keyword_bracket_token20] = ACTIONS(143),
    [aux_sym_keyword_bracket_token21] = ACTIONS(143),
    [aux_sym_keyword_stacking_token1] = ACTIONS(143),
    [aux_sym_keyword_stacking_token2] = ACTIONS(143),
    [aux_sym_keyword_stacking_token3] = ACTIONS(143),
    [aux_sym_keyword_stacking_token4] = ACTIONS(143),
    [aux_sym_keyword_stacking_token5] = ACTIONS(143),
    [aux_sym_keyword_stacking_token6] = ACTIONS(143),
    [aux_sym_keyword_stacking_token7] = ACTIONS(143),
    [aux_sym_keyword_stacking_token8] = ACTIONS(143),
    [aux_sym_keyword_stacking_token9] = ACTIONS(143),
    [aux_sym_keyword_stacking_token10] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(143),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(143),
    [aux_sym_keyword_session_token1] = ACTIONS(143),
    [aux_sym_keyword_session_token2] = ACTIONS(143),
    [aux_sym_keyword_session_token3] = ACTIONS(143),
    [aux_sym_keyword_session_token4] = ACTIONS(143),
    [aux_sym_keyword_session_token5] = ACTIONS(143),
    [aux_sym_keyword_session_token6] = ACTIONS(143),
    [aux_sym_keyword_session_token7] = ACTIONS(143),
    [aux_sym_keyword_session_token8] = ACTIONS(143),
    [aux_sym_keyword_session_token9] = ACTIONS(143),
    [aux_sym_keyword_session_token10] = ACTIONS(143),
    [aux_sym_keyword_session_token11] = ACTIONS(143),
    [aux_sym_keyword_hook_token1] = ACTIONS(143),
    [aux_sym_keyword_hook_token2] = ACTIONS(143),
    [aux_sym_keyword_hook_token3] = ACTIONS(143),
    [aux_sym_keyword_hook_token4] = ACTIONS(143),
    [aux_sym_keyword_state_token1] = ACTIONS(143),
    [aux_sym_keyword_state_token2] = ACTIONS(143),
    [aux_sym_keyword_state_token3] = ACTIONS(143),
    [aux_sym_keyword_state_token4] = ACTIONS(143),
    [aux_sym_keyword_state_token5] = ACTIONS(143),
    [aux_sym_keyword_state_token6] = ACTIONS(143),
    [aux_sym_keyword_state_token7] = ACTIONS(143),
    [aux_sym_keyword_state_token8] = ACTIONS(143),
    [aux_sym_keyword_state_token9] = ACTIONS(143),
    [aux_sym_keyword_state_token10] = ACTIONS(143),
    [aux_sym_keyword_state_token11] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(143),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(143),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(143),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(143),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(143),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(143),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(143),
    [aux_sym_keyword_literal_token1] = ACTIONS(143),
    [aux_sym_keyword_literal_token2] = ACTIONS(143),
    [anon_sym_LPAREN] = ACTIONS(141),
    [anon_sym_DOT] = ACTIONS(141),
    [sym_broker_symbol] = ACTIONS(141),
    [sym_duration] = ACTIONS(141),
    [sym_number] = ACTIONS(143),
    [aux_sym_string_token1] = ACTIONS(141),
    [aux_sym_string_token2] = ACTIONS(141),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(141),
    [anon_sym_EQ_EQ] = ACTIONS(141),
    [anon_sym_LT_GT] = ACTIONS(141),
    [anon_sym_BANG_EQ] = ACTIONS(141),
    [anon_sym_LT_EQ] = ACTIONS(141),
    [anon_sym_GT_EQ] = ACTIONS(141),
    [anon_sym_LT] = ACTIONS(143),
    [anon_sym_GT] = ACTIONS(143),
    [anon_sym_EQ] = ACTIONS(143),
    [anon_sym_PLUS] = ACTIONS(141),
    [anon_sym_DASH] = ACTIONS(143),
    [anon_sym_STAR] = ACTIONS(141),
    [anon_sym_SLASH] = ACTIONS(143),
    [anon_sym_PERCENT] = ACTIONS(141),
    [anon_sym_RPAREN] = ACTIONS(141),
    [anon_sym_LBRACK] = ACTIONS(141),
    [anon_sym_RBRACK] = ACTIONS(141),
    [anon_sym_LBRACE] = ACTIONS(141),
    [anon_sym_RBRACE] = ACTIONS(141),
    [anon_sym_COMMA] = ACTIONS(141),
    [anon_sym_COLON] = ACTIONS(141),
    [anon_sym_SEMI] = ACTIONS(141),
    [anon_sym_AT] = ACTIONS(141),
    [anon_sym_DOLLAR] = ACTIONS(141),
  },
  [9] = {
    [ts_builtin_sym_end] = ACTIONS(145),
    [sym_identifier] = ACTIONS(147),
    [aux_sym_keyword_section_token1] = ACTIONS(147),
    [aux_sym_keyword_section_token2] = ACTIONS(147),
    [aux_sym_keyword_section_token3] = ACTIONS(147),
    [aux_sym_keyword_section_token4] = ACTIONS(147),
    [aux_sym_keyword_section_token5] = ACTIONS(147),
    [aux_sym_keyword_section_token6] = ACTIONS(147),
    [aux_sym_keyword_section_token7] = ACTIONS(147),
    [aux_sym_keyword_section_token8] = ACTIONS(147),
    [aux_sym_keyword_section_token9] = ACTIONS(147),
    [aux_sym_keyword_section_token10] = ACTIONS(147),
    [aux_sym_keyword_section_token11] = ACTIONS(147),
    [aux_sym_keyword_section_token12] = ACTIONS(147),
    [aux_sym_keyword_section_token13] = ACTIONS(147),
    [aux_sym_keyword_section_token14] = ACTIONS(147),
    [aux_sym_keyword_section_token15] = ACTIONS(147),
    [aux_sym_keyword_flow_token1] = ACTIONS(147),
    [aux_sym_keyword_flow_token2] = ACTIONS(147),
    [aux_sym_keyword_flow_token3] = ACTIONS(147),
    [aux_sym_keyword_flow_token4] = ACTIONS(147),
    [aux_sym_keyword_flow_token5] = ACTIONS(147),
    [aux_sym_keyword_flow_token6] = ACTIONS(147),
    [aux_sym_keyword_flow_token7] = ACTIONS(147),
    [aux_sym_keyword_flow_token8] = ACTIONS(147),
    [aux_sym_keyword_flow_token9] = ACTIONS(147),
    [aux_sym_keyword_flow_token10] = ACTIONS(147),
    [aux_sym_keyword_flow_token11] = ACTIONS(147),
    [aux_sym_keyword_action_token1] = ACTIONS(147),
    [aux_sym_keyword_action_token2] = ACTIONS(147),
    [aux_sym_keyword_action_token3] = ACTIONS(147),
    [aux_sym_keyword_action_token4] = ACTIONS(147),
    [aux_sym_keyword_action_token5] = ACTIONS(147),
    [aux_sym_keyword_action_token6] = ACTIONS(147),
    [aux_sym_keyword_action_token7] = ACTIONS(147),
    [aux_sym_keyword_action_token8] = ACTIONS(147),
    [aux_sym_keyword_action_token9] = ACTIONS(147),
    [aux_sym_keyword_action_token10] = ACTIONS(147),
    [aux_sym_keyword_action_token11] = ACTIONS(147),
    [aux_sym_keyword_action_token12] = ACTIONS(147),
    [aux_sym_keyword_order_token1] = ACTIONS(147),
    [aux_sym_keyword_order_token2] = ACTIONS(147),
    [aux_sym_keyword_order_token3] = ACTIONS(147),
    [aux_sym_keyword_order_token4] = ACTIONS(147),
    [aux_sym_keyword_order_token5] = ACTIONS(147),
    [aux_sym_keyword_order_token6] = ACTIONS(147),
    [aux_sym_keyword_order_token7] = ACTIONS(147),
    [aux_sym_keyword_order_token8] = ACTIONS(147),
    [aux_sym_keyword_order_token9] = ACTIONS(147),
    [aux_sym_keyword_order_token10] = ACTIONS(147),
    [aux_sym_keyword_order_token11] = ACTIONS(147),
    [aux_sym_keyword_order_token12] = ACTIONS(147),
    [aux_sym_keyword_order_token13] = ACTIONS(147),
    [aux_sym_keyword_order_token14] = ACTIONS(147),
    [aux_sym_keyword_order_token15] = ACTIONS(147),
    [aux_sym_keyword_order_token16] = ACTIONS(147),
    [aux_sym_keyword_order_token17] = ACTIONS(147),
    [aux_sym_keyword_order_token18] = ACTIONS(147),
    [aux_sym_keyword_order_token19] = ACTIONS(147),
    [aux_sym_keyword_order_token20] = ACTIONS(147),
    [aux_sym_keyword_order_token21] = ACTIONS(147),
    [aux_sym_keyword_sizing_token1] = ACTIONS(147),
    [aux_sym_keyword_sizing_token2] = ACTIONS(147),
    [aux_sym_keyword_sizing_token3] = ACTIONS(147),
    [aux_sym_keyword_sizing_token4] = ACTIONS(147),
    [aux_sym_keyword_sizing_token5] = ACTIONS(147),
    [aux_sym_keyword_sizing_token6] = ACTIONS(147),
    [aux_sym_keyword_sizing_token7] = ACTIONS(147),
    [aux_sym_keyword_bracket_token1] = ACTIONS(147),
    [aux_sym_keyword_bracket_token2] = ACTIONS(147),
    [aux_sym_keyword_bracket_token3] = ACTIONS(147),
    [aux_sym_keyword_bracket_token4] = ACTIONS(147),
    [aux_sym_keyword_bracket_token5] = ACTIONS(147),
    [aux_sym_keyword_bracket_token6] = ACTIONS(147),
    [aux_sym_keyword_bracket_token7] = ACTIONS(147),
    [aux_sym_keyword_bracket_token8] = ACTIONS(147),
    [aux_sym_keyword_bracket_token9] = ACTIONS(147),
    [aux_sym_keyword_bracket_token10] = ACTIONS(147),
    [aux_sym_keyword_bracket_token11] = ACTIONS(147),
    [aux_sym_keyword_bracket_token12] = ACTIONS(147),
    [aux_sym_keyword_bracket_token13] = ACTIONS(147),
    [aux_sym_keyword_bracket_token14] = ACTIONS(147),
    [aux_sym_keyword_bracket_token15] = ACTIONS(147),
    [aux_sym_keyword_bracket_token16] = ACTIONS(147),
    [aux_sym_keyword_bracket_token17] = ACTIONS(147),
    [aux_sym_keyword_bracket_token18] = ACTIONS(147),
    [aux_sym_keyword_bracket_token19] = ACTIONS(147),
    [aux_sym_keyword_bracket_token20] = ACTIONS(147),
    [aux_sym_keyword_bracket_token21] = ACTIONS(147),
    [aux_sym_keyword_stacking_token1] = ACTIONS(147),
    [aux_sym_keyword_stacking_token2] = ACTIONS(147),
    [aux_sym_keyword_stacking_token3] = ACTIONS(147),
    [aux_sym_keyword_stacking_token4] = ACTIONS(147),
    [aux_sym_keyword_stacking_token5] = ACTIONS(147),
    [aux_sym_keyword_stacking_token6] = ACTIONS(147),
    [aux_sym_keyword_stacking_token7] = ACTIONS(147),
    [aux_sym_keyword_stacking_token8] = ACTIONS(147),
    [aux_sym_keyword_stacking_token9] = ACTIONS(147),
    [aux_sym_keyword_stacking_token10] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(147),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(147),
    [aux_sym_keyword_session_token1] = ACTIONS(147),
    [aux_sym_keyword_session_token2] = ACTIONS(147),
    [aux_sym_keyword_session_token3] = ACTIONS(147),
    [aux_sym_keyword_session_token4] = ACTIONS(147),
    [aux_sym_keyword_session_token5] = ACTIONS(147),
    [aux_sym_keyword_session_token6] = ACTIONS(147),
    [aux_sym_keyword_session_token7] = ACTIONS(147),
    [aux_sym_keyword_session_token8] = ACTIONS(147),
    [aux_sym_keyword_session_token9] = ACTIONS(147),
    [aux_sym_keyword_session_token10] = ACTIONS(147),
    [aux_sym_keyword_session_token11] = ACTIONS(147),
    [aux_sym_keyword_hook_token1] = ACTIONS(147),
    [aux_sym_keyword_hook_token2] = ACTIONS(147),
    [aux_sym_keyword_hook_token3] = ACTIONS(147),
    [aux_sym_keyword_hook_token4] = ACTIONS(147),
    [aux_sym_keyword_state_token1] = ACTIONS(147),
    [aux_sym_keyword_state_token2] = ACTIONS(147),
    [aux_sym_keyword_state_token3] = ACTIONS(147),
    [aux_sym_keyword_state_token4] = ACTIONS(147),
    [aux_sym_keyword_state_token5] = ACTIONS(147),
    [aux_sym_keyword_state_token6] = ACTIONS(147),
    [aux_sym_keyword_state_token7] = ACTIONS(147),
    [aux_sym_keyword_state_token8] = ACTIONS(147),
    [aux_sym_keyword_state_token9] = ACTIONS(147),
    [aux_sym_keyword_state_token10] = ACTIONS(147),
    [aux_sym_keyword_state_token11] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(147),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(147),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(147),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(147),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(147),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(147),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(147),
    [aux_sym_keyword_literal_token1] = ACTIONS(147),
    [aux_sym_keyword_literal_token2] = ACTIONS(147),
    [anon_sym_LPAREN] = ACTIONS(145),
    [anon_sym_DOT] = ACTIONS(145),
    [sym_broker_symbol] = ACTIONS(145),
    [sym_duration] = ACTIONS(145),
    [sym_number] = ACTIONS(147),
    [aux_sym_string_token1] = ACTIONS(145),
    [aux_sym_string_token2] = ACTIONS(145),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(145),
    [anon_sym_EQ_EQ] = ACTIONS(145),
    [anon_sym_LT_GT] = ACTIONS(145),
    [anon_sym_BANG_EQ] = ACTIONS(145),
    [anon_sym_LT_EQ] = ACTIONS(145),
    [anon_sym_GT_EQ] = ACTIONS(145),
    [anon_sym_LT] = ACTIONS(147),
    [anon_sym_GT] = ACTIONS(147),
    [anon_sym_EQ] = ACTIONS(147),
    [anon_sym_PLUS] = ACTIONS(145),
    [anon_sym_DASH] = ACTIONS(147),
    [anon_sym_STAR] = ACTIONS(145),
    [anon_sym_SLASH] = ACTIONS(147),
    [anon_sym_PERCENT] = ACTIONS(145),
    [anon_sym_RPAREN] = ACTIONS(145),
    [anon_sym_LBRACK] = ACTIONS(145),
    [anon_sym_RBRACK] = ACTIONS(145),
    [anon_sym_LBRACE] = ACTIONS(145),
    [anon_sym_RBRACE] = ACTIONS(145),
    [anon_sym_COMMA] = ACTIONS(145),
    [anon_sym_COLON] = ACTIONS(145),
    [anon_sym_SEMI] = ACTIONS(145),
    [anon_sym_AT] = ACTIONS(145),
    [anon_sym_DOLLAR] = ACTIONS(145),
  },
  [10] = {
    [ts_builtin_sym_end] = ACTIONS(149),
    [sym_identifier] = ACTIONS(151),
    [aux_sym_keyword_section_token1] = ACTIONS(151),
    [aux_sym_keyword_section_token2] = ACTIONS(151),
    [aux_sym_keyword_section_token3] = ACTIONS(151),
    [aux_sym_keyword_section_token4] = ACTIONS(151),
    [aux_sym_keyword_section_token5] = ACTIONS(151),
    [aux_sym_keyword_section_token6] = ACTIONS(151),
    [aux_sym_keyword_section_token7] = ACTIONS(151),
    [aux_sym_keyword_section_token8] = ACTIONS(151),
    [aux_sym_keyword_section_token9] = ACTIONS(151),
    [aux_sym_keyword_section_token10] = ACTIONS(151),
    [aux_sym_keyword_section_token11] = ACTIONS(151),
    [aux_sym_keyword_section_token12] = ACTIONS(151),
    [aux_sym_keyword_section_token13] = ACTIONS(151),
    [aux_sym_keyword_section_token14] = ACTIONS(151),
    [aux_sym_keyword_section_token15] = ACTIONS(151),
    [aux_sym_keyword_flow_token1] = ACTIONS(151),
    [aux_sym_keyword_flow_token2] = ACTIONS(151),
    [aux_sym_keyword_flow_token3] = ACTIONS(151),
    [aux_sym_keyword_flow_token4] = ACTIONS(151),
    [aux_sym_keyword_flow_token5] = ACTIONS(151),
    [aux_sym_keyword_flow_token6] = ACTIONS(151),
    [aux_sym_keyword_flow_token7] = ACTIONS(151),
    [aux_sym_keyword_flow_token8] = ACTIONS(151),
    [aux_sym_keyword_flow_token9] = ACTIONS(151),
    [aux_sym_keyword_flow_token10] = ACTIONS(151),
    [aux_sym_keyword_flow_token11] = ACTIONS(151),
    [aux_sym_keyword_action_token1] = ACTIONS(151),
    [aux_sym_keyword_action_token2] = ACTIONS(151),
    [aux_sym_keyword_action_token3] = ACTIONS(151),
    [aux_sym_keyword_action_token4] = ACTIONS(151),
    [aux_sym_keyword_action_token5] = ACTIONS(151),
    [aux_sym_keyword_action_token6] = ACTIONS(151),
    [aux_sym_keyword_action_token7] = ACTIONS(151),
    [aux_sym_keyword_action_token8] = ACTIONS(151),
    [aux_sym_keyword_action_token9] = ACTIONS(151),
    [aux_sym_keyword_action_token10] = ACTIONS(151),
    [aux_sym_keyword_action_token11] = ACTIONS(151),
    [aux_sym_keyword_action_token12] = ACTIONS(151),
    [aux_sym_keyword_order_token1] = ACTIONS(151),
    [aux_sym_keyword_order_token2] = ACTIONS(151),
    [aux_sym_keyword_order_token3] = ACTIONS(151),
    [aux_sym_keyword_order_token4] = ACTIONS(151),
    [aux_sym_keyword_order_token5] = ACTIONS(151),
    [aux_sym_keyword_order_token6] = ACTIONS(151),
    [aux_sym_keyword_order_token7] = ACTIONS(151),
    [aux_sym_keyword_order_token8] = ACTIONS(151),
    [aux_sym_keyword_order_token9] = ACTIONS(151),
    [aux_sym_keyword_order_token10] = ACTIONS(151),
    [aux_sym_keyword_order_token11] = ACTIONS(151),
    [aux_sym_keyword_order_token12] = ACTIONS(151),
    [aux_sym_keyword_order_token13] = ACTIONS(151),
    [aux_sym_keyword_order_token14] = ACTIONS(151),
    [aux_sym_keyword_order_token15] = ACTIONS(151),
    [aux_sym_keyword_order_token16] = ACTIONS(151),
    [aux_sym_keyword_order_token17] = ACTIONS(151),
    [aux_sym_keyword_order_token18] = ACTIONS(151),
    [aux_sym_keyword_order_token19] = ACTIONS(151),
    [aux_sym_keyword_order_token20] = ACTIONS(151),
    [aux_sym_keyword_order_token21] = ACTIONS(151),
    [aux_sym_keyword_sizing_token1] = ACTIONS(151),
    [aux_sym_keyword_sizing_token2] = ACTIONS(151),
    [aux_sym_keyword_sizing_token3] = ACTIONS(151),
    [aux_sym_keyword_sizing_token4] = ACTIONS(151),
    [aux_sym_keyword_sizing_token5] = ACTIONS(151),
    [aux_sym_keyword_sizing_token6] = ACTIONS(151),
    [aux_sym_keyword_sizing_token7] = ACTIONS(151),
    [aux_sym_keyword_bracket_token1] = ACTIONS(151),
    [aux_sym_keyword_bracket_token2] = ACTIONS(151),
    [aux_sym_keyword_bracket_token3] = ACTIONS(151),
    [aux_sym_keyword_bracket_token4] = ACTIONS(151),
    [aux_sym_keyword_bracket_token5] = ACTIONS(151),
    [aux_sym_keyword_bracket_token6] = ACTIONS(151),
    [aux_sym_keyword_bracket_token7] = ACTIONS(151),
    [aux_sym_keyword_bracket_token8] = ACTIONS(151),
    [aux_sym_keyword_bracket_token9] = ACTIONS(151),
    [aux_sym_keyword_bracket_token10] = ACTIONS(151),
    [aux_sym_keyword_bracket_token11] = ACTIONS(151),
    [aux_sym_keyword_bracket_token12] = ACTIONS(151),
    [aux_sym_keyword_bracket_token13] = ACTIONS(151),
    [aux_sym_keyword_bracket_token14] = ACTIONS(151),
    [aux_sym_keyword_bracket_token15] = ACTIONS(151),
    [aux_sym_keyword_bracket_token16] = ACTIONS(151),
    [aux_sym_keyword_bracket_token17] = ACTIONS(151),
    [aux_sym_keyword_bracket_token18] = ACTIONS(151),
    [aux_sym_keyword_bracket_token19] = ACTIONS(151),
    [aux_sym_keyword_bracket_token20] = ACTIONS(151),
    [aux_sym_keyword_bracket_token21] = ACTIONS(151),
    [aux_sym_keyword_stacking_token1] = ACTIONS(151),
    [aux_sym_keyword_stacking_token2] = ACTIONS(151),
    [aux_sym_keyword_stacking_token3] = ACTIONS(151),
    [aux_sym_keyword_stacking_token4] = ACTIONS(151),
    [aux_sym_keyword_stacking_token5] = ACTIONS(151),
    [aux_sym_keyword_stacking_token6] = ACTIONS(151),
    [aux_sym_keyword_stacking_token7] = ACTIONS(151),
    [aux_sym_keyword_stacking_token8] = ACTIONS(151),
    [aux_sym_keyword_stacking_token9] = ACTIONS(151),
    [aux_sym_keyword_stacking_token10] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(151),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(151),
    [aux_sym_keyword_session_token1] = ACTIONS(151),
    [aux_sym_keyword_session_token2] = ACTIONS(151),
    [aux_sym_keyword_session_token3] = ACTIONS(151),
    [aux_sym_keyword_session_token4] = ACTIONS(151),
    [aux_sym_keyword_session_token5] = ACTIONS(151),
    [aux_sym_keyword_session_token6] = ACTIONS(151),
    [aux_sym_keyword_session_token7] = ACTIONS(151),
    [aux_sym_keyword_session_token8] = ACTIONS(151),
    [aux_sym_keyword_session_token9] = ACTIONS(151),
    [aux_sym_keyword_session_token10] = ACTIONS(151),
    [aux_sym_keyword_session_token11] = ACTIONS(151),
    [aux_sym_keyword_hook_token1] = ACTIONS(151),
    [aux_sym_keyword_hook_token2] = ACTIONS(151),
    [aux_sym_keyword_hook_token3] = ACTIONS(151),
    [aux_sym_keyword_hook_token4] = ACTIONS(151),
    [aux_sym_keyword_state_token1] = ACTIONS(151),
    [aux_sym_keyword_state_token2] = ACTIONS(151),
    [aux_sym_keyword_state_token3] = ACTIONS(151),
    [aux_sym_keyword_state_token4] = ACTIONS(151),
    [aux_sym_keyword_state_token5] = ACTIONS(151),
    [aux_sym_keyword_state_token6] = ACTIONS(151),
    [aux_sym_keyword_state_token7] = ACTIONS(151),
    [aux_sym_keyword_state_token8] = ACTIONS(151),
    [aux_sym_keyword_state_token9] = ACTIONS(151),
    [aux_sym_keyword_state_token10] = ACTIONS(151),
    [aux_sym_keyword_state_token11] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(151),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(151),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(151),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(151),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(151),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(151),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(151),
    [aux_sym_keyword_literal_token1] = ACTIONS(151),
    [aux_sym_keyword_literal_token2] = ACTIONS(151),
    [anon_sym_LPAREN] = ACTIONS(149),
    [anon_sym_DOT] = ACTIONS(149),
    [sym_broker_symbol] = ACTIONS(149),
    [sym_duration] = ACTIONS(149),
    [sym_number] = ACTIONS(151),
    [aux_sym_string_token1] = ACTIONS(149),
    [aux_sym_string_token2] = ACTIONS(149),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(149),
    [anon_sym_EQ_EQ] = ACTIONS(149),
    [anon_sym_LT_GT] = ACTIONS(149),
    [anon_sym_BANG_EQ] = ACTIONS(149),
    [anon_sym_LT_EQ] = ACTIONS(149),
    [anon_sym_GT_EQ] = ACTIONS(149),
    [anon_sym_LT] = ACTIONS(151),
    [anon_sym_GT] = ACTIONS(151),
    [anon_sym_EQ] = ACTIONS(151),
    [anon_sym_PLUS] = ACTIONS(149),
    [anon_sym_DASH] = ACTIONS(151),
    [anon_sym_STAR] = ACTIONS(149),
    [anon_sym_SLASH] = ACTIONS(151),
    [anon_sym_PERCENT] = ACTIONS(149),
    [anon_sym_RPAREN] = ACTIONS(149),
    [anon_sym_LBRACK] = ACTIONS(149),
    [anon_sym_RBRACK] = ACTIONS(149),
    [anon_sym_LBRACE] = ACTIONS(149),
    [anon_sym_RBRACE] = ACTIONS(149),
    [anon_sym_COMMA] = ACTIONS(149),
    [anon_sym_COLON] = ACTIONS(149),
    [anon_sym_SEMI] = ACTIONS(149),
    [anon_sym_AT] = ACTIONS(149),
    [anon_sym_DOLLAR] = ACTIONS(149),
  },
  [11] = {
    [ts_builtin_sym_end] = ACTIONS(153),
    [sym_identifier] = ACTIONS(155),
    [aux_sym_keyword_section_token1] = ACTIONS(155),
    [aux_sym_keyword_section_token2] = ACTIONS(155),
    [aux_sym_keyword_section_token3] = ACTIONS(155),
    [aux_sym_keyword_section_token4] = ACTIONS(155),
    [aux_sym_keyword_section_token5] = ACTIONS(155),
    [aux_sym_keyword_section_token6] = ACTIONS(155),
    [aux_sym_keyword_section_token7] = ACTIONS(155),
    [aux_sym_keyword_section_token8] = ACTIONS(155),
    [aux_sym_keyword_section_token9] = ACTIONS(155),
    [aux_sym_keyword_section_token10] = ACTIONS(155),
    [aux_sym_keyword_section_token11] = ACTIONS(155),
    [aux_sym_keyword_section_token12] = ACTIONS(155),
    [aux_sym_keyword_section_token13] = ACTIONS(155),
    [aux_sym_keyword_section_token14] = ACTIONS(155),
    [aux_sym_keyword_section_token15] = ACTIONS(155),
    [aux_sym_keyword_flow_token1] = ACTIONS(155),
    [aux_sym_keyword_flow_token2] = ACTIONS(155),
    [aux_sym_keyword_flow_token3] = ACTIONS(155),
    [aux_sym_keyword_flow_token4] = ACTIONS(155),
    [aux_sym_keyword_flow_token5] = ACTIONS(155),
    [aux_sym_keyword_flow_token6] = ACTIONS(155),
    [aux_sym_keyword_flow_token7] = ACTIONS(155),
    [aux_sym_keyword_flow_token8] = ACTIONS(155),
    [aux_sym_keyword_flow_token9] = ACTIONS(155),
    [aux_sym_keyword_flow_token10] = ACTIONS(155),
    [aux_sym_keyword_flow_token11] = ACTIONS(155),
    [aux_sym_keyword_action_token1] = ACTIONS(155),
    [aux_sym_keyword_action_token2] = ACTIONS(155),
    [aux_sym_keyword_action_token3] = ACTIONS(155),
    [aux_sym_keyword_action_token4] = ACTIONS(155),
    [aux_sym_keyword_action_token5] = ACTIONS(155),
    [aux_sym_keyword_action_token6] = ACTIONS(155),
    [aux_sym_keyword_action_token7] = ACTIONS(155),
    [aux_sym_keyword_action_token8] = ACTIONS(155),
    [aux_sym_keyword_action_token9] = ACTIONS(155),
    [aux_sym_keyword_action_token10] = ACTIONS(155),
    [aux_sym_keyword_action_token11] = ACTIONS(155),
    [aux_sym_keyword_action_token12] = ACTIONS(155),
    [aux_sym_keyword_order_token1] = ACTIONS(155),
    [aux_sym_keyword_order_token2] = ACTIONS(155),
    [aux_sym_keyword_order_token3] = ACTIONS(155),
    [aux_sym_keyword_order_token4] = ACTIONS(155),
    [aux_sym_keyword_order_token5] = ACTIONS(155),
    [aux_sym_keyword_order_token6] = ACTIONS(155),
    [aux_sym_keyword_order_token7] = ACTIONS(155),
    [aux_sym_keyword_order_token8] = ACTIONS(155),
    [aux_sym_keyword_order_token9] = ACTIONS(155),
    [aux_sym_keyword_order_token10] = ACTIONS(155),
    [aux_sym_keyword_order_token11] = ACTIONS(155),
    [aux_sym_keyword_order_token12] = ACTIONS(155),
    [aux_sym_keyword_order_token13] = ACTIONS(155),
    [aux_sym_keyword_order_token14] = ACTIONS(155),
    [aux_sym_keyword_order_token15] = ACTIONS(155),
    [aux_sym_keyword_order_token16] = ACTIONS(155),
    [aux_sym_keyword_order_token17] = ACTIONS(155),
    [aux_sym_keyword_order_token18] = ACTIONS(155),
    [aux_sym_keyword_order_token19] = ACTIONS(155),
    [aux_sym_keyword_order_token20] = ACTIONS(155),
    [aux_sym_keyword_order_token21] = ACTIONS(155),
    [aux_sym_keyword_sizing_token1] = ACTIONS(155),
    [aux_sym_keyword_sizing_token2] = ACTIONS(155),
    [aux_sym_keyword_sizing_token3] = ACTIONS(155),
    [aux_sym_keyword_sizing_token4] = ACTIONS(155),
    [aux_sym_keyword_sizing_token5] = ACTIONS(155),
    [aux_sym_keyword_sizing_token6] = ACTIONS(155),
    [aux_sym_keyword_sizing_token7] = ACTIONS(155),
    [aux_sym_keyword_bracket_token1] = ACTIONS(155),
    [aux_sym_keyword_bracket_token2] = ACTIONS(155),
    [aux_sym_keyword_bracket_token3] = ACTIONS(155),
    [aux_sym_keyword_bracket_token4] = ACTIONS(155),
    [aux_sym_keyword_bracket_token5] = ACTIONS(155),
    [aux_sym_keyword_bracket_token6] = ACTIONS(155),
    [aux_sym_keyword_bracket_token7] = ACTIONS(155),
    [aux_sym_keyword_bracket_token8] = ACTIONS(155),
    [aux_sym_keyword_bracket_token9] = ACTIONS(155),
    [aux_sym_keyword_bracket_token10] = ACTIONS(155),
    [aux_sym_keyword_bracket_token11] = ACTIONS(155),
    [aux_sym_keyword_bracket_token12] = ACTIONS(155),
    [aux_sym_keyword_bracket_token13] = ACTIONS(155),
    [aux_sym_keyword_bracket_token14] = ACTIONS(155),
    [aux_sym_keyword_bracket_token15] = ACTIONS(155),
    [aux_sym_keyword_bracket_token16] = ACTIONS(155),
    [aux_sym_keyword_bracket_token17] = ACTIONS(155),
    [aux_sym_keyword_bracket_token18] = ACTIONS(155),
    [aux_sym_keyword_bracket_token19] = ACTIONS(155),
    [aux_sym_keyword_bracket_token20] = ACTIONS(155),
    [aux_sym_keyword_bracket_token21] = ACTIONS(155),
    [aux_sym_keyword_stacking_token1] = ACTIONS(155),
    [aux_sym_keyword_stacking_token2] = ACTIONS(155),
    [aux_sym_keyword_stacking_token3] = ACTIONS(155),
    [aux_sym_keyword_stacking_token4] = ACTIONS(155),
    [aux_sym_keyword_stacking_token5] = ACTIONS(155),
    [aux_sym_keyword_stacking_token6] = ACTIONS(155),
    [aux_sym_keyword_stacking_token7] = ACTIONS(155),
    [aux_sym_keyword_stacking_token8] = ACTIONS(155),
    [aux_sym_keyword_stacking_token9] = ACTIONS(155),
    [aux_sym_keyword_stacking_token10] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(155),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(155),
    [aux_sym_keyword_session_token1] = ACTIONS(155),
    [aux_sym_keyword_session_token2] = ACTIONS(155),
    [aux_sym_keyword_session_token3] = ACTIONS(155),
    [aux_sym_keyword_session_token4] = ACTIONS(155),
    [aux_sym_keyword_session_token5] = ACTIONS(155),
    [aux_sym_keyword_session_token6] = ACTIONS(155),
    [aux_sym_keyword_session_token7] = ACTIONS(155),
    [aux_sym_keyword_session_token8] = ACTIONS(155),
    [aux_sym_keyword_session_token9] = ACTIONS(155),
    [aux_sym_keyword_session_token10] = ACTIONS(155),
    [aux_sym_keyword_session_token11] = ACTIONS(155),
    [aux_sym_keyword_hook_token1] = ACTIONS(155),
    [aux_sym_keyword_hook_token2] = ACTIONS(155),
    [aux_sym_keyword_hook_token3] = ACTIONS(155),
    [aux_sym_keyword_hook_token4] = ACTIONS(155),
    [aux_sym_keyword_state_token1] = ACTIONS(155),
    [aux_sym_keyword_state_token2] = ACTIONS(155),
    [aux_sym_keyword_state_token3] = ACTIONS(155),
    [aux_sym_keyword_state_token4] = ACTIONS(155),
    [aux_sym_keyword_state_token5] = ACTIONS(155),
    [aux_sym_keyword_state_token6] = ACTIONS(155),
    [aux_sym_keyword_state_token7] = ACTIONS(155),
    [aux_sym_keyword_state_token8] = ACTIONS(155),
    [aux_sym_keyword_state_token9] = ACTIONS(155),
    [aux_sym_keyword_state_token10] = ACTIONS(155),
    [aux_sym_keyword_state_token11] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(155),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(155),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(155),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(155),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(155),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(155),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(155),
    [aux_sym_keyword_literal_token1] = ACTIONS(155),
    [aux_sym_keyword_literal_token2] = ACTIONS(155),
    [anon_sym_LPAREN] = ACTIONS(153),
    [anon_sym_DOT] = ACTIONS(153),
    [sym_broker_symbol] = ACTIONS(153),
    [sym_duration] = ACTIONS(153),
    [sym_number] = ACTIONS(155),
    [aux_sym_string_token1] = ACTIONS(153),
    [aux_sym_string_token2] = ACTIONS(153),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(153),
    [anon_sym_EQ_EQ] = ACTIONS(153),
    [anon_sym_LT_GT] = ACTIONS(153),
    [anon_sym_BANG_EQ] = ACTIONS(153),
    [anon_sym_LT_EQ] = ACTIONS(153),
    [anon_sym_GT_EQ] = ACTIONS(153),
    [anon_sym_LT] = ACTIONS(155),
    [anon_sym_GT] = ACTIONS(155),
    [anon_sym_EQ] = ACTIONS(155),
    [anon_sym_PLUS] = ACTIONS(153),
    [anon_sym_DASH] = ACTIONS(155),
    [anon_sym_STAR] = ACTIONS(153),
    [anon_sym_SLASH] = ACTIONS(155),
    [anon_sym_PERCENT] = ACTIONS(153),
    [anon_sym_RPAREN] = ACTIONS(153),
    [anon_sym_LBRACK] = ACTIONS(153),
    [anon_sym_RBRACK] = ACTIONS(153),
    [anon_sym_LBRACE] = ACTIONS(153),
    [anon_sym_RBRACE] = ACTIONS(153),
    [anon_sym_COMMA] = ACTIONS(153),
    [anon_sym_COLON] = ACTIONS(153),
    [anon_sym_SEMI] = ACTIONS(153),
    [anon_sym_AT] = ACTIONS(153),
    [anon_sym_DOLLAR] = ACTIONS(153),
  },
  [12] = {
    [ts_builtin_sym_end] = ACTIONS(157),
    [sym_identifier] = ACTIONS(159),
    [aux_sym_keyword_section_token1] = ACTIONS(159),
    [aux_sym_keyword_section_token2] = ACTIONS(159),
    [aux_sym_keyword_section_token3] = ACTIONS(159),
    [aux_sym_keyword_section_token4] = ACTIONS(159),
    [aux_sym_keyword_section_token5] = ACTIONS(159),
    [aux_sym_keyword_section_token6] = ACTIONS(159),
    [aux_sym_keyword_section_token7] = ACTIONS(159),
    [aux_sym_keyword_section_token8] = ACTIONS(159),
    [aux_sym_keyword_section_token9] = ACTIONS(159),
    [aux_sym_keyword_section_token10] = ACTIONS(159),
    [aux_sym_keyword_section_token11] = ACTIONS(159),
    [aux_sym_keyword_section_token12] = ACTIONS(159),
    [aux_sym_keyword_section_token13] = ACTIONS(159),
    [aux_sym_keyword_section_token14] = ACTIONS(159),
    [aux_sym_keyword_section_token15] = ACTIONS(159),
    [aux_sym_keyword_flow_token1] = ACTIONS(159),
    [aux_sym_keyword_flow_token2] = ACTIONS(159),
    [aux_sym_keyword_flow_token3] = ACTIONS(159),
    [aux_sym_keyword_flow_token4] = ACTIONS(159),
    [aux_sym_keyword_flow_token5] = ACTIONS(159),
    [aux_sym_keyword_flow_token6] = ACTIONS(159),
    [aux_sym_keyword_flow_token7] = ACTIONS(159),
    [aux_sym_keyword_flow_token8] = ACTIONS(159),
    [aux_sym_keyword_flow_token9] = ACTIONS(159),
    [aux_sym_keyword_flow_token10] = ACTIONS(159),
    [aux_sym_keyword_flow_token11] = ACTIONS(159),
    [aux_sym_keyword_action_token1] = ACTIONS(159),
    [aux_sym_keyword_action_token2] = ACTIONS(159),
    [aux_sym_keyword_action_token3] = ACTIONS(159),
    [aux_sym_keyword_action_token4] = ACTIONS(159),
    [aux_sym_keyword_action_token5] = ACTIONS(159),
    [aux_sym_keyword_action_token6] = ACTIONS(159),
    [aux_sym_keyword_action_token7] = ACTIONS(159),
    [aux_sym_keyword_action_token8] = ACTIONS(159),
    [aux_sym_keyword_action_token9] = ACTIONS(159),
    [aux_sym_keyword_action_token10] = ACTIONS(159),
    [aux_sym_keyword_action_token11] = ACTIONS(159),
    [aux_sym_keyword_action_token12] = ACTIONS(159),
    [aux_sym_keyword_order_token1] = ACTIONS(159),
    [aux_sym_keyword_order_token2] = ACTIONS(159),
    [aux_sym_keyword_order_token3] = ACTIONS(159),
    [aux_sym_keyword_order_token4] = ACTIONS(159),
    [aux_sym_keyword_order_token5] = ACTIONS(159),
    [aux_sym_keyword_order_token6] = ACTIONS(159),
    [aux_sym_keyword_order_token7] = ACTIONS(159),
    [aux_sym_keyword_order_token8] = ACTIONS(159),
    [aux_sym_keyword_order_token9] = ACTIONS(159),
    [aux_sym_keyword_order_token10] = ACTIONS(159),
    [aux_sym_keyword_order_token11] = ACTIONS(159),
    [aux_sym_keyword_order_token12] = ACTIONS(159),
    [aux_sym_keyword_order_token13] = ACTIONS(159),
    [aux_sym_keyword_order_token14] = ACTIONS(159),
    [aux_sym_keyword_order_token15] = ACTIONS(159),
    [aux_sym_keyword_order_token16] = ACTIONS(159),
    [aux_sym_keyword_order_token17] = ACTIONS(159),
    [aux_sym_keyword_order_token18] = ACTIONS(159),
    [aux_sym_keyword_order_token19] = ACTIONS(159),
    [aux_sym_keyword_order_token20] = ACTIONS(159),
    [aux_sym_keyword_order_token21] = ACTIONS(159),
    [aux_sym_keyword_sizing_token1] = ACTIONS(159),
    [aux_sym_keyword_sizing_token2] = ACTIONS(159),
    [aux_sym_keyword_sizing_token3] = ACTIONS(159),
    [aux_sym_keyword_sizing_token4] = ACTIONS(159),
    [aux_sym_keyword_sizing_token5] = ACTIONS(159),
    [aux_sym_keyword_sizing_token6] = ACTIONS(159),
    [aux_sym_keyword_sizing_token7] = ACTIONS(159),
    [aux_sym_keyword_bracket_token1] = ACTIONS(159),
    [aux_sym_keyword_bracket_token2] = ACTIONS(159),
    [aux_sym_keyword_bracket_token3] = ACTIONS(159),
    [aux_sym_keyword_bracket_token4] = ACTIONS(159),
    [aux_sym_keyword_bracket_token5] = ACTIONS(159),
    [aux_sym_keyword_bracket_token6] = ACTIONS(159),
    [aux_sym_keyword_bracket_token7] = ACTIONS(159),
    [aux_sym_keyword_bracket_token8] = ACTIONS(159),
    [aux_sym_keyword_bracket_token9] = ACTIONS(159),
    [aux_sym_keyword_bracket_token10] = ACTIONS(159),
    [aux_sym_keyword_bracket_token11] = ACTIONS(159),
    [aux_sym_keyword_bracket_token12] = ACTIONS(159),
    [aux_sym_keyword_bracket_token13] = ACTIONS(159),
    [aux_sym_keyword_bracket_token14] = ACTIONS(159),
    [aux_sym_keyword_bracket_token15] = ACTIONS(159),
    [aux_sym_keyword_bracket_token16] = ACTIONS(159),
    [aux_sym_keyword_bracket_token17] = ACTIONS(159),
    [aux_sym_keyword_bracket_token18] = ACTIONS(159),
    [aux_sym_keyword_bracket_token19] = ACTIONS(159),
    [aux_sym_keyword_bracket_token20] = ACTIONS(159),
    [aux_sym_keyword_bracket_token21] = ACTIONS(159),
    [aux_sym_keyword_stacking_token1] = ACTIONS(159),
    [aux_sym_keyword_stacking_token2] = ACTIONS(159),
    [aux_sym_keyword_stacking_token3] = ACTIONS(159),
    [aux_sym_keyword_stacking_token4] = ACTIONS(159),
    [aux_sym_keyword_stacking_token5] = ACTIONS(159),
    [aux_sym_keyword_stacking_token6] = ACTIONS(159),
    [aux_sym_keyword_stacking_token7] = ACTIONS(159),
    [aux_sym_keyword_stacking_token8] = ACTIONS(159),
    [aux_sym_keyword_stacking_token9] = ACTIONS(159),
    [aux_sym_keyword_stacking_token10] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(159),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(159),
    [aux_sym_keyword_session_token1] = ACTIONS(159),
    [aux_sym_keyword_session_token2] = ACTIONS(159),
    [aux_sym_keyword_session_token3] = ACTIONS(159),
    [aux_sym_keyword_session_token4] = ACTIONS(159),
    [aux_sym_keyword_session_token5] = ACTIONS(159),
    [aux_sym_keyword_session_token6] = ACTIONS(159),
    [aux_sym_keyword_session_token7] = ACTIONS(159),
    [aux_sym_keyword_session_token8] = ACTIONS(159),
    [aux_sym_keyword_session_token9] = ACTIONS(159),
    [aux_sym_keyword_session_token10] = ACTIONS(159),
    [aux_sym_keyword_session_token11] = ACTIONS(159),
    [aux_sym_keyword_hook_token1] = ACTIONS(159),
    [aux_sym_keyword_hook_token2] = ACTIONS(159),
    [aux_sym_keyword_hook_token3] = ACTIONS(159),
    [aux_sym_keyword_hook_token4] = ACTIONS(159),
    [aux_sym_keyword_state_token1] = ACTIONS(159),
    [aux_sym_keyword_state_token2] = ACTIONS(159),
    [aux_sym_keyword_state_token3] = ACTIONS(159),
    [aux_sym_keyword_state_token4] = ACTIONS(159),
    [aux_sym_keyword_state_token5] = ACTIONS(159),
    [aux_sym_keyword_state_token6] = ACTIONS(159),
    [aux_sym_keyword_state_token7] = ACTIONS(159),
    [aux_sym_keyword_state_token8] = ACTIONS(159),
    [aux_sym_keyword_state_token9] = ACTIONS(159),
    [aux_sym_keyword_state_token10] = ACTIONS(159),
    [aux_sym_keyword_state_token11] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(159),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(159),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(159),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(159),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(159),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(159),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(159),
    [aux_sym_keyword_literal_token1] = ACTIONS(159),
    [aux_sym_keyword_literal_token2] = ACTIONS(159),
    [anon_sym_LPAREN] = ACTIONS(157),
    [anon_sym_DOT] = ACTIONS(157),
    [sym_broker_symbol] = ACTIONS(157),
    [sym_duration] = ACTIONS(157),
    [sym_number] = ACTIONS(159),
    [aux_sym_string_token1] = ACTIONS(157),
    [aux_sym_string_token2] = ACTIONS(157),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(157),
    [anon_sym_EQ_EQ] = ACTIONS(157),
    [anon_sym_LT_GT] = ACTIONS(157),
    [anon_sym_BANG_EQ] = ACTIONS(157),
    [anon_sym_LT_EQ] = ACTIONS(157),
    [anon_sym_GT_EQ] = ACTIONS(157),
    [anon_sym_LT] = ACTIONS(159),
    [anon_sym_GT] = ACTIONS(159),
    [anon_sym_EQ] = ACTIONS(159),
    [anon_sym_PLUS] = ACTIONS(157),
    [anon_sym_DASH] = ACTIONS(159),
    [anon_sym_STAR] = ACTIONS(157),
    [anon_sym_SLASH] = ACTIONS(159),
    [anon_sym_PERCENT] = ACTIONS(157),
    [anon_sym_RPAREN] = ACTIONS(157),
    [anon_sym_LBRACK] = ACTIONS(157),
    [anon_sym_RBRACK] = ACTIONS(157),
    [anon_sym_LBRACE] = ACTIONS(157),
    [anon_sym_RBRACE] = ACTIONS(157),
    [anon_sym_COMMA] = ACTIONS(157),
    [anon_sym_COLON] = ACTIONS(157),
    [anon_sym_SEMI] = ACTIONS(157),
    [anon_sym_AT] = ACTIONS(157),
    [anon_sym_DOLLAR] = ACTIONS(157),
  },
  [13] = {
    [ts_builtin_sym_end] = ACTIONS(161),
    [sym_identifier] = ACTIONS(163),
    [aux_sym_keyword_section_token1] = ACTIONS(163),
    [aux_sym_keyword_section_token2] = ACTIONS(163),
    [aux_sym_keyword_section_token3] = ACTIONS(163),
    [aux_sym_keyword_section_token4] = ACTIONS(163),
    [aux_sym_keyword_section_token5] = ACTIONS(163),
    [aux_sym_keyword_section_token6] = ACTIONS(163),
    [aux_sym_keyword_section_token7] = ACTIONS(163),
    [aux_sym_keyword_section_token8] = ACTIONS(163),
    [aux_sym_keyword_section_token9] = ACTIONS(163),
    [aux_sym_keyword_section_token10] = ACTIONS(163),
    [aux_sym_keyword_section_token11] = ACTIONS(163),
    [aux_sym_keyword_section_token12] = ACTIONS(163),
    [aux_sym_keyword_section_token13] = ACTIONS(163),
    [aux_sym_keyword_section_token14] = ACTIONS(163),
    [aux_sym_keyword_section_token15] = ACTIONS(163),
    [aux_sym_keyword_flow_token1] = ACTIONS(163),
    [aux_sym_keyword_flow_token2] = ACTIONS(163),
    [aux_sym_keyword_flow_token3] = ACTIONS(163),
    [aux_sym_keyword_flow_token4] = ACTIONS(163),
    [aux_sym_keyword_flow_token5] = ACTIONS(163),
    [aux_sym_keyword_flow_token6] = ACTIONS(163),
    [aux_sym_keyword_flow_token7] = ACTIONS(163),
    [aux_sym_keyword_flow_token8] = ACTIONS(163),
    [aux_sym_keyword_flow_token9] = ACTIONS(163),
    [aux_sym_keyword_flow_token10] = ACTIONS(163),
    [aux_sym_keyword_flow_token11] = ACTIONS(163),
    [aux_sym_keyword_action_token1] = ACTIONS(163),
    [aux_sym_keyword_action_token2] = ACTIONS(163),
    [aux_sym_keyword_action_token3] = ACTIONS(163),
    [aux_sym_keyword_action_token4] = ACTIONS(163),
    [aux_sym_keyword_action_token5] = ACTIONS(163),
    [aux_sym_keyword_action_token6] = ACTIONS(163),
    [aux_sym_keyword_action_token7] = ACTIONS(163),
    [aux_sym_keyword_action_token8] = ACTIONS(163),
    [aux_sym_keyword_action_token9] = ACTIONS(163),
    [aux_sym_keyword_action_token10] = ACTIONS(163),
    [aux_sym_keyword_action_token11] = ACTIONS(163),
    [aux_sym_keyword_action_token12] = ACTIONS(163),
    [aux_sym_keyword_order_token1] = ACTIONS(163),
    [aux_sym_keyword_order_token2] = ACTIONS(163),
    [aux_sym_keyword_order_token3] = ACTIONS(163),
    [aux_sym_keyword_order_token4] = ACTIONS(163),
    [aux_sym_keyword_order_token5] = ACTIONS(163),
    [aux_sym_keyword_order_token6] = ACTIONS(163),
    [aux_sym_keyword_order_token7] = ACTIONS(163),
    [aux_sym_keyword_order_token8] = ACTIONS(163),
    [aux_sym_keyword_order_token9] = ACTIONS(163),
    [aux_sym_keyword_order_token10] = ACTIONS(163),
    [aux_sym_keyword_order_token11] = ACTIONS(163),
    [aux_sym_keyword_order_token12] = ACTIONS(163),
    [aux_sym_keyword_order_token13] = ACTIONS(163),
    [aux_sym_keyword_order_token14] = ACTIONS(163),
    [aux_sym_keyword_order_token15] = ACTIONS(163),
    [aux_sym_keyword_order_token16] = ACTIONS(163),
    [aux_sym_keyword_order_token17] = ACTIONS(163),
    [aux_sym_keyword_order_token18] = ACTIONS(163),
    [aux_sym_keyword_order_token19] = ACTIONS(163),
    [aux_sym_keyword_order_token20] = ACTIONS(163),
    [aux_sym_keyword_order_token21] = ACTIONS(163),
    [aux_sym_keyword_sizing_token1] = ACTIONS(163),
    [aux_sym_keyword_sizing_token2] = ACTIONS(163),
    [aux_sym_keyword_sizing_token3] = ACTIONS(163),
    [aux_sym_keyword_sizing_token4] = ACTIONS(163),
    [aux_sym_keyword_sizing_token5] = ACTIONS(163),
    [aux_sym_keyword_sizing_token6] = ACTIONS(163),
    [aux_sym_keyword_sizing_token7] = ACTIONS(163),
    [aux_sym_keyword_bracket_token1] = ACTIONS(163),
    [aux_sym_keyword_bracket_token2] = ACTIONS(163),
    [aux_sym_keyword_bracket_token3] = ACTIONS(163),
    [aux_sym_keyword_bracket_token4] = ACTIONS(163),
    [aux_sym_keyword_bracket_token5] = ACTIONS(163),
    [aux_sym_keyword_bracket_token6] = ACTIONS(163),
    [aux_sym_keyword_bracket_token7] = ACTIONS(163),
    [aux_sym_keyword_bracket_token8] = ACTIONS(163),
    [aux_sym_keyword_bracket_token9] = ACTIONS(163),
    [aux_sym_keyword_bracket_token10] = ACTIONS(163),
    [aux_sym_keyword_bracket_token11] = ACTIONS(163),
    [aux_sym_keyword_bracket_token12] = ACTIONS(163),
    [aux_sym_keyword_bracket_token13] = ACTIONS(163),
    [aux_sym_keyword_bracket_token14] = ACTIONS(163),
    [aux_sym_keyword_bracket_token15] = ACTIONS(163),
    [aux_sym_keyword_bracket_token16] = ACTIONS(163),
    [aux_sym_keyword_bracket_token17] = ACTIONS(163),
    [aux_sym_keyword_bracket_token18] = ACTIONS(163),
    [aux_sym_keyword_bracket_token19] = ACTIONS(163),
    [aux_sym_keyword_bracket_token20] = ACTIONS(163),
    [aux_sym_keyword_bracket_token21] = ACTIONS(163),
    [aux_sym_keyword_stacking_token1] = ACTIONS(163),
    [aux_sym_keyword_stacking_token2] = ACTIONS(163),
    [aux_sym_keyword_stacking_token3] = ACTIONS(163),
    [aux_sym_keyword_stacking_token4] = ACTIONS(163),
    [aux_sym_keyword_stacking_token5] = ACTIONS(163),
    [aux_sym_keyword_stacking_token6] = ACTIONS(163),
    [aux_sym_keyword_stacking_token7] = ACTIONS(163),
    [aux_sym_keyword_stacking_token8] = ACTIONS(163),
    [aux_sym_keyword_stacking_token9] = ACTIONS(163),
    [aux_sym_keyword_stacking_token10] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(163),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(163),
    [aux_sym_keyword_session_token1] = ACTIONS(163),
    [aux_sym_keyword_session_token2] = ACTIONS(163),
    [aux_sym_keyword_session_token3] = ACTIONS(163),
    [aux_sym_keyword_session_token4] = ACTIONS(163),
    [aux_sym_keyword_session_token5] = ACTIONS(163),
    [aux_sym_keyword_session_token6] = ACTIONS(163),
    [aux_sym_keyword_session_token7] = ACTIONS(163),
    [aux_sym_keyword_session_token8] = ACTIONS(163),
    [aux_sym_keyword_session_token9] = ACTIONS(163),
    [aux_sym_keyword_session_token10] = ACTIONS(163),
    [aux_sym_keyword_session_token11] = ACTIONS(163),
    [aux_sym_keyword_hook_token1] = ACTIONS(163),
    [aux_sym_keyword_hook_token2] = ACTIONS(163),
    [aux_sym_keyword_hook_token3] = ACTIONS(163),
    [aux_sym_keyword_hook_token4] = ACTIONS(163),
    [aux_sym_keyword_state_token1] = ACTIONS(163),
    [aux_sym_keyword_state_token2] = ACTIONS(163),
    [aux_sym_keyword_state_token3] = ACTIONS(163),
    [aux_sym_keyword_state_token4] = ACTIONS(163),
    [aux_sym_keyword_state_token5] = ACTIONS(163),
    [aux_sym_keyword_state_token6] = ACTIONS(163),
    [aux_sym_keyword_state_token7] = ACTIONS(163),
    [aux_sym_keyword_state_token8] = ACTIONS(163),
    [aux_sym_keyword_state_token9] = ACTIONS(163),
    [aux_sym_keyword_state_token10] = ACTIONS(163),
    [aux_sym_keyword_state_token11] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(163),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(163),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(163),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(163),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(163),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(163),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(163),
    [aux_sym_keyword_literal_token1] = ACTIONS(163),
    [aux_sym_keyword_literal_token2] = ACTIONS(163),
    [anon_sym_LPAREN] = ACTIONS(161),
    [anon_sym_DOT] = ACTIONS(161),
    [sym_broker_symbol] = ACTIONS(161),
    [sym_duration] = ACTIONS(161),
    [sym_number] = ACTIONS(163),
    [aux_sym_string_token1] = ACTIONS(161),
    [aux_sym_string_token2] = ACTIONS(161),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(161),
    [anon_sym_EQ_EQ] = ACTIONS(161),
    [anon_sym_LT_GT] = ACTIONS(161),
    [anon_sym_BANG_EQ] = ACTIONS(161),
    [anon_sym_LT_EQ] = ACTIONS(161),
    [anon_sym_GT_EQ] = ACTIONS(161),
    [anon_sym_LT] = ACTIONS(163),
    [anon_sym_GT] = ACTIONS(163),
    [anon_sym_EQ] = ACTIONS(163),
    [anon_sym_PLUS] = ACTIONS(161),
    [anon_sym_DASH] = ACTIONS(163),
    [anon_sym_STAR] = ACTIONS(161),
    [anon_sym_SLASH] = ACTIONS(163),
    [anon_sym_PERCENT] = ACTIONS(161),
    [anon_sym_RPAREN] = ACTIONS(161),
    [anon_sym_LBRACK] = ACTIONS(161),
    [anon_sym_RBRACK] = ACTIONS(161),
    [anon_sym_LBRACE] = ACTIONS(161),
    [anon_sym_RBRACE] = ACTIONS(161),
    [anon_sym_COMMA] = ACTIONS(161),
    [anon_sym_COLON] = ACTIONS(161),
    [anon_sym_SEMI] = ACTIONS(161),
    [anon_sym_AT] = ACTIONS(161),
    [anon_sym_DOLLAR] = ACTIONS(161),
  },
  [14] = {
    [ts_builtin_sym_end] = ACTIONS(165),
    [sym_identifier] = ACTIONS(167),
    [aux_sym_keyword_section_token1] = ACTIONS(167),
    [aux_sym_keyword_section_token2] = ACTIONS(167),
    [aux_sym_keyword_section_token3] = ACTIONS(167),
    [aux_sym_keyword_section_token4] = ACTIONS(167),
    [aux_sym_keyword_section_token5] = ACTIONS(167),
    [aux_sym_keyword_section_token6] = ACTIONS(167),
    [aux_sym_keyword_section_token7] = ACTIONS(167),
    [aux_sym_keyword_section_token8] = ACTIONS(167),
    [aux_sym_keyword_section_token9] = ACTIONS(167),
    [aux_sym_keyword_section_token10] = ACTIONS(167),
    [aux_sym_keyword_section_token11] = ACTIONS(167),
    [aux_sym_keyword_section_token12] = ACTIONS(167),
    [aux_sym_keyword_section_token13] = ACTIONS(167),
    [aux_sym_keyword_section_token14] = ACTIONS(167),
    [aux_sym_keyword_section_token15] = ACTIONS(167),
    [aux_sym_keyword_flow_token1] = ACTIONS(167),
    [aux_sym_keyword_flow_token2] = ACTIONS(167),
    [aux_sym_keyword_flow_token3] = ACTIONS(167),
    [aux_sym_keyword_flow_token4] = ACTIONS(167),
    [aux_sym_keyword_flow_token5] = ACTIONS(167),
    [aux_sym_keyword_flow_token6] = ACTIONS(167),
    [aux_sym_keyword_flow_token7] = ACTIONS(167),
    [aux_sym_keyword_flow_token8] = ACTIONS(167),
    [aux_sym_keyword_flow_token9] = ACTIONS(167),
    [aux_sym_keyword_flow_token10] = ACTIONS(167),
    [aux_sym_keyword_flow_token11] = ACTIONS(167),
    [aux_sym_keyword_action_token1] = ACTIONS(167),
    [aux_sym_keyword_action_token2] = ACTIONS(167),
    [aux_sym_keyword_action_token3] = ACTIONS(167),
    [aux_sym_keyword_action_token4] = ACTIONS(167),
    [aux_sym_keyword_action_token5] = ACTIONS(167),
    [aux_sym_keyword_action_token6] = ACTIONS(167),
    [aux_sym_keyword_action_token7] = ACTIONS(167),
    [aux_sym_keyword_action_token8] = ACTIONS(167),
    [aux_sym_keyword_action_token9] = ACTIONS(167),
    [aux_sym_keyword_action_token10] = ACTIONS(167),
    [aux_sym_keyword_action_token11] = ACTIONS(167),
    [aux_sym_keyword_action_token12] = ACTIONS(167),
    [aux_sym_keyword_order_token1] = ACTIONS(167),
    [aux_sym_keyword_order_token2] = ACTIONS(167),
    [aux_sym_keyword_order_token3] = ACTIONS(167),
    [aux_sym_keyword_order_token4] = ACTIONS(167),
    [aux_sym_keyword_order_token5] = ACTIONS(167),
    [aux_sym_keyword_order_token6] = ACTIONS(167),
    [aux_sym_keyword_order_token7] = ACTIONS(167),
    [aux_sym_keyword_order_token8] = ACTIONS(167),
    [aux_sym_keyword_order_token9] = ACTIONS(167),
    [aux_sym_keyword_order_token10] = ACTIONS(167),
    [aux_sym_keyword_order_token11] = ACTIONS(167),
    [aux_sym_keyword_order_token12] = ACTIONS(167),
    [aux_sym_keyword_order_token13] = ACTIONS(167),
    [aux_sym_keyword_order_token14] = ACTIONS(167),
    [aux_sym_keyword_order_token15] = ACTIONS(167),
    [aux_sym_keyword_order_token16] = ACTIONS(167),
    [aux_sym_keyword_order_token17] = ACTIONS(167),
    [aux_sym_keyword_order_token18] = ACTIONS(167),
    [aux_sym_keyword_order_token19] = ACTIONS(167),
    [aux_sym_keyword_order_token20] = ACTIONS(167),
    [aux_sym_keyword_order_token21] = ACTIONS(167),
    [aux_sym_keyword_sizing_token1] = ACTIONS(167),
    [aux_sym_keyword_sizing_token2] = ACTIONS(167),
    [aux_sym_keyword_sizing_token3] = ACTIONS(167),
    [aux_sym_keyword_sizing_token4] = ACTIONS(167),
    [aux_sym_keyword_sizing_token5] = ACTIONS(167),
    [aux_sym_keyword_sizing_token6] = ACTIONS(167),
    [aux_sym_keyword_sizing_token7] = ACTIONS(167),
    [aux_sym_keyword_bracket_token1] = ACTIONS(167),
    [aux_sym_keyword_bracket_token2] = ACTIONS(167),
    [aux_sym_keyword_bracket_token3] = ACTIONS(167),
    [aux_sym_keyword_bracket_token4] = ACTIONS(167),
    [aux_sym_keyword_bracket_token5] = ACTIONS(167),
    [aux_sym_keyword_bracket_token6] = ACTIONS(167),
    [aux_sym_keyword_bracket_token7] = ACTIONS(167),
    [aux_sym_keyword_bracket_token8] = ACTIONS(167),
    [aux_sym_keyword_bracket_token9] = ACTIONS(167),
    [aux_sym_keyword_bracket_token10] = ACTIONS(167),
    [aux_sym_keyword_bracket_token11] = ACTIONS(167),
    [aux_sym_keyword_bracket_token12] = ACTIONS(167),
    [aux_sym_keyword_bracket_token13] = ACTIONS(167),
    [aux_sym_keyword_bracket_token14] = ACTIONS(167),
    [aux_sym_keyword_bracket_token15] = ACTIONS(167),
    [aux_sym_keyword_bracket_token16] = ACTIONS(167),
    [aux_sym_keyword_bracket_token17] = ACTIONS(167),
    [aux_sym_keyword_bracket_token18] = ACTIONS(167),
    [aux_sym_keyword_bracket_token19] = ACTIONS(167),
    [aux_sym_keyword_bracket_token20] = ACTIONS(167),
    [aux_sym_keyword_bracket_token21] = ACTIONS(167),
    [aux_sym_keyword_stacking_token1] = ACTIONS(167),
    [aux_sym_keyword_stacking_token2] = ACTIONS(167),
    [aux_sym_keyword_stacking_token3] = ACTIONS(167),
    [aux_sym_keyword_stacking_token4] = ACTIONS(167),
    [aux_sym_keyword_stacking_token5] = ACTIONS(167),
    [aux_sym_keyword_stacking_token6] = ACTIONS(167),
    [aux_sym_keyword_stacking_token7] = ACTIONS(167),
    [aux_sym_keyword_stacking_token8] = ACTIONS(167),
    [aux_sym_keyword_stacking_token9] = ACTIONS(167),
    [aux_sym_keyword_stacking_token10] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(167),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(167),
    [aux_sym_keyword_session_token1] = ACTIONS(167),
    [aux_sym_keyword_session_token2] = ACTIONS(167),
    [aux_sym_keyword_session_token3] = ACTIONS(167),
    [aux_sym_keyword_session_token4] = ACTIONS(167),
    [aux_sym_keyword_session_token5] = ACTIONS(167),
    [aux_sym_keyword_session_token6] = ACTIONS(167),
    [aux_sym_keyword_session_token7] = ACTIONS(167),
    [aux_sym_keyword_session_token8] = ACTIONS(167),
    [aux_sym_keyword_session_token9] = ACTIONS(167),
    [aux_sym_keyword_session_token10] = ACTIONS(167),
    [aux_sym_keyword_session_token11] = ACTIONS(167),
    [aux_sym_keyword_hook_token1] = ACTIONS(167),
    [aux_sym_keyword_hook_token2] = ACTIONS(167),
    [aux_sym_keyword_hook_token3] = ACTIONS(167),
    [aux_sym_keyword_hook_token4] = ACTIONS(167),
    [aux_sym_keyword_state_token1] = ACTIONS(167),
    [aux_sym_keyword_state_token2] = ACTIONS(167),
    [aux_sym_keyword_state_token3] = ACTIONS(167),
    [aux_sym_keyword_state_token4] = ACTIONS(167),
    [aux_sym_keyword_state_token5] = ACTIONS(167),
    [aux_sym_keyword_state_token6] = ACTIONS(167),
    [aux_sym_keyword_state_token7] = ACTIONS(167),
    [aux_sym_keyword_state_token8] = ACTIONS(167),
    [aux_sym_keyword_state_token9] = ACTIONS(167),
    [aux_sym_keyword_state_token10] = ACTIONS(167),
    [aux_sym_keyword_state_token11] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(167),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(167),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(167),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(167),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(167),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(167),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(167),
    [aux_sym_keyword_literal_token1] = ACTIONS(167),
    [aux_sym_keyword_literal_token2] = ACTIONS(167),
    [anon_sym_LPAREN] = ACTIONS(165),
    [anon_sym_DOT] = ACTIONS(165),
    [sym_broker_symbol] = ACTIONS(165),
    [sym_duration] = ACTIONS(165),
    [sym_number] = ACTIONS(167),
    [aux_sym_string_token1] = ACTIONS(165),
    [aux_sym_string_token2] = ACTIONS(165),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(165),
    [anon_sym_EQ_EQ] = ACTIONS(165),
    [anon_sym_LT_GT] = ACTIONS(165),
    [anon_sym_BANG_EQ] = ACTIONS(165),
    [anon_sym_LT_EQ] = ACTIONS(165),
    [anon_sym_GT_EQ] = ACTIONS(165),
    [anon_sym_LT] = ACTIONS(167),
    [anon_sym_GT] = ACTIONS(167),
    [anon_sym_EQ] = ACTIONS(167),
    [anon_sym_PLUS] = ACTIONS(165),
    [anon_sym_DASH] = ACTIONS(167),
    [anon_sym_STAR] = ACTIONS(165),
    [anon_sym_SLASH] = ACTIONS(167),
    [anon_sym_PERCENT] = ACTIONS(165),
    [anon_sym_RPAREN] = ACTIONS(165),
    [anon_sym_LBRACK] = ACTIONS(165),
    [anon_sym_RBRACK] = ACTIONS(165),
    [anon_sym_LBRACE] = ACTIONS(165),
    [anon_sym_RBRACE] = ACTIONS(165),
    [anon_sym_COMMA] = ACTIONS(165),
    [anon_sym_COLON] = ACTIONS(165),
    [anon_sym_SEMI] = ACTIONS(165),
    [anon_sym_AT] = ACTIONS(165),
    [anon_sym_DOLLAR] = ACTIONS(165),
  },
  [15] = {
    [ts_builtin_sym_end] = ACTIONS(169),
    [sym_identifier] = ACTIONS(171),
    [aux_sym_keyword_section_token1] = ACTIONS(171),
    [aux_sym_keyword_section_token2] = ACTIONS(171),
    [aux_sym_keyword_section_token3] = ACTIONS(171),
    [aux_sym_keyword_section_token4] = ACTIONS(171),
    [aux_sym_keyword_section_token5] = ACTIONS(171),
    [aux_sym_keyword_section_token6] = ACTIONS(171),
    [aux_sym_keyword_section_token7] = ACTIONS(171),
    [aux_sym_keyword_section_token8] = ACTIONS(171),
    [aux_sym_keyword_section_token9] = ACTIONS(171),
    [aux_sym_keyword_section_token10] = ACTIONS(171),
    [aux_sym_keyword_section_token11] = ACTIONS(171),
    [aux_sym_keyword_section_token12] = ACTIONS(171),
    [aux_sym_keyword_section_token13] = ACTIONS(171),
    [aux_sym_keyword_section_token14] = ACTIONS(171),
    [aux_sym_keyword_section_token15] = ACTIONS(171),
    [aux_sym_keyword_flow_token1] = ACTIONS(171),
    [aux_sym_keyword_flow_token2] = ACTIONS(171),
    [aux_sym_keyword_flow_token3] = ACTIONS(171),
    [aux_sym_keyword_flow_token4] = ACTIONS(171),
    [aux_sym_keyword_flow_token5] = ACTIONS(171),
    [aux_sym_keyword_flow_token6] = ACTIONS(171),
    [aux_sym_keyword_flow_token7] = ACTIONS(171),
    [aux_sym_keyword_flow_token8] = ACTIONS(171),
    [aux_sym_keyword_flow_token9] = ACTIONS(171),
    [aux_sym_keyword_flow_token10] = ACTIONS(171),
    [aux_sym_keyword_flow_token11] = ACTIONS(171),
    [aux_sym_keyword_action_token1] = ACTIONS(171),
    [aux_sym_keyword_action_token2] = ACTIONS(171),
    [aux_sym_keyword_action_token3] = ACTIONS(171),
    [aux_sym_keyword_action_token4] = ACTIONS(171),
    [aux_sym_keyword_action_token5] = ACTIONS(171),
    [aux_sym_keyword_action_token6] = ACTIONS(171),
    [aux_sym_keyword_action_token7] = ACTIONS(171),
    [aux_sym_keyword_action_token8] = ACTIONS(171),
    [aux_sym_keyword_action_token9] = ACTIONS(171),
    [aux_sym_keyword_action_token10] = ACTIONS(171),
    [aux_sym_keyword_action_token11] = ACTIONS(171),
    [aux_sym_keyword_action_token12] = ACTIONS(171),
    [aux_sym_keyword_order_token1] = ACTIONS(171),
    [aux_sym_keyword_order_token2] = ACTIONS(171),
    [aux_sym_keyword_order_token3] = ACTIONS(171),
    [aux_sym_keyword_order_token4] = ACTIONS(171),
    [aux_sym_keyword_order_token5] = ACTIONS(171),
    [aux_sym_keyword_order_token6] = ACTIONS(171),
    [aux_sym_keyword_order_token7] = ACTIONS(171),
    [aux_sym_keyword_order_token8] = ACTIONS(171),
    [aux_sym_keyword_order_token9] = ACTIONS(171),
    [aux_sym_keyword_order_token10] = ACTIONS(171),
    [aux_sym_keyword_order_token11] = ACTIONS(171),
    [aux_sym_keyword_order_token12] = ACTIONS(171),
    [aux_sym_keyword_order_token13] = ACTIONS(171),
    [aux_sym_keyword_order_token14] = ACTIONS(171),
    [aux_sym_keyword_order_token15] = ACTIONS(171),
    [aux_sym_keyword_order_token16] = ACTIONS(171),
    [aux_sym_keyword_order_token17] = ACTIONS(171),
    [aux_sym_keyword_order_token18] = ACTIONS(171),
    [aux_sym_keyword_order_token19] = ACTIONS(171),
    [aux_sym_keyword_order_token20] = ACTIONS(171),
    [aux_sym_keyword_order_token21] = ACTIONS(171),
    [aux_sym_keyword_sizing_token1] = ACTIONS(171),
    [aux_sym_keyword_sizing_token2] = ACTIONS(171),
    [aux_sym_keyword_sizing_token3] = ACTIONS(171),
    [aux_sym_keyword_sizing_token4] = ACTIONS(171),
    [aux_sym_keyword_sizing_token5] = ACTIONS(171),
    [aux_sym_keyword_sizing_token6] = ACTIONS(171),
    [aux_sym_keyword_sizing_token7] = ACTIONS(171),
    [aux_sym_keyword_bracket_token1] = ACTIONS(171),
    [aux_sym_keyword_bracket_token2] = ACTIONS(171),
    [aux_sym_keyword_bracket_token3] = ACTIONS(171),
    [aux_sym_keyword_bracket_token4] = ACTIONS(171),
    [aux_sym_keyword_bracket_token5] = ACTIONS(171),
    [aux_sym_keyword_bracket_token6] = ACTIONS(171),
    [aux_sym_keyword_bracket_token7] = ACTIONS(171),
    [aux_sym_keyword_bracket_token8] = ACTIONS(171),
    [aux_sym_keyword_bracket_token9] = ACTIONS(171),
    [aux_sym_keyword_bracket_token10] = ACTIONS(171),
    [aux_sym_keyword_bracket_token11] = ACTIONS(171),
    [aux_sym_keyword_bracket_token12] = ACTIONS(171),
    [aux_sym_keyword_bracket_token13] = ACTIONS(171),
    [aux_sym_keyword_bracket_token14] = ACTIONS(171),
    [aux_sym_keyword_bracket_token15] = ACTIONS(171),
    [aux_sym_keyword_bracket_token16] = ACTIONS(171),
    [aux_sym_keyword_bracket_token17] = ACTIONS(171),
    [aux_sym_keyword_bracket_token18] = ACTIONS(171),
    [aux_sym_keyword_bracket_token19] = ACTIONS(171),
    [aux_sym_keyword_bracket_token20] = ACTIONS(171),
    [aux_sym_keyword_bracket_token21] = ACTIONS(171),
    [aux_sym_keyword_stacking_token1] = ACTIONS(171),
    [aux_sym_keyword_stacking_token2] = ACTIONS(171),
    [aux_sym_keyword_stacking_token3] = ACTIONS(171),
    [aux_sym_keyword_stacking_token4] = ACTIONS(171),
    [aux_sym_keyword_stacking_token5] = ACTIONS(171),
    [aux_sym_keyword_stacking_token6] = ACTIONS(171),
    [aux_sym_keyword_stacking_token7] = ACTIONS(171),
    [aux_sym_keyword_stacking_token8] = ACTIONS(171),
    [aux_sym_keyword_stacking_token9] = ACTIONS(171),
    [aux_sym_keyword_stacking_token10] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(171),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(171),
    [aux_sym_keyword_session_token1] = ACTIONS(171),
    [aux_sym_keyword_session_token2] = ACTIONS(171),
    [aux_sym_keyword_session_token3] = ACTIONS(171),
    [aux_sym_keyword_session_token4] = ACTIONS(171),
    [aux_sym_keyword_session_token5] = ACTIONS(171),
    [aux_sym_keyword_session_token6] = ACTIONS(171),
    [aux_sym_keyword_session_token7] = ACTIONS(171),
    [aux_sym_keyword_session_token8] = ACTIONS(171),
    [aux_sym_keyword_session_token9] = ACTIONS(171),
    [aux_sym_keyword_session_token10] = ACTIONS(171),
    [aux_sym_keyword_session_token11] = ACTIONS(171),
    [aux_sym_keyword_hook_token1] = ACTIONS(171),
    [aux_sym_keyword_hook_token2] = ACTIONS(171),
    [aux_sym_keyword_hook_token3] = ACTIONS(171),
    [aux_sym_keyword_hook_token4] = ACTIONS(171),
    [aux_sym_keyword_state_token1] = ACTIONS(171),
    [aux_sym_keyword_state_token2] = ACTIONS(171),
    [aux_sym_keyword_state_token3] = ACTIONS(171),
    [aux_sym_keyword_state_token4] = ACTIONS(171),
    [aux_sym_keyword_state_token5] = ACTIONS(171),
    [aux_sym_keyword_state_token6] = ACTIONS(171),
    [aux_sym_keyword_state_token7] = ACTIONS(171),
    [aux_sym_keyword_state_token8] = ACTIONS(171),
    [aux_sym_keyword_state_token9] = ACTIONS(171),
    [aux_sym_keyword_state_token10] = ACTIONS(171),
    [aux_sym_keyword_state_token11] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(171),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(171),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(171),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(171),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(171),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(171),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(171),
    [aux_sym_keyword_literal_token1] = ACTIONS(171),
    [aux_sym_keyword_literal_token2] = ACTIONS(171),
    [anon_sym_LPAREN] = ACTIONS(169),
    [anon_sym_DOT] = ACTIONS(169),
    [sym_broker_symbol] = ACTIONS(169),
    [sym_duration] = ACTIONS(169),
    [sym_number] = ACTIONS(171),
    [aux_sym_string_token1] = ACTIONS(169),
    [aux_sym_string_token2] = ACTIONS(169),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(169),
    [anon_sym_EQ_EQ] = ACTIONS(169),
    [anon_sym_LT_GT] = ACTIONS(169),
    [anon_sym_BANG_EQ] = ACTIONS(169),
    [anon_sym_LT_EQ] = ACTIONS(169),
    [anon_sym_GT_EQ] = ACTIONS(169),
    [anon_sym_LT] = ACTIONS(171),
    [anon_sym_GT] = ACTIONS(171),
    [anon_sym_EQ] = ACTIONS(171),
    [anon_sym_PLUS] = ACTIONS(169),
    [anon_sym_DASH] = ACTIONS(171),
    [anon_sym_STAR] = ACTIONS(169),
    [anon_sym_SLASH] = ACTIONS(171),
    [anon_sym_PERCENT] = ACTIONS(169),
    [anon_sym_RPAREN] = ACTIONS(169),
    [anon_sym_LBRACK] = ACTIONS(169),
    [anon_sym_RBRACK] = ACTIONS(169),
    [anon_sym_LBRACE] = ACTIONS(169),
    [anon_sym_RBRACE] = ACTIONS(169),
    [anon_sym_COMMA] = ACTIONS(169),
    [anon_sym_COLON] = ACTIONS(169),
    [anon_sym_SEMI] = ACTIONS(169),
    [anon_sym_AT] = ACTIONS(169),
    [anon_sym_DOLLAR] = ACTIONS(169),
  },
  [16] = {
    [ts_builtin_sym_end] = ACTIONS(173),
    [sym_identifier] = ACTIONS(175),
    [aux_sym_keyword_section_token1] = ACTIONS(175),
    [aux_sym_keyword_section_token2] = ACTIONS(175),
    [aux_sym_keyword_section_token3] = ACTIONS(175),
    [aux_sym_keyword_section_token4] = ACTIONS(175),
    [aux_sym_keyword_section_token5] = ACTIONS(175),
    [aux_sym_keyword_section_token6] = ACTIONS(175),
    [aux_sym_keyword_section_token7] = ACTIONS(175),
    [aux_sym_keyword_section_token8] = ACTIONS(175),
    [aux_sym_keyword_section_token9] = ACTIONS(175),
    [aux_sym_keyword_section_token10] = ACTIONS(175),
    [aux_sym_keyword_section_token11] = ACTIONS(175),
    [aux_sym_keyword_section_token12] = ACTIONS(175),
    [aux_sym_keyword_section_token13] = ACTIONS(175),
    [aux_sym_keyword_section_token14] = ACTIONS(175),
    [aux_sym_keyword_section_token15] = ACTIONS(175),
    [aux_sym_keyword_flow_token1] = ACTIONS(175),
    [aux_sym_keyword_flow_token2] = ACTIONS(175),
    [aux_sym_keyword_flow_token3] = ACTIONS(175),
    [aux_sym_keyword_flow_token4] = ACTIONS(175),
    [aux_sym_keyword_flow_token5] = ACTIONS(175),
    [aux_sym_keyword_flow_token6] = ACTIONS(175),
    [aux_sym_keyword_flow_token7] = ACTIONS(175),
    [aux_sym_keyword_flow_token8] = ACTIONS(175),
    [aux_sym_keyword_flow_token9] = ACTIONS(175),
    [aux_sym_keyword_flow_token10] = ACTIONS(175),
    [aux_sym_keyword_flow_token11] = ACTIONS(175),
    [aux_sym_keyword_action_token1] = ACTIONS(175),
    [aux_sym_keyword_action_token2] = ACTIONS(175),
    [aux_sym_keyword_action_token3] = ACTIONS(175),
    [aux_sym_keyword_action_token4] = ACTIONS(175),
    [aux_sym_keyword_action_token5] = ACTIONS(175),
    [aux_sym_keyword_action_token6] = ACTIONS(175),
    [aux_sym_keyword_action_token7] = ACTIONS(175),
    [aux_sym_keyword_action_token8] = ACTIONS(175),
    [aux_sym_keyword_action_token9] = ACTIONS(175),
    [aux_sym_keyword_action_token10] = ACTIONS(175),
    [aux_sym_keyword_action_token11] = ACTIONS(175),
    [aux_sym_keyword_action_token12] = ACTIONS(175),
    [aux_sym_keyword_order_token1] = ACTIONS(175),
    [aux_sym_keyword_order_token2] = ACTIONS(175),
    [aux_sym_keyword_order_token3] = ACTIONS(175),
    [aux_sym_keyword_order_token4] = ACTIONS(175),
    [aux_sym_keyword_order_token5] = ACTIONS(175),
    [aux_sym_keyword_order_token6] = ACTIONS(175),
    [aux_sym_keyword_order_token7] = ACTIONS(175),
    [aux_sym_keyword_order_token8] = ACTIONS(175),
    [aux_sym_keyword_order_token9] = ACTIONS(175),
    [aux_sym_keyword_order_token10] = ACTIONS(175),
    [aux_sym_keyword_order_token11] = ACTIONS(175),
    [aux_sym_keyword_order_token12] = ACTIONS(175),
    [aux_sym_keyword_order_token13] = ACTIONS(175),
    [aux_sym_keyword_order_token14] = ACTIONS(175),
    [aux_sym_keyword_order_token15] = ACTIONS(175),
    [aux_sym_keyword_order_token16] = ACTIONS(175),
    [aux_sym_keyword_order_token17] = ACTIONS(175),
    [aux_sym_keyword_order_token18] = ACTIONS(175),
    [aux_sym_keyword_order_token19] = ACTIONS(175),
    [aux_sym_keyword_order_token20] = ACTIONS(175),
    [aux_sym_keyword_order_token21] = ACTIONS(175),
    [aux_sym_keyword_sizing_token1] = ACTIONS(175),
    [aux_sym_keyword_sizing_token2] = ACTIONS(175),
    [aux_sym_keyword_sizing_token3] = ACTIONS(175),
    [aux_sym_keyword_sizing_token4] = ACTIONS(175),
    [aux_sym_keyword_sizing_token5] = ACTIONS(175),
    [aux_sym_keyword_sizing_token6] = ACTIONS(175),
    [aux_sym_keyword_sizing_token7] = ACTIONS(175),
    [aux_sym_keyword_bracket_token1] = ACTIONS(175),
    [aux_sym_keyword_bracket_token2] = ACTIONS(175),
    [aux_sym_keyword_bracket_token3] = ACTIONS(175),
    [aux_sym_keyword_bracket_token4] = ACTIONS(175),
    [aux_sym_keyword_bracket_token5] = ACTIONS(175),
    [aux_sym_keyword_bracket_token6] = ACTIONS(175),
    [aux_sym_keyword_bracket_token7] = ACTIONS(175),
    [aux_sym_keyword_bracket_token8] = ACTIONS(175),
    [aux_sym_keyword_bracket_token9] = ACTIONS(175),
    [aux_sym_keyword_bracket_token10] = ACTIONS(175),
    [aux_sym_keyword_bracket_token11] = ACTIONS(175),
    [aux_sym_keyword_bracket_token12] = ACTIONS(175),
    [aux_sym_keyword_bracket_token13] = ACTIONS(175),
    [aux_sym_keyword_bracket_token14] = ACTIONS(175),
    [aux_sym_keyword_bracket_token15] = ACTIONS(175),
    [aux_sym_keyword_bracket_token16] = ACTIONS(175),
    [aux_sym_keyword_bracket_token17] = ACTIONS(175),
    [aux_sym_keyword_bracket_token18] = ACTIONS(175),
    [aux_sym_keyword_bracket_token19] = ACTIONS(175),
    [aux_sym_keyword_bracket_token20] = ACTIONS(175),
    [aux_sym_keyword_bracket_token21] = ACTIONS(175),
    [aux_sym_keyword_stacking_token1] = ACTIONS(175),
    [aux_sym_keyword_stacking_token2] = ACTIONS(175),
    [aux_sym_keyword_stacking_token3] = ACTIONS(175),
    [aux_sym_keyword_stacking_token4] = ACTIONS(175),
    [aux_sym_keyword_stacking_token5] = ACTIONS(175),
    [aux_sym_keyword_stacking_token6] = ACTIONS(175),
    [aux_sym_keyword_stacking_token7] = ACTIONS(175),
    [aux_sym_keyword_stacking_token8] = ACTIONS(175),
    [aux_sym_keyword_stacking_token9] = ACTIONS(175),
    [aux_sym_keyword_stacking_token10] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(175),
    [aux_sym_keyword_session_token1] = ACTIONS(175),
    [aux_sym_keyword_session_token2] = ACTIONS(175),
    [aux_sym_keyword_session_token3] = ACTIONS(175),
    [aux_sym_keyword_session_token4] = ACTIONS(175),
    [aux_sym_keyword_session_token5] = ACTIONS(175),
    [aux_sym_keyword_session_token6] = ACTIONS(175),
    [aux_sym_keyword_session_token7] = ACTIONS(175),
    [aux_sym_keyword_session_token8] = ACTIONS(175),
    [aux_sym_keyword_session_token9] = ACTIONS(175),
    [aux_sym_keyword_session_token10] = ACTIONS(175),
    [aux_sym_keyword_session_token11] = ACTIONS(175),
    [aux_sym_keyword_hook_token1] = ACTIONS(175),
    [aux_sym_keyword_hook_token2] = ACTIONS(175),
    [aux_sym_keyword_hook_token3] = ACTIONS(175),
    [aux_sym_keyword_hook_token4] = ACTIONS(175),
    [aux_sym_keyword_state_token1] = ACTIONS(175),
    [aux_sym_keyword_state_token2] = ACTIONS(175),
    [aux_sym_keyword_state_token3] = ACTIONS(175),
    [aux_sym_keyword_state_token4] = ACTIONS(175),
    [aux_sym_keyword_state_token5] = ACTIONS(175),
    [aux_sym_keyword_state_token6] = ACTIONS(175),
    [aux_sym_keyword_state_token7] = ACTIONS(175),
    [aux_sym_keyword_state_token8] = ACTIONS(175),
    [aux_sym_keyword_state_token9] = ACTIONS(175),
    [aux_sym_keyword_state_token10] = ACTIONS(175),
    [aux_sym_keyword_state_token11] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(175),
    [aux_sym_keyword_literal_token1] = ACTIONS(175),
    [aux_sym_keyword_literal_token2] = ACTIONS(175),
    [anon_sym_LPAREN] = ACTIONS(173),
    [anon_sym_DOT] = ACTIONS(173),
    [sym_broker_symbol] = ACTIONS(173),
    [sym_duration] = ACTIONS(173),
    [sym_number] = ACTIONS(175),
    [aux_sym_string_token1] = ACTIONS(173),
    [aux_sym_string_token2] = ACTIONS(173),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(173),
    [anon_sym_EQ_EQ] = ACTIONS(173),
    [anon_sym_LT_GT] = ACTIONS(173),
    [anon_sym_BANG_EQ] = ACTIONS(173),
    [anon_sym_LT_EQ] = ACTIONS(173),
    [anon_sym_GT_EQ] = ACTIONS(173),
    [anon_sym_LT] = ACTIONS(175),
    [anon_sym_GT] = ACTIONS(175),
    [anon_sym_EQ] = ACTIONS(175),
    [anon_sym_PLUS] = ACTIONS(173),
    [anon_sym_DASH] = ACTIONS(175),
    [anon_sym_STAR] = ACTIONS(173),
    [anon_sym_SLASH] = ACTIONS(175),
    [anon_sym_PERCENT] = ACTIONS(173),
    [anon_sym_RPAREN] = ACTIONS(173),
    [anon_sym_LBRACK] = ACTIONS(173),
    [anon_sym_RBRACK] = ACTIONS(173),
    [anon_sym_LBRACE] = ACTIONS(173),
    [anon_sym_RBRACE] = ACTIONS(173),
    [anon_sym_COMMA] = ACTIONS(173),
    [anon_sym_COLON] = ACTIONS(173),
    [anon_sym_SEMI] = ACTIONS(173),
    [anon_sym_AT] = ACTIONS(173),
    [anon_sym_DOLLAR] = ACTIONS(173),
  },
  [17] = {
    [ts_builtin_sym_end] = ACTIONS(173),
    [sym_identifier] = ACTIONS(177),
    [aux_sym_keyword_section_token1] = ACTIONS(175),
    [aux_sym_keyword_section_token2] = ACTIONS(175),
    [aux_sym_keyword_section_token3] = ACTIONS(175),
    [aux_sym_keyword_section_token4] = ACTIONS(175),
    [aux_sym_keyword_section_token5] = ACTIONS(175),
    [aux_sym_keyword_section_token6] = ACTIONS(175),
    [aux_sym_keyword_section_token7] = ACTIONS(175),
    [aux_sym_keyword_section_token8] = ACTIONS(175),
    [aux_sym_keyword_section_token9] = ACTIONS(175),
    [aux_sym_keyword_section_token10] = ACTIONS(175),
    [aux_sym_keyword_section_token11] = ACTIONS(175),
    [aux_sym_keyword_section_token12] = ACTIONS(175),
    [aux_sym_keyword_section_token13] = ACTIONS(175),
    [aux_sym_keyword_section_token14] = ACTIONS(175),
    [aux_sym_keyword_section_token15] = ACTIONS(175),
    [aux_sym_keyword_flow_token1] = ACTIONS(175),
    [aux_sym_keyword_flow_token2] = ACTIONS(175),
    [aux_sym_keyword_flow_token3] = ACTIONS(175),
    [aux_sym_keyword_flow_token4] = ACTIONS(175),
    [aux_sym_keyword_flow_token5] = ACTIONS(175),
    [aux_sym_keyword_flow_token6] = ACTIONS(175),
    [aux_sym_keyword_flow_token7] = ACTIONS(175),
    [aux_sym_keyword_flow_token8] = ACTIONS(175),
    [aux_sym_keyword_flow_token9] = ACTIONS(175),
    [aux_sym_keyword_flow_token10] = ACTIONS(175),
    [aux_sym_keyword_flow_token11] = ACTIONS(175),
    [aux_sym_keyword_action_token1] = ACTIONS(175),
    [aux_sym_keyword_action_token2] = ACTIONS(175),
    [aux_sym_keyword_action_token3] = ACTIONS(175),
    [aux_sym_keyword_action_token4] = ACTIONS(175),
    [aux_sym_keyword_action_token5] = ACTIONS(175),
    [aux_sym_keyword_action_token6] = ACTIONS(175),
    [aux_sym_keyword_action_token7] = ACTIONS(175),
    [aux_sym_keyword_action_token8] = ACTIONS(175),
    [aux_sym_keyword_action_token9] = ACTIONS(175),
    [aux_sym_keyword_action_token10] = ACTIONS(175),
    [aux_sym_keyword_action_token11] = ACTIONS(175),
    [aux_sym_keyword_action_token12] = ACTIONS(175),
    [aux_sym_keyword_order_token1] = ACTIONS(175),
    [aux_sym_keyword_order_token2] = ACTIONS(175),
    [aux_sym_keyword_order_token3] = ACTIONS(175),
    [aux_sym_keyword_order_token4] = ACTIONS(175),
    [aux_sym_keyword_order_token5] = ACTIONS(175),
    [aux_sym_keyword_order_token6] = ACTIONS(175),
    [aux_sym_keyword_order_token7] = ACTIONS(175),
    [aux_sym_keyword_order_token8] = ACTIONS(175),
    [aux_sym_keyword_order_token9] = ACTIONS(175),
    [aux_sym_keyword_order_token10] = ACTIONS(175),
    [aux_sym_keyword_order_token11] = ACTIONS(175),
    [aux_sym_keyword_order_token12] = ACTIONS(175),
    [aux_sym_keyword_order_token13] = ACTIONS(175),
    [aux_sym_keyword_order_token14] = ACTIONS(175),
    [aux_sym_keyword_order_token15] = ACTIONS(175),
    [aux_sym_keyword_order_token16] = ACTIONS(175),
    [aux_sym_keyword_order_token17] = ACTIONS(175),
    [aux_sym_keyword_order_token18] = ACTIONS(175),
    [aux_sym_keyword_order_token19] = ACTIONS(175),
    [aux_sym_keyword_order_token20] = ACTIONS(175),
    [aux_sym_keyword_order_token21] = ACTIONS(175),
    [aux_sym_keyword_sizing_token1] = ACTIONS(175),
    [aux_sym_keyword_sizing_token2] = ACTIONS(175),
    [aux_sym_keyword_sizing_token3] = ACTIONS(175),
    [aux_sym_keyword_sizing_token4] = ACTIONS(175),
    [aux_sym_keyword_sizing_token5] = ACTIONS(175),
    [aux_sym_keyword_sizing_token6] = ACTIONS(175),
    [aux_sym_keyword_sizing_token7] = ACTIONS(175),
    [aux_sym_keyword_bracket_token1] = ACTIONS(175),
    [aux_sym_keyword_bracket_token2] = ACTIONS(175),
    [aux_sym_keyword_bracket_token3] = ACTIONS(175),
    [aux_sym_keyword_bracket_token4] = ACTIONS(175),
    [aux_sym_keyword_bracket_token5] = ACTIONS(175),
    [aux_sym_keyword_bracket_token6] = ACTIONS(175),
    [aux_sym_keyword_bracket_token7] = ACTIONS(175),
    [aux_sym_keyword_bracket_token8] = ACTIONS(175),
    [aux_sym_keyword_bracket_token9] = ACTIONS(175),
    [aux_sym_keyword_bracket_token10] = ACTIONS(175),
    [aux_sym_keyword_bracket_token11] = ACTIONS(175),
    [aux_sym_keyword_bracket_token12] = ACTIONS(175),
    [aux_sym_keyword_bracket_token13] = ACTIONS(175),
    [aux_sym_keyword_bracket_token14] = ACTIONS(175),
    [aux_sym_keyword_bracket_token15] = ACTIONS(175),
    [aux_sym_keyword_bracket_token16] = ACTIONS(175),
    [aux_sym_keyword_bracket_token17] = ACTIONS(175),
    [aux_sym_keyword_bracket_token18] = ACTIONS(175),
    [aux_sym_keyword_bracket_token19] = ACTIONS(175),
    [aux_sym_keyword_bracket_token20] = ACTIONS(175),
    [aux_sym_keyword_bracket_token21] = ACTIONS(175),
    [aux_sym_keyword_stacking_token1] = ACTIONS(175),
    [aux_sym_keyword_stacking_token2] = ACTIONS(175),
    [aux_sym_keyword_stacking_token3] = ACTIONS(175),
    [aux_sym_keyword_stacking_token4] = ACTIONS(175),
    [aux_sym_keyword_stacking_token5] = ACTIONS(175),
    [aux_sym_keyword_stacking_token6] = ACTIONS(175),
    [aux_sym_keyword_stacking_token7] = ACTIONS(175),
    [aux_sym_keyword_stacking_token8] = ACTIONS(175),
    [aux_sym_keyword_stacking_token9] = ACTIONS(175),
    [aux_sym_keyword_stacking_token10] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(175),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(175),
    [aux_sym_keyword_session_token1] = ACTIONS(175),
    [aux_sym_keyword_session_token2] = ACTIONS(175),
    [aux_sym_keyword_session_token3] = ACTIONS(175),
    [aux_sym_keyword_session_token4] = ACTIONS(175),
    [aux_sym_keyword_session_token5] = ACTIONS(175),
    [aux_sym_keyword_session_token6] = ACTIONS(175),
    [aux_sym_keyword_session_token7] = ACTIONS(175),
    [aux_sym_keyword_session_token8] = ACTIONS(175),
    [aux_sym_keyword_session_token9] = ACTIONS(175),
    [aux_sym_keyword_session_token10] = ACTIONS(175),
    [aux_sym_keyword_session_token11] = ACTIONS(175),
    [aux_sym_keyword_hook_token1] = ACTIONS(175),
    [aux_sym_keyword_hook_token2] = ACTIONS(175),
    [aux_sym_keyword_hook_token3] = ACTIONS(175),
    [aux_sym_keyword_hook_token4] = ACTIONS(175),
    [aux_sym_keyword_state_token1] = ACTIONS(175),
    [aux_sym_keyword_state_token2] = ACTIONS(175),
    [aux_sym_keyword_state_token3] = ACTIONS(175),
    [aux_sym_keyword_state_token4] = ACTIONS(175),
    [aux_sym_keyword_state_token5] = ACTIONS(175),
    [aux_sym_keyword_state_token6] = ACTIONS(175),
    [aux_sym_keyword_state_token7] = ACTIONS(175),
    [aux_sym_keyword_state_token8] = ACTIONS(175),
    [aux_sym_keyword_state_token9] = ACTIONS(175),
    [aux_sym_keyword_state_token10] = ACTIONS(175),
    [aux_sym_keyword_state_token11] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(175),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(175),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(175),
    [aux_sym_keyword_literal_token1] = ACTIONS(175),
    [aux_sym_keyword_literal_token2] = ACTIONS(175),
    [anon_sym_LPAREN] = ACTIONS(173),
    [anon_sym_DOT] = ACTIONS(173),
    [sym_broker_symbol] = ACTIONS(173),
    [sym_duration] = ACTIONS(173),
    [sym_number] = ACTIONS(175),
    [aux_sym_string_token1] = ACTIONS(173),
    [aux_sym_string_token2] = ACTIONS(173),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(173),
    [anon_sym_EQ_EQ] = ACTIONS(173),
    [anon_sym_LT_GT] = ACTIONS(173),
    [anon_sym_BANG_EQ] = ACTIONS(173),
    [anon_sym_LT_EQ] = ACTIONS(173),
    [anon_sym_GT_EQ] = ACTIONS(173),
    [anon_sym_LT] = ACTIONS(175),
    [anon_sym_GT] = ACTIONS(175),
    [anon_sym_EQ] = ACTIONS(175),
    [anon_sym_PLUS] = ACTIONS(173),
    [anon_sym_DASH] = ACTIONS(175),
    [anon_sym_STAR] = ACTIONS(173),
    [anon_sym_SLASH] = ACTIONS(175),
    [anon_sym_PERCENT] = ACTIONS(173),
    [anon_sym_RPAREN] = ACTIONS(173),
    [anon_sym_LBRACK] = ACTIONS(173),
    [anon_sym_RBRACK] = ACTIONS(173),
    [anon_sym_LBRACE] = ACTIONS(173),
    [anon_sym_RBRACE] = ACTIONS(173),
    [anon_sym_COMMA] = ACTIONS(173),
    [anon_sym_COLON] = ACTIONS(173),
    [anon_sym_SEMI] = ACTIONS(173),
    [anon_sym_AT] = ACTIONS(173),
    [anon_sym_DOLLAR] = ACTIONS(173),
  },
  [18] = {
    [ts_builtin_sym_end] = ACTIONS(179),
    [sym_identifier] = ACTIONS(181),
    [aux_sym_keyword_section_token1] = ACTIONS(181),
    [aux_sym_keyword_section_token2] = ACTIONS(181),
    [aux_sym_keyword_section_token3] = ACTIONS(181),
    [aux_sym_keyword_section_token4] = ACTIONS(181),
    [aux_sym_keyword_section_token5] = ACTIONS(181),
    [aux_sym_keyword_section_token6] = ACTIONS(181),
    [aux_sym_keyword_section_token7] = ACTIONS(181),
    [aux_sym_keyword_section_token8] = ACTIONS(181),
    [aux_sym_keyword_section_token9] = ACTIONS(181),
    [aux_sym_keyword_section_token10] = ACTIONS(181),
    [aux_sym_keyword_section_token11] = ACTIONS(181),
    [aux_sym_keyword_section_token12] = ACTIONS(181),
    [aux_sym_keyword_section_token13] = ACTIONS(181),
    [aux_sym_keyword_section_token14] = ACTIONS(181),
    [aux_sym_keyword_section_token15] = ACTIONS(181),
    [aux_sym_keyword_flow_token1] = ACTIONS(181),
    [aux_sym_keyword_flow_token2] = ACTIONS(181),
    [aux_sym_keyword_flow_token3] = ACTIONS(181),
    [aux_sym_keyword_flow_token4] = ACTIONS(181),
    [aux_sym_keyword_flow_token5] = ACTIONS(181),
    [aux_sym_keyword_flow_token6] = ACTIONS(181),
    [aux_sym_keyword_flow_token7] = ACTIONS(181),
    [aux_sym_keyword_flow_token8] = ACTIONS(181),
    [aux_sym_keyword_flow_token9] = ACTIONS(181),
    [aux_sym_keyword_flow_token10] = ACTIONS(181),
    [aux_sym_keyword_flow_token11] = ACTIONS(181),
    [aux_sym_keyword_action_token1] = ACTIONS(181),
    [aux_sym_keyword_action_token2] = ACTIONS(181),
    [aux_sym_keyword_action_token3] = ACTIONS(181),
    [aux_sym_keyword_action_token4] = ACTIONS(181),
    [aux_sym_keyword_action_token5] = ACTIONS(181),
    [aux_sym_keyword_action_token6] = ACTIONS(181),
    [aux_sym_keyword_action_token7] = ACTIONS(181),
    [aux_sym_keyword_action_token8] = ACTIONS(181),
    [aux_sym_keyword_action_token9] = ACTIONS(181),
    [aux_sym_keyword_action_token10] = ACTIONS(181),
    [aux_sym_keyword_action_token11] = ACTIONS(181),
    [aux_sym_keyword_action_token12] = ACTIONS(181),
    [aux_sym_keyword_order_token1] = ACTIONS(181),
    [aux_sym_keyword_order_token2] = ACTIONS(181),
    [aux_sym_keyword_order_token3] = ACTIONS(181),
    [aux_sym_keyword_order_token4] = ACTIONS(181),
    [aux_sym_keyword_order_token5] = ACTIONS(181),
    [aux_sym_keyword_order_token6] = ACTIONS(181),
    [aux_sym_keyword_order_token7] = ACTIONS(181),
    [aux_sym_keyword_order_token8] = ACTIONS(181),
    [aux_sym_keyword_order_token9] = ACTIONS(181),
    [aux_sym_keyword_order_token10] = ACTIONS(181),
    [aux_sym_keyword_order_token11] = ACTIONS(181),
    [aux_sym_keyword_order_token12] = ACTIONS(181),
    [aux_sym_keyword_order_token13] = ACTIONS(181),
    [aux_sym_keyword_order_token14] = ACTIONS(181),
    [aux_sym_keyword_order_token15] = ACTIONS(181),
    [aux_sym_keyword_order_token16] = ACTIONS(181),
    [aux_sym_keyword_order_token17] = ACTIONS(181),
    [aux_sym_keyword_order_token18] = ACTIONS(181),
    [aux_sym_keyword_order_token19] = ACTIONS(181),
    [aux_sym_keyword_order_token20] = ACTIONS(181),
    [aux_sym_keyword_order_token21] = ACTIONS(181),
    [aux_sym_keyword_sizing_token1] = ACTIONS(181),
    [aux_sym_keyword_sizing_token2] = ACTIONS(181),
    [aux_sym_keyword_sizing_token3] = ACTIONS(181),
    [aux_sym_keyword_sizing_token4] = ACTIONS(181),
    [aux_sym_keyword_sizing_token5] = ACTIONS(181),
    [aux_sym_keyword_sizing_token6] = ACTIONS(181),
    [aux_sym_keyword_sizing_token7] = ACTIONS(181),
    [aux_sym_keyword_bracket_token1] = ACTIONS(181),
    [aux_sym_keyword_bracket_token2] = ACTIONS(181),
    [aux_sym_keyword_bracket_token3] = ACTIONS(181),
    [aux_sym_keyword_bracket_token4] = ACTIONS(181),
    [aux_sym_keyword_bracket_token5] = ACTIONS(181),
    [aux_sym_keyword_bracket_token6] = ACTIONS(181),
    [aux_sym_keyword_bracket_token7] = ACTIONS(181),
    [aux_sym_keyword_bracket_token8] = ACTIONS(181),
    [aux_sym_keyword_bracket_token9] = ACTIONS(181),
    [aux_sym_keyword_bracket_token10] = ACTIONS(181),
    [aux_sym_keyword_bracket_token11] = ACTIONS(181),
    [aux_sym_keyword_bracket_token12] = ACTIONS(181),
    [aux_sym_keyword_bracket_token13] = ACTIONS(181),
    [aux_sym_keyword_bracket_token14] = ACTIONS(181),
    [aux_sym_keyword_bracket_token15] = ACTIONS(181),
    [aux_sym_keyword_bracket_token16] = ACTIONS(181),
    [aux_sym_keyword_bracket_token17] = ACTIONS(181),
    [aux_sym_keyword_bracket_token18] = ACTIONS(181),
    [aux_sym_keyword_bracket_token19] = ACTIONS(181),
    [aux_sym_keyword_bracket_token20] = ACTIONS(181),
    [aux_sym_keyword_bracket_token21] = ACTIONS(181),
    [aux_sym_keyword_stacking_token1] = ACTIONS(181),
    [aux_sym_keyword_stacking_token2] = ACTIONS(181),
    [aux_sym_keyword_stacking_token3] = ACTIONS(181),
    [aux_sym_keyword_stacking_token4] = ACTIONS(181),
    [aux_sym_keyword_stacking_token5] = ACTIONS(181),
    [aux_sym_keyword_stacking_token6] = ACTIONS(181),
    [aux_sym_keyword_stacking_token7] = ACTIONS(181),
    [aux_sym_keyword_stacking_token8] = ACTIONS(181),
    [aux_sym_keyword_stacking_token9] = ACTIONS(181),
    [aux_sym_keyword_stacking_token10] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(181),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(181),
    [aux_sym_keyword_session_token1] = ACTIONS(181),
    [aux_sym_keyword_session_token2] = ACTIONS(181),
    [aux_sym_keyword_session_token3] = ACTIONS(181),
    [aux_sym_keyword_session_token4] = ACTIONS(181),
    [aux_sym_keyword_session_token5] = ACTIONS(181),
    [aux_sym_keyword_session_token6] = ACTIONS(181),
    [aux_sym_keyword_session_token7] = ACTIONS(181),
    [aux_sym_keyword_session_token8] = ACTIONS(181),
    [aux_sym_keyword_session_token9] = ACTIONS(181),
    [aux_sym_keyword_session_token10] = ACTIONS(181),
    [aux_sym_keyword_session_token11] = ACTIONS(181),
    [aux_sym_keyword_hook_token1] = ACTIONS(181),
    [aux_sym_keyword_hook_token2] = ACTIONS(181),
    [aux_sym_keyword_hook_token3] = ACTIONS(181),
    [aux_sym_keyword_hook_token4] = ACTIONS(181),
    [aux_sym_keyword_state_token1] = ACTIONS(181),
    [aux_sym_keyword_state_token2] = ACTIONS(181),
    [aux_sym_keyword_state_token3] = ACTIONS(181),
    [aux_sym_keyword_state_token4] = ACTIONS(181),
    [aux_sym_keyword_state_token5] = ACTIONS(181),
    [aux_sym_keyword_state_token6] = ACTIONS(181),
    [aux_sym_keyword_state_token7] = ACTIONS(181),
    [aux_sym_keyword_state_token8] = ACTIONS(181),
    [aux_sym_keyword_state_token9] = ACTIONS(181),
    [aux_sym_keyword_state_token10] = ACTIONS(181),
    [aux_sym_keyword_state_token11] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(181),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(181),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(181),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(181),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(181),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(181),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(181),
    [aux_sym_keyword_literal_token1] = ACTIONS(181),
    [aux_sym_keyword_literal_token2] = ACTIONS(181),
    [anon_sym_LPAREN] = ACTIONS(179),
    [anon_sym_DOT] = ACTIONS(179),
    [sym_broker_symbol] = ACTIONS(179),
    [sym_duration] = ACTIONS(179),
    [sym_number] = ACTIONS(181),
    [aux_sym_string_token1] = ACTIONS(179),
    [aux_sym_string_token2] = ACTIONS(179),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(179),
    [anon_sym_EQ_EQ] = ACTIONS(179),
    [anon_sym_LT_GT] = ACTIONS(179),
    [anon_sym_BANG_EQ] = ACTIONS(179),
    [anon_sym_LT_EQ] = ACTIONS(179),
    [anon_sym_GT_EQ] = ACTIONS(179),
    [anon_sym_LT] = ACTIONS(181),
    [anon_sym_GT] = ACTIONS(181),
    [anon_sym_EQ] = ACTIONS(181),
    [anon_sym_PLUS] = ACTIONS(179),
    [anon_sym_DASH] = ACTIONS(181),
    [anon_sym_STAR] = ACTIONS(179),
    [anon_sym_SLASH] = ACTIONS(181),
    [anon_sym_PERCENT] = ACTIONS(179),
    [anon_sym_RPAREN] = ACTIONS(179),
    [anon_sym_LBRACK] = ACTIONS(179),
    [anon_sym_RBRACK] = ACTIONS(179),
    [anon_sym_LBRACE] = ACTIONS(179),
    [anon_sym_RBRACE] = ACTIONS(179),
    [anon_sym_COMMA] = ACTIONS(179),
    [anon_sym_COLON] = ACTIONS(179),
    [anon_sym_SEMI] = ACTIONS(179),
    [anon_sym_AT] = ACTIONS(179),
    [anon_sym_DOLLAR] = ACTIONS(179),
  },
  [19] = {
    [ts_builtin_sym_end] = ACTIONS(183),
    [sym_identifier] = ACTIONS(185),
    [aux_sym_keyword_section_token1] = ACTIONS(185),
    [aux_sym_keyword_section_token2] = ACTIONS(185),
    [aux_sym_keyword_section_token3] = ACTIONS(185),
    [aux_sym_keyword_section_token4] = ACTIONS(185),
    [aux_sym_keyword_section_token5] = ACTIONS(185),
    [aux_sym_keyword_section_token6] = ACTIONS(185),
    [aux_sym_keyword_section_token7] = ACTIONS(185),
    [aux_sym_keyword_section_token8] = ACTIONS(185),
    [aux_sym_keyword_section_token9] = ACTIONS(185),
    [aux_sym_keyword_section_token10] = ACTIONS(185),
    [aux_sym_keyword_section_token11] = ACTIONS(185),
    [aux_sym_keyword_section_token12] = ACTIONS(185),
    [aux_sym_keyword_section_token13] = ACTIONS(185),
    [aux_sym_keyword_section_token14] = ACTIONS(185),
    [aux_sym_keyword_section_token15] = ACTIONS(185),
    [aux_sym_keyword_flow_token1] = ACTIONS(185),
    [aux_sym_keyword_flow_token2] = ACTIONS(185),
    [aux_sym_keyword_flow_token3] = ACTIONS(185),
    [aux_sym_keyword_flow_token4] = ACTIONS(185),
    [aux_sym_keyword_flow_token5] = ACTIONS(185),
    [aux_sym_keyword_flow_token6] = ACTIONS(185),
    [aux_sym_keyword_flow_token7] = ACTIONS(185),
    [aux_sym_keyword_flow_token8] = ACTIONS(185),
    [aux_sym_keyword_flow_token9] = ACTIONS(185),
    [aux_sym_keyword_flow_token10] = ACTIONS(185),
    [aux_sym_keyword_flow_token11] = ACTIONS(185),
    [aux_sym_keyword_action_token1] = ACTIONS(185),
    [aux_sym_keyword_action_token2] = ACTIONS(185),
    [aux_sym_keyword_action_token3] = ACTIONS(185),
    [aux_sym_keyword_action_token4] = ACTIONS(185),
    [aux_sym_keyword_action_token5] = ACTIONS(185),
    [aux_sym_keyword_action_token6] = ACTIONS(185),
    [aux_sym_keyword_action_token7] = ACTIONS(185),
    [aux_sym_keyword_action_token8] = ACTIONS(185),
    [aux_sym_keyword_action_token9] = ACTIONS(185),
    [aux_sym_keyword_action_token10] = ACTIONS(185),
    [aux_sym_keyword_action_token11] = ACTIONS(185),
    [aux_sym_keyword_action_token12] = ACTIONS(185),
    [aux_sym_keyword_order_token1] = ACTIONS(185),
    [aux_sym_keyword_order_token2] = ACTIONS(185),
    [aux_sym_keyword_order_token3] = ACTIONS(185),
    [aux_sym_keyword_order_token4] = ACTIONS(185),
    [aux_sym_keyword_order_token5] = ACTIONS(185),
    [aux_sym_keyword_order_token6] = ACTIONS(185),
    [aux_sym_keyword_order_token7] = ACTIONS(185),
    [aux_sym_keyword_order_token8] = ACTIONS(185),
    [aux_sym_keyword_order_token9] = ACTIONS(185),
    [aux_sym_keyword_order_token10] = ACTIONS(185),
    [aux_sym_keyword_order_token11] = ACTIONS(185),
    [aux_sym_keyword_order_token12] = ACTIONS(185),
    [aux_sym_keyword_order_token13] = ACTIONS(185),
    [aux_sym_keyword_order_token14] = ACTIONS(185),
    [aux_sym_keyword_order_token15] = ACTIONS(185),
    [aux_sym_keyword_order_token16] = ACTIONS(185),
    [aux_sym_keyword_order_token17] = ACTIONS(185),
    [aux_sym_keyword_order_token18] = ACTIONS(185),
    [aux_sym_keyword_order_token19] = ACTIONS(185),
    [aux_sym_keyword_order_token20] = ACTIONS(185),
    [aux_sym_keyword_order_token21] = ACTIONS(185),
    [aux_sym_keyword_sizing_token1] = ACTIONS(185),
    [aux_sym_keyword_sizing_token2] = ACTIONS(185),
    [aux_sym_keyword_sizing_token3] = ACTIONS(185),
    [aux_sym_keyword_sizing_token4] = ACTIONS(185),
    [aux_sym_keyword_sizing_token5] = ACTIONS(185),
    [aux_sym_keyword_sizing_token6] = ACTIONS(185),
    [aux_sym_keyword_sizing_token7] = ACTIONS(185),
    [aux_sym_keyword_bracket_token1] = ACTIONS(185),
    [aux_sym_keyword_bracket_token2] = ACTIONS(185),
    [aux_sym_keyword_bracket_token3] = ACTIONS(185),
    [aux_sym_keyword_bracket_token4] = ACTIONS(185),
    [aux_sym_keyword_bracket_token5] = ACTIONS(185),
    [aux_sym_keyword_bracket_token6] = ACTIONS(185),
    [aux_sym_keyword_bracket_token7] = ACTIONS(185),
    [aux_sym_keyword_bracket_token8] = ACTIONS(185),
    [aux_sym_keyword_bracket_token9] = ACTIONS(185),
    [aux_sym_keyword_bracket_token10] = ACTIONS(185),
    [aux_sym_keyword_bracket_token11] = ACTIONS(185),
    [aux_sym_keyword_bracket_token12] = ACTIONS(185),
    [aux_sym_keyword_bracket_token13] = ACTIONS(185),
    [aux_sym_keyword_bracket_token14] = ACTIONS(185),
    [aux_sym_keyword_bracket_token15] = ACTIONS(185),
    [aux_sym_keyword_bracket_token16] = ACTIONS(185),
    [aux_sym_keyword_bracket_token17] = ACTIONS(185),
    [aux_sym_keyword_bracket_token18] = ACTIONS(185),
    [aux_sym_keyword_bracket_token19] = ACTIONS(185),
    [aux_sym_keyword_bracket_token20] = ACTIONS(185),
    [aux_sym_keyword_bracket_token21] = ACTIONS(185),
    [aux_sym_keyword_stacking_token1] = ACTIONS(185),
    [aux_sym_keyword_stacking_token2] = ACTIONS(185),
    [aux_sym_keyword_stacking_token3] = ACTIONS(185),
    [aux_sym_keyword_stacking_token4] = ACTIONS(185),
    [aux_sym_keyword_stacking_token5] = ACTIONS(185),
    [aux_sym_keyword_stacking_token6] = ACTIONS(185),
    [aux_sym_keyword_stacking_token7] = ACTIONS(185),
    [aux_sym_keyword_stacking_token8] = ACTIONS(185),
    [aux_sym_keyword_stacking_token9] = ACTIONS(185),
    [aux_sym_keyword_stacking_token10] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(185),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(185),
    [aux_sym_keyword_session_token1] = ACTIONS(185),
    [aux_sym_keyword_session_token2] = ACTIONS(185),
    [aux_sym_keyword_session_token3] = ACTIONS(185),
    [aux_sym_keyword_session_token4] = ACTIONS(185),
    [aux_sym_keyword_session_token5] = ACTIONS(185),
    [aux_sym_keyword_session_token6] = ACTIONS(185),
    [aux_sym_keyword_session_token7] = ACTIONS(185),
    [aux_sym_keyword_session_token8] = ACTIONS(185),
    [aux_sym_keyword_session_token9] = ACTIONS(185),
    [aux_sym_keyword_session_token10] = ACTIONS(185),
    [aux_sym_keyword_session_token11] = ACTIONS(185),
    [aux_sym_keyword_hook_token1] = ACTIONS(185),
    [aux_sym_keyword_hook_token2] = ACTIONS(185),
    [aux_sym_keyword_hook_token3] = ACTIONS(185),
    [aux_sym_keyword_hook_token4] = ACTIONS(185),
    [aux_sym_keyword_state_token1] = ACTIONS(185),
    [aux_sym_keyword_state_token2] = ACTIONS(185),
    [aux_sym_keyword_state_token3] = ACTIONS(185),
    [aux_sym_keyword_state_token4] = ACTIONS(185),
    [aux_sym_keyword_state_token5] = ACTIONS(185),
    [aux_sym_keyword_state_token6] = ACTIONS(185),
    [aux_sym_keyword_state_token7] = ACTIONS(185),
    [aux_sym_keyword_state_token8] = ACTIONS(185),
    [aux_sym_keyword_state_token9] = ACTIONS(185),
    [aux_sym_keyword_state_token10] = ACTIONS(185),
    [aux_sym_keyword_state_token11] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(185),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(185),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(185),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(185),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(185),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(185),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(185),
    [aux_sym_keyword_literal_token1] = ACTIONS(185),
    [aux_sym_keyword_literal_token2] = ACTIONS(185),
    [anon_sym_LPAREN] = ACTIONS(183),
    [anon_sym_DOT] = ACTIONS(183),
    [sym_broker_symbol] = ACTIONS(183),
    [sym_duration] = ACTIONS(183),
    [sym_number] = ACTIONS(185),
    [aux_sym_string_token1] = ACTIONS(183),
    [aux_sym_string_token2] = ACTIONS(183),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(183),
    [anon_sym_EQ_EQ] = ACTIONS(183),
    [anon_sym_LT_GT] = ACTIONS(183),
    [anon_sym_BANG_EQ] = ACTIONS(183),
    [anon_sym_LT_EQ] = ACTIONS(183),
    [anon_sym_GT_EQ] = ACTIONS(183),
    [anon_sym_LT] = ACTIONS(185),
    [anon_sym_GT] = ACTIONS(185),
    [anon_sym_EQ] = ACTIONS(185),
    [anon_sym_PLUS] = ACTIONS(183),
    [anon_sym_DASH] = ACTIONS(185),
    [anon_sym_STAR] = ACTIONS(183),
    [anon_sym_SLASH] = ACTIONS(185),
    [anon_sym_PERCENT] = ACTIONS(183),
    [anon_sym_RPAREN] = ACTIONS(183),
    [anon_sym_LBRACK] = ACTIONS(183),
    [anon_sym_RBRACK] = ACTIONS(183),
    [anon_sym_LBRACE] = ACTIONS(183),
    [anon_sym_RBRACE] = ACTIONS(183),
    [anon_sym_COMMA] = ACTIONS(183),
    [anon_sym_COLON] = ACTIONS(183),
    [anon_sym_SEMI] = ACTIONS(183),
    [anon_sym_AT] = ACTIONS(183),
    [anon_sym_DOLLAR] = ACTIONS(183),
  },
  [20] = {
    [ts_builtin_sym_end] = ACTIONS(187),
    [sym_identifier] = ACTIONS(189),
    [aux_sym_keyword_section_token1] = ACTIONS(189),
    [aux_sym_keyword_section_token2] = ACTIONS(189),
    [aux_sym_keyword_section_token3] = ACTIONS(189),
    [aux_sym_keyword_section_token4] = ACTIONS(189),
    [aux_sym_keyword_section_token5] = ACTIONS(189),
    [aux_sym_keyword_section_token6] = ACTIONS(189),
    [aux_sym_keyword_section_token7] = ACTIONS(189),
    [aux_sym_keyword_section_token8] = ACTIONS(189),
    [aux_sym_keyword_section_token9] = ACTIONS(189),
    [aux_sym_keyword_section_token10] = ACTIONS(189),
    [aux_sym_keyword_section_token11] = ACTIONS(189),
    [aux_sym_keyword_section_token12] = ACTIONS(189),
    [aux_sym_keyword_section_token13] = ACTIONS(189),
    [aux_sym_keyword_section_token14] = ACTIONS(189),
    [aux_sym_keyword_section_token15] = ACTIONS(189),
    [aux_sym_keyword_flow_token1] = ACTIONS(189),
    [aux_sym_keyword_flow_token2] = ACTIONS(189),
    [aux_sym_keyword_flow_token3] = ACTIONS(189),
    [aux_sym_keyword_flow_token4] = ACTIONS(189),
    [aux_sym_keyword_flow_token5] = ACTIONS(189),
    [aux_sym_keyword_flow_token6] = ACTIONS(189),
    [aux_sym_keyword_flow_token7] = ACTIONS(189),
    [aux_sym_keyword_flow_token8] = ACTIONS(189),
    [aux_sym_keyword_flow_token9] = ACTIONS(189),
    [aux_sym_keyword_flow_token10] = ACTIONS(189),
    [aux_sym_keyword_flow_token11] = ACTIONS(189),
    [aux_sym_keyword_action_token1] = ACTIONS(189),
    [aux_sym_keyword_action_token2] = ACTIONS(189),
    [aux_sym_keyword_action_token3] = ACTIONS(189),
    [aux_sym_keyword_action_token4] = ACTIONS(189),
    [aux_sym_keyword_action_token5] = ACTIONS(189),
    [aux_sym_keyword_action_token6] = ACTIONS(189),
    [aux_sym_keyword_action_token7] = ACTIONS(189),
    [aux_sym_keyword_action_token8] = ACTIONS(189),
    [aux_sym_keyword_action_token9] = ACTIONS(189),
    [aux_sym_keyword_action_token10] = ACTIONS(189),
    [aux_sym_keyword_action_token11] = ACTIONS(189),
    [aux_sym_keyword_action_token12] = ACTIONS(189),
    [aux_sym_keyword_order_token1] = ACTIONS(189),
    [aux_sym_keyword_order_token2] = ACTIONS(189),
    [aux_sym_keyword_order_token3] = ACTIONS(189),
    [aux_sym_keyword_order_token4] = ACTIONS(189),
    [aux_sym_keyword_order_token5] = ACTIONS(189),
    [aux_sym_keyword_order_token6] = ACTIONS(189),
    [aux_sym_keyword_order_token7] = ACTIONS(189),
    [aux_sym_keyword_order_token8] = ACTIONS(189),
    [aux_sym_keyword_order_token9] = ACTIONS(189),
    [aux_sym_keyword_order_token10] = ACTIONS(189),
    [aux_sym_keyword_order_token11] = ACTIONS(189),
    [aux_sym_keyword_order_token12] = ACTIONS(189),
    [aux_sym_keyword_order_token13] = ACTIONS(189),
    [aux_sym_keyword_order_token14] = ACTIONS(189),
    [aux_sym_keyword_order_token15] = ACTIONS(189),
    [aux_sym_keyword_order_token16] = ACTIONS(189),
    [aux_sym_keyword_order_token17] = ACTIONS(189),
    [aux_sym_keyword_order_token18] = ACTIONS(189),
    [aux_sym_keyword_order_token19] = ACTIONS(189),
    [aux_sym_keyword_order_token20] = ACTIONS(189),
    [aux_sym_keyword_order_token21] = ACTIONS(189),
    [aux_sym_keyword_sizing_token1] = ACTIONS(189),
    [aux_sym_keyword_sizing_token2] = ACTIONS(189),
    [aux_sym_keyword_sizing_token3] = ACTIONS(189),
    [aux_sym_keyword_sizing_token4] = ACTIONS(189),
    [aux_sym_keyword_sizing_token5] = ACTIONS(189),
    [aux_sym_keyword_sizing_token6] = ACTIONS(189),
    [aux_sym_keyword_sizing_token7] = ACTIONS(189),
    [aux_sym_keyword_bracket_token1] = ACTIONS(189),
    [aux_sym_keyword_bracket_token2] = ACTIONS(189),
    [aux_sym_keyword_bracket_token3] = ACTIONS(189),
    [aux_sym_keyword_bracket_token4] = ACTIONS(189),
    [aux_sym_keyword_bracket_token5] = ACTIONS(189),
    [aux_sym_keyword_bracket_token6] = ACTIONS(189),
    [aux_sym_keyword_bracket_token7] = ACTIONS(189),
    [aux_sym_keyword_bracket_token8] = ACTIONS(189),
    [aux_sym_keyword_bracket_token9] = ACTIONS(189),
    [aux_sym_keyword_bracket_token10] = ACTIONS(189),
    [aux_sym_keyword_bracket_token11] = ACTIONS(189),
    [aux_sym_keyword_bracket_token12] = ACTIONS(189),
    [aux_sym_keyword_bracket_token13] = ACTIONS(189),
    [aux_sym_keyword_bracket_token14] = ACTIONS(189),
    [aux_sym_keyword_bracket_token15] = ACTIONS(189),
    [aux_sym_keyword_bracket_token16] = ACTIONS(189),
    [aux_sym_keyword_bracket_token17] = ACTIONS(189),
    [aux_sym_keyword_bracket_token18] = ACTIONS(189),
    [aux_sym_keyword_bracket_token19] = ACTIONS(189),
    [aux_sym_keyword_bracket_token20] = ACTIONS(189),
    [aux_sym_keyword_bracket_token21] = ACTIONS(189),
    [aux_sym_keyword_stacking_token1] = ACTIONS(189),
    [aux_sym_keyword_stacking_token2] = ACTIONS(189),
    [aux_sym_keyword_stacking_token3] = ACTIONS(189),
    [aux_sym_keyword_stacking_token4] = ACTIONS(189),
    [aux_sym_keyword_stacking_token5] = ACTIONS(189),
    [aux_sym_keyword_stacking_token6] = ACTIONS(189),
    [aux_sym_keyword_stacking_token7] = ACTIONS(189),
    [aux_sym_keyword_stacking_token8] = ACTIONS(189),
    [aux_sym_keyword_stacking_token9] = ACTIONS(189),
    [aux_sym_keyword_stacking_token10] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(189),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(189),
    [aux_sym_keyword_session_token1] = ACTIONS(189),
    [aux_sym_keyword_session_token2] = ACTIONS(189),
    [aux_sym_keyword_session_token3] = ACTIONS(189),
    [aux_sym_keyword_session_token4] = ACTIONS(189),
    [aux_sym_keyword_session_token5] = ACTIONS(189),
    [aux_sym_keyword_session_token6] = ACTIONS(189),
    [aux_sym_keyword_session_token7] = ACTIONS(189),
    [aux_sym_keyword_session_token8] = ACTIONS(189),
    [aux_sym_keyword_session_token9] = ACTIONS(189),
    [aux_sym_keyword_session_token10] = ACTIONS(189),
    [aux_sym_keyword_session_token11] = ACTIONS(189),
    [aux_sym_keyword_hook_token1] = ACTIONS(189),
    [aux_sym_keyword_hook_token2] = ACTIONS(189),
    [aux_sym_keyword_hook_token3] = ACTIONS(189),
    [aux_sym_keyword_hook_token4] = ACTIONS(189),
    [aux_sym_keyword_state_token1] = ACTIONS(189),
    [aux_sym_keyword_state_token2] = ACTIONS(189),
    [aux_sym_keyword_state_token3] = ACTIONS(189),
    [aux_sym_keyword_state_token4] = ACTIONS(189),
    [aux_sym_keyword_state_token5] = ACTIONS(189),
    [aux_sym_keyword_state_token6] = ACTIONS(189),
    [aux_sym_keyword_state_token7] = ACTIONS(189),
    [aux_sym_keyword_state_token8] = ACTIONS(189),
    [aux_sym_keyword_state_token9] = ACTIONS(189),
    [aux_sym_keyword_state_token10] = ACTIONS(189),
    [aux_sym_keyword_state_token11] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(189),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(189),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(189),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(189),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(189),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(189),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(189),
    [aux_sym_keyword_literal_token1] = ACTIONS(189),
    [aux_sym_keyword_literal_token2] = ACTIONS(189),
    [anon_sym_LPAREN] = ACTIONS(191),
    [anon_sym_DOT] = ACTIONS(187),
    [sym_broker_symbol] = ACTIONS(187),
    [sym_duration] = ACTIONS(187),
    [sym_number] = ACTIONS(189),
    [aux_sym_string_token1] = ACTIONS(187),
    [aux_sym_string_token2] = ACTIONS(187),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(187),
    [anon_sym_EQ_EQ] = ACTIONS(187),
    [anon_sym_LT_GT] = ACTIONS(187),
    [anon_sym_BANG_EQ] = ACTIONS(187),
    [anon_sym_LT_EQ] = ACTIONS(187),
    [anon_sym_GT_EQ] = ACTIONS(187),
    [anon_sym_LT] = ACTIONS(189),
    [anon_sym_GT] = ACTIONS(189),
    [anon_sym_EQ] = ACTIONS(189),
    [anon_sym_PLUS] = ACTIONS(187),
    [anon_sym_DASH] = ACTIONS(189),
    [anon_sym_STAR] = ACTIONS(187),
    [anon_sym_SLASH] = ACTIONS(189),
    [anon_sym_PERCENT] = ACTIONS(187),
    [anon_sym_RPAREN] = ACTIONS(187),
    [anon_sym_LBRACK] = ACTIONS(187),
    [anon_sym_RBRACK] = ACTIONS(187),
    [anon_sym_LBRACE] = ACTIONS(187),
    [anon_sym_RBRACE] = ACTIONS(187),
    [anon_sym_COMMA] = ACTIONS(187),
    [anon_sym_COLON] = ACTIONS(187),
    [anon_sym_SEMI] = ACTIONS(187),
    [anon_sym_AT] = ACTIONS(187),
    [anon_sym_DOLLAR] = ACTIONS(187),
  },
  [21] = {
    [ts_builtin_sym_end] = ACTIONS(193),
    [sym_identifier] = ACTIONS(195),
    [aux_sym_keyword_section_token1] = ACTIONS(195),
    [aux_sym_keyword_section_token2] = ACTIONS(195),
    [aux_sym_keyword_section_token3] = ACTIONS(195),
    [aux_sym_keyword_section_token4] = ACTIONS(195),
    [aux_sym_keyword_section_token5] = ACTIONS(195),
    [aux_sym_keyword_section_token6] = ACTIONS(195),
    [aux_sym_keyword_section_token7] = ACTIONS(195),
    [aux_sym_keyword_section_token8] = ACTIONS(195),
    [aux_sym_keyword_section_token9] = ACTIONS(195),
    [aux_sym_keyword_section_token10] = ACTIONS(195),
    [aux_sym_keyword_section_token11] = ACTIONS(195),
    [aux_sym_keyword_section_token12] = ACTIONS(195),
    [aux_sym_keyword_section_token13] = ACTIONS(195),
    [aux_sym_keyword_section_token14] = ACTIONS(195),
    [aux_sym_keyword_section_token15] = ACTIONS(195),
    [aux_sym_keyword_flow_token1] = ACTIONS(195),
    [aux_sym_keyword_flow_token2] = ACTIONS(195),
    [aux_sym_keyword_flow_token3] = ACTIONS(195),
    [aux_sym_keyword_flow_token4] = ACTIONS(195),
    [aux_sym_keyword_flow_token5] = ACTIONS(195),
    [aux_sym_keyword_flow_token6] = ACTIONS(195),
    [aux_sym_keyword_flow_token7] = ACTIONS(195),
    [aux_sym_keyword_flow_token8] = ACTIONS(195),
    [aux_sym_keyword_flow_token9] = ACTIONS(195),
    [aux_sym_keyword_flow_token10] = ACTIONS(195),
    [aux_sym_keyword_flow_token11] = ACTIONS(195),
    [aux_sym_keyword_action_token1] = ACTIONS(195),
    [aux_sym_keyword_action_token2] = ACTIONS(195),
    [aux_sym_keyword_action_token3] = ACTIONS(195),
    [aux_sym_keyword_action_token4] = ACTIONS(195),
    [aux_sym_keyword_action_token5] = ACTIONS(195),
    [aux_sym_keyword_action_token6] = ACTIONS(195),
    [aux_sym_keyword_action_token7] = ACTIONS(195),
    [aux_sym_keyword_action_token8] = ACTIONS(195),
    [aux_sym_keyword_action_token9] = ACTIONS(195),
    [aux_sym_keyword_action_token10] = ACTIONS(195),
    [aux_sym_keyword_action_token11] = ACTIONS(195),
    [aux_sym_keyword_action_token12] = ACTIONS(195),
    [aux_sym_keyword_order_token1] = ACTIONS(195),
    [aux_sym_keyword_order_token2] = ACTIONS(195),
    [aux_sym_keyword_order_token3] = ACTIONS(195),
    [aux_sym_keyword_order_token4] = ACTIONS(195),
    [aux_sym_keyword_order_token5] = ACTIONS(195),
    [aux_sym_keyword_order_token6] = ACTIONS(195),
    [aux_sym_keyword_order_token7] = ACTIONS(195),
    [aux_sym_keyword_order_token8] = ACTIONS(195),
    [aux_sym_keyword_order_token9] = ACTIONS(195),
    [aux_sym_keyword_order_token10] = ACTIONS(195),
    [aux_sym_keyword_order_token11] = ACTIONS(195),
    [aux_sym_keyword_order_token12] = ACTIONS(195),
    [aux_sym_keyword_order_token13] = ACTIONS(195),
    [aux_sym_keyword_order_token14] = ACTIONS(195),
    [aux_sym_keyword_order_token15] = ACTIONS(195),
    [aux_sym_keyword_order_token16] = ACTIONS(195),
    [aux_sym_keyword_order_token17] = ACTIONS(195),
    [aux_sym_keyword_order_token18] = ACTIONS(195),
    [aux_sym_keyword_order_token19] = ACTIONS(195),
    [aux_sym_keyword_order_token20] = ACTIONS(195),
    [aux_sym_keyword_order_token21] = ACTIONS(195),
    [aux_sym_keyword_sizing_token1] = ACTIONS(195),
    [aux_sym_keyword_sizing_token2] = ACTIONS(195),
    [aux_sym_keyword_sizing_token3] = ACTIONS(195),
    [aux_sym_keyword_sizing_token4] = ACTIONS(195),
    [aux_sym_keyword_sizing_token5] = ACTIONS(195),
    [aux_sym_keyword_sizing_token6] = ACTIONS(195),
    [aux_sym_keyword_sizing_token7] = ACTIONS(195),
    [aux_sym_keyword_bracket_token1] = ACTIONS(195),
    [aux_sym_keyword_bracket_token2] = ACTIONS(195),
    [aux_sym_keyword_bracket_token3] = ACTIONS(195),
    [aux_sym_keyword_bracket_token4] = ACTIONS(195),
    [aux_sym_keyword_bracket_token5] = ACTIONS(195),
    [aux_sym_keyword_bracket_token6] = ACTIONS(195),
    [aux_sym_keyword_bracket_token7] = ACTIONS(195),
    [aux_sym_keyword_bracket_token8] = ACTIONS(195),
    [aux_sym_keyword_bracket_token9] = ACTIONS(195),
    [aux_sym_keyword_bracket_token10] = ACTIONS(195),
    [aux_sym_keyword_bracket_token11] = ACTIONS(195),
    [aux_sym_keyword_bracket_token12] = ACTIONS(195),
    [aux_sym_keyword_bracket_token13] = ACTIONS(195),
    [aux_sym_keyword_bracket_token14] = ACTIONS(195),
    [aux_sym_keyword_bracket_token15] = ACTIONS(195),
    [aux_sym_keyword_bracket_token16] = ACTIONS(195),
    [aux_sym_keyword_bracket_token17] = ACTIONS(195),
    [aux_sym_keyword_bracket_token18] = ACTIONS(195),
    [aux_sym_keyword_bracket_token19] = ACTIONS(195),
    [aux_sym_keyword_bracket_token20] = ACTIONS(195),
    [aux_sym_keyword_bracket_token21] = ACTIONS(195),
    [aux_sym_keyword_stacking_token1] = ACTIONS(195),
    [aux_sym_keyword_stacking_token2] = ACTIONS(195),
    [aux_sym_keyword_stacking_token3] = ACTIONS(195),
    [aux_sym_keyword_stacking_token4] = ACTIONS(195),
    [aux_sym_keyword_stacking_token5] = ACTIONS(195),
    [aux_sym_keyword_stacking_token6] = ACTIONS(195),
    [aux_sym_keyword_stacking_token7] = ACTIONS(195),
    [aux_sym_keyword_stacking_token8] = ACTIONS(195),
    [aux_sym_keyword_stacking_token9] = ACTIONS(195),
    [aux_sym_keyword_stacking_token10] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(195),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(195),
    [aux_sym_keyword_session_token1] = ACTIONS(195),
    [aux_sym_keyword_session_token2] = ACTIONS(195),
    [aux_sym_keyword_session_token3] = ACTIONS(195),
    [aux_sym_keyword_session_token4] = ACTIONS(195),
    [aux_sym_keyword_session_token5] = ACTIONS(195),
    [aux_sym_keyword_session_token6] = ACTIONS(195),
    [aux_sym_keyword_session_token7] = ACTIONS(195),
    [aux_sym_keyword_session_token8] = ACTIONS(195),
    [aux_sym_keyword_session_token9] = ACTIONS(195),
    [aux_sym_keyword_session_token10] = ACTIONS(195),
    [aux_sym_keyword_session_token11] = ACTIONS(195),
    [aux_sym_keyword_hook_token1] = ACTIONS(195),
    [aux_sym_keyword_hook_token2] = ACTIONS(195),
    [aux_sym_keyword_hook_token3] = ACTIONS(195),
    [aux_sym_keyword_hook_token4] = ACTIONS(195),
    [aux_sym_keyword_state_token1] = ACTIONS(195),
    [aux_sym_keyword_state_token2] = ACTIONS(195),
    [aux_sym_keyword_state_token3] = ACTIONS(195),
    [aux_sym_keyword_state_token4] = ACTIONS(195),
    [aux_sym_keyword_state_token5] = ACTIONS(195),
    [aux_sym_keyword_state_token6] = ACTIONS(195),
    [aux_sym_keyword_state_token7] = ACTIONS(195),
    [aux_sym_keyword_state_token8] = ACTIONS(195),
    [aux_sym_keyword_state_token9] = ACTIONS(195),
    [aux_sym_keyword_state_token10] = ACTIONS(195),
    [aux_sym_keyword_state_token11] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(195),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(195),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(195),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(195),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(195),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(195),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(195),
    [aux_sym_keyword_literal_token1] = ACTIONS(195),
    [aux_sym_keyword_literal_token2] = ACTIONS(195),
    [anon_sym_LPAREN] = ACTIONS(193),
    [anon_sym_DOT] = ACTIONS(193),
    [sym_broker_symbol] = ACTIONS(193),
    [sym_duration] = ACTIONS(193),
    [sym_number] = ACTIONS(195),
    [aux_sym_string_token1] = ACTIONS(193),
    [aux_sym_string_token2] = ACTIONS(193),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(193),
    [anon_sym_EQ_EQ] = ACTIONS(193),
    [anon_sym_LT_GT] = ACTIONS(193),
    [anon_sym_BANG_EQ] = ACTIONS(193),
    [anon_sym_LT_EQ] = ACTIONS(193),
    [anon_sym_GT_EQ] = ACTIONS(193),
    [anon_sym_LT] = ACTIONS(195),
    [anon_sym_GT] = ACTIONS(195),
    [anon_sym_EQ] = ACTIONS(195),
    [anon_sym_PLUS] = ACTIONS(193),
    [anon_sym_DASH] = ACTIONS(195),
    [anon_sym_STAR] = ACTIONS(193),
    [anon_sym_SLASH] = ACTIONS(195),
    [anon_sym_PERCENT] = ACTIONS(193),
    [anon_sym_RPAREN] = ACTIONS(193),
    [anon_sym_LBRACK] = ACTIONS(193),
    [anon_sym_RBRACK] = ACTIONS(193),
    [anon_sym_LBRACE] = ACTIONS(193),
    [anon_sym_RBRACE] = ACTIONS(193),
    [anon_sym_COMMA] = ACTIONS(193),
    [anon_sym_COLON] = ACTIONS(193),
    [anon_sym_SEMI] = ACTIONS(193),
    [anon_sym_AT] = ACTIONS(193),
    [anon_sym_DOLLAR] = ACTIONS(193),
  },
  [22] = {
    [ts_builtin_sym_end] = ACTIONS(197),
    [sym_identifier] = ACTIONS(199),
    [aux_sym_keyword_section_token1] = ACTIONS(199),
    [aux_sym_keyword_section_token2] = ACTIONS(199),
    [aux_sym_keyword_section_token3] = ACTIONS(199),
    [aux_sym_keyword_section_token4] = ACTIONS(199),
    [aux_sym_keyword_section_token5] = ACTIONS(199),
    [aux_sym_keyword_section_token6] = ACTIONS(199),
    [aux_sym_keyword_section_token7] = ACTIONS(199),
    [aux_sym_keyword_section_token8] = ACTIONS(199),
    [aux_sym_keyword_section_token9] = ACTIONS(199),
    [aux_sym_keyword_section_token10] = ACTIONS(199),
    [aux_sym_keyword_section_token11] = ACTIONS(199),
    [aux_sym_keyword_section_token12] = ACTIONS(199),
    [aux_sym_keyword_section_token13] = ACTIONS(199),
    [aux_sym_keyword_section_token14] = ACTIONS(199),
    [aux_sym_keyword_section_token15] = ACTIONS(199),
    [aux_sym_keyword_flow_token1] = ACTIONS(199),
    [aux_sym_keyword_flow_token2] = ACTIONS(199),
    [aux_sym_keyword_flow_token3] = ACTIONS(199),
    [aux_sym_keyword_flow_token4] = ACTIONS(199),
    [aux_sym_keyword_flow_token5] = ACTIONS(199),
    [aux_sym_keyword_flow_token6] = ACTIONS(199),
    [aux_sym_keyword_flow_token7] = ACTIONS(199),
    [aux_sym_keyword_flow_token8] = ACTIONS(199),
    [aux_sym_keyword_flow_token9] = ACTIONS(199),
    [aux_sym_keyword_flow_token10] = ACTIONS(199),
    [aux_sym_keyword_flow_token11] = ACTIONS(199),
    [aux_sym_keyword_action_token1] = ACTIONS(199),
    [aux_sym_keyword_action_token2] = ACTIONS(199),
    [aux_sym_keyword_action_token3] = ACTIONS(199),
    [aux_sym_keyword_action_token4] = ACTIONS(199),
    [aux_sym_keyword_action_token5] = ACTIONS(199),
    [aux_sym_keyword_action_token6] = ACTIONS(199),
    [aux_sym_keyword_action_token7] = ACTIONS(199),
    [aux_sym_keyword_action_token8] = ACTIONS(199),
    [aux_sym_keyword_action_token9] = ACTIONS(199),
    [aux_sym_keyword_action_token10] = ACTIONS(199),
    [aux_sym_keyword_action_token11] = ACTIONS(199),
    [aux_sym_keyword_action_token12] = ACTIONS(199),
    [aux_sym_keyword_order_token1] = ACTIONS(199),
    [aux_sym_keyword_order_token2] = ACTIONS(199),
    [aux_sym_keyword_order_token3] = ACTIONS(199),
    [aux_sym_keyword_order_token4] = ACTIONS(199),
    [aux_sym_keyword_order_token5] = ACTIONS(199),
    [aux_sym_keyword_order_token6] = ACTIONS(199),
    [aux_sym_keyword_order_token7] = ACTIONS(199),
    [aux_sym_keyword_order_token8] = ACTIONS(199),
    [aux_sym_keyword_order_token9] = ACTIONS(199),
    [aux_sym_keyword_order_token10] = ACTIONS(199),
    [aux_sym_keyword_order_token11] = ACTIONS(199),
    [aux_sym_keyword_order_token12] = ACTIONS(199),
    [aux_sym_keyword_order_token13] = ACTIONS(199),
    [aux_sym_keyword_order_token14] = ACTIONS(199),
    [aux_sym_keyword_order_token15] = ACTIONS(199),
    [aux_sym_keyword_order_token16] = ACTIONS(199),
    [aux_sym_keyword_order_token17] = ACTIONS(199),
    [aux_sym_keyword_order_token18] = ACTIONS(199),
    [aux_sym_keyword_order_token19] = ACTIONS(199),
    [aux_sym_keyword_order_token20] = ACTIONS(199),
    [aux_sym_keyword_order_token21] = ACTIONS(199),
    [aux_sym_keyword_sizing_token1] = ACTIONS(199),
    [aux_sym_keyword_sizing_token2] = ACTIONS(199),
    [aux_sym_keyword_sizing_token3] = ACTIONS(199),
    [aux_sym_keyword_sizing_token4] = ACTIONS(199),
    [aux_sym_keyword_sizing_token5] = ACTIONS(199),
    [aux_sym_keyword_sizing_token6] = ACTIONS(199),
    [aux_sym_keyword_sizing_token7] = ACTIONS(199),
    [aux_sym_keyword_bracket_token1] = ACTIONS(199),
    [aux_sym_keyword_bracket_token2] = ACTIONS(199),
    [aux_sym_keyword_bracket_token3] = ACTIONS(199),
    [aux_sym_keyword_bracket_token4] = ACTIONS(199),
    [aux_sym_keyword_bracket_token5] = ACTIONS(199),
    [aux_sym_keyword_bracket_token6] = ACTIONS(199),
    [aux_sym_keyword_bracket_token7] = ACTIONS(199),
    [aux_sym_keyword_bracket_token8] = ACTIONS(199),
    [aux_sym_keyword_bracket_token9] = ACTIONS(199),
    [aux_sym_keyword_bracket_token10] = ACTIONS(199),
    [aux_sym_keyword_bracket_token11] = ACTIONS(199),
    [aux_sym_keyword_bracket_token12] = ACTIONS(199),
    [aux_sym_keyword_bracket_token13] = ACTIONS(199),
    [aux_sym_keyword_bracket_token14] = ACTIONS(199),
    [aux_sym_keyword_bracket_token15] = ACTIONS(199),
    [aux_sym_keyword_bracket_token16] = ACTIONS(199),
    [aux_sym_keyword_bracket_token17] = ACTIONS(199),
    [aux_sym_keyword_bracket_token18] = ACTIONS(199),
    [aux_sym_keyword_bracket_token19] = ACTIONS(199),
    [aux_sym_keyword_bracket_token20] = ACTIONS(199),
    [aux_sym_keyword_bracket_token21] = ACTIONS(199),
    [aux_sym_keyword_stacking_token1] = ACTIONS(199),
    [aux_sym_keyword_stacking_token2] = ACTIONS(199),
    [aux_sym_keyword_stacking_token3] = ACTIONS(199),
    [aux_sym_keyword_stacking_token4] = ACTIONS(199),
    [aux_sym_keyword_stacking_token5] = ACTIONS(199),
    [aux_sym_keyword_stacking_token6] = ACTIONS(199),
    [aux_sym_keyword_stacking_token7] = ACTIONS(199),
    [aux_sym_keyword_stacking_token8] = ACTIONS(199),
    [aux_sym_keyword_stacking_token9] = ACTIONS(199),
    [aux_sym_keyword_stacking_token10] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(199),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(199),
    [aux_sym_keyword_session_token1] = ACTIONS(199),
    [aux_sym_keyword_session_token2] = ACTIONS(199),
    [aux_sym_keyword_session_token3] = ACTIONS(199),
    [aux_sym_keyword_session_token4] = ACTIONS(199),
    [aux_sym_keyword_session_token5] = ACTIONS(199),
    [aux_sym_keyword_session_token6] = ACTIONS(199),
    [aux_sym_keyword_session_token7] = ACTIONS(199),
    [aux_sym_keyword_session_token8] = ACTIONS(199),
    [aux_sym_keyword_session_token9] = ACTIONS(199),
    [aux_sym_keyword_session_token10] = ACTIONS(199),
    [aux_sym_keyword_session_token11] = ACTIONS(199),
    [aux_sym_keyword_hook_token1] = ACTIONS(199),
    [aux_sym_keyword_hook_token2] = ACTIONS(199),
    [aux_sym_keyword_hook_token3] = ACTIONS(199),
    [aux_sym_keyword_hook_token4] = ACTIONS(199),
    [aux_sym_keyword_state_token1] = ACTIONS(199),
    [aux_sym_keyword_state_token2] = ACTIONS(199),
    [aux_sym_keyword_state_token3] = ACTIONS(199),
    [aux_sym_keyword_state_token4] = ACTIONS(199),
    [aux_sym_keyword_state_token5] = ACTIONS(199),
    [aux_sym_keyword_state_token6] = ACTIONS(199),
    [aux_sym_keyword_state_token7] = ACTIONS(199),
    [aux_sym_keyword_state_token8] = ACTIONS(199),
    [aux_sym_keyword_state_token9] = ACTIONS(199),
    [aux_sym_keyword_state_token10] = ACTIONS(199),
    [aux_sym_keyword_state_token11] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(199),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(199),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(199),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(199),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(199),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(199),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(199),
    [aux_sym_keyword_literal_token1] = ACTIONS(199),
    [aux_sym_keyword_literal_token2] = ACTIONS(199),
    [anon_sym_LPAREN] = ACTIONS(197),
    [anon_sym_DOT] = ACTIONS(197),
    [sym_broker_symbol] = ACTIONS(197),
    [sym_duration] = ACTIONS(197),
    [sym_number] = ACTIONS(199),
    [aux_sym_string_token1] = ACTIONS(197),
    [aux_sym_string_token2] = ACTIONS(197),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(197),
    [anon_sym_EQ_EQ] = ACTIONS(197),
    [anon_sym_LT_GT] = ACTIONS(197),
    [anon_sym_BANG_EQ] = ACTIONS(197),
    [anon_sym_LT_EQ] = ACTIONS(197),
    [anon_sym_GT_EQ] = ACTIONS(197),
    [anon_sym_LT] = ACTIONS(199),
    [anon_sym_GT] = ACTIONS(199),
    [anon_sym_EQ] = ACTIONS(199),
    [anon_sym_PLUS] = ACTIONS(197),
    [anon_sym_DASH] = ACTIONS(199),
    [anon_sym_STAR] = ACTIONS(197),
    [anon_sym_SLASH] = ACTIONS(199),
    [anon_sym_PERCENT] = ACTIONS(197),
    [anon_sym_RPAREN] = ACTIONS(197),
    [anon_sym_LBRACK] = ACTIONS(197),
    [anon_sym_RBRACK] = ACTIONS(197),
    [anon_sym_LBRACE] = ACTIONS(197),
    [anon_sym_RBRACE] = ACTIONS(197),
    [anon_sym_COMMA] = ACTIONS(197),
    [anon_sym_COLON] = ACTIONS(197),
    [anon_sym_SEMI] = ACTIONS(197),
    [anon_sym_AT] = ACTIONS(197),
    [anon_sym_DOLLAR] = ACTIONS(197),
  },
  [23] = {
    [ts_builtin_sym_end] = ACTIONS(201),
    [sym_identifier] = ACTIONS(203),
    [aux_sym_keyword_section_token1] = ACTIONS(203),
    [aux_sym_keyword_section_token2] = ACTIONS(203),
    [aux_sym_keyword_section_token3] = ACTIONS(203),
    [aux_sym_keyword_section_token4] = ACTIONS(203),
    [aux_sym_keyword_section_token5] = ACTIONS(203),
    [aux_sym_keyword_section_token6] = ACTIONS(203),
    [aux_sym_keyword_section_token7] = ACTIONS(203),
    [aux_sym_keyword_section_token8] = ACTIONS(203),
    [aux_sym_keyword_section_token9] = ACTIONS(203),
    [aux_sym_keyword_section_token10] = ACTIONS(203),
    [aux_sym_keyword_section_token11] = ACTIONS(203),
    [aux_sym_keyword_section_token12] = ACTIONS(203),
    [aux_sym_keyword_section_token13] = ACTIONS(203),
    [aux_sym_keyword_section_token14] = ACTIONS(203),
    [aux_sym_keyword_section_token15] = ACTIONS(203),
    [aux_sym_keyword_flow_token1] = ACTIONS(203),
    [aux_sym_keyword_flow_token2] = ACTIONS(203),
    [aux_sym_keyword_flow_token3] = ACTIONS(203),
    [aux_sym_keyword_flow_token4] = ACTIONS(203),
    [aux_sym_keyword_flow_token5] = ACTIONS(203),
    [aux_sym_keyword_flow_token6] = ACTIONS(203),
    [aux_sym_keyword_flow_token7] = ACTIONS(203),
    [aux_sym_keyword_flow_token8] = ACTIONS(203),
    [aux_sym_keyword_flow_token9] = ACTIONS(203),
    [aux_sym_keyword_flow_token10] = ACTIONS(203),
    [aux_sym_keyword_flow_token11] = ACTIONS(203),
    [aux_sym_keyword_action_token1] = ACTIONS(203),
    [aux_sym_keyword_action_token2] = ACTIONS(203),
    [aux_sym_keyword_action_token3] = ACTIONS(203),
    [aux_sym_keyword_action_token4] = ACTIONS(203),
    [aux_sym_keyword_action_token5] = ACTIONS(203),
    [aux_sym_keyword_action_token6] = ACTIONS(203),
    [aux_sym_keyword_action_token7] = ACTIONS(203),
    [aux_sym_keyword_action_token8] = ACTIONS(203),
    [aux_sym_keyword_action_token9] = ACTIONS(203),
    [aux_sym_keyword_action_token10] = ACTIONS(203),
    [aux_sym_keyword_action_token11] = ACTIONS(203),
    [aux_sym_keyword_action_token12] = ACTIONS(203),
    [aux_sym_keyword_order_token1] = ACTIONS(203),
    [aux_sym_keyword_order_token2] = ACTIONS(203),
    [aux_sym_keyword_order_token3] = ACTIONS(203),
    [aux_sym_keyword_order_token4] = ACTIONS(203),
    [aux_sym_keyword_order_token5] = ACTIONS(203),
    [aux_sym_keyword_order_token6] = ACTIONS(203),
    [aux_sym_keyword_order_token7] = ACTIONS(203),
    [aux_sym_keyword_order_token8] = ACTIONS(203),
    [aux_sym_keyword_order_token9] = ACTIONS(203),
    [aux_sym_keyword_order_token10] = ACTIONS(203),
    [aux_sym_keyword_order_token11] = ACTIONS(203),
    [aux_sym_keyword_order_token12] = ACTIONS(203),
    [aux_sym_keyword_order_token13] = ACTIONS(203),
    [aux_sym_keyword_order_token14] = ACTIONS(203),
    [aux_sym_keyword_order_token15] = ACTIONS(203),
    [aux_sym_keyword_order_token16] = ACTIONS(203),
    [aux_sym_keyword_order_token17] = ACTIONS(203),
    [aux_sym_keyword_order_token18] = ACTIONS(203),
    [aux_sym_keyword_order_token19] = ACTIONS(203),
    [aux_sym_keyword_order_token20] = ACTIONS(203),
    [aux_sym_keyword_order_token21] = ACTIONS(203),
    [aux_sym_keyword_sizing_token1] = ACTIONS(203),
    [aux_sym_keyword_sizing_token2] = ACTIONS(203),
    [aux_sym_keyword_sizing_token3] = ACTIONS(203),
    [aux_sym_keyword_sizing_token4] = ACTIONS(203),
    [aux_sym_keyword_sizing_token5] = ACTIONS(203),
    [aux_sym_keyword_sizing_token6] = ACTIONS(203),
    [aux_sym_keyword_sizing_token7] = ACTIONS(203),
    [aux_sym_keyword_bracket_token1] = ACTIONS(203),
    [aux_sym_keyword_bracket_token2] = ACTIONS(203),
    [aux_sym_keyword_bracket_token3] = ACTIONS(203),
    [aux_sym_keyword_bracket_token4] = ACTIONS(203),
    [aux_sym_keyword_bracket_token5] = ACTIONS(203),
    [aux_sym_keyword_bracket_token6] = ACTIONS(203),
    [aux_sym_keyword_bracket_token7] = ACTIONS(203),
    [aux_sym_keyword_bracket_token8] = ACTIONS(203),
    [aux_sym_keyword_bracket_token9] = ACTIONS(203),
    [aux_sym_keyword_bracket_token10] = ACTIONS(203),
    [aux_sym_keyword_bracket_token11] = ACTIONS(203),
    [aux_sym_keyword_bracket_token12] = ACTIONS(203),
    [aux_sym_keyword_bracket_token13] = ACTIONS(203),
    [aux_sym_keyword_bracket_token14] = ACTIONS(203),
    [aux_sym_keyword_bracket_token15] = ACTIONS(203),
    [aux_sym_keyword_bracket_token16] = ACTIONS(203),
    [aux_sym_keyword_bracket_token17] = ACTIONS(203),
    [aux_sym_keyword_bracket_token18] = ACTIONS(203),
    [aux_sym_keyword_bracket_token19] = ACTIONS(203),
    [aux_sym_keyword_bracket_token20] = ACTIONS(203),
    [aux_sym_keyword_bracket_token21] = ACTIONS(203),
    [aux_sym_keyword_stacking_token1] = ACTIONS(203),
    [aux_sym_keyword_stacking_token2] = ACTIONS(203),
    [aux_sym_keyword_stacking_token3] = ACTIONS(203),
    [aux_sym_keyword_stacking_token4] = ACTIONS(203),
    [aux_sym_keyword_stacking_token5] = ACTIONS(203),
    [aux_sym_keyword_stacking_token6] = ACTIONS(203),
    [aux_sym_keyword_stacking_token7] = ACTIONS(203),
    [aux_sym_keyword_stacking_token8] = ACTIONS(203),
    [aux_sym_keyword_stacking_token9] = ACTIONS(203),
    [aux_sym_keyword_stacking_token10] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(203),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(203),
    [aux_sym_keyword_session_token1] = ACTIONS(203),
    [aux_sym_keyword_session_token2] = ACTIONS(203),
    [aux_sym_keyword_session_token3] = ACTIONS(203),
    [aux_sym_keyword_session_token4] = ACTIONS(203),
    [aux_sym_keyword_session_token5] = ACTIONS(203),
    [aux_sym_keyword_session_token6] = ACTIONS(203),
    [aux_sym_keyword_session_token7] = ACTIONS(203),
    [aux_sym_keyword_session_token8] = ACTIONS(203),
    [aux_sym_keyword_session_token9] = ACTIONS(203),
    [aux_sym_keyword_session_token10] = ACTIONS(203),
    [aux_sym_keyword_session_token11] = ACTIONS(203),
    [aux_sym_keyword_hook_token1] = ACTIONS(203),
    [aux_sym_keyword_hook_token2] = ACTIONS(203),
    [aux_sym_keyword_hook_token3] = ACTIONS(203),
    [aux_sym_keyword_hook_token4] = ACTIONS(203),
    [aux_sym_keyword_state_token1] = ACTIONS(203),
    [aux_sym_keyword_state_token2] = ACTIONS(203),
    [aux_sym_keyword_state_token3] = ACTIONS(203),
    [aux_sym_keyword_state_token4] = ACTIONS(203),
    [aux_sym_keyword_state_token5] = ACTIONS(203),
    [aux_sym_keyword_state_token6] = ACTIONS(203),
    [aux_sym_keyword_state_token7] = ACTIONS(203),
    [aux_sym_keyword_state_token8] = ACTIONS(203),
    [aux_sym_keyword_state_token9] = ACTIONS(203),
    [aux_sym_keyword_state_token10] = ACTIONS(203),
    [aux_sym_keyword_state_token11] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(203),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(203),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(203),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(203),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(203),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(203),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(203),
    [aux_sym_keyword_literal_token1] = ACTIONS(203),
    [aux_sym_keyword_literal_token2] = ACTIONS(203),
    [anon_sym_LPAREN] = ACTIONS(201),
    [anon_sym_DOT] = ACTIONS(201),
    [sym_broker_symbol] = ACTIONS(201),
    [sym_duration] = ACTIONS(201),
    [sym_number] = ACTIONS(203),
    [aux_sym_string_token1] = ACTIONS(201),
    [aux_sym_string_token2] = ACTIONS(201),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(201),
    [anon_sym_EQ_EQ] = ACTIONS(201),
    [anon_sym_LT_GT] = ACTIONS(201),
    [anon_sym_BANG_EQ] = ACTIONS(201),
    [anon_sym_LT_EQ] = ACTIONS(201),
    [anon_sym_GT_EQ] = ACTIONS(201),
    [anon_sym_LT] = ACTIONS(203),
    [anon_sym_GT] = ACTIONS(203),
    [anon_sym_EQ] = ACTIONS(203),
    [anon_sym_PLUS] = ACTIONS(201),
    [anon_sym_DASH] = ACTIONS(203),
    [anon_sym_STAR] = ACTIONS(201),
    [anon_sym_SLASH] = ACTIONS(203),
    [anon_sym_PERCENT] = ACTIONS(201),
    [anon_sym_RPAREN] = ACTIONS(201),
    [anon_sym_LBRACK] = ACTIONS(201),
    [anon_sym_RBRACK] = ACTIONS(201),
    [anon_sym_LBRACE] = ACTIONS(201),
    [anon_sym_RBRACE] = ACTIONS(201),
    [anon_sym_COMMA] = ACTIONS(201),
    [anon_sym_COLON] = ACTIONS(201),
    [anon_sym_SEMI] = ACTIONS(201),
    [anon_sym_AT] = ACTIONS(201),
    [anon_sym_DOLLAR] = ACTIONS(201),
  },
  [24] = {
    [ts_builtin_sym_end] = ACTIONS(205),
    [sym_identifier] = ACTIONS(207),
    [aux_sym_keyword_section_token1] = ACTIONS(207),
    [aux_sym_keyword_section_token2] = ACTIONS(207),
    [aux_sym_keyword_section_token3] = ACTIONS(207),
    [aux_sym_keyword_section_token4] = ACTIONS(207),
    [aux_sym_keyword_section_token5] = ACTIONS(207),
    [aux_sym_keyword_section_token6] = ACTIONS(207),
    [aux_sym_keyword_section_token7] = ACTIONS(207),
    [aux_sym_keyword_section_token8] = ACTIONS(207),
    [aux_sym_keyword_section_token9] = ACTIONS(207),
    [aux_sym_keyword_section_token10] = ACTIONS(207),
    [aux_sym_keyword_section_token11] = ACTIONS(207),
    [aux_sym_keyword_section_token12] = ACTIONS(207),
    [aux_sym_keyword_section_token13] = ACTIONS(207),
    [aux_sym_keyword_section_token14] = ACTIONS(207),
    [aux_sym_keyword_section_token15] = ACTIONS(207),
    [aux_sym_keyword_flow_token1] = ACTIONS(207),
    [aux_sym_keyword_flow_token2] = ACTIONS(207),
    [aux_sym_keyword_flow_token3] = ACTIONS(207),
    [aux_sym_keyword_flow_token4] = ACTIONS(207),
    [aux_sym_keyword_flow_token5] = ACTIONS(207),
    [aux_sym_keyword_flow_token6] = ACTIONS(207),
    [aux_sym_keyword_flow_token7] = ACTIONS(207),
    [aux_sym_keyword_flow_token8] = ACTIONS(207),
    [aux_sym_keyword_flow_token9] = ACTIONS(207),
    [aux_sym_keyword_flow_token10] = ACTIONS(207),
    [aux_sym_keyword_flow_token11] = ACTIONS(207),
    [aux_sym_keyword_action_token1] = ACTIONS(207),
    [aux_sym_keyword_action_token2] = ACTIONS(207),
    [aux_sym_keyword_action_token3] = ACTIONS(207),
    [aux_sym_keyword_action_token4] = ACTIONS(207),
    [aux_sym_keyword_action_token5] = ACTIONS(207),
    [aux_sym_keyword_action_token6] = ACTIONS(207),
    [aux_sym_keyword_action_token7] = ACTIONS(207),
    [aux_sym_keyword_action_token8] = ACTIONS(207),
    [aux_sym_keyword_action_token9] = ACTIONS(207),
    [aux_sym_keyword_action_token10] = ACTIONS(207),
    [aux_sym_keyword_action_token11] = ACTIONS(207),
    [aux_sym_keyword_action_token12] = ACTIONS(207),
    [aux_sym_keyword_order_token1] = ACTIONS(207),
    [aux_sym_keyword_order_token2] = ACTIONS(207),
    [aux_sym_keyword_order_token3] = ACTIONS(207),
    [aux_sym_keyword_order_token4] = ACTIONS(207),
    [aux_sym_keyword_order_token5] = ACTIONS(207),
    [aux_sym_keyword_order_token6] = ACTIONS(207),
    [aux_sym_keyword_order_token7] = ACTIONS(207),
    [aux_sym_keyword_order_token8] = ACTIONS(207),
    [aux_sym_keyword_order_token9] = ACTIONS(207),
    [aux_sym_keyword_order_token10] = ACTIONS(207),
    [aux_sym_keyword_order_token11] = ACTIONS(207),
    [aux_sym_keyword_order_token12] = ACTIONS(207),
    [aux_sym_keyword_order_token13] = ACTIONS(207),
    [aux_sym_keyword_order_token14] = ACTIONS(207),
    [aux_sym_keyword_order_token15] = ACTIONS(207),
    [aux_sym_keyword_order_token16] = ACTIONS(207),
    [aux_sym_keyword_order_token17] = ACTIONS(207),
    [aux_sym_keyword_order_token18] = ACTIONS(207),
    [aux_sym_keyword_order_token19] = ACTIONS(207),
    [aux_sym_keyword_order_token20] = ACTIONS(207),
    [aux_sym_keyword_order_token21] = ACTIONS(207),
    [aux_sym_keyword_sizing_token1] = ACTIONS(207),
    [aux_sym_keyword_sizing_token2] = ACTIONS(207),
    [aux_sym_keyword_sizing_token3] = ACTIONS(207),
    [aux_sym_keyword_sizing_token4] = ACTIONS(207),
    [aux_sym_keyword_sizing_token5] = ACTIONS(207),
    [aux_sym_keyword_sizing_token6] = ACTIONS(207),
    [aux_sym_keyword_sizing_token7] = ACTIONS(207),
    [aux_sym_keyword_bracket_token1] = ACTIONS(207),
    [aux_sym_keyword_bracket_token2] = ACTIONS(207),
    [aux_sym_keyword_bracket_token3] = ACTIONS(207),
    [aux_sym_keyword_bracket_token4] = ACTIONS(207),
    [aux_sym_keyword_bracket_token5] = ACTIONS(207),
    [aux_sym_keyword_bracket_token6] = ACTIONS(207),
    [aux_sym_keyword_bracket_token7] = ACTIONS(207),
    [aux_sym_keyword_bracket_token8] = ACTIONS(207),
    [aux_sym_keyword_bracket_token9] = ACTIONS(207),
    [aux_sym_keyword_bracket_token10] = ACTIONS(207),
    [aux_sym_keyword_bracket_token11] = ACTIONS(207),
    [aux_sym_keyword_bracket_token12] = ACTIONS(207),
    [aux_sym_keyword_bracket_token13] = ACTIONS(207),
    [aux_sym_keyword_bracket_token14] = ACTIONS(207),
    [aux_sym_keyword_bracket_token15] = ACTIONS(207),
    [aux_sym_keyword_bracket_token16] = ACTIONS(207),
    [aux_sym_keyword_bracket_token17] = ACTIONS(207),
    [aux_sym_keyword_bracket_token18] = ACTIONS(207),
    [aux_sym_keyword_bracket_token19] = ACTIONS(207),
    [aux_sym_keyword_bracket_token20] = ACTIONS(207),
    [aux_sym_keyword_bracket_token21] = ACTIONS(207),
    [aux_sym_keyword_stacking_token1] = ACTIONS(207),
    [aux_sym_keyword_stacking_token2] = ACTIONS(207),
    [aux_sym_keyword_stacking_token3] = ACTIONS(207),
    [aux_sym_keyword_stacking_token4] = ACTIONS(207),
    [aux_sym_keyword_stacking_token5] = ACTIONS(207),
    [aux_sym_keyword_stacking_token6] = ACTIONS(207),
    [aux_sym_keyword_stacking_token7] = ACTIONS(207),
    [aux_sym_keyword_stacking_token8] = ACTIONS(207),
    [aux_sym_keyword_stacking_token9] = ACTIONS(207),
    [aux_sym_keyword_stacking_token10] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token1] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token2] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token3] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token4] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token5] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token6] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token7] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token8] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token9] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token10] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token11] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token12] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token13] = ACTIONS(207),
    [aux_sym_keyword_portfolio_token14] = ACTIONS(207),
    [aux_sym_keyword_session_token1] = ACTIONS(207),
    [aux_sym_keyword_session_token2] = ACTIONS(207),
    [aux_sym_keyword_session_token3] = ACTIONS(207),
    [aux_sym_keyword_session_token4] = ACTIONS(207),
    [aux_sym_keyword_session_token5] = ACTIONS(207),
    [aux_sym_keyword_session_token6] = ACTIONS(207),
    [aux_sym_keyword_session_token7] = ACTIONS(207),
    [aux_sym_keyword_session_token8] = ACTIONS(207),
    [aux_sym_keyword_session_token9] = ACTIONS(207),
    [aux_sym_keyword_session_token10] = ACTIONS(207),
    [aux_sym_keyword_session_token11] = ACTIONS(207),
    [aux_sym_keyword_hook_token1] = ACTIONS(207),
    [aux_sym_keyword_hook_token2] = ACTIONS(207),
    [aux_sym_keyword_hook_token3] = ACTIONS(207),
    [aux_sym_keyword_hook_token4] = ACTIONS(207),
    [aux_sym_keyword_state_token1] = ACTIONS(207),
    [aux_sym_keyword_state_token2] = ACTIONS(207),
    [aux_sym_keyword_state_token3] = ACTIONS(207),
    [aux_sym_keyword_state_token4] = ACTIONS(207),
    [aux_sym_keyword_state_token5] = ACTIONS(207),
    [aux_sym_keyword_state_token6] = ACTIONS(207),
    [aux_sym_keyword_state_token7] = ACTIONS(207),
    [aux_sym_keyword_state_token8] = ACTIONS(207),
    [aux_sym_keyword_state_token9] = ACTIONS(207),
    [aux_sym_keyword_state_token10] = ACTIONS(207),
    [aux_sym_keyword_state_token11] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token1] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token2] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token3] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token4] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token5] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token6] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token7] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token8] = ACTIONS(207),
    [aux_sym_keyword_operator_word_token9] = ACTIONS(207),
    [aux_sym_keyword_aggregate_token1] = ACTIONS(207),
    [aux_sym_keyword_aggregate_token2] = ACTIONS(207),
    [aux_sym_keyword_aggregate_token3] = ACTIONS(207),
    [aux_sym_keyword_aggregate_token4] = ACTIONS(207),
    [aux_sym_keyword_aggregate_token5] = ACTIONS(207),
    [aux_sym_keyword_literal_token1] = ACTIONS(207),
    [aux_sym_keyword_literal_token2] = ACTIONS(207),
    [anon_sym_LPAREN] = ACTIONS(205),
    [anon_sym_DOT] = ACTIONS(205),
    [sym_broker_symbol] = ACTIONS(205),
    [sym_duration] = ACTIONS(205),
    [sym_number] = ACTIONS(207),
    [aux_sym_string_token1] = ACTIONS(205),
    [aux_sym_string_token2] = ACTIONS(205),
    [sym_comment] = ACTIONS(3),
    [anon_sym_DASH_GT] = ACTIONS(205),
    [anon_sym_EQ_EQ] = ACTIONS(205),
    [anon_sym_LT_GT] = ACTIONS(205),
    [anon_sym_BANG_EQ] = ACTIONS(205),
    [anon_sym_LT_EQ] = ACTIONS(205),
    [anon_sym_GT_EQ] = ACTIONS(205),
    [anon_sym_LT] = ACTIONS(207),
    [anon_sym_GT] = ACTIONS(207),
    [anon_sym_EQ] = ACTIONS(207),
    [anon_sym_PLUS] = ACTIONS(205),
    [anon_sym_DASH] = ACTIONS(207),
    [anon_sym_STAR] = ACTIONS(205),
    [anon_sym_SLASH] = ACTIONS(207),
    [anon_sym_PERCENT] = ACTIONS(205),
    [anon_sym_RPAREN] = ACTIONS(205),
    [anon_sym_LBRACK] = ACTIONS(205),
    [anon_sym_RBRACK] = ACTIONS(205),
    [anon_sym_LBRACE] = ACTIONS(205),
    [anon_sym_RBRACE] = ACTIONS(205),
    [anon_sym_COMMA] = ACTIONS(205),
    [anon_sym_COLON] = ACTIONS(205),
    [anon_sym_SEMI] = ACTIONS(205),
    [anon_sym_AT] = ACTIONS(205),
    [anon_sym_DOLLAR] = ACTIONS(205),
  },
};

static const uint16_t ts_small_parse_table[] = {
  [0] = 2,
    ACTIONS(3), 1,
      sym_comment,
    ACTIONS(209), 1,
      ts_builtin_sym_end,
};

static const uint32_t ts_small_parse_table_map[] = {
  [SMALL_STATE(25)] = 0,
};

static const TSParseActionEntry ts_parse_actions[] = {
  [0] = {.entry = {.count = 0, .reusable = false}},
  [1] = {.entry = {.count = 1, .reusable = false}}, RECOVER(),
  [3] = {.entry = {.count = 1, .reusable = true}}, SHIFT_EXTRA(),
  [5] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_source_file, 0, 0, 0),
  [7] = {.entry = {.count = 1, .reusable = false}}, SHIFT(20),
  [9] = {.entry = {.count = 1, .reusable = false}}, SHIFT(4),
  [11] = {.entry = {.count = 1, .reusable = false}}, SHIFT(5),
  [13] = {.entry = {.count = 1, .reusable = false}}, SHIFT(6),
  [15] = {.entry = {.count = 1, .reusable = false}}, SHIFT(7),
  [17] = {.entry = {.count = 1, .reusable = false}}, SHIFT(8),
  [19] = {.entry = {.count = 1, .reusable = false}}, SHIFT(9),
  [21] = {.entry = {.count = 1, .reusable = false}}, SHIFT(10),
  [23] = {.entry = {.count = 1, .reusable = false}}, SHIFT(11),
  [25] = {.entry = {.count = 1, .reusable = false}}, SHIFT(12),
  [27] = {.entry = {.count = 1, .reusable = false}}, SHIFT(13),
  [29] = {.entry = {.count = 1, .reusable = false}}, SHIFT(14),
  [31] = {.entry = {.count = 1, .reusable = false}}, SHIFT(23),
  [33] = {.entry = {.count = 1, .reusable = false}}, SHIFT(15),
  [35] = {.entry = {.count = 1, .reusable = false}}, SHIFT(22),
  [37] = {.entry = {.count = 1, .reusable = true}}, SHIFT(16),
  [39] = {.entry = {.count = 1, .reusable = true}}, SHIFT(17),
  [41] = {.entry = {.count = 1, .reusable = true}}, SHIFT(3),
  [43] = {.entry = {.count = 1, .reusable = false}}, SHIFT(3),
  [45] = {.entry = {.count = 1, .reusable = true}}, SHIFT(18),
  [47] = {.entry = {.count = 1, .reusable = true}}, SHIFT(19),
  [49] = {.entry = {.count = 1, .reusable = false}}, SHIFT(19),
  [51] = {.entry = {.count = 1, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0),
  [53] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(20),
  [56] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(4),
  [59] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(5),
  [62] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(6),
  [65] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(7),
  [68] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(8),
  [71] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(9),
  [74] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(10),
  [77] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(11),
  [80] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(12),
  [83] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(13),
  [86] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(14),
  [89] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(23),
  [92] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(15),
  [95] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(22),
  [98] = {.entry = {.count = 2, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(16),
  [101] = {.entry = {.count = 2, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(17),
  [104] = {.entry = {.count = 2, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(2),
  [107] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(2),
  [110] = {.entry = {.count = 2, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(18),
  [113] = {.entry = {.count = 2, .reusable = true}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(19),
  [116] = {.entry = {.count = 2, .reusable = false}}, REDUCE(aux_sym_source_file_repeat1, 2, 0, 0), SHIFT_REPEAT(19),
  [119] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_source_file, 1, 0, 0),
  [121] = {.entry = {.count = 1, .reusable = true}}, SHIFT(2),
  [123] = {.entry = {.count = 1, .reusable = false}}, SHIFT(2),
  [125] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_section, 1, 0, 0),
  [127] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_section, 1, 0, 0),
  [129] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_flow, 1, 0, 0),
  [131] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_flow, 1, 0, 0),
  [133] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_action, 1, 0, 0),
  [135] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_action, 1, 0, 0),
  [137] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_order, 1, 0, 0),
  [139] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_order, 1, 0, 0),
  [141] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_sizing, 1, 0, 0),
  [143] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_sizing, 1, 0, 0),
  [145] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_bracket, 1, 0, 0),
  [147] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_bracket, 1, 0, 0),
  [149] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_stacking, 1, 0, 0),
  [151] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_stacking, 1, 0, 0),
  [153] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_portfolio, 1, 0, 0),
  [155] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_portfolio, 1, 0, 0),
  [157] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_session, 1, 0, 0),
  [159] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_session, 1, 0, 0),
  [161] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_hook, 1, 0, 0),
  [163] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_hook, 1, 0, 0),
  [165] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_state, 1, 0, 0),
  [167] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_state, 1, 0, 0),
  [169] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_aggregate, 1, 0, 0),
  [171] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_aggregate, 1, 0, 0),
  [173] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_punctuation, 1, 0, 0),
  [175] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_punctuation, 1, 0, 0),
  [177] = {.entry = {.count = 1, .reusable = false}}, SHIFT(21),
  [179] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_string, 1, 0, 0),
  [181] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_string, 1, 0, 0),
  [183] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_operator, 1, 0, 0),
  [185] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_operator, 1, 0, 0),
  [187] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym__item, 1, 0, 0),
  [189] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym__item, 1, 0, 0),
  [191] = {.entry = {.count = 1, .reusable = true}}, SHIFT(24),
  [193] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_member, 2, 0, 1),
  [195] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_member, 2, 0, 1),
  [197] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_literal, 1, 0, 0),
  [199] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_literal, 1, 0, 0),
  [201] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_keyword_operator_word, 1, 0, 0),
  [203] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_keyword_operator_word, 1, 0, 0),
  [205] = {.entry = {.count = 1, .reusable = true}}, REDUCE(sym_call, 2, 0, 2),
  [207] = {.entry = {.count = 1, .reusable = false}}, REDUCE(sym_call, 2, 0, 2),
  [209] = {.entry = {.count = 1, .reusable = true}},  ACCEPT_INPUT(),
};

#ifdef __cplusplus
extern "C" {
#endif
#ifdef TREE_SITTER_HIDE_SYMBOLS
#define TS_PUBLIC
#elif defined(_WIN32)
#define TS_PUBLIC __declspec(dllexport)
#else
#define TS_PUBLIC __attribute__((visibility("default")))
#endif

TS_PUBLIC const TSLanguage *tree_sitter_qkt(void) {
  static const TSLanguage language = {
    .version = LANGUAGE_VERSION,
    .symbol_count = SYMBOL_COUNT,
    .alias_count = ALIAS_COUNT,
    .token_count = TOKEN_COUNT,
    .external_token_count = EXTERNAL_TOKEN_COUNT,
    .state_count = STATE_COUNT,
    .large_state_count = LARGE_STATE_COUNT,
    .production_id_count = PRODUCTION_ID_COUNT,
    .field_count = FIELD_COUNT,
    .max_alias_sequence_length = MAX_ALIAS_SEQUENCE_LENGTH,
    .parse_table = &ts_parse_table[0][0],
    .small_parse_table = ts_small_parse_table,
    .small_parse_table_map = ts_small_parse_table_map,
    .parse_actions = ts_parse_actions,
    .symbol_names = ts_symbol_names,
    .field_names = ts_field_names,
    .field_map_slices = ts_field_map_slices,
    .field_map_entries = ts_field_map_entries,
    .symbol_metadata = ts_symbol_metadata,
    .public_symbol_map = ts_symbol_map,
    .alias_map = ts_non_terminal_alias_map,
    .alias_sequences = &ts_alias_sequences[0][0],
    .lex_modes = ts_lex_modes,
    .lex_fn = ts_lex,
    .keyword_lex_fn = ts_lex_keywords,
    .keyword_capture_token = sym_identifier,
    .primary_state_ids = ts_primary_state_ids,
  };
  return &language;
}
#ifdef __cplusplus
}
#endif
