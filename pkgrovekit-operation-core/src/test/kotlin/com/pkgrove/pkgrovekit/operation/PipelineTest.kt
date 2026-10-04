package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The functional pipeline: composition, ordering, hooks (HEL-602). */
class PipelineTest {

    private val plans = SamplePlans()
    private val registry = sampleRegistry(plans)
    private val profiles = sampleProfiles()

    private fun pipeline(
        audit: AuditSink = AuditSink.none,
        metrics: MetricsSink = MetricsSink.none,
        tracer: Tracer = Tracer.none,
        idempotency: IdempotencyStore = IdempotencyStore.none,
        domainAuthorizer: DomainAuthorizer = DomainAuthorizer.allow,
        clock: Clock = Clock.system,
        extraStages: List<Stage> = emptyList(),
    ) = defaultPipeline(
        profiles = profiles,
        domainAuthorizer = domainAuthorizer,
        idempotencyStore = idempotency,
        audit = audit,
        metrics = metrics,
        tracer = tracer,
        clock = clock,
        extraStages = extraStages,
    )

    private fun getPlanPayload(planId: String = "p-1") = Json.obj("planId" to Json.of(planId))

    private fun setActualPayload(
        state: String = "DONE",
        opId: String? = null,
        amount: String? = null,
    ): Json.Obj {
        val fields = linkedMapOf<String, Json>(
            "planId" to Json.of("p-1"),
            "itemId" to Json.of("i-2"),
            "state" to Json.of(state),
        )
        if (opId != null) fields["opId"] = Json.of(opId)
        if (amount != null) fields["amount"] = Json.Num(amount)
        return Json.Obj(fields)
    }

    @Test
    fun `the default pipeline composes the documented stage order`() {
        assertEquals(Stages.defaultOrder, pipeline().stageNames())
    }

    @Test
    fun `stages execute in that order and the handler runs last`() {
        val trace = mutableListOf<String>()
        val traced = Stages.defaultOrder.map { name ->
            stage("trace-$name") { exchange, next ->
                trace += name
                next(exchange)
            }
        }
        // Interleaving a tracer before each real stage would change the
        // composition, so instead assert the order the SUPPLIED stages see by
        // running them as their own pipeline over the real ones.
        val composed = OperationPipeline(traced + Stages.handle())
        val outcome = composed.execute(
            Exchange(
                operation = registry.byId("plan.get")!!,
                surface = Surface.REST,
                credential = REST_TOKEN,
                payload = getPlanPayload(),
                correlationId = "c-1",
                caller = CallerContext("alice", Surface.REST, setOf("plans:read")),
                input = GetPlan("p-1"),
            ),
        )
        assertEquals(Stages.defaultOrder, trace)
        assertTrue(outcome is OperationOutcome.Success)
    }

    @Test
    fun `an extra stage is inserted immediately before the handler`() {
        val seen = mutableListOf<String>()
        val names = pipeline(
            extraStages = listOf(
                stage("rateLimit") { exchange, next ->
                    seen += "rateLimit:${exchange.caller?.subjectId}"
                    next(exchange)
                },
            ),
        ).stageNames()
        assertEquals("rateLimit", names[names.size - 2])
        assertEquals("handle", names.last())
    }

    @Test
    fun `a duplicate stage name is refused and an empty pipeline is refused`() {
        assertThrows<IllegalArgumentException> {
            OperationPipeline(listOf(Stages.handle(), Stages.handle()))
        }
        assertThrows<IllegalArgumentException> { OperationPipeline(emptyList()) }
    }

    @Test
    fun `a pipeline with no terminal handler fails typed rather than hanging`() {
        val outcome = OperationPipeline(listOf(stage("passthrough") { e, next -> next(e) })).execute(
            Exchange(registry.byId("plan.get")!!, Surface.REST, REST_TOKEN, getPlanPayload(), "c"),
        )
        assertEquals("failed", outcome.errorOrNull()!!.code)
        assertTrue(outcome.errorOrNull()!!.message.contains("without a handler stage"))
    }

    @Test
    fun `the handler receives the caller the auth profile produced`() {
        val outcome = pipeline().call(
            operation = registry.byId("plan.get")!!,
            surface = Surface.REST,
            payload = getPlanPayload(),
            credential = REST_TOKEN,
        )
        val detail = (outcome as OperationOutcome.Success).output as PlanDetail
        assertEquals("Kyoto trip (for alice)", detail.title)
        assertFalse(outcome.replayed)
    }

