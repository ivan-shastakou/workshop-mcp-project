# Prompt and model options in Spring AI: demo cookbook

Ideas and ready-to-paste examples for showing how a prompt and the LLM's generation parameters can be configured
in Spring AI 2.0.1 with Gemini (`spring-ai-starter-model-google-genai`). Everything here is checked against the
APIs of the versions this project uses; none of it is wired into the workshop branches yet.

> Every demo below is a real Gemini call. The free tier allows only ~20 requests per day per model, so plan the
> demos, or switch `GEMINI_MODEL` between models to spread the quota.

## 1. Where things can be configured

There are three levels. A value set at a lower level overrides the level above it:

| Level | Prompt | Model options | Scope |
|---|---|---|---|
| 1. `application.properties` | - | `spring.ai.google.genai.chat.*` | whole application |
| 2. `ChatClient.Builder` (in `ChatConfig`) | `defaultSystem(...)`, `defaultUser(...)` | `defaultOptions(...)` | every call of this `ChatClient` |
| 3. Per request (in `ChatService`) | `.system(...)`, `.user(...)`, `.messages(...)` | `.options(...)` | one call |

What the project does today (step2+, before it the chat has no system prompt):

```properties
spring.ai.google.genai.chat.model=${GEMINI_MODEL:gemini-3.5-flash-lite}
spring.ai.google.genai.chat.temperature=0.4
spring.ai.google.genai.chat.max-output-tokens=2048
```

```java
// ChatConfig: level 2
builder.defaultSystem(SYSTEM_PROMPT)
       .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
       .build();

// ChatService: level 3 fills the template parameters of the system prompt
chatClient.prompt()
        .system(s -> s.param("userName", name).param("now", now) /* ... */)
        .user(message)
        .stream().content();
```

## 2. Configuring the prompt

### 2.1 System prompt vs user prompt

- **System prompt**: who the assistant is, its rules, tone, format and context (user, date). The user doesn't see it
  and can't change it through the chat.
- **User prompt**: what the user typed.

Demo: ask the same question with and without `defaultSystem(...)` (`git diff step1-spring-ai-chat
step2-system-prompt-and-mcp -- chat-service/src/main/java/com/leverx/chat/config`). Without a system prompt, "What day is it?" gets "I don't know"; with it, the model knows the
user and the date.

### 2.2 Template parameters

Prompts are [StringTemplate](https://www.stringtemplate.org/) templates: `{name}` placeholders are filled from
`param(...)`/`params(...)`. This works in both system and user prompts:

```java
chatClient.prompt()
        .system(s -> s.text("You answer in {language}. Max {sentences} sentences.")
                      .param("language", "German")
                      .param("sentences", 2))
        .user(u -> u.text("Explain what {topic} is").param("topic", "a stand-up meeting"))
        .call().content();
```

If the prompt itself contains `{`/`}` (JSON examples, code), change the delimiters so they aren't parsed as
placeholders:

```java
builder.defaultTemplateRenderer(StTemplateRenderer.builder()
        .startDelimiterToken('<').endDelimiterToken('>')
        .build());
// now: "Reply as JSON like {\"room\": \"Orion\"}. The user is <userName>."
```

### 2.3 Keep prompts in files

Long prompts read better outside Java code and can be changed without touching it:

```
src/main/resources/prompts/system.st
```

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      @Value("classpath:prompts/system.st") Resource systemPrompt) {
    return builder.defaultSystem(systemPrompt).build();
}
```

### 2.4 Switch the persona per request

A request-level `.system(...)` replaces the default system prompt. Demo idea: a "style" selector in the chat
(formal / friendly / pirate):

```java
var persona = switch (style) {
    case "formal" -> "You are a formal corporate assistant. No emojis, no jokes.";
    case "pirate" -> "You are a cheerful pirate. Answer in pirate speak, but keep the facts correct.";
    default -> SYSTEM_PROMPT;
};
chatClient.prompt().system(persona).user(message).call().content();
```

### 2.5 Few-shot examples

Show the model two or three example question/answer pairs before the real question. They steer the format better
than a long description:

```java
chatClient.prompt()
        .system("Turn the request into a booking command. Reply with the command only.")
        .messages(
                new UserMessage("Book Orion tomorrow 10 to 11 for a retro"),
                new AssistantMessage("BOOK room=Orion date=+1d from=10:00 to=11:00 title=\"Retro\""),
                new UserMessage("I need Andromeda on Friday after lunch for one hour, 1:1 with Kate"),
                new AssistantMessage("BOOK room=Andromeda date=friday from=13:00 to=14:00 title=\"1:1 with Kate\""))
        .user("Reserve Vega next Monday 9:30-10 for a daily")
        .call().content();
