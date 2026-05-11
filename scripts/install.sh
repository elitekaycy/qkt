#!/usr/bin/env bash
# qkt — one-line installer.
#
# Fetches the latest GitHub release tarball, extracts under ~/.local/share/qkt,
# symlinks `qkt` into ~/.local/bin. Mac + Linux.
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.sh | bash
#
#   # Or pin a specific version:
#   curl -sSL .../install.sh | QKT_VERSION=v0.25.0 bash
#
#   # Or install to a different prefix:
#   curl -sSL .../install.sh | QKT_PREFIX=/opt/qkt bash

set -euo pipefail

REPO="${QKT_REPO:-elitekaycy/qkt}"
VERSION="${QKT_VERSION:-latest}"
PREFIX="${QKT_PREFIX:-$HOME/.local/share/qkt}"
BIN_DIR="${QKT_BIN_DIR:-$HOME/.local/bin}"

# ─────────────────────────────── helpers ─────────────────────────────── #
say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

# ─────────────────────────────── prechecks ────────────────────────────── #
need curl
need tar

JAVA_VERSION_OK=0
if command -v java >/dev/null 2>&1; then
    JV=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
    if [ -n "$JV" ] && [ "$JV" -ge 21 ]; then
        JAVA_VERSION_OK=1
    fi
fi

if [ "$JAVA_VERSION_OK" -ne 1 ]; then
    warn "JDK 21+ not detected on PATH."
    warn "qkt requires a JDK 21 runtime. Install Temurin from https://adoptium.net/"
    warn "Continuing installation, but \`qkt --version\` will fail until JDK 21 is available."
fi

# ─────────────────────────────── resolve version ──────────────────────── #
if [ "$VERSION" = "latest" ]; then
    say "Resolving latest qkt release from $REPO ..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep -oE '"tag_name":\s*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"/\1/')
    [ -n "$VERSION" ] || die "could not determine latest release tag (network? rate-limit?)"
    ok "Latest release: $VERSION"
else
    say "Installing pinned version: $VERSION"
fi

# Strip leading 'v' for the tar name (release artifacts are qkt-X.Y.Z.tar)
VERSION_NUM="${VERSION#v}"
TARBALL_NAME="qkt-${VERSION_NUM}.tar"
TARBALL_URL="https://github.com/${REPO}/releases/download/${VERSION}/${TARBALL_NAME}"

# ─────────────────────────────── download ─────────────────────────────── #
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

say "Downloading $TARBALL_NAME"
if ! curl -fSL --progress-bar -o "$TMP_DIR/$TARBALL_NAME" "$TARBALL_URL"; then
    die "download failed: $TARBALL_URL"
fi

# ─────────────────────────────── extract ──────────────────────────────── #
say "Extracting to $PREFIX"
mkdir -p "$PREFIX"
# Wipe the prefix first so old files don't linger across versions.
# Keep the user's strategies/ dir if they put one in PREFIX (unusual but possible).
rm -rf "$PREFIX/bin" "$PREFIX/lib"
tar -xf "$TMP_DIR/$TARBALL_NAME" -C "$PREFIX" --strip-components=1
[ -x "$PREFIX/bin/qkt" ] || die "tarball did not contain bin/qkt — release artifact is broken"

# ─────────────────────────────── symlink ──────────────────────────────── #
mkdir -p "$BIN_DIR"
ln -sf "$PREFIX/bin/qkt" "$BIN_DIR/qkt"
ok "Symlinked $BIN_DIR/qkt → $PREFIX/bin/qkt"

# ─────────────────────────────── verify ───────────────────────────────── #
if "$BIN_DIR/qkt" --version >/dev/null 2>&1; then
    INSTALLED_VERSION=$("$BIN_DIR/qkt" --version 2>&1 | head -1)
    ok "Installed: $INSTALLED_VERSION"
else
    warn "qkt binary present but \`--version\` failed (probably JDK 21 not on PATH)."
fi

# ─────────────────────────────── PATH advice ──────────────────────────── #
case ":$PATH:" in
    *":$BIN_DIR:"*) ok "$BIN_DIR is already on your PATH" ;;
    *)
        printf '\n'
        warn "$BIN_DIR is NOT on your PATH."
        warn "Add this to your shell rc (~/.bashrc, ~/.zshrc, etc.):"
        printf '\n    export PATH="%s:$PATH"\n\n' "$BIN_DIR"
        ;;
esac

printf '\n'
ok "qkt installed. Try:"
printf '    qkt --version\n'
printf '    qkt --help\n'
printf '\nDocs: https://elitekaycy.github.io/qkt/\n\n'
