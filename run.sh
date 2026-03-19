#!/usr/bin/env bash
# proxycli — CLI-to-OpenAI API proxy
set -euo pipefail
cd "$(dirname "$0")"

# Load .env if present
if [ -f .env ]; then
  set -a; source .env; set +a
fi

CMD="${1:-start}"
shift 2>/dev/null || true

ARCH=$(uname -m)
BINARY="target/proxycli-${ARCH}"

# Defaults (compatible with Python wrapper env vars)
export PORT="${PORT:-28000}"
export CLAUDE_CWD="${CLAUDE_CWD:-$HOME/org}"
export DEFAULT_MODEL="${DEFAULT_MODEL:-claude-sonnet-4-6}"
export CLAUDE_INDEPENDENT_MODE="${CLAUDE_INDEPENDENT_MODE:-true}"
export CLAUDE_MINIMAL_TOOLS="${CLAUDE_MINIMAL_TOOLS:-true}"

# Model aliases
resolve_model() {
  case "$1" in
    opus)   echo "claude-opus-4-6" ;;
    sonnet) echo "claude-sonnet-4-6" ;;
    haiku)  echo "claude-haiku-4-5-20251001" ;;
    *)      echo "$1" ;;
  esac
}

case "$CMD" in
  ## --- Server ---
  start)
    while [[ $# -gt 0 ]]; do
      case $1 in
        --model|-m) export DEFAULT_MODEL="$(resolve_model "$2")"; shift 2 ;;
        --port|-p)  export PORT="$2"; shift 2 ;;
        --cwd|-c)   export CLAUDE_CWD="$2"; shift 2 ;;
        *) shift ;;
      esac
    done

    echo ""
    echo "══════════════════════════════════════"
    echo "  proxycli — CLI-to-OpenAI API Proxy"
    echo "══════════════════════════════════════"
    echo "  URL:    http://localhost:$PORT"
    echo "  CWD:    $CLAUDE_CWD"
    echo "  Model:  $DEFAULT_MODEL"
    [ "$CLAUDE_INDEPENDENT_MODE" = "true" ] && echo "  Mode:   ⚡ Independent (MCP off)"
    [ "$CLAUDE_MINIMAL_TOOLS" = "true" ] && echo "  Tools:  🔧 Minimal (8 core tools)"
    if [ -n "${PROXYCLI_SKILLS:-}" ]; then
      echo "  Skills: ${PROXYCLI_SKILLS}"
    else
      echo "  Skills: (none)"
    fi
    if [ -f "${BINARY}" ]; then
      echo "  Binary: ${BINARY}"
    else
      echo "  Binary: JVM (clj -M:run)"
    fi
    echo "══════════════════════════════════════"
    echo ""
    read -rp "Press Enter to start (Ctrl+C to cancel)... "

    # Prefer native binary, fall back to JVM
    if [ -f "${BINARY}" ]; then
      exec "${BINARY}"
    else
      exec clj -M:run
    fi
    ;;

  ## --- Build ---
  build)
    OUTPUT=""
    FORCE=false
    ARGS=("$@")
    i=0
    while [ $i -lt ${#ARGS[@]} ]; do
      case "${ARGS[$i]}" in
        --output) i=$((i+1)); OUTPUT="${ARGS[$i]:-}" ;;
        --force)  FORCE=true ;;
        *)        [ -z "$OUTPUT" ] && OUTPUT="${ARGS[$i]}" ;;
      esac
      i=$((i+1))
    done

    if [ "$FORCE" = false ] && [ -f "${BINARY}" ]; then
      echo "✅ Cached: ${BINARY}"
    else
      NI_ARGS="--initialize-at-build-time --no-fallback -H:+ReportExceptionStackTraces"
      NI_ARGS="$NI_ARGS -H:Name=proxycli-${ARCH} -jar target/proxycli.jar -o ${BINARY}"

      if command -v native-image &>/dev/null; then
        echo "=== GraalVM native-image build (${ARCH}) ==="
        clj -T:build uber
        # shellcheck disable=SC2086
        native-image $NI_ARGS
      else
        FHS_BIN="$(nix build .#fhs --no-link --print-out-paths 2>/dev/null)/bin/proxycli-build"
        if [ -x "$FHS_BIN" ]; then
          echo "=== FHS → native-image build (${ARCH}) ==="
          "$FHS_BIN" -c "cd $(pwd) && clj -T:build uber && native-image $NI_ARGS"
        else
          echo "=== nix develop → native-image build (${ARCH}) ==="
          nix develop --command bash -c "cd $(pwd) && clj -T:build uber && native-image $NI_ARGS"
        fi
      fi

      if command -v patchelf &>/dev/null; then
        INTERP="/lib64/ld-linux-x86-64.so.2"
        [ "$ARCH" = "aarch64" ] && INTERP="/lib/ld-linux-aarch64.so.1"
        patchelf --set-interpreter "$INTERP" "${BINARY}" 2>/dev/null || true
        patchelf --remove-rpath "${BINARY}" 2>/dev/null || true
      fi
      echo "  ✅ ${BINARY} ($(du -h "${BINARY}" | cut -f1))"
    fi

    [ -n "$OUTPUT" ] && cp "${BINARY}" "$OUTPUT" && echo "→ $OUTPUT"
    ;;

  jar-build)
    echo "=== JVM uberjar build ==="
    clj -T:build uber
    echo "✅ target/proxycli.jar"
    echo "Run: java -jar target/proxycli.jar"
    ;;

  test)
    echo "=== Tests ==="
    clj -M:test
    ;;
  repl)
    clj
    ;;
  clean)
    rm -rf .cpcache/ target/
    echo "✅ cleaned"
    ;;
  help|*)
    echo "proxycli — CLI-to-OpenAI API proxy (Clojure/GraalVM)"
    echo ""
    echo "Usage: ./run.sh [command] [options]"
    echo ""
    echo "Commands:"
    echo "  start [options]       Start server (default)"
    echo "  build [--output PATH] GraalVM native binary"
    echo "  jar-build             JVM uberjar"
    echo "  test                  Run tests"
    echo "  repl / clean / help"
    echo ""
    echo "Start Options:"
    echo "  --model, -m MODEL  opus, sonnet, haiku (default: sonnet)"
    echo "  --port, -p PORT    Port (default: 8000)"
    echo "  --cwd, -c PATH     Working directory (default: ~/org)"
    echo ""
    echo "Environment:"
    echo "  PORT, CLAUDE_CWD, DEFAULT_MODEL, CLAUDE_CLI_PATH"
    echo "  CLAUDE_INDEPENDENT_MODE  Disable MCP (default: true)"
    echo "  CLAUDE_MINIMAL_TOOLS     8 core tools only (default: true)"
    echo "  PROXYCLI_SKILLS          Selective skill injection (e.g. botlog,denotecli)"
    ;;
esac