// -> BOOK room=Vega date=monday from=09:30 to=10:00 title="Daily"
```

### 2.6 Structured output: answer as a Java object

`entity(...)` adds format instructions (a JSON schema of the class) to the prompt and parses the answer:

```java
record BookingDraft(String room, LocalDate date, LocalTime start, LocalTime end, String title) {}

BookingDraft draft = chatClient.prompt()
        .system(s -> s.text("Today is {today}.").param("today", LocalDate.now()))
        .user("Book Orion tomorrow from 10 to 11:30 for sprint planning")
        .call()
        .entity(BookingDraft.class);
```

For Gemini, add `.options(GoogleGenAiChatOptions.builder().responseMimeType("application/json"))` so the model is
forced to return JSON only. Lists work too: `.entity(new ParameterizedTypeReference<List<BookingDraft>>() {})`.
This is the natural bridge to the planned booking MCP tools.

### 2.7 Images (multimodal)

```java
chatClient.prompt()
        .user(u -> u.text("Which meeting room is on this floor plan, and how many seats does it have?")
                    .media(MimeTypeUtils.IMAGE_PNG, new ClassPathResource("floor-plan.png")))
        .call().content();
```

## 3. Model (generation) parameters

All of them can be set in properties (`spring.ai.google.genai.chat.<name>`), in `defaultOptions(...)` and per request
in `.options(...)`.

| Option | Property | Range / values | What it does |
|---|---|---|---|
| `model` | `model` | e.g. `gemini-3.5-flash-lite` | Which model answers; can be changed per request |
| `temperature` | `temperature` | 0.0 to 2.0 | Randomness. 0 = almost the same answer every time; high = creative, less predictable |
| `topP` | `top-p` | 0.0 to 1.0 | Nucleus sampling: pick only from the most likely tokens whose probabilities add up to P |
| `topK` | `top-k` | integer | Pick only from the K most likely tokens |
| `maxOutputTokens` | `max-output-tokens` | integer | Hard limit on the answer length; the answer is cut off, not shortened |
| `stopSequences` | `stop-sequences` | list of strings | Generation stops when the model produces one of them |
| `frequencyPenalty` | `frequency-penalty` | about -2.0 to 2.0 | Penalizes tokens by how often they already appeared → less repetition |
| `presencePenalty` | `presence-penalty` | about -2.0 to 2.0 | Penalizes any token that already appeared → pushes to new topics/words |
| `candidateCount` | `candidate-count` | integer | Several alternative answers in one call (`chatResponse().getResults()`) |
| `thinkingLevel` | `thinking-level` | `MINIMAL`, `LOW`, `MEDIUM`, `HIGH` | How much the model "thinks" before answering (Gemini 3 models) |
| `thinkingBudget` | `thinking-budget` | tokens; 0 = off | Same idea, as a token budget (Gemini 2.5 models) |
| `includeThoughts` | `include-thoughts` | boolean | Return the model's thought summary too |
| `responseMimeType` | `response-mime-type` | `text/plain`, `application/json` | JSON mode |
| `responseSchema` | `response-schema` | JSON schema | Exact JSON shape the answer must follow |
| `safetySettings` | `safety-settings` | category + threshold | How strictly harmful content is blocked |
| `googleSearchRetrieval` | `google-search-retrieval` | boolean | Ground the answer in Google Search results |
| `toolChoice` | `tool-choice.mode` | `AUTO`, `ANY`, `NONE` | Whether the model may / must call tools (MCP on step2+) |

Model support differs. If Gemini answers `400 INVALID_ARGUMENT` after you add an option, the chosen model doesn't
support it; this is common for `frequencyPenalty`/`presencePenalty` and `thinkingBudget`/`thinkingLevel`. Google
also recommends keeping the default `temperature` (1.0) for Gemini 3 models, so treat low temperatures there as a
demo, not as a production setting.

### Setting them

```properties
# level 1: application.properties
spring.ai.google.genai.chat.temperature=0.4
spring.ai.google.genai.chat.top-p=0.95
spring.ai.google.genai.chat.max-output-tokens=2048
spring.ai.google.genai.chat.stop-sequences=END,###
```

```java
// level 2: defaults for one ChatClient
builder.defaultOptions(GoogleGenAiChatOptions.builder()
        .temperature(0.4)
        .maxOutputTokens(2048));

// level 3: one request; only the given fields are overridden, the rest stay as configured
chatClient.prompt()
        .user(message)
        .options(GoogleGenAiChatOptions.builder().temperature(1.5).topP(0.9))
        .call().content();
