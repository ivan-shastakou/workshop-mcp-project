# CLAUDE.md

Office room booking app used for a **Spring AI workshop**. The AI chat is built step by step on git branches;
every branch must build and run on its own. Attendee-facing guide: `WORKSHOP.md`; full feature/API docs: `README.md`.

## Services

| Service | Stack | Port (host) | Database |
|---|---|---|---|
| `backend` | Booking REST API: Java 25, Spring Boot 4.1.1, JPA, Flyway, JWT resource server | 8080 | `postgres` :5432 |
| `auth-service` | Register/login, issues RS256 JWTs, JWKS at `/.well-known/jwks.json` | 8081 | `auth-db` :5433 |
| `chat-service` | AI chat: Spring AI 2.0.1, Gemini (Google GenAI), chat memory, MCP client | 8082 | `chat-db` :5434 |
| `frontend` | Booking UI (React 19 + Vite + FullCalendar) with chat panel, nginx | 3000 | - |
| `chat-ui` | Standalone chat UI (React 19 + Vite), nginx | 3001 | - |

- nginx in `frontend`/`chat-ui` proxies `/api/auth` → auth-service, `/api/chat` (and `/api/mcp` on step2+) →
  chat-service, `/api/*` → backend (booking UI only), and on step4+ `/mcp` → the backend's MCP server (booking UI
  only). It re-resolves upstreams through Docker DNS (`resolver 127.0.0.11`), so re-created containers don't cause
  502s.
- backend and chat-service only verify JWTs (JWKS from auth-service); they never see passwords.
- Docker Compose profiles: `full` (everything, default via `COMPOSE_PROFILES=full` in `.env`) and `chat`
  (chat-ui, chat-service, auth-service, chat-db, auth-db).

## Workshop branches

History is linear: `raw` → `step1` → … → `step5` = `master`. Each step is **one commit**, so
`git diff <previous> <step>` shows exactly what the step teaches. step1 and step3 only touch `chat-service`
(plus `.env.example`/`docker-compose.yml`); step2 also changes both UIs; step4 changes backend, chat-service, both UIs
and the booking UI's nginx; step5 only adds `BookingMcpTools` (+ test) to the backend.

