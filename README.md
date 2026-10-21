# Office Room Booking

> Workshop: this project is built step by step on git branches (`raw`, `step1-…` … `step5-…`), see [WORKSHOP.md](WORKSHOP.md).

Room booking service for the office: a calendar of meeting rooms where signed-in users can create, move, edit and cancel
their bookings.

| Part           | Stack                                                                    |
|----------------|--------------------------------------------------------------------------|
| `auth-service` | Java 25, Spring Boot 4.1, Spring Security, issues RS256 JWTs, own DB      |
| `backend`      | Java 25, Spring Boot 4.1, Spring Data JPA, Flyway, Spring AI 2.0, JWT resource server |
| databases      | PostgreSQL 18 (`auth-db` users, `postgres` rooms/bookings, `chat-db` chat history) |
| `chat-service` | Java 25, Spring Boot 4.1, Spring AI 2.0 (Google GenAI / Gemini), JWT resource server |
| `frontend`     | React 19, TypeScript, Vite, FullCalendar (booking UI with built-in chat panel) |
| `chat-ui`      | React 19, TypeScript, Vite (standalone full-page AI chat)                 |

```
browser ──► chat-ui  (nginx :3001) ──► /api/auth/**, /api/chat** (same services as below)

browser ──► frontend (nginx :3000) ──► /api/auth/** ──► auth-service :8081 ──► auth-db
                                   ├─► /api/chat**  ──► chat-service :8082 ──► Gemini API, chat-db
                                   └─► /api/**      ──► backend :8080      ──► postgres

backend and chat-service verify JWTs with auth-service /.well-known/jwks.json
```

## AI assistant (chat-service)

> On the workshop branches the assistant is built up step by step: on `raw` chat-service only echoes the message,
> the features described below are complete from `step6-timeouts-and-errors` on (see [WORKSHOP.md](WORKSHOP.md)).

The chat is available in two places, both talking to the same `chat-service` (so the conversation is the same):