```

In Spring AI 2.0, `defaultOptions(...)` and `options(...)` take the **builder**, without `.build()`.
For portable code, `ChatOptions.builder()` covers the common options (`temperature`, `topP`, `topK`,
`maxTokens`, `stopSequences`, `frequencyPenalty`, `presencePenalty`, `model`); `GoogleGenAiChatOptions.builder()`
adds the Gemini-specific ones.

Two `ChatClient`s with different settings from the same auto-configured builder:

```java
@Bean ChatClient preciseClient(ChatClient.Builder builder) {
    return builder.clone().defaultOptions(GoogleGenAiChatOptions.builder().temperature(0.0)).build();
}
@Bean ChatClient creativeClient(ChatClient.Builder builder) {
    return builder.clone().defaultOptions(GoogleGenAiChatOptions.builder().temperature(1.8).topP(0.98)).build();
}
```

## 4. Demo scenarios

Each scenario shows one idea with a visible effect. Run the same prompt with two settings side by side.

| # | Demo | Setting A | Setting B | What the audience sees |
|---|---|---|---|---|
| 1 | **Temperature** | `temperature 0.0` | `temperature 1.8` | "Suggest a name for our new meeting room" ×3: A repeats the same name, B gives a different, wilder name every time |
| 2 | **Top-P / Top-K** | `topK 1` | `topK 40, topP 0.95` | `topK 1` ≈ greedy decoding: same as temperature 0 |
| 3 | **Max output tokens** | `maxOutputTokens 30` | `2048` | A is cut off mid-sentence. Shows it's a hard limit, not "be brief"; for brevity, ask in the prompt |
| 4 | **Stop sequences** | `stopSequences ["3."]` | none | "List 5 tips for short meetings": A stops after tip 2 |
| 5 | **Frequency/presence penalty** | `0.0` | `1.5` | "Write 10 slogans for the booking app": B repeats words like "book" and "room" less |
| 6 | **System prompt persona** | default | pirate / formal | Same question, completely different tone, same facts |
| 7 | **Prompt params** | `{userName}`, `{now}` | without them | "Who am I and what's the date?" (step2) |
| 8 | **Few-shot** | 2 examples | no examples | The format of the answer follows the examples exactly |
| 9 | **Structured output** | `.entity(BookingDraft.class)` | `.content()` | A Java record vs free text; print the record in the log |
| 10 | **Thinking** | `thinkingLevel MINIMAL` | `HIGH` + `includeThoughts` | A logic puzzle ("3 teams, 2 rooms, these constraints…"): B is slower but right, and you can show its thoughts |
| 11 | **Token usage** | short prompt | long system prompt + history | Log `chatResponse().getMetadata().getUsage()`: prompt/completion tokens, and how chat memory grows the prompt (step1+) |

Logging the token usage for demo 11:

```java
var response = chatClient.prompt().user(message).call().chatResponse();
var usage = response.getMetadata().getUsage();
log.info("prompt={} completion={} total={}", usage.getPromptTokens(), usage.getCompletionTokens(),
        usage.getTotalTokens());
```

`SimpleLoggerAdvisor` shows the full request that is sent to the model, including the system prompt, history and
options. It's useful during the demo to show what the advisors add:

```java
builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor());
```

```properties
logging.level.org.springframework.ai.chat.client.advisor=DEBUG
```

## 5. Idea: a "playground" endpoint for live demos

To change the options live without restarts, chat-service could get an endpoint that takes them from the request.
This is a sketch only; it isn't implemented:

```java
record PlaygroundRequest(@NotBlank String message, String system, Double temperature, Double topP,
                         Integer topK, Integer maxOutputTokens, List<String> stopSequences,
                         Double frequencyPenalty, Double presencePenalty) {}

@PostMapping("/api/chat/playground")
String playground(@RequestBody @Valid PlaygroundRequest r) {
    var options = GoogleGenAiChatOptions.builder()
            .temperature(r.temperature()).topP(r.topP()).topK(r.topK())
            .maxOutputTokens(r.maxOutputTokens()).stopSequences(r.stopSequences())
            .frequencyPenalty(r.frequencyPenalty()).presencePenalty(r.presencePenalty());
    var prompt = chatClient.prompt().user(r.message()).options(options);
    if (r.system() != null) prompt = prompt.system(r.system());
    return prompt.call().content();
}
```

```bash
TOKEN=...   # from POST /api/auth/login
for t in 0.0 1.8; do
  curl -s localhost:8082/api/chat/playground -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"message\":\"Suggest a name for our new meeting room\",\"temperature\":$t}"; echo
done
```

In the UI, this could be a small settings panel next to the chat with sliders for temperature/top-P and a
max-tokens field. Changing a slider and resending the same question is the clearest live demo of the parameters.
If it's added to the workshop, it would be its own step branch (for example `step6-prompt-playground`), following
the rules in `CLAUDE.md`.
