# EXAMPLES.md

Reference catalogue of "good working examples" already in this repository. Each section pairs a **best-practice description** with a **minimal code snippet** and a pointer to **where to see more** in the source tree.

When in doubt, copy the snippet's shape — not a whole file — and link back here from PR descriptions instead of re-inventing the pattern.

## Table of contents

1. [Ktor server setup](#1-ktor-server-setup)
2. [Kotlin (Ktor) serialization](#2-kotlin-ktor-serialization)
3. [MCP server in Kotlin (Kotlin MCP SDK + streamable HTTP)](#3-mcp-server-in-kotlin-kotlin-mcp-sdk--streamable-http)
4. [Agent in Kotlin (resolver / future Koog)](#4-agent-in-kotlin-resolver--future-koog)
5. [Python + LangGraph agent](#5-python--langgraph-agent)
6. [Frontend in TypeScript + Vue 3](#6-frontend-in-typescript--vue-3)
7. [Apache Calcite (SQL, RelNode, RelBuilder)](#7-apache-calcite-sql-relnode-relbuilder)
8. [OpenTelemetry setup](#8-opentelemetry-setup)

---

## 1. Ktor server setup

### 1a. Shared base installer (`installKtorServerBase`)

**Best practice.** Centralize `ContentNegotiation`, `CORS`, `CallLogging`, `ForwardedHeaders` into one extension function driven by a typed config object. Every service calls `installKtorServerBase(config)` instead of duplicating plugin blocks — JSON behaviour (lenient, ignoreUnknownKeys) stays uniform across the platform.

```kotlin
fun Application.installKtorServerBase(config: KtorServerConfig) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = config.jsonConfig.prettyPrint
            isLenient = config.jsonConfig.isLenient
            encodeDefaults = config.jsonConfig.encodeDefaults
            ignoreUnknownKeys = config.jsonConfig.ignoreUnknownKeys
        })
    }
    install(CORS) {
        config.corsAllowedHosts.forEach { allowHost(it.trimEnd('/'), schemes = listOf("http", "https")) }
        config.corsConfig.allowedMethods.forEach { allowMethod(it) }
        config.corsConfig.allowedHeaders.forEach { allowHeader(it) }
        allowNonSimpleContentTypes = config.corsConfig.allowNonSimpleContentTypes
        allowCredentials = config.corsConfig.allowCredentials
    }
    config.callLoggingConfig?.let { install(CallLogging) { level = it.level } }
    if (config.forwardedHeaderEnabled) install(ForwardedHeaders)
}
```

**See more:**
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/KtorServerConfigurator.kt`
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/KtorServerConfig.kt` (typed config with nested `JsonConfig`, `CorsConfig`, `CallLoggingConfig` defaults)
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/KtorServerBootstrap.kt` (CIO vs Netty engine selection)

### 1b. Canonical service `Application.kt`

**Best practice.** Five steps and out: (1) `ConfigFactory.load()`, (2) `createOpenTelemetrySdk(...)`, (3) build `KtorServerConfig` via `KtorConfigFactory.fromConfig(...)`, (4) `KtorServerBootstrap.createServer(serverConfig) { module(...) }.start(wait = true)`, (5) the `Application.module` function calls `installKtorServerBase` then `routing { ... }`. Keep this file under ~45 lines — routes, handlers, and domain wiring belong elsewhere.

```kotlin
fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "starters",
            protocol = System.getenv("STARTERS_OTEL_PROTOCOL") ?: "grpc",
        ),
    )
    val serverConfig = KtorConfigFactory.fromConfig(
        config = config,
        defaultServiceName = "starters",
        defaultPort = 7114,
        engine = KtorEngine.NETTY,
    )
    KtorServerBootstrap.createServer(serverConfig) { module(serverConfig) }.start(wait = true)
}

fun Application.module(serverConfig: KtorServerConfig) {
    val downloadStore = DownloadStore()
    installKtorServerBase(serverConfig)
    routing {
        configureRouting(downloadStore)
    }
}
```

**See more:**
- `infra/starters/src/main/kotlin/infra/starters/Application.kt` — 42 lines, the cleanest reference
- `services/sql-formatter/src/main/kotlin/services/sql/formatter/Application.kt` — near-identical template (44 lines)

**Do NOT install `content negotiation`, this is already done in `installKtorServerBase`**

---

## 2. Kotlin (Ktor) serialization

### 2a. `buildJsonObject` for `call.respond()`

**Best practice.** Never use `mapOf` in `call.respond()` — Kotlin's type erasure collapses `Map<String, Any>` into a shape kotlinx.serialization cannot dispatch. Use `buildJsonObject { put(key, JsonPrimitive(v)) }`. For arrays, wrap a list in `JsonArray(list.map { JsonPrimitive(it) })`.

```kotlin
fun Route.configureRouting(handler: DispatcherHandler) {
    get("/health") {
        call.respond(buildJsonObject { put("status", JsonPrimitive("ok")) })
    }
    get("/ready") {
        call.respond(
            buildJsonObject {
                put("status", JsonPrimitive("ready"))
                put("databases", JsonPrimitive(handler.databases.joinToString(", ")))
            },
        )
    }
    post("/dispatch") { handler.dispatch(call) }
}
```

**See more:** `services/erp-sql-dispatcher/src/main/kotlin/services/erp/sql/dispatcher/routes/DispatcherRoutes.kt`. For a `JsonArray` example: `services/fuzzy-matcher/src/main/kotlin/services/fuzzy/matcher/core/NlpLemmatizer.kt`.

### 2b. Sealed interface for multi-type fields

**Best practice.** For a field that can take one of several concrete shapes, use a `@Serializable sealed interface` with `@Serializable data class` members. kotlinx.serialization picks the right variant via the discriminator automatically. Add a `companion object` factory so callers can construct from a raw string without reaching for `when`.

```kotlin
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
sealed interface SqlValue {
    @Serializable data class StringValue(val value: String) : SqlValue
    @Serializable data class IntValue(val value: Int) : SqlValue
    @Serializable data class LongValue(val value: Long) : SqlValue
    @Serializable data class DoubleValue(val value: Double) : SqlValue
    @Serializable data class BooleanValue(val value: Boolean) : SqlValue

    companion object {
        fun ofString(value: String): SqlValue {
            if (value.isBlank()) return StringValue("")
            if (value.toIntOrNull() != null) return IntValue(value.toInt())
            if (value.toLongOrNull() != null) return LongValue(value.toLong())
            if (value.toDoubleOrNull() != null) return DoubleValue(value.toDouble())
            if (value.equals("true", ignoreCase = true)) return BooleanValue(true)
            if (value.equals("false", ignoreCase = true)) return BooleanValue(false)
            return StringValue(value)
        }
    }
}
```

**See more:** `shared/libs/kotlin/erp-sql-common/src/main/kotlin/infra/erp/sql/common/domain/Models.kt`.

### 2c. Custom `KSerializer` (primitive form)

**Best practice.** When the wire format does not match the Kotlin type (string-encoded enum, hand-rolled discriminator, lenient primitive-or-object), implement `KSerializer<T>` and attach it via `@Serializable(with = ...)` on the field. For simple enum-to-string, use `PrimitiveSerialDescriptor` — the surrounding data class stays clean.

```kotlin
enum class ValidationBehavior { WARN, ERROR }

class ValidationBehaviorSerializer : KSerializer<ValidationBehavior> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ValidationBehavior", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ValidationBehavior) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ValidationBehavior =
        ValidationBehavior.valueOf(decoder.decodeString())
}

@Serializable
data class ValidationConfig(
    @Serializable(with = ValidationBehaviorSerializer::class)
    val onParameterMismatch: ValidationBehavior = ValidationBehavior.WARN,
)
```

**See more:** `shared/libs/kotlin/erp-sql-metadata/src/main/kotlin/infra/erp/sql/catalog/ValidationConfig.kt`.

### 2d. Custom `KSerializer` (lenient JsonElement form for sealed types)

**Best practice.** When the wire format accepts a primitive **or** an array **or** a tagged object for the same sealed type, drop into `JsonDecoder` / `JsonEncoder` and dispatch on `JsonElement` shape. This is the template for "accept what the caller writes, normalize on the way in".

```kotlin
@Serializable
sealed interface MetadataValue

@Serializable data class MetadataSingle(val value: String) : MetadataValue
@Serializable data class MetadataList(val values: List<String>) : MetadataValue

object MetadataValueSerializer : KSerializer<MetadataValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetadataValue")

    override fun deserialize(decoder: Decoder): MetadataValue {
        val jd = decoder as? JsonDecoder ?: error("MetadataValueSerializer requires Json")
        return when (val e = jd.decodeJsonElement()) {
            is JsonObject -> when {
                e["value"] is JsonPrimitive -> MetadataSingle((e["value"] as JsonPrimitive).content)
                e["values"] is JsonArray -> MetadataList(
                    (e["values"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content },
                )
                else -> MetadataSingle(e.toString())
            }
            is JsonPrimitive -> MetadataSingle(e.content)
            is JsonArray -> MetadataList(e.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(e.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: MetadataValue) {
        val je = encoder as? JsonEncoder ?: error("MetadataValueSerializer requires Json")
        je.encodeJsonElement(when (value) {
            is MetadataSingle -> buildJsonObject { put("value", JsonPrimitive(value.value)) }
            is MetadataList -> buildJsonObject { put("values", JsonArray(value.values.map { JsonPrimitive(it) })) }
        })
    }
}
```

**See more:** this pattern is currently template-only — the canonical real-world sealed-interface example in source is `SqlValue` (section 2b). Use the snippet above as the starting point when introducing the lenient form for a new domain type.

---

## 3. MCP server in Kotlin (Kotlin MCP SDK + streamable HTTP)

The reference implementation is `tools/query-mcp`; `tools/fuzzy-mcp` is a smaller variant. Shared infrastructure lives under `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/mcp/`.

### 3a. MCP-flavoured Ktor bootstrap

**Best practice.** All MCP services share one Ktor base installer (`installMcpKtorBase`) that wires `ContentNegotiation(json(McpJson))`, `StatusPages`, `CORS` (with the mandatory `mcp-session-id` / `mcp-protocol-version` exposed headers), `KtorServerTelemetry`, optional `CallLogging`, and `/health` + `/ready` endpoints. Compose it inside `serverConfig { module { ... } }` and start with `embeddedServer(CIO, ...)` on `0.0.0.0`. Per-service wiring (extra routes, gRPC clients, request-context interceptors) layers around it — never inside it.

```kotlin
val appConfig = serverConfig {
    module {
        installMcpKtorBase(mcpKtorConfig, telemetry.openTelemetrySdk)
        routing {
            get("/status")  { call.respondText(statusJson, ContentType.Application.Json) }
            get("/metrics") { call.respondText(metrics.scrape(), ContentType.parse("text/plain; version=0.0.4")) }
        }
        installQueryMcp(cfg, registry, requestContext)
    }
}
embeddedServer(
    factory = CIO,
    rootConfig = appConfig,
    configure = {
        connectionIdleTimeoutSeconds = 120          // never 3600 — see AGENTS.md
        connectors.add(EngineConnectorBuilder().apply { port = cfg.serverPort; host = "0.0.0.0" })
    },
).start(wait = true)
```

**See more:**
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/mcp/McpKtorConfigurator.kt`
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/mcp/McpKtorServerBootstrap.kt`
- `tools/query-mcp/src/main/kotlin/tools/querymcp/Application.kt`

### 3b. Streamable-HTTP transport + tool registration

**Best practice.** Mount the transport with `mcpStreamableHttp { ... }` inside a Ktor module extension function. Inside, build one `Server` with explicit `Implementation(name, version)` and `ServerCapabilities.Tools(listChanged = false)`, then loop a tool registry and call `server.addTool(name, description, inputSchema, outputSchema) { request -> ... }`. The SDK propagates client-disconnect cancellation into the lambda, so any upstream gRPC/Flow collected inside it cancels for free — do not add manual cancellation.

```kotlin
fun Application.installQueryMcp(cfg: QueryMcpConfig, registry: ToolRegistry, requestContext: RequestContext) {
    mcpStreamableHttp {
        val server = Server(
            serverInfo = Implementation(name = "query-mcp", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )
        for (tool in registry.all()) {
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
                outputSchema = tool.outputSchema,
            ) { request ->
                val identity = IdentityResolver.resolve(
                    requestContext.authHeader.get(),
                    requestContext.userIdHeader.get(),
                    argUserId = null,
                )
                tool.execute(request, identity)
            }
        }
        server
    }
}
```

**See more:**
- `tools/query-mcp/src/main/kotlin/tools/querymcp/mcp/McpTransport.kt`
- `tools/query-mcp/src/main/kotlin/tools/querymcp/mcp/McpTool.kt` (`McpTool` interface + `ToolRegistry`)
- `tools/fuzzy-mcp/src/main/kotlin/org/tatrman/fuzzy/mcp/Application.kt` (single-tool inline variant)

### 3c. Safe tool callback wrapper (`safeMcpTool`)

**Best practice.** Every tool callback must be wrapped so that timeouts and uncaught exceptions become well-formed `CallToolResult(isError = true, ...)` payloads instead of bubbling up and killing the SSE stream. The shared `safeMcpTool(name, timeoutMs) { block }` decorator runs the block under `withTimeout(...)`, catches `TimeoutCancellationException` (→ `errorCode = "TIMEOUT"`) and any `Throwable` (→ `errorCode = "EXECUTION_ERROR"`), and logs `toolName=… durationMs=… outcome=…`. Pass per-tool timeouts from config; stack `InstrumentedTool` *outside* it for Prometheus metrics.

```kotlin
server.addTool(toolsLogic.fuzzyMatchTool) { request ->
    safeMcpTool("fuzzy_match", timeoutMs = 15_000) { req ->
        toolsLogic.fuzzyMatchCallback(req)
    }(request)
}

// On timeout, the wrapper returns:
CallToolResult(
    isError = true,
    content = listOf(TextContent(text = "Tool 'fuzzy_match' timed out after 15000 ms")),
    structuredContent = buildJsonObject {
        put("errorCode", "TIMEOUT")
        put("error", "...")
        put("message", "...")
        put("extras", buildJsonObject { put("tool", "fuzzy_match") })
    },
)
```

**See more:**
- `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/mcp/SafeMcpTool.kt`
- `shared/libs/kotlin/ktor-configurator/src/test/kotlin/shared/ktor/mcp/SafeMcpToolTest.kt` (contract tests)
- `tools/query-mcp/src/main/kotlin/tools/querymcp/mcp/InstrumentedTool.kt` (metrics decorator)

### 3d. `McpJson` serializer

**Best practice.** Use the SDK-provided `io.modelcontextprotocol.kotlin.sdk.types.McpJson` (a pre-configured `kotlinx.serialization.json.Json`) as the **only** JSON instance for the MCP HTTP layer — pass it to `ContentNegotiation { json(McpJson) }`. It already carries the MCP-required settings (`ignoreUnknownKeys`, polymorphism for `Content` / `TextContent`). Do not construct your own `Json {}` for MCP routes. For ad-hoc business JSON in your own routes (`/status`, `/metrics`), build with `buildJsonObject` / `JsonPrimitive` — never `mapOf`.

```kotlin
install(ContentNegotiation) { json(McpJson) }
```

**See more:** `shared/libs/kotlin/ktor-configurator/src/main/kotlin/shared/ktor/mcp/McpKtorConfigurator.kt`.

---

## 4. Agent in Kotlin (resolver / future Koog)

### Status of Koog in this repo

**Koog libraries are listed in `gradle/libs.versions.toml` but no module depends on them yet.** The `agents/resolver` package name `agents.resolver.koog` is misleading legacy scaffolding — v1 deviated from Koog because of a Ktor 2.x/3.x conflict. Koog migration is planned for v1.1. Until then, the canonical Kotlin-agent pattern in this repo is plain coroutines.

### Best practice (current deviation pattern)

**State-machine-as-suspending-functions.** A single immutable `ResolverContext` flows through a `when`-branched dispatch loop. Every "node" is `suspend fun (ResolverContext) -> NodeResult`. Each node opens its own OTel span, returns a sealed `NodeResult` (`Continue` / `EmitResolution` / `EmitAwaiting` / `Error`), and the dispatcher routes the next step by reading `state.parseState.lastNode`. Parallel fan-out inside a node uses `coroutineScope { async { … } }` rather than a framework primitive. This gives Koog-graph-style traceability and resumability (via an HMAC `resumeToken` in state) without an agent runtime — at the cost of encoding "edges" as `when` branches rather than a declared DAG.

```kotlin
class ResolverGraph(/* clients, cache, config */) {
    sealed class NodeResult {
        data class Continue(val state: ResolverContext) : NodeResult()
        data class EmitResolution(val resolution: Resolver.Resolution, val state: ResolverContext) : NodeResult()
        data class EmitAwaiting(val awaiting: Resolver.AwaitingClarification, val state: ResolverContext) : NodeResult()
        data class Error(val message: String, val state: ResolverContext) : NodeResult()
    }

    suspend fun run(state: ResolverContext): NodeResult {
        var current = state
        while (true) {
            when (val r = branchOnInput(current)) {
                is NodeResult.Continue -> current = r.state
                else -> return r
            }
        }
    }

    // "Edges" of the graph live in this when-table.
    private suspend fun branchOnInput(s: ResolverContext): NodeResult = when {
        s.parseState.resumeToken != null            -> decodeTokenAndApplyChoice(s)
        s.parseState.lastNode == null               -> detectLangAndParse(s)
        s.parseState.lastNode == "extractUniversal" -> proposeDomainSpans(s)
        s.parseState.lastNode == "fuzzyMatchSpans"  -> jointInference(s)
        else -> NodeResult.Error("unknown lastNode=${s.parseState.lastNode}", s)
    }

    private suspend fun fuzzyMatchSpans(s: ResolverContext): NodeResult = coroutineScope {
        val results = s.parseState.filteredSpans.map { sp ->
            async { sp.charStart.toString() to callFuzzyService(sp, s) }
        }.awaitAll().toMap()
        NodeResult.Continue(s.copy(parseState = s.parseState.copy(fuzzyMatches = results, lastNode = "fuzzyMatchSpans")))
    }
}
```

**See more:**
- `agents/resolver/src/main/kotlin/agents/resolver/koog/ResolverGraph.kt` — full 7-node graph with HITL clarification loop and parallel fuzzy lookup
- `agents/resolver/src/main/kotlin/agents/resolver/koog/ResolverModels.kt` — immutable `ResolverContext` / `ParseState` / `OutcomeState`
- `agents/resolver/src/main/kotlin/agents/resolver/Main.kt` — graph instantiation and Ktor wiring
- `agents/resolver/src/test/kotlin/agents/resolver/koog/ResolverGraphNodeTest.kt` — per-node unit tests

---

## 5. Python + LangGraph agent

The reference implementation is `agents/golem` (Python + LangGraph + FastAPI).

### 5a. State definition (`TypedDict` with `add_messages` reducer)

**Best practice.** Use `TypedDict` (not Pydantic) for the LangGraph state so it interops with `add_messages` reducers and accepts partial-dict updates returned from nodes. Annotate `messages` with `Annotated[list[BaseMessage], add_messages]`, carry a `phase` literal that conditional edges read to route, and keep per-turn flags (`dry_run`, `correlation_id`) on the same dict so every node sees them without extra plumbing.

```python
from typing import Annotated, Literal, Optional
from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages
from typing_extensions import TypedDict


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    phase: Optional[Literal[
        "erp_pattern_query", "local_pattern_query",
        "select_erp_named_query", "select_pattern", "format_response",
    ]]
    database: str
    user_id: Optional[str]
    correlation_id: Optional[str]
    step_history: list[str]
    query_result: Optional[dict]
    dry_run: Optional[bool]
```

**See more:** `agents/golem/src/agent/state.py`.

### 5b. Node function signature

**Best practice.** Each node is `async def name_node(state: AgentState) -> dict`, returns a **partial** state update (LangGraph merges it), and chooses the next stop by setting `phase`. Wrap the body in OTel metrics / span decorators, append to `step_history`, dispatch a `node_start` custom event for streaming, guard required inputs (`user_id`), check the MCP circuit breaker, then call the MCP client and inspect the result.

```python
async def erp_pattern_query_node(state: AgentState) -> dict:
    node_invocation_counter.add(1, {"node": "erp_pattern_query"})
    await adispatch_custom_event("node_start", {"node": "erp_pattern_query"})

    last_msg = get_last_user_message(state)
    step_history = state.get("step_history", []) + ["erp_pattern_query"]
    user_id = state.get("user_id")
    correlation_id = state.get("correlation_id")

    if not user_id:
        return {"messages": [AIMessage(content=get_prompt("agent.missing_user_id"))],
                "phase": None, "step_history": step_history}
    if erp_data_client.breaker_open:
        raise McpServiceUnavailableError("erp-data-mcp", "circuit breaker open")

    try:
        result = await erp_query_client.pattern_query(
            query=last_msg, database=state.get("database", settings.BP_TEST_DATABASE),
            user_id=user_id, correlation_id=correlation_id,
        )
        if _has_data(result):
            return {"query_result": result, "phase": "format_response", "step_history": step_history}
    except McpServiceUnavailableError:
        raise
    except Exception as e:
        log_tool_exception(logger, "ERP Data pattern query failed", e)

    return {"phase": "local_pattern_query", "step_history": step_history}
```

**See more:** `agents/golem/src/agent/nodes.py`.

### 5c. Graph assembly

**Best practice.** Build the graph in a `build_graph()` factory that returns a compiled app instantiated once at module load. Register every node, then use `add_conditional_edges(src, router_fn)` where `router_fn(state) -> str` reads `state["phase"]` and returns the next node name (or `END`). Compile with `MemorySaver()` so per-`thread_id` state persists across `/chat` turns.

```python
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph


def build_graph():
    workflow = StateGraph(AgentState)
    workflow.add_node("erp_pattern_query", erp_pattern_query_node)
    workflow.add_node("local_pattern_query", local_pattern_query_node)
    workflow.add_node("format_response", format_response_node)
    # ... remaining nodes ...

    def route_after_erp(state: AgentState) -> str:
        phase = state.get("phase")
        if phase == "format_response": return "format_response"
        if phase == "local_pattern_query": return "local_pattern_query"
        if phase in {"select_erp_named_query", "select_pattern"}: return str(phase)
        return END

    workflow.add_conditional_edges("erp_pattern_query", route_after_erp)
    workflow.add_edge("format_response", END)
    workflow.set_entry_point("erp_pattern_query")

    return workflow.compile(checkpointer=MemorySaver())


agent_app = build_graph()
```

Invocation from FastAPI:
```python
await agent_app.ainvoke(inputs, config={"configurable": {"thread_id": session_id}})
```

**See more:**
- `agents/golem/src/agent/graph.py`
- `agents/golem/src/api/routes.py` (FastAPI wiring, sync `/chat` and SSE `/chat/stream`)

### 5d. MCP client consumption (long-lived `ClientSession` with timeouts)

**Best practice.** Wrap each MCP server in an `MCPClientManager` that owns one persistent `mcp.ClientSession` opened over `streamable_http_client`, guarded by an `asyncio.Lock`, with a health-probe `send_ping()` loop and a circuit breaker. **Always** wrap `session.initialize()`, `load_mcp_tools(...)`, and `session.call_tool(...)` in `asyncio.wait_for(...)` with per-tool timeouts from settings — never call MCP without a timeout. Use `langchain_tools_context` when you need LangChain `BaseTool` adapters for an LLM step; use `call_tool(name, args)` directly for fixed invocations.

```python
class MCPClientManager:
    async def _open_persistent_session(self, *, headers=None) -> ClientSession:
        stack = contextlib.AsyncExitStack()
        http_client = await stack.enter_async_context(
            httpx.AsyncClient(headers=headers or {},
                              timeout=httpx.Timeout(connect=settings.MCP_CONNECT_TIMEOUT,
                                                    read=300, write=30, pool=5))
        )
        read_stream, write_stream, _ = await stack.enter_async_context(
            streamable_http_client(self.url, http_client=http_client)
        )
        session = await stack.enter_async_context(ClientSession(read_stream, write_stream))
        await asyncio.wait_for(session.initialize(), timeout=settings.MCP_INIT_TIMEOUT)
        self._session, self._session_stack = session, stack
        return session

    async def call_tool(self, name: str, arguments: dict, *, user_id=None, correlation_id=None):
        if self.breaker_open:
            raise McpCircuitOpenError(self.name, settings.MCP_CIRCUIT_BREAKER_COOLDOWN)
        async with self.session(user_id=user_id, correlation_id=correlation_id) as s:
            try:
                return await asyncio.wait_for(
                    s.call_tool(name, arguments=arguments),
                    timeout=self._timeout_for(name),
                )
            except asyncio.TimeoutError:
                self._consecutive_failures += 1
                raise McpToolTimeoutError(name, self._timeout_for(name))
```

**See more:**
- `agents/golem/src/mcp_client/client_manager.py`
- Typed wrappers per server: `agents/golem/src/mcp_client/erp_data.py`, `metadata_mcp.py`, `local_mcp.py`

---

## 6. Frontend in TypeScript + Vue 3

Two production frontends — pick the one matching the consumer:

- `frontends/agents-fe/` — Vue 3 + PrimeVue + i18n + Dockview. Scoped `<style>` blocks per SFC; colour from PrimeVue CSS variables (`var(--p-surface-700)`).
- `frontends/erp-sql-fe/` — Vue 3 + Tailwind. Tailwind utility classes plus a small `ui/` kit.

> Note on the "split CSS/HTML/JS into separate files" rule (CLAUDE.md): currently realized as scoped `<style>` blocks per SFC plus shared theme tokens. No inline `style=""` attributes on markup, no hardcoded colours. `<style src="./Foo.css" scoped>` is allowed but not currently in use.

### 6a. Typed component (props + emits + scoped style + theme tokens)

**Best practice.** Declare props with `defineProps<T>()` (type-only generic — no runtime `{...}` form). Pull colour values from CSS variables, never hex literals. Keep CSS in a scoped `<style>` block in the same SFC. i18n strings via `useI18n()` — no bare English in templates.

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useTabsStore } from '@/stores/tabsStore'

interface DockviewTabParams {
  params?: { panelId?: string }
  api?: { close: () => void; setTitle: (title: string) => void }
}

const props = defineProps<{ params: DockviewTabParams }>()
const tabsStore = useTabsStore()
const { t } = useI18n()

const panelId = computed(() => props.params?.params?.panelId)
const title = computed(() => panelId.value ? tabsStore.panels[panelId.value]?.title ?? 'Untitled' : 'Untitled')

const onClose = (e: MouseEvent) => { e.stopPropagation(); props.params?.api?.close() }
</script>

<template>
  <div class="tab-header">
    <span class="tab-title" :title="title">{{ title }}</span>
    <Button text rounded size="small" icon="pi pi-times"
            :aria-label="t('tabs.closeTabAria', { title })"
            @click.stop="onClose" @mousedown.stop />
  </div>
</template>

<style scoped>
.tab-header {
  display: flex; align-items: center; gap: 0.4rem;
  padding: 0 0.65rem 0 0.85rem;
  height: 100%;
  font-size: 0.78rem;
  color: var(--p-surface-700);
}
</style>
```

**See more:**
- `frontends/agents-fe/src/components/tabs/TabHeader.vue` (PrimeVue + scoped CSS)
- `frontends/erp-sql-fe/src/components/ui/BaseInput.vue` (Tailwind + `defineEmits` + `withDefaults`)
- `frontends/agents-fe/src/components/layout/ConnectionStatusDot.vue` (dynamic theme colour via `:style` + `computed`)

### 6b. Composable (`useXxx()`)

**Best practice.** Module-level singleton state for app-wide concerns, exposed as `readonly()` refs plus action functions. Return a plain object — no `reactive()` wrap. Composables hold timers/cleanup; components call `start()`/`stop()` in `onMounted`/`onUnmounted`.

```ts
import { ref, readonly } from 'vue'
import type { HealthResponse } from '@/types/agent-responses'

export type ConnectionStatus = 'ok' | 'degraded' | 'disconnected'
const POLL_INTERVAL_MS = 30_000
const MAX_BACKOFF_MS = 60_000

const _status = ref<ConnectionStatus>('disconnected')
const _lastSuccessAt = ref<Date | null>(null)
let _pollTimer: ReturnType<typeof setTimeout> | null = null
let _backoff = POLL_INTERVAL_MS
let _started = false

const _poll = async () => {
  try {
    const res = await fetch(`${_currentBaseUrl}/health`)
    const data: HealthResponse = await res.json()
    _status.value = data.status === 'ok' ? 'ok' : 'degraded'
    _lastSuccessAt.value = new Date()
    _backoff = POLL_INTERVAL_MS
  } catch {
    _backoff = Math.min(_backoff * 2, MAX_BACKOFF_MS)
    _status.value = 'degraded'
  }
  _pollTimer = setTimeout(_poll, _backoff)
}

export function useConnectionStatus() {
  const start = (baseUrl: string) => { if (_started) return; _started = true; _poll() }
  const stop = () => { if (_pollTimer) clearTimeout(_pollTimer); _started = false }
  return { status: readonly(_status), lastSuccessAt: readonly(_lastSuccessAt), start, stop }
}
```

**See more:** `frontends/agents-fe/src/composables/useConnectionStatus.ts`, `useTheme.ts`, `useAgentSession.ts`.

### 6c. Typed API client

**Best practice.** One shared `api` axios instance with auth/header/refresh interceptors, then a thin `xxxService` module per backend that exposes typed `async` methods. Request/response types live next to the service. Base URLs come from `config/`, never hardcoded.

```ts
import { api } from './api'
import { config } from '@/config'
import type { ValidatorResponse } from './validatorService'

export interface EntityQueryRequest {
  entity: string
  parameters: Record<string, string>
  database: string
  format?: string
  queryType?: 'select' | 'count' | 'sum'
}

export const entityService = {
  async query(request: EntityQueryRequest): Promise<ValidatorResponse> {
    const response = await api.post(`${config.services.entityService.baseUrl}/query`, request)
    return response.data
  },
  async getCatalogEntities(): Promise<EntityCatalog[]> {
    const response = await api.get(`${config.services.entityService.baseUrl}/catalog/entities`)
    return response.data
  },
}
```

**See more:**
- `frontends/erp-sql-fe/src/services/api.ts` (Keycloak refresh, `X-User-Id`, 401 retry)
- `frontends/erp-sql-fe/src/services/entityService.ts` (consumer)
- `frontends/agents-fe/src/services/llmGatewayService.ts` (proto-generated-type variant)

### 6d. Pinia store (Composition / "setup" form)

**Best practice.** Use the function form of `defineStore('id', () => { ... })` — declare `ref()` state, define action functions, return everything. Avoid the options-API form. Keep stores minimal — pure state + small mutators; orchestration goes in composables.

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Chip } from '@/types/envelope'

export const useChipStore = defineStore('chips', () => {
  const staticChips = ref<Chip[]>([])
  const dynamicChips = ref<Chip[]>([])

  const setStaticChips = (chips: Chip[]) => { staticChips.value = chips }
  const setDynamicChips = (chips: Chip[]) => { dynamicChips.value = chips }
  const clearDynamic = () => { dynamicChips.value = [] }

  return { staticChips, dynamicChips, setStaticChips, setDynamicChips, clearDynamic }
})
```

**See more:**
- `frontends/agents-fe/src/stores/chipStore.ts` (minimal)
- `frontends/agents-fe/src/stores/auth.ts` (with async actions + Keycloak)

---

## 7. Apache Calcite (SQL, RelNode, RelBuilder)

### Architectural deviation

The v1 query-translator deliberately walks the **proto `PlanNode` wire form** in its semantic stages (`Unfold`, `JoinerLogical`, `JoinerPhysical`, `MapToPhysical`). Calcite (`RelNode` / `RelBuilder` / `Planner`) is engaged only at the SQL parse/validate boundary and the final SQL unparse boundary. Reasons codified in source: (1) avoids Calcite's cluster-mixing constraints when fragments come from multiple planners; (2) avoids the protected `LogicalJoin` / `Project` constructors that block manual `RelNode` synthesis; (3) makes encode/decode round-trips byte-stable.

### 7a. Framework + schema setup

**Best practice.** A `Planner` is single-use — its internal cluster, validator and `RelOptUtil` caches are stateful and cannot be reused. Construct one `TranslatorFramework` per query, hand out **fresh** `Planner` / `RelBuilder` instances per stage, then discard. Schema is mounted as sub-schemas under a root from `Frameworks.createRootSchema(true)`; parser config uses `Lex.MYSQL_ANSI` for case-insensitive identifiers and dual quoting.

```kotlin
class TranslatorFramework(
    val model: ModelHandle,
    val schemaCode: SchemaCode = SchemaCode.DB,
    val namespace: String = "dbo",
) {
    val schemaPlusAdapter = SchemaPlusAdapter(model)

    val rootSchema: SchemaPlus =
        Frameworks.createRootSchema(true).also {
            if (model.namespaces(SchemaCode.DB).isNotEmpty())  it.add("db",  schemaPlusAdapter.db)
            if (model.namespaces(SchemaCode.ER).isNotEmpty())  it.add("er",  schemaPlusAdapter.er)
            if (model.namespaces(SchemaCode.OBJ).isNotEmpty()) it.add("obj", schemaPlusAdapter.obj)
        }

    private val frameworkConfig: FrameworkConfig =
        Frameworks.newConfigBuilder()
            .parserConfig(SqlParser.config().withLex(Lex.MYSQL_ANSI))
            .defaultSchema(/* nav into <schemaCode>/<namespace> or fall back to root */)
            .build()

    fun newPlanner(): Planner = Frameworks.getPlanner(frameworkConfig)       // single-use!
    fun newRelBuilder(): RelBuilder = RelBuilder.create(frameworkConfig)
}
```

**See more:** `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/framework/TranslatorFramework.kt`, `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/schema/SchemaPlusAdapter.kt`.

### 7b. SQL parsing and validation

**Best practice.** The `Planner` enforces a state machine `RESET → PARSED → VALIDATED → CONVERTED`. Drive parse + validate + rel-conversion through the **same** planner instance, and only ever feed it a fresh planner. Catch the four distinct Calcite exception types (`SqlParseException`, `ValidationException`, `SqlValidatorException`, `RelConversionException`) and surface them as structured errors with code, line, column — never raw exceptions to callers. The standalone `SqlParser.parseQuery` (no planner) is for inspection-only paths (UI parse-error highlighting, lint).

```kotlin
object SqlValidator {
    fun validateAndConvert(planner: Planner, sqlText: String): ValidateResult =
        try {
            val parsed = planner.parse(sqlText)
            val validated = planner.validate(parsed)
            val rel = planner.rel(validated).rel
            ValidateResult.Success(rel)
        } catch (ex: SqlParseException)     { ValidateResult.Failure(toError("parse_failed", ex)) }
        catch (ex: ValidationException)     { ValidateResult.Failure(toError("validation_failed", ex)) }
        catch (ex: SqlValidatorException)   { ValidateResult.Failure(toError("validation_failed", ex)) }
        catch (ex: RelConversionException)  { ValidateResult.Failure(toError("rel_conversion_failed", ex)) }
}
```

**See more:** `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/codec/sql/SqlValidator.kt`, `SqlParser.kt`.

### 7c. RelNode construction with RelBuilder

**Best practice.** Thread **one** `RelBuilder` through a recursive descent; each branch leaves its sub-tree on the builder's stack and the parent pops it. Stack discipline means a buggy walker fails loudly with `IllegalStateException` rather than producing a wrong tree. Always pass `force = true` to `RelBuilder.project(nodes, aliases, force)` when you must preserve identity projects — without it `RelBuilder` silently elides a project whose nodes equal the input row type, breaking structural round-trips (encode/decode/encode byte stability).

```kotlin
object PlanNodeDecoder {
    fun decode(plan: PlanNode, framework: TranslatorFramework): RelNode {
        val builder = framework.newRelBuilder()
        push(builder, plan)
        return builder.build()
    }

    private fun pushProject(builder: RelBuilder, project: ProjectNode) {
        push(builder, project.input)
        val nodes   = project.expressionsList.map { Expressions.decode(builder, it.expression) }
        val aliases = project.expressionsList.map { it.alias.ifEmpty { null } }
        // force=true preserves identity Projects — RelBuilder would otherwise elide them.
        builder.project(nodes, aliases, true)
    }
}
```

**See more:** `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/wire/PlanNodeDecoder.kt`, `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/wire/Expressions.kt`.

### 7d. The proto-level walker pattern

**Best practice (and why).** Semantic transformations operate on the proto `PlanNode` wire form, not on `RelNode`. This **side-steps Calcite cluster-mixing** (fragments from different planners cannot be stitched together) and the **protected `LogicalJoin` / `Project` constructors** that block free `RelNode` synthesis. Calcite is decoded-into once, at the orchestrator boundary, when SQL is actually emitted. `JoinerPlanWalker.rewriteChildren` is the shared post-order child-rewrite helper used by `JoinerLogical`, `JoinerPhysical`, `MapToPhysical`, and `Unfold`. Reference-equality return when no child changed makes idempotency cheap and the rewriter allocation-free on no-op trees.

```kotlin
object JoinerPlanWalker {
    fun rewriteChildren(plan: PlanNode, rewrite: (PlanNode) -> PlanNode): PlanNode =
        when (plan.nodeCase) {
            PlanNode.NodeCase.JOIN -> {
                val newLeft  = rewrite(plan.join.left)
                val newRight = rewrite(plan.join.right)
                if (newLeft === plan.join.left && newRight === plan.join.right) plan
                else plan.toBuilder()
                    .setJoin(plan.join.toBuilder().setLeft(newLeft).setRight(newRight))
                    .build()
            }
            PlanNode.NodeCase.PROJECT -> {
                val newInput = rewrite(plan.project.input)
                if (newInput === plan.project.input) plan
                else plan.toBuilder().setProject(plan.project.toBuilder().setInput(newInput)).build()
            }
            /* FILTER, AGGREGATE, SORT, LIMIT_OFFSET, SUBQUERY — same pattern */
            else -> plan
        }
}

// Caller — post-order with idempotency check baked in.
private fun walk(plan: PlanNode, matcher: RelationMatcher, warnings: MutableList<JoinerWarning>): PlanNode {
    val withChildren = JoinerPlanWalker.rewriteChildren(plan) { walk(it, matcher, warnings) }
    if (withChildren.nodeCase != PlanNode.NodeCase.JOIN) return withChildren
    val join = withChildren.join
    if (join.hasCondition()) return withChildren        // idempotent
    /* ... match relation, insert equality condition ... */
}
```

**See more:**
- `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/joiner/JoinerPlanWalker.kt`
- `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/joiner/JoinerLogical.kt`, `JoinerPhysical.kt`
- `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/schema/Unfold.kt`, `MapToPhysical.kt`

### 7e. SQL string generation (RelNode → dialect SQL)

**Best practice.** Use `RelToSqlConverter(dialect).visitRoot(rel).asStatement()` and emit with `toSqlString { it.withDialect(dialect) }`. Never reach for `MssqlSqlDialect.DEFAULT` directly — go through the `Dialects` registry which constructs the dialect with the right version flags (e.g. `databaseMajorVersion = 11` so `OFFSET/FETCH` stays enabled). Accept some Calcite noise: explicit `NULLS FIRST` / `NULLS LAST` expands to `CASE WHEN ... IS NULL THEN 0 ELSE 1 END, <key>` — semantically correct; v1 chooses not to fight Calcite for a stylistic difference.

```kotlin
object RelToSqlUnparser {
    fun unparse(rel: RelNode, dialectCode: SqlDialectProto): String =
        unparse(rel, Dialects.byCode(dialectCode))       // version-aware

    fun unparse(rel: RelNode, dialect: SqlDialect): String {
        val converter = RelToSqlConverter(dialect)
        val sqlNode = converter.visitRoot(rel).asStatement()
        return sqlNode.toSqlString { it.withDialect(dialect) }.sql
    }
}
```

**See more:** `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/codec/sql/RelToSqlUnparser.kt`, `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/dialects/Dialects.kt`.

### 7f. Named-parameter bridge (`prepareSqlForCalcite`)

**Best practice.** Calcite's parser does not natively understand `{name}` placeholders. `ParameterBridge.prepareSqlForCalcite()` handles three things Calcite gets wrong if used naively: identity-by-name dedup of `{a}` references; pre-supplied parameter types for `RexDynamicParam` so validation doesn't fail; and name restoration after Calcite mangles names into positions.

**See more:** `shared/libs/kotlin/query-translator/src/main/kotlin/shared/translator/params/ParameterBridge.kt`.

### Numbered "Calcite engagement rules" referenced inline

The codebase cites these in KDoc throughout:
- **#1** — never use a default dialect singleton; go through `Dialects.byCode()`.
- **#2** — `Planner` is single-use per query stage.
- **#3 / #6** — pre-supply parameter types; restore parameter names post-validation.
- **#5** — the wrapper only does standard SQL parsing; the planner is for custom rules, not classical optimisation.
- **#8** — accept Calcite's `NULLS FIRST` / `LAST` `CASE` expansion as visual noise.

---

## 8. OpenTelemetry setup

All services use OTel via the shared `shared/libs/kotlin/otel-config` library. Stack: Alloy → Tempo (traces), Prometheus (metrics), Loki (logs).

### 8a. SDK bootstrap

**Best practice.** Call `createOpenTelemetrySdk(...)` once at the top of `main()`, before any Ktor/Spring setup. Service name and protocol come from env vars so the same image runs identically across local K3s and prod.

```kotlin
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "my-service",
            protocol = System.getenv("MY_SERVICE_OTEL_PROTOCOL") ?: "grpc",
        ),
    )
    // ... rest of main
}
```

### 8b. `logback.xml` OTEL appender

```xml
<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureExperimentalAttributes>true</captureExperimentalAttributes>
    <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    <captureLoggerContext>true</captureLoggerContext>
    <captureMdcAttributes>*</captureMdcAttributes>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
</root>
```

### 8c. `build.gradle.kts` dependencies

```kotlin
dependencies {
    implementation(project(":shared:libs:kotlin:otel-config"))
    api(libs.otel.logback.appender)
    implementation(libs.otel.exporter.otlp)
}
```

### 8d. Auto-configured SDK gotcha

If you use `AutoConfiguredOpenTelemetrySdk` instead of the shared bootstrap, you **must** also install the logback appender manually — otherwise log forwarding is silently disabled:

```kotlin
val openTelemetry = AutoConfiguredOpenTelemetrySdk.builder().build().openTelemetrySdk
OpenTelemetryAppender.install(openTelemetry)
```

### Environment variables

| Variable | Default | Description |
| :--- | :--- | :--- |
| `OTEL_EXPORTER_OTLP_HOST` | `localhost` | Alloy/collector host |
| `OTEL_EXPORTER_OTLP_GRPC_PORT` | `4317` | gRPC port for OTLP |
| `OTEL_EXPORTER_OTLP_HTTP_PORT` | `4318` | HTTP port for OTLP |
| `<SERVICE>_OTEL_PROTOCOL` | `grpc` | Per-service protocol override |

**See more:** `shared/libs/kotlin/otel-config/src/main/kotlin/shared/otel/`.
