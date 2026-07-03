#!/usr/bin/env bash
# Deterministic, low-context helpers for routine agent work.

set -euo pipefail

REPO_ROOT="${QKT_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

need() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

usage() {
    cat <<'EOF'
Usage:
  ./scripts/agent-workflow.sh status
  ./scripts/agent-workflow.sh bugs [--json]
  ./scripts/agent-workflow.sh issue-create [options] [--apply]
  ./scripts/agent-workflow.sh commit --message MESSAGE [--apply]

Commands:
  status        Print compact branch, change, and recent-commit context.
  bugs          List open bugs sorted by priority, effort, and issue number.
  issue-create  Validate and render an issue; create it only with --apply.
  commit        Validate staged changes/message; commit only with --apply.

issue-create options:
  --title TEXT
  --priority P0|P1|P2|P3
  --effort simple|moderate|advanced
  --type bug|enhancement|documentation|ops|tooling|epic
  --problem TEXT | --problem-file PATH
  --solution TEXT | --solution-file PATH
  --backlog TEXT
  --label LABEL                 Repeat for optional flags/additional types.

Mutating commands are dry-run by default. Pass --apply explicitly.
EOF
}

status_command() {
    need git
    printf 'branch\t%s\n' "$(git branch --show-current)"
    git status --short
    git log -5 --pretty=format:'commit	%h	%s'
    printf '\n'
}