- the "AI Assistant" tab on the right edge of the booking calendar opens a chat panel that slides in from the right;
- `chat-ui` (http://localhost:3001) is a standalone full-page chat that can run without the booking services.

1. Get a Gemini API key at https://aistudio.google.com/apikey.
2. Put it into `.env` in the project root (template: `.env.example`):
   ```
   GEMINI_API_KEY=your-key
   GEMINI_MODEL=gemini-3.5-flash-lite
   ```
3. `docker compose up -d chat-service` (re-creates the container with the new key).

Until the key is set, the chat answers with "Gemini API key is not configured". Configuration lives in
`chat-service/src/main/resources/application.properties` (`spring.ai.google.genai.*`).

- Answers are streamed as server-sent events (`event: chunk` / `done` / `error`, data is `{"text": "..."}`).
- Each user has one conversation (keyed by the user id from the JWT). History is stored in its own PostgreSQL
  (`chat-db`, Spring AI JDBC chat memory, table `SPRING_AI_CHAT_MEMORY`), so it survives restarts; the last 30 messages
  are kept and sent to the model as context.
- Each Gemini call is limited to `chat.gemini.timeout` (90s); the Google SDK default is no timeout at all. Rate limit
  (429), overload (503) and timeout errors are shown to the user as readable messages.
- The assistant does not read or change bookings yet; that is planned via the MCP server of the booking backend.

| Method | Path                | Description                                                  |
|--------|---------------------|--------------------------------------------------------------|
| POST   | `/api/chat`         | `{message, timeZone}` → `text/event-stream` with the answer   |
| GET    | `/api/chat/history` | Messages of the current conversation                         |
| DELETE | `/api/chat/history` | Start a new conversation                                     |

## Authentication

- `auth-service` stores users (BCrypt password hashes) and signs JWTs with an RSA key pair. The key pair is generated on
  first start and stored in `auth-db`, so tokens survive restarts.
- The public key is published at `GET /.well-known/jwks.json`. The booking `backend` validates tokens (signature, expiry,
  issuer) against it and never needs the private key or the user database.
- Token claims: `sub` = user id, `email`, `name`, `iss` = `room-booking-auth`; lifetime is `JWT_TTL` (default 8h).
- The booking organizer is always the authenticated user (taken from the token, not from the request body).
  Only the organizer can update or delete their booking (`403` otherwise); everyone can see all bookings.
- All `/api/**` endpoints except register/login require `Authorization: Bearer <token>`.

## Run with Docker

Create `.env` from the template first: `cp .env.example .env` (and put your Gemini API key into it).


There are two run modes, implemented as Docker Compose profiles:

| Mode   | Command                                  | Services                                                              |
|--------|------------------------------------------|-----------------------------------------------------------------------|
| `full` | `docker compose up -d --build`           | everything: booking UI + backend, auth, AI chat, standalone chat UI   |
| `chat` | `docker compose --profile chat up -d --build` | only the AI chat: `chat-ui`, `chat-service`, `auth-service`, `chat-db`, `auth-db` |

`full` is the default (`COMPOSE_PROFILES=full` in `.env`). To switch modes, stop the current one first with
`docker compose down` (it stops everything, add `-v` to also wipe the databases), then start the other one.
Data is kept in volumes, so accounts and chat history are shared between the modes.

| URL                                   | Mode         | What                                   |
|---------------------------------------|--------------|----------------------------------------|
| http://localhost:3000                 | full         | Booking UI (calendar + chat panel)     |
| http://localhost:3001                 | full, chat   | Standalone AI chat                     |
| http://localhost:8080/api             | full         | Booking API                            |
| http://localhost:8081/api/auth        | full, chat   | Auth API                               |
| http://localhost:8082/api/chat        | full, chat   | Chat API                               |

Register an account on the first screen of either UI; the same account works in both. Five demo rooms are seeded by
the Flyway migration. Health endpoints: `/actuator/health` on ports 8080, 8081, 8082.

## Using the UI

- Register or sign in. The session is kept in the browser until the token expires or you sign out.
- Select a time range in the week/day view (or a day in month view) to create a booking.
- Click a booking to edit or delete it. Bookings of other people open read-only and are shown faded.
- Drag your booking to move it, drag its bottom edge to change its duration.
- Toggle rooms or "Only my bookings" in the sidebar to filter the calendar.

## REST API

### Auth service

| Method | Path                     | Auth | Description                                              |
|--------|--------------------------|------|----------------------------------------------------------|
| POST   | `/api/auth/register`     | no   | `{email, fullName, password}` → token + user              |
| POST   | `/api/auth/login`        | no   | `{email, password}` → token + user                       |
| GET    | `/api/auth/me`           | yes  | Current user                                             |
| GET    | `/.well-known/jwks.json` | no   | Public signing keys                                      |

Login/register response:

```json
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresAt": "2026-10-20T19:00:00Z",
  "user": { "id": 1, "email": "ivan@example.com", "fullName": "Ivan" } }
```

### Booking service (all endpoints require a token)

| Method | Path                                         | Description                                  |
|--------|----------------------------------------------|----------------------------------------------|
| GET    | `/api/rooms`                                 | List rooms                                   |
| GET    | `/api/rooms/{id}`                            | Get room                                     |
| POST   | `/api/rooms`                                 | Create room                                  |
| PUT    | `/api/rooms/{id}`                            | Update room                                  |
| DELETE | `/api/rooms/{id}`                            | Delete room (and its bookings)               |
| GET    | `/api/bookings?from=&to=[&roomId=]`          | Bookings overlapping `[from, to)` (ISO-8601) |
| GET    | `/api/bookings/{id}`                         | Get booking                                  |
| POST   | `/api/bookings`                              | Create booking                               |
| PUT    | `/api/bookings/{id}`                         | Update / reschedule booking (organizer only) |
| DELETE | `/api/bookings/{id}`                         | Delete booking (organizer only)              |

Booking payload:

```json
{
  "roomId": 1,
  "title": "Sprint planning",
  "description": "optional",
  "startTime": "2026-10-21T09:00:00Z",
  "endTime": "2026-10-21T10:00:00Z"
}
```

The response additionally contains `organizerId`, `organizerName` and `organizerEmail` of the user who created it.
Bookings created before authentication was added have `organizerId: null` and are read-only.

Errors are returned as RFC 9457 problem details. Overlapping bookings for the same room return `409 Conflict`;
this is checked in the service and also enforced by a PostgreSQL exclusion constraint, so concurrent requests
cannot double-book a room.

## Local development

```bash
docker compose up -d postgres auth-db chat-db
cd auth-service && ./mvnw spring-boot:run     # http://localhost:8081
cd chat-service && GEMINI_API_KEY=... ./mvnw spring-boot:run   # http://localhost:8082
cd backend && ./mvnw spring-boot:run          # http://localhost:8080
cd frontend && npm install && npm run dev     # http://localhost:5173, proxies /api/auth to :8081, /api/chat to :8082, /api to :8080
cd chat-ui && npm install && npm run dev      # http://localhost:5174, proxies /api/auth to :8081, /api/chat to :8082
```

Run tests for all Java services with `./mvnw verify` from the repository root (root `pom.xml` aggregates `backend`, `auth-service`, `chat-service`; open the root folder in IntelliJ IDEA to import all of them). The Maven wrappers are pinned to Maven 3.9.x: IntelliJ IDEA 2025.2 cannot import projects with Maven 3.10.

## MCP (future)

`spring-ai-starter-mcp-server-webmvc` is already on the classpath so the booking operations can later be exposed
as MCP tools. The MCP server is disabled for now via `spring.ai.mcp.server.enabled=false` in
`backend/src/main/resources/application.properties`.
