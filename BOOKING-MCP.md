# Booking service as an MCP server (step4 and step5)

Branches `step4-booking-mcp` and `step5-booking-mcp-tools`. The booking backend exposes its functions as MCP tools, and the AI chat uses them after
the user adds the server in the 🔌 MCP panel. Diagram: [`architecture-booking-mcp.svg`](architecture-booking-mcp.svg)
(purple = what step4 and step5 add). See the code with `git diff step3-chat-logging step4-booking-mcp` and
`git diff step4-booking-mcp step5-booking-mcp-tools`.

- **step4** has everything except the tools: the MCP endpoint, the user in the MCP context, nginx `/mcp` and the
  chat's "Send my login token". The server connects with 0 tools, so the tools can be written live.
- **step5** adds the finished `BookingMcpTools` (7 tools) and its test.

## 1. What changes

- **backend** gets an MCP endpoint `/mcp` next to its REST API: 7 tools (rooms, free rooms, bookings, my bookings,
  create, update, cancel). They call the existing `RoomService`/`BookingService`, so the rules stay in one place:
  no overlaps, and only the organizer can change or cancel a booking.
- **chat-service** gets nothing booking-specific. The user adds the server in 🔌 MCP like any other one, with the
  URL `http://backend:8080/mcp` and the new option **Send my login token**. The tools then show up in the chat as
  `booking__create_booking` and so on.
- **Bookings made through the chat belong to the user who is chatting**: chat-service calls the MCP server with that
  user's own JWT (token relay), so the backend sees the same user as in the calendar.
- **External MCP clients** (MCP Inspector, Claude Desktop, IDEs) can use the same endpoint at
  `http://localhost:3000/mcp` with a user's JWT.

## 2. How a chat request flows

"Book Elbrus on 2026-12-01 from 12:00 to 12:30":

1. The browser sends `POST /api/chat` with the user's JWT. `UserTokenRelay` (a Spring MVC interceptor) remembers
   the token for this user.
2. chat-service collects the tools of the user's enabled MCP servers, including `booking__*`, and sends them to
   Gemini with the prompt.
3. Gemini answers with tool calls: `booking__list_rooms`, `booking__find_free_rooms`. Spring AI's
   `ToolCallingAdvisor` executes them through the MCP client: `POST http://backend:8080/mcp` with
   `Authorization: Bearer <user's JWT>`.
4. The backend's Spring Security checks the JWT (JWKS from auth-service, as for REST). The tool gets the user from
   the MCP transport context and calls the service.
5. The model shows the details and asks for confirmation (system prompt rule). After "yes" it calls
   `booking__create_booking`, and the booking appears in the calendar with the user as organizer.

With `docker compose logs -f chat-service` (step3 logging) you see every round:

```
Model round 1 ← 1 tool call(s): [booking__list_rooms {}]
MCP tool call: server=booking tool=booking__list_rooms args={}
Model round 2 ← 1 tool call(s): [booking__find_free_rooms {"start":"2026-12-01T12:00:00+01:00","end":"…"}]
Model round 3 ← answer 214 chars …                        ← "I am going to book Elbrus … please confirm"
Model round 1 ← 1 tool call(s): [booking__create_booking {"roomId":3,"title":"MCP chat test",…}]
```

## 3. Backend: Spring AI MCP server

### 3.1 Dependency

Already there since `raw`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

It adds a Streamable HTTP MCP endpoint to the existing Spring MVC app: same port 8080, same security.

### 3.2 Properties (`backend/.../application.properties`)

```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=room-booking
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
spring.ai.mcp.server.instructions=Meeting room bookings of the office. Times are ISO-8601 with an offset, …
```

| Property | Why |
|---|---|
| `enabled` | Was `false` until step3 |
| `name`, `version` | Shown to clients as `serverInfo` (🔌 MCP panel: "room-booking 1.0.0") |
| `protocol=STREAMABLE` | Current MCP transport over HTTP: one endpoint, optional SSE stream. `STATELESS` would be the alternative without sessions |
| `type=SYNC` | The services are blocking (JPA), so sync tool methods fit |
| `instructions` | Sent on `initialize`; models use it as a hint (time format, check before booking) |
| `annotation-scanner.enabled` (default `true`) | Finds the `@McpTool` methods in Spring beans |

