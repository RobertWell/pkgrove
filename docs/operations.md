# The operation layer — one declaration, every surface (HEL-602)

**An application that serves both a REST API and an MCP tool surface writes
every operation twice: once as a resource method, once as a tool. The two
copies drift, and the drift is a security problem — the question "can an AI
client call this?" stops having an answer you can read.**

`pkgrovekit-operation-core` makes the operation the unit of declaration. One
declaration carries the id, the surfaces, the required scopes, the validation,
the idempotency key and the handler; the REST and MCP adapters are projections
of it. The surfaces are **declared, never implied** — and an operation that
declares none does not build.

- **Status:** opt-in. Nothing in the data-access spine depends on it, and
  `pkgrovekit-operation-core` has **zero runtime dependencies** (Kotlin stdlib
  only), so it imposes no JSON, REST, MCP or DI stack on a consumer.
- **Modules:** `pkgrovekit-operation-core` (the model + DSL + pipeline),
  `pkgrovekit-operation-rest` (HTTP), `pkgrovekit-operation-mcp` (MCP tools),
  `pkgrovekit-operation-quarkus` (CDI wiring + framework-identity seam).

## Contents

- [Getting started](#getting-started)
- [The one-screen declaration](#the-one-screen-declaration)
- [Fail-closed surface classification](#fail-closed-surface-classification)
- [The surface policy](#the-surface-policy)
- [Codecs](#codecs)
- [Validation](#validation)
- [Idempotency](#idempotency)
- [The pipeline](#the-pipeline)
- [Auth profiles — different credentials, one caller](#auth-profiles--different-credentials-one-caller)
- [The REST adapter](#the-rest-adapter)
- [The MCP adapter](#the-mcp-adapter)
- [Quarkus wiring](#quarkus-wiring)
- [Coexisting with a legacy MCP registry](#coexisting-with-a-legacy-mcp-registry)
- [The parity check in CI](#the-parity-check-in-ci)
- [Gallery descriptors](#gallery-descriptors)
- [What this layer deliberately does not do](#what-this-layer-deliberately-does-not-do)

## Getting started

```kotlin
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-operation-core")
    implementation("com.pkgrove:pkgrovekit-operation-rest")      // if you serve HTTP
    implementation("com.pkgrove:pkgrovekit-operation-mcp")       // if you serve MCP tools
    implementation("com.pkgrove:pkgrovekit-operation-quarkus")   // if you are on Quarkus/CDI
}
```

Maven consumers declare the same four artifacts under `com.pkgrove`. Until a
release is cut they are available from a local `publishToMavenLocal` — see
[docs/RELEASING.md](RELEASING.md).

## The one-screen declaration

```kotlin
val ops = operations {
    // Codecs: one line per transported type, shared by every operation and by
    // both surfaces (see "Codecs" below for why these are explicit).
    decoder<GetPlan> { GetPlan(it.string("planId")) }
    decoder<SetActual> {
        SetActual(
            planId = it.string("planId"),
            itemId = it.string("itemId"),
            state = it.string("state"),
            amount = it.decimalStringOrNull("amount"),
            opId = it.stringOrNull("opId"),
        )
    }
    encoder<PlanDetail> { Json.obj("planId" to Json.of(it.planId), "title" to Json.of(it.title)) }
    encoder<MutationResponse> { Json.obj("revision" to Json.of(it.revision)) }

    read<GetPlan, PlanDetail>("plan.get") {
        both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
        requires("plans:read")
        handle(plans::get)
    }

    write<SetActual, MutationResponse>("actual.set") {
        both {
            rest POST "/v1/plans/{planId}/items/{itemId}/actual"
            mcp("set_item_actual")
        }
        requires("plans:write")
        validate { field(SetActual::state) { required() } }
        idempotent(SetActual::opId)
        handle(actualTrip::setActual)
    }

    write<RotateCreds, Unit>("admin.credentials.rotate") {
        restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
            rest POST "/v1/admin/credentials/rotate"
        }
        requires("admin")
        categories(OperationCategory.CREDENTIAL_ROTATION)
        handle(admin::rotate)
    }

    read<ExplainReq, Explanation>("recommendation.explain") {
        mcpOnly(reason = "Agent-specific reasoning helper") { mcp("explain_recommendation") }
        handle(recs::explain)
    }

    write<ReindexRequest, Unit>("plan.reindex") {
        internalOnly(reason = "batch job")
        handle(jobs::reindex)
    }
}
```

`rest GET "/path"` is an infix member of the surface block, so it needs no
import. `mcp("get_plan")` is a plain call — `mcp "get_plan"` is not valid
Kotlin (an infix call needs an explicit receiver), so `mcp tool "get_plan"` is
provided as the infix form if you prefer it.

Other clauses: `describe(...)`, `tags(...)`, `status(OperationStatus.DEPRECATED)`,
`input { field(...) }` / `output { ... }`, `example(...)`, `decode { }` /
`encode { }` (per-operation codec override), `handleInput { }` (a handler that
does not need the caller), `audited(false)`, `metered(false)`,
`idempotentBy { }`, `mcpOverride(reason = ...)`.

## Fail-closed surface classification

Every operation declares **exactly one** of:

| Declaration | Reachable on | `reason` |
|---|---|---|
| `both { rest ...; mcp(...) }` | REST + MCP (+ in-process) | — |
| `restOnly(reason) { rest ... }` | REST only | **required** |
| `mcpOnly(reason) { mcp(...) }` | MCP only | **required** |
| `internalOnly(reason)` | in-process only | **required** |

There is **no default**. `operations { }` throws
`OperationDeclarationException` — before the registry exists — on any of:

- an operation that declared no surface, or declared more than one;
- a blank `reason` on a restricted form;
- a duplicate operation id;
- a duplicate REST `(method, path)`;
- a duplicate MCP tool name;
- a REST path template whose `{param}`s are missing from a declared input schema;
- validation rules naming a field the declared input schema does not contain;
- an operation exposed on a transport with no codec for its input/output;
- an MCP exposure the [surface policy](#the-surface-policy) refuses.

Every message names the offender, and the registry reports **all** violations at
once rather than the first.

The registry is immutable afterwards: `all()` is unmodifiable and the lookup maps
are defensive copies, so an adapter cannot be handed a registry and then have an
operation appear in it.

Enumerations for reviews, docs and CI:

```kotlin
ops.classification()        // id -> "both" | "rest_only" | "mcp_only" | "internal_only"
ops.restOnlyExclusions()    // the operations withheld from MCP, with reasons
ops.exclusions()            // everything withheld from any surface, with reasons
ops.mcpToolNames()          // exactly what an agent can see
```

## The surface policy

A `SurfacePolicy` is the deployment-wide rule about what may reach an AI client.
Tagging is declarative (`categories(...)`); the refusal is the policy's.

```kotlin
val ops = operations(CategorySurfacePolicy.default) { ... }
```

`CategorySurfacePolicy.default` refuses MCP exposure for
`CREDENTIAL_ROTATION`, `PRIVILEGED_ADMIN`, `OAUTH_CALLBACK`,
`WEBHOOK_RECEIVER` and `BINARY_TRANSFER`. (`DESTRUCTIVE_DELETE` and
`BULK_EXPORT` exist as categories but are not refused by default — plenty of
applications legitimately want an agent to delete a draft.)

An operation in a refused category that is nevertheless MCP-exposed must say
why:

```kotlin
mcpOverride(reason = "feature flags are tenant-scoped and reversible; reviewed 2026-10")
```

A blank override reason is refused. `PermissiveSurfacePolicy` objects to
nothing, for tests and internal-only catalogues.

## Codecs

`pkgrovekit-operation-core` is zero-dependency, so there is no reflective
binder: decoding is one explicit line per transported type.

```kotlin
decoder<SetActual> { SetActual(planId = it.string("planId"), state = it.string("state")) }
encoder<MutationResponse> { Json.obj("revision" to Json.of(it.revision)) }
```

The receiver is a `Json.Obj` with field-attributing accessors — `string`, `int`,
`long`, `bool`, `decimalString`, `nested`, `array`, plus `…OrNull` variants.
Each failure names the field, so a malformed payload becomes
`invalid` with `{field, code, message}` rather than a 500:

```json
{"error":"invalid","message":"the request is not valid",
 "details":[{"field":"planId","code":"required","message":"field 'planId' is required"}]}
```

`decimalString` returns the **lossless source text** of a number. Money must
never round-trip through a binary float, and a client that sends `"19.99"` as a
quoted string is being careful, not wrong — both forms are accepted.

`Unit` input and output need no codec. An operation exposed on a transport
without a codec for its types is a construction failure, not a runtime one.

## Validation

Rules are declared once against the typed input and run on **every** surface, so
a REST client and an MCP agent get the same errors for the same mistake. That is
structural, not a promise: there is one validator per operation.

```kotlin
validate {
    field(SetActual::state) {
        required()
        oneOf("PLANNED", "BOOKED", "DONE")
    }
    field(SetActual::amount) {
        positive()
        scale(2)          // rejects 1.005 rather than silently rounding it
    }
    field(SetActual::currency) { isoCurrency() }
    field(SetActual::note) { length(max = 280); pattern(Regex("[^<>]*"), "no angle brackets") }

    // a cross-field invariant no single field owns
    rule("currency_requires_amount", "currency may only be sent with an amount", field = "currency") {
        it.currency == null || it.amount != null
    }
}
```

Built-ins: `required`, `positive`, `nonNegative`, `scale(n)`, `isoCurrency`,
`length(min, max)`, `pattern(regex)`, `oneOf(...)`, `custom(code, message) { }`;
plus `rule(...)` and `custom { }` at payload level.

Every rule except `required` **skips a null value** — "absent" is `required`'s
business alone, so an optional field with a `scale(2)` rule does not produce two
errors when it is simply not sent.

Property references supply the field name (`KProperty1.name`/`get` are stdlib —
no reflection library is involved), so the error always names the field the
client sent. Use `field("path.to.thing", { it.nested.thing }) { ... }` for
computed or nested fields.

## Idempotency

```kotlin
idempotent(SetActual::opId)        // keyed on a client-supplied operation id
idempotentBy { "${it.planId}/${it.itemId}" }
```

A repeat with the same key replays the first outcome instead of applying the
mutation twice. This matters most on MCP, where a retrying agent is the normal
case rather than the exception. Keys are scoped per operation, so two operations
cannot collide; a **failed** call is not stored, so a retry really retries.

Supply the store: `IdempotencyStore.inMemory()` for tests and single-process
tools, your database in production, `IdempotencyStore.none` to disable.

## The pipeline

Stages are **values**, composed in a documented order:

```
audit -> authenticate -> decode -> validate -> authorizeOperation
      -> domainAuthorization -> idempotency -> handle
```

```kotlin
val pipeline = defaultPipeline(
    profiles = authProfiles,
    domainAuthorizer = PlanOwnership,            // entity-level decisions
    idempotencyStore = PostgresIdempotency(ds),
    audit = auditLog,
    metrics = micrometerSink,
    tracer = otelTracer,
    extraStages = listOf(rateLimit),             // inserted just before `handle`
)
pipeline.stageNames()  // the composition, assertable in a test
```

Compose your own with `OperationPipeline(listOf(...))` and `stage(name) { exchange, next -> ... }`.

`audit` is **outermost** on purpose: a failed authentication is exactly the kind
of event that must not go unaudited. The audit record carries source
attribution — `surface` + `clientId` + `authProfileName` — so after the fact
"an agent did this" is distinguishable from "a person did this" without
correlating logs by hand.

Operation-level scopes (`requires(...)`) answer *may this caller call this
operation at all*. Whether **this** caller may touch **that** plan is a domain
question the library cannot answer, so `DomainAuthorizer` is a declared seam
rather than a guess. `AuditSink`, `MetricsSink` and `Tracer` are interfaces with
no-op defaults; the library chooses no logging or metrics stack for you.

`OperationError` is the closed set of outcomes — `Unauthenticated`, `Forbidden`,
`Invalid(errors)`, `NotFound`, `Conflict`, `Failed` — and adapters **map** them,
never invent their own. Handlers raise them with `notFound(...)`,
`conflict(...)`, `forbidden(...)`, `invalid(...)`. An unexpected exception
becomes `Failed` with the throwable kept for logs and **off the wire**.

## Auth profiles — different credentials, one caller

REST and MCP normally present different credentials: a user's bearer token
versus an agent session token. Their profiles differ; what they produce does
not.

```kotlin
val profiles = AuthProfiles.of(
    Surface.REST to authProfile<String>("rest-bearer", "Authorization: Bearer <jwt>") { token, request ->
        jwt.verify(token)?.let {
            CallerContext(it.subject, request.surface, scopes = it.scopes, clientId = it.clientId)
        }
    },
    Surface.MCP to authProfile<McpSession>("mcp-session", "MCP session token") { session, request ->
        sessions.lookup(session.id)?.let {
            CallerContext(it.subject, request.surface, scopes = it.scopes, clientId = it.agentName)
        }
    },
)
```

Everything downstream — authorization, idempotency, the handler, the audit
record — sees one `CallerContext`, so a handler cannot accidentally behave
differently for an agent than for a person.

Returning `null` means *not authenticated*; it must never mean "authenticated
with no scopes". A credential of the wrong **type** also resolves to `null` — a
transport presenting the wrong credential shape is an auth failure, not a server
fault. A surface with **no** configured profile is unauthenticatable: forgetting
to configure MCP auth must not silently admit every agent.

## The REST adapter

Framework-free: the dispatcher takes `(method, path, headers, body)` and returns
`(status, body)`, so your host owns its JAX-RS / Vert.x / Ktor resource classes
and this module pins no REST API version onto you.

```kotlin
@Path("/")
class OperationResource(private val dispatcher: RestDispatcher, private val identity: SecurityIdentity) {

    @POST @Path("{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    fun post(@PathParam("path") path: String, body: String?, @Context uri: UriInfo): Response {
        val response = dispatcher.dispatch(
            RestRequest(
                method = HttpMethod.POST,
                path = "/$path",
                query = uri.queryParameters.mapValues { it.value.first() },
                body = body,
                correlationId = MDC.get("correlationId") ?: "",
            ),
        )
        return Response.status(response.status).entity(response.body).type(response.contentType).build()
    }
}
```

`dispatcher.routes()` lists what it serves; `handles(method, path)` tests one.
Path parameters, query parameters and the body are merged into one payload with
precedence **body < query < path** — a body claiming a different `planId` than
the URL must not win, or a caller could address one plan and mutate another.
`POST` returns 201, except a **replayed** idempotent write, which returns 200
(the resource was created by the earlier call).

Only REST-exposed operations are routable. An `mcpOnly` or `internalOnly`
operation has no binding at all, so `dispatch` cannot reach it.

Override `renderError` to keep your own error envelope.

## The MCP adapter

Transport-neutral: it does **not** depend on any MCP server runtime. The first
consumer runs its own JSON-RPC 2.0 Streamable-HTTP server at `/mcp` (protocol
2025-06-18), and a dependency on someone else's MCP runtime would make this
module unusable there. A `quarkiverse-mcp-server` adapter can be added later as
another thin module over the same `McpDispatcher`.

```kotlin
val mcp = McpDispatcher(ops, pipeline)

// tools/list
mcp.toolsListJson()       // [{name, description, inputSchema}, ...]

// tools/call
when (val result = mcp.call(name, argumentsJson, rawCredential = sessionToken)) {
    is McpCallResult.Ok -> toolResult(result.content)
    is McpCallResult.Error -> toolError(result.code, result.message, result.field)
}
```

`toolsList()` is an **allow-list**: only operations that declared an MCP binding
are present, so a `restOnly`/`internalOnly` operation has no tool name,
`handles(name)` is `false`, and `call(name, ...)` returns a `not_found` error
without reaching a handler. The exclusion is structural, not a filter someone
can forget to apply.

A write tool and a non-stable tool say so in the description an **agent** reads
(`"[write] Records what an item actually cost"`, `"[experimental] ..."`): an
agent choosing between tools has no other channel for "this one mutates state".

Errors carry the same semantic `code` / `field` / `message` the REST adapter
renders, plus the equivalent `httpStatus`, because both come from one
`OperationError.toWire()` in core.

## Quarkus wiring

```kotlin
@ApplicationScoped
open class PlanOperations(private val plans: Plans) : OperationModule {
    override fun contribute(builder: OperationsBuilder) {
        with(builder) {
            decoder<GetPlan> { GetPlan(it.string("planId")) }
            read<GetPlan, PlanDetail>("plan.get") {
                both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
                requires("plans:read")
                handle(plans::get)
            }
        }
    }
}
```

`OperationRegistryProducer` produces one `@Singleton OperationRegistry` from
every discovered `OperationModule` bean, and an
`@Observes @Initialized(ApplicationScoped)` observer builds it **at startup** —
so an undeclared surface, a duplicate tool name or a policy-refused agent
exposure **fails the boot**, naming the operation and the contributing beans.
(The CDI-standard event is used rather than `io.quarkus.runtime.StartupEvent`,
so the module needs no `quarkus-core` dependency and works in any CDI container.)

Configuration:

| Property | Meaning |
|---|---|
| `pkgrovekit.operations.mcp-refused-categories` | comma-separated category tokens to refuse for MCP, when no `SurfacePolicy` bean is supplied |
| `pkgrovekit.operations.require-operations` | `true` to fail startup when no module contributed any operation |

Two `SurfacePolicy` beans is a hard failure: "which of our two policies decides
what agents can reach" is not a question to answer by bean-resolution order.

**Identity.** `IdentityClaims` is the seam to the framework's security identity.
OAuth/JWT is **not** reimplemented — the framework verifies the token, this
layer reads the verified result:

```kotlin
@RequestScoped
class QuarkusIdentityClaims(private val identity: SecurityIdentity) : IdentityClaims {
    override val subject get() = identity.principal?.name
    override val roles get() = identity.roles
    override val clientId get() = identity.getAttribute<String>("azp")
    override fun claim(name: String) = identity.getAttribute<Any?>(name)?.toString()
}

@Produces @Singleton
fun profiles(claims: Instance<QuarkusIdentityClaims>): AuthProfiles = AuthProfiles.of(
    Surface.REST to QuarkusAuthProfiles.ambient { claims.get() },
    Surface.MCP to QuarkusAuthProfiles.credential("mcp-session") { token -> sessions.claims(token) },
    Surface.INTERNAL to QuarkusAuthProfiles.internalJob("reindexer", setOf("plans:write")),
)
```

`ambient` reads the already-verified identity (the usual Quarkus shape);
`credential` resolves a credential the host extracted from its own transport
(the MCP case); `internalJob` grants fixed scopes to in-process callers and
refuses every remote surface. A `null` **or blank** subject is unauthenticated:
an identity with no subject cannot be audited or authorized.

`quarkus-security`/`smallrye-jwt` are deliberately **not** dependencies — they
are not in this repository's version catalog or its generated
dependency-verification metadata, and the binding is four lines in the consumer.

## Coexisting with a legacy MCP registry

The normal migration state is a declarative registry living beside a
hand-written tool list. Two tools with one name is the failure mode: whichever
registry the host checks first silently wins, which is a security decision made
by accident.

```kotlin
mcp.assertNoOverlap(LegacyTools.names)   // throws, naming the collisions
```

Check it where the host assembles its tool list, so the collision is a startup
failure rather than a surprise in production.

## The parity check in CI

`ParityCheck` cross-checks a registry against what the host **actually** serves.
Run it as a test in your own CI:

```kotlin
@Test
fun `surfaces are consistent`() {
    ParityCheck.assertConsistent(
        registry = ops,
        liveMcpToolNames = mcpServer.toolNames(),
        legacyMcpToolNames = LegacyTools.names,
        legacyRestPaths = LegacyResources.paths,
        knownOperationIds = ClientSdk.operationIds,
    )
}
```

It reports:

| Violation | Meaning |
|---|---|
| `UNREGISTERED_OPERATION` | the host expects an id the registry does not declare |
| `DUPLICATE_MCP_TOOL` | a tool name served by both registries |
| `DUPLICATE_REST_PATH` | a route served by both the registry and a legacy resource |
| `RESTRICTED_OPERATION_IN_MCP_LISTING` | a withheld operation appears in the live tool listing (by id, underscored id, or REST path tail) |
| `MISSING_FROM_MCP_LISTING` | a declared tool the live listing does not contain |
| `POLICY_REFUSED_MCP_EXPOSURE` | a `policy` override refuses an exposure that has no `mcpOverride` |

The `policy` parameter answers *"if we tighten the rules, what stops being
agent-callable?"* without first shipping a build that refuses to start.

## Gallery descriptors

`registry.describe(profiles)` returns JSON-serialisable `OperationDescriptor`s —
plain Kotlin data with a hand-rolled JSON rendering, so your Jackson version can
never conflict with the library's. Each carries the id, kind, status,
description, tags, surfaces and exclusion reasons, REST method/path, MCP
name/description, input/output JSON-Schema, examples, required scopes, the
per-surface auth profile names, the policy verdict, a validation-rule summary,
the capability flags (idempotent/audited/metered) — and a **copyable DSL
snippet**.

The snippet is the point: someone reading the Gallery can paste it into their own
registry and get the same operation, rather than reverse-engineering it from a
rendered table.

`OperationCatalog.toJsonText(registry, profiles)` renders the whole catalogue as
one document — the artifact to publish, and to snapshot in CI. A reviewer can
answer "what can an agent reach, and what did we deliberately keep from it?" by
reading one file.

## What this layer deliberately does not do

- **No OAuth/JWT implementation.** The framework verifies credentials; this
  layer reads the verified result through `AuthProfile`/`IdentityClaims`.
- **No reflective binding.** Core is zero-dependency and `kotlin-reflect` is not
  on this repository's locked classpath, so codecs and schemas are declared. The
  schema declaration is cross-checked against the validation rules and the REST
  path parameters at construction time, which catches the drift that reflection
  would otherwise hide.
- **No domain authorization.** Operation scopes are enforced; entity-level
  decisions are the host's, through `DomainAuthorizer` or the handler.
- **No MCP transport.** The host owns its JSON-RPC server.
- **No server-side guessing.** Every surface, scope, codec and rule is declared;
  nothing is inferred from a client's shape.

## See also

- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — where this sits in the module hierarchy
- [gradle/allowed-dependencies.txt](../gradle/allowed-dependencies.txt) — the enforced boundary
- [docs/security-controls.md](security-controls.md) — the repository's security posture
- [docs/test-traceability.md](test-traceability.md) — which test proves which claim above