| Branch | Adds | Rebuild after checkout | Check |
|---|---|---|---|
| `raw` | Everything except AI: chat-service has all Spring AI deps but `spring.ai.model.chat=none`, `ChatService` echoes | all: `docker compose up -d --build` | chat answers "AI is not connected yet. You said: …"; `/api/mcp` is 404 |
| `step1-spring-ai-chat` | `spring.ai.google.genai.*` props, `ChatClient` with `stream().content()` → SSE chunks, `MessageChatMemoryAdvisor` + `MessageWindowChatMemory` with auto-configured `JdbcChatMemoryRepository` (`initialize-schema=always`), history/clear endpoints, own Google GenAI `Client` with timeout (`chat.gemini.timeout`), errors → SSE `error` event with readable text. **No system prompt yet** | all: `docker compose up -d --build` | streamed answers; follow-ups work and survive `docker compose restart chat-service`; placeholder key → 503 "Gemini API key is not configured", invalid key → readable error |
| `step2-system-prompt-and-mcp` | `defaultSystem(...)` with `{userName}`, `{userEmail}`, `{now}`, `{timeZone}` params; runtime MCP clients managed from the UI (🔌 MCP), table `mcp_server` (Flyway V1), encrypted headers, SSRF guard | all: `docker compose up -d --build` | "Who am I and what day is it?"; add `https://mcp.deepwiki.com/mcp`, ask about a GitHub repo |
| `step3-chat-logging` | `ChatLoggingAdvisor` (after Spring AI's `ToolCallingAdvisor`, so once per model round), turn stats in `ChatService`, `LoggingToolCallback` around MCP tools, MCP connect/settings logs, `CHAT_LOG_LEVEL` | all: `docker compose up -d --build` | `docker compose logs -f chat-service`: "Chat turn", "Model round", "MCP tool" lines; `CHAT_LOG_LEVEL=DEBUG` adds full prompts |
| `step4-booking-mcp` | backend MCP server **without tools** (`spring.ai.mcp.server.*`, `McpServerConfig` puts the user into the MCP transport context), nginx `/mcp`; chat-service option "Send my login token" (`forward_user_token`, Flyway V2, `UserTokenRelay`), only for `MCP_ALLOWED_HOSTS` (default `backend`). Starting point for writing `@McpTool` methods live | all: `docker compose up -d --build` | 🔌 MCP → add `booking`, `http://backend:8080/mcp`, Send my login token → "connected · 0 tools"; `POST /mcp` without token → 401 |
| `step5-booking-mcp-tools` (= `master`) | `BookingMcpTools`: 7 `@McpTool` tools (rooms, free rooms, bookings, my bookings, create/update/cancel booking) reusing `RoomService`/`BookingService`, user from the transport context | `docker compose up -d --build backend` | 🔌 MCP → booking → Reconnect → 7 tools; ask the chat to book a room and confirm |

## Running

To run a step, use the **`/run-step` skill** (`.claude/skills/run-step/`):
`/run-step step3`, `/run-step master --chat-only`. It checks out the branch, creates `.env` if missing, rebuilds with
`docker compose up -d --build`, and waits for services to become ready without creating accounts or calling Gemini
(`--fresh` wipes chat-db). The script can also be run directly:
`.claude/skills/run-step/scripts/run-step.sh <branch> [--chat-only] [--fresh]`.
On Windows, use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\.claude\skills\run-step\scripts\run-step-win.ps1 <branch> [--chat-only] [--fresh]`.
The skill chooses PowerShell on Windows and Bash on macOS/Linux (including WSL).

Manual equivalent: `cp .env.example .env` once (without `.env` no Compose profile is active and nothing starts;
set `GEMINI_API_KEY` for step1+), then `git checkout <branch> && docker compose up -d --build` (always rebuild after
a checkout). Chat only: `docker compose down && docker compose --profile chat up -d --build`. Stop:
`docker compose down` (keeps data); `docker compose down -v` deletes all databases.

- UIs: http://localhost:3000 (booking) and http://localhost:3001 (chat). Register an account first.
- Hard-refresh the browser after switching branches (cached JS may still show e.g. the MCP button).
- Switching branches in **any direction** keeps the databases working: booking/auth are identical on all branches;
  chat-service has `spring.flyway.enabled=false` in step1 (step2 enables Flyway with `baseline-on-migrate`), so
  step1 never fails validation on a chat-db that already has later migrations; step2/step3 accept a schema newer than
  their migrations (step4's V2). Leftover tables are unused.
  Reset only the chat data: `docker compose rm -sf chat-db chat-service && docker volume rm workshop-mcp-project_chatdata`.
- `.env` holds real secrets and is git-ignored; never commit it. Only `.env.example` is versioned.

## Build and test

```bash
./mvnw verify                      # root aggregator pom: backend, auth-service, chat-service (unit tests, no Docker)
(cd chat-service && ./mvnw test)   # one service
(cd frontend && npm install && npm run build)   # same for chat-ui; tsc + vite
```

Local dev without Docker for the Java/UI apps: `docker compose up -d postgres auth-db chat-db`, then
`./mvnw spring-boot:run` in each service and `npm run dev` in `frontend` (:5173) / `chat-ui` (:5174); Vite proxies
`/api/*` to the local services.

## Conventions and gotchas

- Maven wrappers are pinned to **Maven 3.9.x** on purpose: IntelliJ IDEA 2025.2 cannot import Maven 3.10 projects.
- Java style: constructor injection, records for DTOs (`*Dtos` holder classes), `@Transactional(readOnly = true)`
  services, errors as RFC 9457 `ProblemDetail`. Comments explain *why*, not *what*.
- Gemini free tier is tiny (≈20 requests/day per model). Avoid unnecessary real chat calls when testing; prefer
  health/history/MCP endpoints or an invalid key (does not consume quota). Model is set via `GEMINI_MODEL`.
- The Google GenAI SDK has no timeout by default; step1+ sets one through the custom `Client` bean in `ChatConfig`.
- MCP: only public `https://` servers unless the host is in `MCP_ALLOWED_HOSTS`; stdio servers are intentionally
  not supported. The booking backend's own MCP server is disabled (`spring.ai.mcp.server.enabled=false`) until step4;
  users add it from the UI. The user's login token is only ever sent to `MCP_ALLOWED_HOSTS` ("Send my login token").
- `frontend/src/McpPanel.tsx` and `chat-ui/src/McpPanel.tsx` are identical copies; change both.

## Changing workshop steps

A change that belongs to step N must also be present in every later step and in `master`, and each step must stay a
single commit:

1. Make the change on the step N branch as a fixup, then replay the later steps on top of it (or rebuild the commit
   chain from the trees), keeping `master` == the last step (`step5-booking-mcp-tools`).
2. Verify every branch: `git diff <prev> <step>` still contains only that step's topic, `./mvnw verify` passes, and
   `docker compose up -d --build` starts (chat-service health on :8082).
3. Changes that are not workshop content (docs, infra) go into `raw` so they exist on all branches.