    @Test
    fun `REST and MCP present different credentials and reach the same handler`() {
        val op = registry.byId("plan.get")!!
        val viaRest = pipeline().call(op, Surface.REST, getPlanPayload(), REST_TOKEN)
        val viaMcp = pipeline().call(
            op,
            Surface.MCP,
            getPlanPayload(),
            AgentSession("planner-agent", "alice", setOf("plans:read")),
        )
        assertEquals(
            (viaRest as OperationOutcome.Success).output,
            (viaMcp as OperationOutcome.Success).output,
        )
    }

    @Test
    fun `a wrong-shaped credential is unauthenticated, not a server fault`() {
        val outcome = pipeline().call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            credential = AgentSession("agent", "alice", setOf("plans:read")),
        )
        assertEquals("unauthenticated", outcome.errorOrNull()!!.code)
    }

    @Test
    fun `a rejected credential is unauthenticated`() {
        val outcome = pipeline().call(registry.byId("plan.get")!!, Surface.REST, getPlanPayload(), "wrong")
        assertEquals("unauthenticated", outcome.errorOrNull()!!.code)
    }

    @Test
    fun `a surface with no configured profile is unauthenticatable, not open`() {
        val restOnlyProfiles = AuthProfiles.of(
            Surface.REST to authProfile<String>("rest") { _, _ -> CallerContext("alice", Surface.REST) },
        )
        val outcome = defaultPipeline(restOnlyProfiles).call(
            registry.byId("plan.get")!!,
            Surface.MCP,
            getPlanPayload(),
            "whatever",
        )
        assertEquals("unauthenticated", outcome.errorOrNull()!!.code)
        assertTrue(outcome.errorOrNull()!!.message.contains("no auth profile is configured for surface mcp"))
    }

    @Test
    fun `a missing scope is forbidden and names what is missing`() {
        val weak = sampleProfiles(restScopes = setOf("plans:read"))
        val outcome = defaultPipeline(weak).call(
            registry.byId("actual.set")!!,
            Surface.REST,
            setActualPayload(),
            REST_TOKEN,
        )
        val error = outcome.errorOrNull() as OperationError.Forbidden
        assertEquals(setOf("plans:write"), error.missingScopes)
        assertTrue(error.message.contains("plans:write"))
    }

    @Test
    fun `roles satisfy a required scope, so a deployment may model either`() {
        val viaRoles = AuthProfiles.uniform(
            authProfile<String>("roles") { _, _ ->
                CallerContext("alice", Surface.REST, roles = setOf("plans:read"))
            },
        )
        val outcome = defaultPipeline(viaRoles).call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            "t",
        )
        assertTrue(outcome is OperationOutcome.Success)
    }

    @Test
    fun `validation runs before the handler and before authorization`() {
        val weak = sampleProfiles(restScopes = setOf("plans:read"))
        val outcome = defaultPipeline(weak).call(
            registry.byId("actual.set")!!,
            Surface.REST,
            setActualPayload(state = "NOPE"),
            REST_TOKEN,
        )
        // the scope is ALSO missing, but validation speaks first: a client fixing
        // one error at a time should be told about its own payload first
        val error = outcome.errorOrNull() as OperationError.Invalid
        assertEquals(listOf("state"), error.errors.map { it.field })
        assertEquals(0, plans.revision)
    }

    @Test
    fun `a decode failure becomes an invalid error naming the field`() {
        val outcome = pipeline().call(
            registry.byId("plan.get")!!,
            Surface.REST,
            Json.emptyObject,
            REST_TOKEN,
        )
        val error = outcome.errorOrNull() as OperationError.Invalid
        assertEquals("planId", error.errors.single().field)
        assertEquals("required", error.errors.single().code)
    }

    @Test
    fun `the domain authorization hook can reject after validation`() {
        val authorizer = DomainAuthorizer { caller, operation, input ->
            if (operation.id == "plan.get" && (input as GetPlan).planId != "p-9") {
                OperationError.Forbidden("${caller.subjectId} may only read p-9")
            } else {
                null
            }
        }
        val outcome = pipeline(domainAuthorizer = authorizer).call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            REST_TOKEN,
        )
        assertEquals("forbidden", outcome.errorOrNull()!!.code)
        assertTrue(outcome.errorOrNull()!!.message.contains("may only read p-9"))
    }

    @Test
    fun `an idempotent write replays instead of applying twice`() {
        val store = IdempotencyStore.inMemory()
        val run = pipeline(idempotency = store)
        val op = registry.byId("actual.set")!!

        val first = run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)
        val second = run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)

        assertFalse((first as OperationOutcome.Success).replayed)
        assertTrue((second as OperationOutcome.Success).replayed)
        assertEquals(first.output, second.output)
        assertEquals(1, plans.revision)
    }

    @Test
    fun `a different idempotency key applies the write again`() {
        val run = pipeline(idempotency = IdempotencyStore.inMemory())
        val op = registry.byId("actual.set")!!
        run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)
        run.call(op, Surface.REST, setActualPayload(opId = "op-2"), REST_TOKEN)
        assertEquals(2, plans.revision)
    }

    @Test
    fun `a write with no idempotency key is never replayed`() {
        val run = pipeline(idempotency = IdempotencyStore.inMemory())
        val op = registry.byId("actual.set")!!
        run.call(op, Surface.REST, setActualPayload(), REST_TOKEN)
        run.call(op, Surface.REST, setActualPayload(), REST_TOKEN)
        assertEquals(2, plans.revision)
    }

    @Test
    fun `keys are scoped per operation so two operations cannot collide`() {
        val store = IdempotencyStore.inMemory()
        store.store("other.op:op-1", "someone else's outcome")
        val outcome = pipeline(idempotency = store).call(
            registry.byId("actual.set")!!,
            Surface.REST,
            setActualPayload(opId = "op-1"),
            REST_TOKEN,
        )
        assertFalse((outcome as OperationOutcome.Success).replayed)
    }

    @Test
    fun `a failed write is not stored, so a retry really retries`() {
        val store = IdempotencyStore.inMemory()
        val run = pipeline(idempotency = store)
        val op = registry.byId("actual.set")!!
        val missing = Json.obj(
            "planId" to Json.of("absent"),
            "itemId" to Json.of("i-2"),
            "state" to Json.of("DONE"),
            "opId" to Json.of("op-1"),
        )
        assertEquals("not_found", run.call(op, Surface.REST, missing, REST_TOKEN).errorOrNull()!!.code)
        assertNull(store.lookup("actual.set:op-1"))
    }

    @Test
    fun `the disabled idempotency store never replays`() {
        val run = pipeline(idempotency = IdempotencyStore.none)
        val op = registry.byId("actual.set")!!
        run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)
        run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)
        assertEquals(2, plans.revision)
    }

    @Test
    fun `audit records carry surface and client attribution for success and failure`() {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        val run = pipeline(audit = audit, metrics = metrics)
        val op = registry.byId("plan.get")!!

        run.call(op, Surface.REST, getPlanPayload(), REST_TOKEN, correlationId = "c-rest")
        run.call(
            op,
            Surface.MCP,
            getPlanPayload(),
            AgentSession("planner-agent", "alice", setOf("plans:read")),
            correlationId = "c-mcp",
        )
        run.call(op, Surface.REST, getPlanPayload(), "bad-token", correlationId = "c-bad")

        assertEquals(3, audit.records.size)
        val (viaRest, viaMcp, failed) = audit.records

        assertEquals(Surface.REST, viaRest.surface)
        assertEquals("ios-app", viaRest.clientId)
        assertEquals("rest-bearer", viaRest.authProfileName)
        assertEquals("success", viaRest.outcome)
        assertEquals("c-rest", viaRest.correlationId)
        assertEquals(OperationKind.READ, viaRest.kind)

        assertEquals(Surface.MCP, viaMcp.surface)
        assertEquals("planner-agent", viaMcp.clientId)
        assertEquals("mcp-session", viaMcp.authProfileName)

        // An unattributable attempt is the interesting one: it is still recorded.
        assertEquals("failure", failed.outcome)
        assertEquals("unauthenticated", failed.errorCode)
        assertNull(failed.subjectId)
        assertNull(failed.clientId)

        assertEquals(
            listOf(
                "plan.get|rest|success",
                "plan.get|mcp|success",
                "plan.get|rest|unauthenticated",
            ),
            metrics.observations,
        )
    }

    @Test
    fun `a replayed call is audited and metered as a replay`() {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        val run = pipeline(audit = audit, metrics = metrics, idempotency = IdempotencyStore.inMemory())
        val op = registry.byId("actual.set")!!
        run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)
        run.call(op, Surface.REST, setActualPayload(opId = "op-1"), REST_TOKEN)

        assertFalse(audit.records[0].replayed)
        assertTrue(audit.records[1].replayed)
        assertEquals(listOf("actual.set|rest|success", "actual.set|rest|replay"), metrics.observations)
    }

    @Test
    fun `audited(false) and metered(false) suppress the hooks`() {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        val quiet = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("quiet.op") {
                internalOnly(reason = "test")
                audited(false)
                metered(false)
                handle { _, _ -> }
            }
        }
        pipeline(audit = audit, metrics = metrics).call(
            quiet.byId("quiet.op")!!,
            Surface.INTERNAL,
            Json.emptyObject,
            "job",
        )
        assertTrue(audit.records.isEmpty())
        assertTrue(metrics.observations.isEmpty())
    }

    @Test
    fun `the duration reported comes from the supplied clock`() {
        val audit = RecordingAudit()
        var now = 1_000L
        val clock = Clock {
            now += 7
            now
        }
        pipeline(audit = audit, clock = clock).call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            REST_TOKEN,
        )
        assertEquals(7L, audit.records.single().durationMillis)
    }

    @Test
    fun `request attributes reach the audit record and the caller context`() {
        val audit = RecordingAudit()
        pipeline(audit = audit).call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            REST_TOKEN,
            attributes = mapOf("clientIp" to "10.0.0.1"),
        )
        assertEquals(mapOf("clientIp" to "10.0.0.1"), audit.records.single().attributes)
    }

    @Test
    fun `the tracer wraps the whole call`() {
        val spans = mutableListOf<String>()
        val tracer = object : Tracer {
            override fun <T> span(operationId: String, surface: Surface, correlationId: String, body: () -> T): T {
                spans += "enter:$operationId:${surface.token}:$correlationId"
                val result = body()
                spans += "exit:$operationId"
                return result
            }
        }
        pipeline(tracer = tracer).call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload(),
            REST_TOKEN,
            correlationId = "c-9",
        )
        assertEquals(listOf("enter:plan.get:rest:c-9", "exit:plan.get"), spans)
    }

    @Test
    fun `the no-op hooks are inert`() {
        AuditSink.none.record(
            AuditRecord(
                "x", OperationKind.READ, Surface.REST, null, null, null, "", "success", null, 0, false, emptyMap(),
            ),
        )
        MetricsSink.none.observe("x", Surface.REST, "success", 0)
        assertEquals(42, Tracer.none.span("x", Surface.REST, "c") { 42 })
        IdempotencyStore.none.store("k", "v")
        assertNull(IdempotencyStore.none.lookup("k"))
        assertNull(DomainAuthorizer.allow.authorize(CallerContext("a", Surface.REST), registry.all().first(), null))
        assertTrue(Clock.system.millis() > 0)
    }

    @Test
    fun `a handler throwing a typed operation error keeps its type`() {
        val outcome = pipeline().call(
            registry.byId("plan.get")!!,
            Surface.REST,
            getPlanPayload("nope"),
            REST_TOKEN,
        )
        assertEquals("not_found", outcome.errorOrNull()!!.code)
    }

    @Test
    fun `an unexpected handler failure is wrapped and the cause stays off the wire`() {
        val exploding = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("boom.op") {
                internalOnly(reason = "test")
                handle { _, _ -> throw IllegalStateException("internal detail nobody should see") }
            }
        }
        val outcome = pipeline().call(exploding.byId("boom.op")!!, Surface.INTERNAL, Json.emptyObject, "job")
        val error = outcome.errorOrNull() as OperationError.Failed
        assertEquals("operation 'boom.op' failed", error.message)
        assertFalse(error.message.contains("internal detail"))
        assertEquals("internal detail nobody should see", error.cause!!.message)
        assertTrue(error.toWire().details.isEmpty())
    }

    @Test
    fun `the handler error helpers each produce their own type`() {
        val helpers = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("fail.notfound") {
                internalOnly(reason = "t")
                handle { _, _ -> notFound("gone") }
            }
            read<Unit, Unit>("fail.conflict") {
                internalOnly(reason = "t")
                handle { _, _ -> conflict("already") }
            }
            read<Unit, Unit>("fail.forbidden") {
                internalOnly(reason = "t")
                handle { _, _ -> forbidden("not yours", setOf("plans:admin")) }
            }
            read<Unit, Unit>("fail.invalid") {
                internalOnly(reason = "t")
                handle { _, _ -> invalid(listOf(ValidationError("f", "c", "m"))) }
            }
        }
        val run = pipeline()
        assertEquals(
            listOf("not_found", "conflict", "forbidden", "invalid"),
            listOf("fail.notfound", "fail.conflict", "fail.forbidden", "fail.invalid").map { id ->
                run.call(helpers.byId(id)!!, Surface.INTERNAL, Json.emptyObject, "job").errorOrNull()!!.code
            },
        )
    }

    @Test
    fun `stages run out of order fail typed instead of dereferencing a null caller`() {
        val op = registry.byId("plan.get")!!
        val exchange = Exchange(op, Surface.REST, REST_TOKEN, getPlanPayload(), "c")
        listOf(Stages.authorizeOperation(), Stages.domainAuthorization(DomainAuthorizer.allow), Stages.handle())
            .forEach { stage ->
                val outcome = OperationPipeline(listOf(stage)).execute(exchange)
                assertEquals("failed", outcome.errorOrNull()!!.code, stage.name)
                assertTrue(
                    outcome.errorOrNull()!!.message.contains("before authenticate"),
                    outcome.errorOrNull()!!.message,
                )
            }
    }

    @Test
    fun `a decoder that is not configured fails typed rather than throwing out of the pipeline`() {
        // An internal-only operation legitimately has no decoder; calling it
        // through a transport-shaped payload must still be a typed failure.
        val internal = operations(PermissiveSurfacePolicy) {
            write<ReindexRequest, Unit>("plan.reindex") {
                internalOnly(reason = "batch job")
                handle { _, _ -> }
            }
        }
        val outcome = pipeline().call(
            internal.byId("plan.reindex")!!,
            Surface.INTERNAL,
            Json.emptyObject,
            "job",
        )
        val error = outcome.errorOrNull() as OperationError.Failed
        assertTrue(error.message.contains("no decoder"), error.message)
    }

    @Test
    fun `stage toString names the stage`() {
        assertEquals("Stage(handle)", Stages.handle().toString())
    }

    @Test
    fun `duplicate auth profiles for one surface are refused`() {
        assertThrows<IllegalArgumentException> {
            AuthProfiles.of(
                Surface.REST to authProfile<String>("a") { _, _ -> null },
                Surface.REST to authProfile<String>("b") { _, _ -> null },
            )
        }
    }

    @Test
    fun `a blank auth profile name is refused`() {
        assertThrows<IllegalArgumentException> { authProfile<String>(" ") { _, _ -> null } }
    }

    @Test
    fun `profile names are reportable per surface`() {
        assertEquals(
            mapOf(Surface.REST to "rest-bearer", Surface.MCP to "mcp-session", Surface.INTERNAL to "internal-job"),
            profiles.names(),
        )
        assertEquals("rest-bearer", profiles.forSurface(Surface.REST)!!.name)
        assertEquals("Authorization: Bearer <token>", profiles.forSurface(Surface.REST)!!.credentialDescription)
        assertNull(AuthProfiles.of().forSurface(Surface.REST))
    }

    @Test
    fun `the internal-only trusting profile admits only internal callers`() {
        val trusting = AuthProfiles.trustingInternalOnly("batch")
        assertNull(trusting.forSurface(Surface.REST))
        val context = trusting.forSurface(Surface.INTERNAL)!!
            .authenticate(null, AuthRequest(Surface.INTERNAL, "plan.reindex", "c-1"))
        assertEquals("batch", context!!.subjectId)
        assertEquals("internal-trusted", context.authProfileName)
        assertEquals("none (in-process caller)", trusting.forSurface(Surface.INTERNAL)!!.credentialDescription)
    }

    @Test
    fun `caller scope helpers answer holdsAll and missing`() {
        val caller = CallerContext("alice", Surface.REST, scopes = setOf("a"), roles = setOf("b"))
        assertTrue(caller.holdsAll(setOf("a", "b")))
        assertFalse(caller.holdsAll(setOf("a", "c")))
        assertEquals(setOf("c"), caller.missing(setOf("a", "b", "c")))
        assertEquals(emptySet<String>(), caller.missing(emptySet()))
    }
}
