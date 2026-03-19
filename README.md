# proxycli

A CLI-to-OpenAI API proxy. **Clojure/GraalVM native binary** that turns Claude Code CLI into an OpenAI-compatible API endpoint with tool support.

## Why

There are many ways to call AI from Emacs:

| Method | Tool Use | File Access | Speed |
|--------|----------|-------------|-------|
| OpenRouter API | ❌ | ❌ | Fast |
| CLIProxyAPI (chat only) | ❌ | ❌ | Fast |
| Claude Code TUI | ✅ | ✅ | Manual |
| **proxycli** | **✅** | **✅** | **Fast** |

The problem: **when you need tools** (read/write files, run commands), plain API calls are useless. You'd have to open Claude Code TUI every time. But you can't always switch to a terminal — **sometimes you just want to call it from wherever you are.**

proxycli wraps Claude Code CLI as an OpenAI API, so clients like [gptel](https://github.com/karthink/gptel) get **tools (Read, Write, Edit, Bash) + skills** out of the box.

## Origin

This project started as a fork of [claude-code-openai-wrapper](https://github.com/aaronlippold/claude-code-openai-wrapper) (Python, 4,828 lines). While adding gptel compatibility, we discovered the core functionality could be replaced with 375 lines of Clojure. The minimal tool set for gptel (file access, SSE streaming, skill injection) was built from scratch during this process.

Thanks to the original Python wrapper for the inspiration.

- Fork history: [junghan0611/claude-code-openai-wrapper](https://github.com/junghan0611/claude-code-openai-wrapper) (ko branch)

## Architecture

```
gptel (Emacs)  →  proxycli (localhost:28000)  →  claude CLI
  OpenAI API       375 lines Clojure / 39MB       Read/Write/Edit/Bash
                   72ms startup                    + skills (botlog, denotecli...)
```

## Why Clojure

| | Python (original) | Clojure (proxycli) |
|---|---:|---:|
| Source code | 4,828 lines / 12 files | **375 lines / 3 files** |
| Dependencies | 10+ (SDK, FastAPI, ...) | **3** (ring, data.json) |
| Deployment | Python env required | **39MB single binary** |
| Startup | ~3s | **72ms** |

What the Python SDK (`claude-agent-sdk`) actually does internally:

```bash
claude --output-format stream-json --verbose --print "prompt"
```

One subprocess, stdout JSON stream parsing. **Call the CLI directly** — no SDK needed. GraalVM native-image eliminates JVM startup overhead.

## Quick Start

```bash
# Run with JVM
./run.sh start

# Or build native binary first
nix develop
./run.sh build
./target/proxycli-x86_64
```

### Configuration

Create `.env` in the project root (gitignored):

```bash
# .env
PROXYCLI_SKILLS=denotecli,botlog,bibcli
```

`run.sh` loads `.env` automatically on startup.

### Environment Variables

```bash
PORT=28000                         # Server port
CLAUDE_CWD=~/org                   # Working directory (file access scope)
CLAUDE_CLI_PATH=/path/to/claude    # Claude binary path (recommended for native)
DEFAULT_MODEL=claude-sonnet-4-6    # Default model
CLAUDE_INDEPENDENT_MODE=true       # Disable MCP/plugins (faster startup)
CLAUDE_MINIMAL_TOOLS=true          # 8 core tools only
PROXYCLI_SKILLS=botlog,denotecli   # Selective skill injection
```

## gptel Setup (Doom Emacs)

```elisp
(setq gptel-proxycli-backend
      (gptel-make-openai "Claude-Code"
        :host "localhost:28000"
        :endpoint "/v1/chat/completions"
        :protocol "http"
        :stream t
        :key "not-needed"
        :models '((claude-sonnet-4-6
                   :description "Sonnet 4.6 + tool-use (Read/Write/Bash)"
                   :capabilities (media tool-use)
                   :context-window 200
                   :input-cost 3
                   :output-cost 15)
                  (claude-opus-4-6
                   :description "Opus 4.6 + tool-use"
                   :capabilities (media tool-use)
                   :context-window 200
                   :input-cost 5
                   :output-cost 25))))
```

## API

Two endpoints that gptel uses:

```
POST /v1/chat/completions   # OpenAI-compatible (SSE streaming)
GET  /v1/models             # Available models
GET  /health                # Health check
```

### Tool Usage

```json
{
  "model": "claude-sonnet-4-6",
  "messages": [{"role": "user", "content": "Read README.md"}],
  "enable_tools": true,
  "stream": true
}
```

`enable_tools: true` → Read, Write, Edit, Bash, Glob, Grep, WebSearch, WebFetch

### Skill Injection

`PROXYCLI_SKILLS=botlog,denotecli` reads `~/.claude/skills/<name>/SKILL.md` and injects into system prompt. Only what you declare explicitly.

## Token Efficiency

| Mode | System Prompt Tokens |
|------|---:|
| Clean (no skills) | **13,546** |
| 3 skills injected | ~16,000 |
| Everything loaded (Python default) | 20,417 |

Clean mode makes Opus calls affordable.

## Logging

```
17:34:23 ════════════════════════════════════════
17:34:23 🔄 claude-sonnet-4-6 | stream
17:34:23    Human: Read README.md
17:34:28    ⏱️ first token: 4700ms
17:34:28    🔧 Read
17:34:29 ✅ 5259ms
```

## Build

```bash
nix develop              # GraalVM environment
./run.sh build           # → target/proxycli-x86_64 (~39MB)
```

Build environment managed via `flake.nix`. FHS support for Docker/non-NixOS compatible binaries.

## Project Structure

```
proxycli/
├── deps.edn          # 3 dependencies
├── build.clj         # Uberjar config
├── flake.nix         # NixOS + GraalVM
├── run.sh            # CLI entrypoint
├── src/proxycli/
│   ├── core.clj      # Entry point (30 lines)
│   ├── claude.clj    # CLI execution + stream-json parsing (160 lines)
│   └── server.clj    # Ring HTTP + SSE streaming (210 lines)
└── test/proxycli/
    ├── claude_test.clj
    └── server_test.clj
```

## Vision: General-Purpose CLI Proxy

Currently Claude Code CLI only, but the core is generic:

```clojure
;; Claude-specific: build-command function, ~20 lines
;; Generic "CLI → OpenAI API" pattern: ~350 lines
```

Future: EDN config files to proxy any CLI:

```edn
{:name "claude" :cli "claude" :args ["--output-format" "stream-json" "--print"]}
{:name "ollama" :cli "ollama" :args ["run" "--format" "json"]}
```

## License

MIT

## Credits

- [claude-code-openai-wrapper](https://github.com/aaronlippold/claude-code-openai-wrapper) — Original Python wrapper
- [gptel](https://github.com/karthink/gptel) — Emacs LLM client
- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) — Anthropic CLI