bugs_command() {
    need gh
    need jq

    local output=json
    if [[ "${1:-}" == "--json" ]]; then
        shift
    else
        output=compact
    fi
    [[ $# -eq 0 ]] || die "bugs accepts only --json"

    local issues
    issues="$(
        gh issue list \
            --state open \
            --label bug \
            --limit 200 \
            --json number,title,labels,updatedAt,url
    )"

    if [[ "$output" == json ]]; then
        jq -c '
            def get_label($prefix):
                [.labels[].name | select(startswith($prefix))][0] // "";
            def rank_priority:
                (get_label("P")) as $p
                | {"P0": 0, "P1": 1, "P2": 2, "P3": 3}[$p] // 9;
            def rank_effort:
                (get_label("effort: ")) as $e
                | {"effort: simple": 0, "effort: moderate": 1, "effort: advanced": 2}[$e] // 9;
            sort_by(rank_priority, rank_effort, .number)
        ' <<<"$issues"
        return
    fi

    jq -r '
        def get_label($prefix):
            [.labels[].name | select(startswith($prefix))][0] // "-";
        def priority: get_label("P");
        def effort: get_label("effort: ") | sub("^effort: "; "");
        def rank_priority:
            {"P0": 0, "P1": 1, "P2": 2, "P3": 3}[priority] // 9;
        def rank_effort:
            {"simple": 0, "moderate": 1, "advanced": 2}[effort] // 9;
        sort_by(rank_priority, rank_effort, .number)[]
        | [priority, effort, ("#" + (.number | tostring)), .title]
        | @tsv
    ' <<<"$issues"
}

read_text_option() {
    local value="$1"
    local file="$2"
    local name="$3"

    if [[ -n "$value" && -n "$file" ]]; then
        die "use either --$name or --$name-file, not both"
    fi
    if [[ -n "$file" ]]; then
        [[ -f "$file" ]] || die "$name file does not exist: $file"
        value="$(<"$file")"
    fi
    [[ -n "$value" ]] || die "--$name or --$name-file is required"
    printf '%s' "$value"
}

issue_create_command() {
    need gh

    local title=
    local priority=
    local effort=
    local type=
    local problem=
    local problem_file=
    local solution=
    local solution_file=
    local backlog=
    local apply=false
    local -a extra_labels=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --title | --priority | --effort | --type | --problem | --problem-file | --solution | --solution-file | --backlog | --label)
                [[ $# -ge 2 ]] || die "$1 requires a value"
                case "$1" in
                    --title) title="$2" ;;
                    --priority) priority="$2" ;;
                    --effort) effort="$2" ;;
                    --type) type="$2" ;;
                    --problem) problem="$2" ;;
                    --problem-file) problem_file="$2" ;;
                    --solution) solution="$2" ;;
                    --solution-file) solution_file="$2" ;;
                    --backlog) backlog="$2" ;;
                    --label) extra_labels+=("$2") ;;
                esac
                shift 2
                ;;
            --apply)
                apply=true
                shift
                ;;
            *)
                die "unknown issue-create option: $1"
                ;;
        esac
    done

    [[ -n "$title" ]] || die "--title is required"
    [[ ${#title} -le 120 ]] || die "issue title exceeds 120 characters"
    [[ "$priority" =~ ^P[0-3]$ ]] || die "--priority must be P0, P1, P2, or P3"
    [[ "$effort" =~ ^(simple|moderate|advanced)$ ]] ||
        die "--effort must be simple, moderate, or advanced"
    [[ "$type" =~ ^(bug|enhancement|documentation|ops|tooling|epic)$ ]] ||
        die "--type is not in the qkt issue taxonomy"
    [[ -n "$backlog" ]] || die "--backlog is required"

    problem="$(read_text_option "$problem" "$problem_file" problem)"
    solution="$(read_text_option "$solution" "$solution_file" solution)"

    local body_file
    body_file="$(mktemp)"
    trap "rm -f '$body_file'" EXIT
    {
        printf '## Problem\n\n%s\n\n' "$problem"
        printf '## Proposed solution\n\n%s\n\n' "$solution"
        printf '%s\n' '---'
        printf 'Backlog: `docs/backlog.md` — %s\n' "$backlog"
    } >"$body_file"

    local -a labels=("$priority" "$type" "effort: $effort" "${extra_labels[@]}")
    local -a command=(gh issue create --title "$title" --body-file "$body_file")
    local label
    for label in "${labels[@]}"; do
        command+=(--label "$label")
    done

    if ! $apply; then
        printf 'DRY RUN: issue not created; pass --apply after reviewing.\n'
        printf 'title: %s\nlabels: %s\n\n' "$title" "$(IFS=,; printf '%s' "${labels[*]}")"
        cat "$body_file"
        rm -f "$body_file"
        trap - EXIT
        return
    fi

    "${command[@]}"
    rm -f "$body_file"
    trap - EXIT
}

validate_commit_message() {
    local message="$1"

    [[ "$message" != *$'\n'* ]] || die "commit message must be a subject line only"
    [[ ${#message} -le 70 ]] || die "commit message exceeds 70 characters"
    [[ "$message" =~ ^(feat|fix|refactor|docs|test|style|build|chore|perf)(\([a-z0-9-]+\))?:\ [a-z0-9] ]] ||
        die "commit message must be a lowercase Conventional Commit subject"
    [[ "$message" != *"." ]] || die "commit message must not end with a period"
    if [[ "$message" =~ (AI|Claude|GPT|Co-Authored-By|Generated\ with) ]]; then
        die "commit message contains forbidden attribution"
    fi
}

commit_command() {
    need git

    local message=
    local apply=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --message)
                [[ $# -ge 2 ]] || die "--message requires a value"
                message="$2"
                shift 2
                ;;
            --apply)
                apply=true
                shift
                ;;
            *)
                die "unknown commit option: $1"
                ;;
        esac
    done

    [[ -n "$message" ]] || die "--message is required"
    validate_commit_message "$message"

    local branch
    branch="$(git branch --show-current)"
    [[ -n "$branch" ]] || die "detached HEAD; refusing to commit"
    case "$branch" in
        main | testing | dev)
            die "refusing to commit directly on protected branch: $branch"
            ;;
    esac

    git diff --cached --quiet && die "no staged changes"
    git diff --cached --check
    if git diff --cached -U0 |
        grep -E '^\+.*(Co-Authored-By:.*(Claude|GPT)|Generated with.*Claude|🤖)' >/dev/null; then
        die "staged additions contain forbidden attribution"
    fi

    printf 'branch: %s\nmessage: %s\nstaged:\n' "$branch" "$message"
    git diff --cached --name-status

    if ! $apply; then
        printf 'DRY RUN: commit not created; pass --apply after reviewing.\n'
        return
    fi

    git commit -m "$message"
}

main() {
    local command="${1:-help}"
    [[ $# -eq 0 ]] || shift

    case "$command" in
        help | -h | --help) usage ;;
        status) status_command "$@" ;;
        bugs) bugs_command "$@" ;;
        issue-create) issue_create_command "$@" ;;
        commit) commit_command "$@" ;;
        *) die "unknown command: $command (run with --help)" ;;
    esac
}

main "$@"