### 3.3 Tools (`backend/.../mcp/BookingMcpTools.java`, step5)

In step4 this class doesn't exist yet. A first tool to write live:

```java
@Component
public class BookingMcpTools {

    private final RoomService roomService;

    public BookingMcpTools(RoomService roomService) {
        this.roomService = roomService;
    }

    @McpTool(name = "list_rooms", description = "All meeting rooms with id, location and capacity (seats)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<RoomResponse> listRooms() {
        return roomService.findAll();
    }
}
```

Then `docker compose up -d --build backend`, ↻ Reconnect on the booking card (1 tool), and ask "Which rooms do we
have?". The full version in step5:

| Tool | Annotations | Calls |
|---|---|---|
| `list_rooms` | read-only | `RoomService.findAll()` |
| `find_free_rooms(start, end, minCapacity?)` | read-only | rooms minus rooms with overlapping bookings |
| `list_bookings(start, end, roomId?)` | read-only | `BookingService.find(...)` |
| `my_bookings(start, end)` | read-only | bookings where the current user is the organizer |
| `create_booking(roomId, title, start, end, description?)` | not destructive | `BookingService.create(request, currentUser)` |
| `update_booking(bookingId, roomId, title, start, end, description?)` | idempotent | `BookingService.update(...)` |
| `cancel_booking(bookingId)` | destructive | `BookingService.delete(...)` |

```java
@McpTool(name = "create_booking", description = "Book a room; the current user becomes the organizer. …",
        annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
public BookingResponse createBooking(
        McpSyncRequestContext context,
        @McpToolParam(description = "Room id from list_rooms") Long roomId,
        @McpToolParam(description = "Meeting title") String title,
        @McpToolParam(description = "Start, " + TIME) String start,
        @McpToolParam(description = "End, " + TIME) String end,
        @McpToolParam(description = "Agenda or notes", required = false) String description) {
    return bookingService.create(
            new BookingRequest(roomId, title, description, time(start, "start"), time(end, "end")),
            currentUser(context));
}
```

- Spring AI builds the JSON input schema from the parameters and `@McpToolParam`, including `required`. Return
  values (records) are serialized to JSON.
- `McpSyncRequestContext` is injected by Spring AI and isn't part of the schema.
- Times are `String` on purpose: when a model sends a time without an offset, `time(...)` answers "start must be an
  ISO-8601 date-time with offset, e.g. 2026-10-23T10:00:00+02:00", and the model can retry.
- Exceptions become MCP tool errors (`isError: true`) with their message, for example "Room 'Everest' is already
  booked from …". The model reads it and explains it to the user.

### 3.4 Who is the user? (`backend/.../mcp/McpServerConfig.java`)

`/mcp` is a normal Spring MVC route, so the existing `SecurityFilterChain` (JWT resource server,
`anyRequest().authenticated()`) protects it: without a token the client gets `401`.

The tool methods may run on another thread than the HTTP request, so they don't use `SecurityContextHolder`. The
transport puts the user into the MCP transport context while it still has the request. The bean replaces Spring AI's
auto-configured one (`@ConditionalOnMissingBean`):

```java
@Bean
WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
        JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
    return WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(properties.getMcpEndpoint())
            .keepAliveInterval(properties.getKeepAliveInterval())
            .disallowDelete(properties.isDisallowDelete())
            .contextExtractor(request -> request.principal()
                    .filter(JwtAuthenticationToken.class::isInstance)
                    .map(principal -> CurrentUser.from(((JwtAuthenticationToken) principal).getToken()))
                    .map(user -> McpTransportContext.create(Map.of(USER, user)))
                    .orElse(McpTransportContext.EMPTY))
            .build();
}
```

The tools read it with `context.transportContext().get(USER)`, the same `CurrentUser` the REST controllers use.

### 3.5 External clients (`frontend/nginx.conf`)

`location = /mcp` proxies to the backend with buffering off, so the endpoint is also reachable at
`http://localhost:3000/mcp`. To try it with MCP Inspector (`npx @modelcontextprotocol/inspector`): choose transport
"Streamable HTTP", URL `http://localhost:3000/mcp`, header `Authorization: Bearer <accessToken from login>`.

## 4. chat-service: "Send my login token"

