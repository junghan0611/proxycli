# Agent Instructions

## Documentation & Commit Language

**IMPORTANT:** All documentation, commit messages, and code comments must be written in **English**.
- Commit messages: English only
- Documentation (*.md): English only
- Code comments: English only
- Conversation with user: Korean is OK

## Project Purpose

proxycli is a CLI-to-OpenAI API proxy written in Clojure, compiled to a GraalVM native binary.

### Core Problem

When calling AI from Emacs (gptel), plain API endpoints (OpenRouter, CLIProxyAPI) have no tool support.
You can't read/write files, run commands, or use skills. To get tools, you need Claude Code TUI — but
you can't always switch to a terminal.

proxycli solves this by wrapping Claude Code CLI as an OpenAI-compatible API, giving gptel clients
**tools (Read, Write, Edit, Bash) + skills** without leaving Emacs.

### Architecture

```
gptel (Emacs)  →  proxycli (localhost:28000)  →  claude CLI
  OpenAI API       Clojure / 39MB native         Read/Write/Edit/Bash
                   72ms startup                   + selective skills
```

### Key Design Decisions

1. **No Python SDK dependency** — Call `claude` CLI directly via subprocess
2. **Clean mode by default** — Block MCP/plugins/slash-commands (13.5K tokens)
3. **Selective skill injection** — Only load skills declared in `PROXYCLI_SKILLS`
4. **GraalVM native binary** — 72ms startup, 39MB single file
5. **Minimal API surface** — Only 2 endpoints that gptel actually uses

### Origin

Rewrite of [claude-code-openai-wrapper](https://github.com/aaronlippold/claude-code-openai-wrapper)
(Python, 4,828 lines → Clojure 375 lines, 92% reduction).

Fork history: [junghan0611/claude-code-openai-wrapper](https://github.com/junghan0611/claude-code-openai-wrapper) (ko branch)

## Development

```bash
./run.sh start          # JVM server
./run.sh test           # Unit tests
./run.sh build          # GraalVM native binary
nix develop             # Enter dev shell with GraalVM
```

## Code Structure

- `src/proxycli/core.clj` — Entry point, env var parsing
- `src/proxycli/claude.clj` — CLI execution, stream-json parsing, skill loading
- `src/proxycli/server.clj` — Ring HTTP server, SSE streaming, OpenAI format conversion
