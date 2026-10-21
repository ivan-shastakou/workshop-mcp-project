# Spring AI workshop

This repository is used to demonstrate Spring AI step by step. Every step is a git branch; each branch builds and
runs on its own, so you can check out any step and see the intermediate result.

| Branch | What it adds | Main files |
|---|---|---|
| `raw` | Starting point: booking app (calendar UI + REST backend), auth-service (JWT), chat UIs and an **empty chat-service** that has all Spring AI dependencies but no AI yet (it only echoes your message) | `chat-service/pom.xml`, `chat-service/.../chat/ChatService.java` |
| `step1-spring-ai-chat` | A working AI chat with Gemini: configuration (`spring.ai.google.genai.*`), the `ChatClient`, streaming answers token by token (server-sent events), conversation memory stored in PostgreSQL (`MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository`), a timeout for Gemini calls and readable error messages | `application.properties`, `ChatConfig.java`, `ChatService.java`, `ChatController.java`, `.env.example` |
| `step2-system-prompt-and-mcp` | System prompt with template parameters (user name, date, time zone) and the MCP client: users connect MCP servers from the chat UI (🔌 MCP), their tools are offered to the model | `ChatConfig.java`, `ChatService.java`, `chat-service/.../mcp/*`, `McpPanel.tsx` |
| `step3-chat-logging` | Logging to see how the chat works: a custom advisor logs every model round (prompt size, tool calls, tokens, timings), every MCP tool call and every chat turn | `ChatLoggingAdvisor.java`, `LoggingToolCallback.java`, `application.properties` |
| `step4-booking-mcp` | The booking service as an MCP server, still **without tools**: endpoint `/mcp`, the user from the JWT in the MCP context, and "Send my login token" in 🔌 MCP. Here we write the first `@McpTool` methods live | `backend/.../mcp/McpServerConfig.java`, `McpServerService.java`, `UserTokenRelay.java`, `McpPanel.tsx` |
| `step5-booking-mcp-tools` | The finished booking tools (`@McpTool`): rooms, free rooms, bookings, create/change/cancel, so the chat can book in the user's name | `backend/.../mcp/BookingMcpTools.java` |
| `master` | Latest state of the project (same as the last step) | |

See what a step changes:

```bash
git diff raw step1-spring-ai-chat
git diff step1-spring-ai-chat step2-system-prompt-and-mcp -- chat-service
```

## Running a step

```bash
git checkout step1-spring-ai-chat
cp .env.example .env            # once; then put your Gemini API key into .env (GEMINI_API_KEY)
docker compose up -d --build    # rebuild after every checkout
```

- Booking UI: http://localhost:3000, standalone chat: http://localhost:3001 (register an account first).
- Only the chat: `docker compose down && docker compose --profile chat up -d --build`.
- Gemini API key: https://aistudio.google.com/apikey. The free tier allows only a few requests per minute and per
  day per model; if you hit the limit, switch `GEMINI_MODEL` in `.env` and run `docker compose up -d chat-service`.
- Switching between branches keeps the databases. Starting completely fresh: `docker compose down -v`.

## What to check in each step

1. **raw** - register, create/move/delete bookings; the chat answers "AI is not connected yet" with an echo.
2. **step1** - the chat answers with Gemini, word by word ("Write an agenda for a sprint planning"). Ask a follow-up
   ("Make it shorter"): the model remembers the conversation, also after reloading the page and after
   `docker compose restart chat-service` (table `spring_ai_chat_memory` in `chat-db`); "New chat" clears it. Ask
   "Who am I and what day is it?": the model doesn't know yet. With an invalid key or exhausted quota the chat shows
   a readable message; without a key you get "Gemini API key is not configured".
3. **step2** - ask "Who am I and what day is it today?" again: now the model knows your name and the date. Then click
   🔌 MCP, add `https://mcp.deepwiki.com/mcp` (name `deepwiki`), test and save it, and ask "Using deepwiki, what is
   the GitHub repository spring-projects/spring-ai about?".
4. **step3** - run `docker compose logs -f chat-service | grep -E "Chat turn|Model round|MCP tool"` and ask the deepwiki
   question again: you see round 1 asking for a tool, the MCP call, round 2 answering, with tokens and timings. Set
   `CHAT_LOG_LEVEL=DEBUG` in `.env` and run `docker compose up -d chat-service` to also see the full prompt (system
   prompt, history, tool results).
5. **step4** - click 🔌 MCP, add name `booking`, URL `http://backend:8080/mcp`, check **Send my login token**, test
   and save: "connected · 0 tools". Write a first tool (e.g. `list_rooms`) in a `@Component` with `@McpTool`,
   run `docker compose up -d --build backend`, click ↻ Reconnect and ask the chat about rooms.
6. **step5** - with all booking tools, ask "Which rooms are free tomorrow from 10 to 11?", then "Book Elbrus for a
   retro" and confirm: the booking appears in the calendar with you as the organizer.
   Details: [BOOKING-MCP.md](BOOKING-MCP.md).