Adding the booking server is a normal user action in 🔌 MCP. One thing was missing: the server needs the user's
identity, but a stored header would hold a fixed token (expires after 8h) and would act as one user for good.
So step4 adds a per-server option:

| Change | Where |
|---|---|
| `forwardUserToken` in request/response, column `forward_user_token` (Flyway `V2`) | `McpServerDtos`, `McpServer`, `V2__mcp_server_forward_user_token.sql` |
| Only allowed for hosts in `MCP_ALLOWED_HOSTS` (`backend` by default in Docker), never together with an own header | `McpServerService.spec(...)`, `McpUrlValidator.isAllowListed(...)` |
| The latest JWT of each user, remembered on every request to `/api/**` | `UserTokenRelay` (HandlerInterceptor), `McpWebConfig` |
| The auth header is a `Supplier<String>`, read on **every** HTTP request of the MCP client | `McpClientRegistry.ConnectionSpec` |
| Checkbox in the add form, "· your login token" on the card | `McpPanel.tsx` (both UIs) |
| System prompt: how to use `booking__*` tools, time format, check before booking | `ChatConfig.SYSTEM_PROMPT` |

```java
// McpServerService.spec(...): for "Send my login token"
return new ConnectionSpec(name, target, transport, HttpHeaders.AUTHORIZATION,
        () -> userTokenRelay.authorization(userId));   // "Bearer <latest JWT of this user>"
```

Why the token is looked up by user id instead of taken from the SecurityContext: tool calls run inside Spring AI's
tool loop on Reactor threads, where the request's SecurityContext is gone. MCP connections are per server row,
and rows belong to one user, so each connection knows its user.

Why the allow-list: the user's JWT also works for the booking REST API. Sending it to an arbitrary internet MCP
server would hand over the account. `MCP_ALLOWED_HOSTS` is set by whoever runs the app, not by the user.

### Alternative: configuration-only MCP client

Spring AI can also create MCP clients from properties at startup and give their tools to every request:

```properties
spring.ai.mcp.client.streamable-http.connections.booking.url=http://backend:8080
spring.ai.mcp.client.streamable-http.connections.booking.endpoint=/mcp
```

Headers then come from a `McpSyncHttpClientRequestCustomizer` bean. It's the shortest demo of "Spring AI + MCP", but
one client is shared by all users and doesn't know whose token to send. Every booking would get the same
(technical) organizer, and users couldn't switch it on or off. That's why this project builds clients at runtime
(step2) and only adds the token option in step4.

## 5. Try it

1. `/run-step step5` (or `git checkout step5-booking-mcp-tools && docker compose up -d --build`). On `step4` the
   same steps give "connected · 0 tools" until you write the tools yourself.
2. 🔌 MCP → Add server: name `booking`, URL `http://backend:8080/mcp`, check **Send my login token** → Test → Save.
   The card shows "connected · 7 tools · room-booking 1.0.0 · your login token".
3. In the chat:
   - "Which rooms are free tomorrow from 10 to 11 for 6 people?" → `find_free_rooms`
   - "Book Elbrus for a sprint retro." → the bot shows the details and asks → "yes" → `create_booking`; refresh the
     calendar
   - "Move it to 14:00." → `update_booking`
   - Book the same slot from a second account, then ask the bot again → conflict → the bot explains it
   - Try to cancel another user's booking → "Only the organizer can change or delete this booking"
4. Without the checkbox, Test fails with "Authorization failed". With the checkbox and a public URL, saving fails
   with "Your login token can only be sent to internal servers listed in MCP_ALLOWED_HOSTS".
5. In the `chat` profile there is no backend: the server shows "Cannot connect", and the chat keeps working without
   the booking tools.

## 6. Security checklist

- **Identity**: the organizer is always the JWT subject; the model can't choose a user.
- **Authorization** stays in `BookingService`: only the organizer can change or cancel, for REST and MCP alike.
- **Confirmation** before write actions: system prompt rule, plus MCP `destructiveHint` on `cancel_booking`.
- **Least privilege**: only booking tools; room administration (`RoomService.create/delete`) is not exposed.
- **Tokens**: kept in memory only, never logged and never stored in the database; only sent to allow-listed hosts.
- **Network**: chat-service → backend stays inside the Docker network. From outside, `/mcp` works only through nginx
  and only with a valid JWT.
