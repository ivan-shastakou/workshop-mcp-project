---
name: run-step
description: Run the room booking workshop apps at a given git branch (raw, step1 … step5, master) with Docker Compose and wait for readiness. Use when asked to run, start, switch to or check a workshop step, the whole app, or only the AI chat.
argument-hint: "<raw|step1…step5|master> [--chat-only] [--fresh]"
---

# Run a workshop step

The workshop is a chain of branches (`raw` → `step1-…` → … → `step5-…` = `master`, see `WORKSHOP.md`).
Every branch runs with the same Docker Compose setup; this skill switches the branch, rebuilds and waits for readiness.
Requires Git and Docker Compose. macOS/Linux also require Bash and curl; Windows requires PowerShell 5.1 or newer.
The scripts only start services and check readiness; they do not call Gemini or create accounts.

## Steps

1. Resolve what the user asked for:
   - the branch: `raw`, `step1` … `step5` (short names work), `master`, or a full branch name;
     if none is given, use the currently checked-out branch with `--no-checkout`;
   - `--chat-only` if they want only the AI chat (no booking services);
   - `--fresh` only if they explicitly want to clear chat history / MCP servers (it deletes chat-db data).
2. Choose the script based on the execution environment's OS and run it from the repository root. Use the copy
   beside this skill (`scripts/`); the `.agents` and `.claude` copies are identical.
   On Windows, use PowerShell (not Bash):
   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\.claude\skills\run-step\scripts\run-step-win.ps1 <branch> [--chat-only] [--fresh]
   ```
   On macOS/Linux (including WSL), use Bash:
   ```bash
   .claude/skills/run-step/scripts/run-step.sh <branch> [--chat-only] [--fresh]
   ```
   Both scripts support `--no-checkout` when no branch switch is needed. Each checks out the branch,
   creates `.env` from `.env.example` if missing, runs `docker compose up -d --build`,
   and waits for the service health endpoints and UIs to respond.
   The first build of all images takes several minutes; use a long timeout (10+ minutes).
3. Untracked files are allowed; Git still refuses checkout if it would overwrite them. If the script stops because
   of **uncommitted changes to tracked files** or a checkout conflict, do not stash or discard files yourself:
   show them to the user and ask what to do.
4. Report to the user: branch, whether services became ready, the URLs, and what to try on this step in the UI
   (table below). If a check failed, show the relevant output and the likely cause from the table at the end.

## What to try on each step

| Branch | Try in the UI |
|---|---|
| `raw` | Book rooms at :3000; the chat answers "AI is not connected yet. You said: …" |
| `step1-spring-ai-chat` | Chat answers with Gemini word by word; follow-ups work and survive `docker compose restart chat-service`; errors (invalid key, quota) show as readable messages; it doesn't know who you are yet |
| `step2-system-prompt-and-mcp` | "Who am I and what day is it today?"; 🔌 MCP → add `https://mcp.deepwiki.com/mcp` (name `deepwiki`) → ask about a GitHub repository |
| `step3-chat-logging` | As step2, while watching `docker compose logs -f chat-service`: "Chat turn", "Model round" and "MCP tool" lines |
| `step4-booking-mcp` | 🔌 MCP → add `booking`, URL `http://backend:8080/mcp`, check **Send my login token** → "connected · 0 tools"; write `@McpTool` methods live, rebuild backend, ↻ Reconnect |
| `step5-booking-mcp-tools`, `master` | As step4, now with 7 tools → ask the chat to book a room and confirm |

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "GEMINI_API_KEY is not set" | Put a key from https://aistudio.google.com/apikey into `.env`, rerun with `--no-checkout` |
| Chat says "rate limit or quota exceeded" | Gemini free-tier quota used up: change `GEMINI_MODEL` in `.env` and rerun |
| A service does not become ready | The script prints its last log lines; port already in use → stop the other process or `docker compose down` |
| UI still shows the previous step (e.g. MCP button) | Browser cache: hard-refresh (Cmd+Shift+R) |
| Want everything from scratch | `docker compose down -v` (deletes all databases, accounts included), then run the step again |

Never commit `.env`: it contains real API keys.
