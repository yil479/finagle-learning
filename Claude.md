# Travel Message Decisioning Service

## What this project is

A miniature travel-notification decisioning service, built as a learning project before
I start on a Scala/Finagle messaging platform team. Given events happening to a traveler
(flight delayed, price dropped, trip starting soon), the service decides **what message to
send, on which channel, at what time — or whether to send nothing at all.**

The hard problem here is not sending notifications. It is deciding what *not* to send:
frequency capping, quiet hours, deduplication, and arbitration between messages that
compete for the same moment.

## How to work with me on this

**This is a learning project. Optimizing for my understanding beats optimizing for
working code.** I am an experienced Spring Boot / Java developer with no Scala, Finagle,
or sbt background.

Concretely:

- **Do not write whole slices for me.** Write the skeleton and the tricky parts, and
  leave clearly marked `// TODO(me):` gaps for the logic I should write myself.
- **Explain Scala idioms inline the first time they appear.** Implicits, for-comprehensions
  over futures, `sealed trait` exhaustivity, type classes, variance annotations.
- **Compare to Spring when it helps.** "This is Finagle's `HandlerInterceptor`" saves me
  ten minutes.
- **Prefer explicit over clever.** No shapeless, no cats, no free monads. Plain Scala 2.13
  and the Twitter stack. If there is an idiomatic-but-obscure way and a boring way, use
  the boring way and mention the other exists.
- **When I write something that compiles but isn't idiomatic, tell me.** I want the
  correction, not silent acceptance.
- Ask before adding a dependency.

## Tech stack

- **Scala 2.13** (Finagle has no native Scala 3 support)
- **Finatra / Finagle** for the HTTP server and clients
- **sbt** for builds
- **Guice** for DI (comes with Finatra)
- **Jackson** + `jackson-module-scala` for JSON
- **ScalaTest** via Finatra's `FeatureTest` / `EmbeddedHttpServer`
- Later slices: **LocalStack** (SQS, DynamoDB, SES, SNS), **React + TypeScript** console

## Critical gotchas

**`com.twitter.util.Future` is not `scala.concurrent.Future`.** This is the number one
source of confusion. Never import `scala.concurrent.Future` in this project.

- No implicit `ExecutionContext` is needed or wanted
- `rescue` not `recoverWith`; `handle` not `recover`
- `Future.collect` not `Future.sequence`
- `Future.value(x)` not `Future.successful(x)`
- It runs on the Netty event loop. **Any blocking call must go in a `FuturePool`** or it
  stalls the server.

**ThreadLocal does not survive async hops.** Request-scoped context (request IDs, user
context, tracing) uses Finagle's `Local` / `Contexts`, not MDC or a `ThreadLocal`.

**There is no annotation magic.** No component scan, no `@Order`, no proxies. The request
pipeline is an expression I write by hand: `filterA andThen filterB andThen service`.

## Domain model conventions

- Events and decisions are **sealed traits with case class variants**, so pattern matches
  are checked for exhaustivity. This is the point of the exercise; don't use plain classes
  or enums-with-fields.
- Everything is immutable. `.copy(field = x)` to derive a modified value.
- Business logic lives in `Service[TravelEvent, Decision]`, **not** in
  `Service[Request, Response]`. The HTTP controller is a thin shell that decodes JSON and
  delegates. This keeps the decision pipeline testable with no HTTP involved.
- Cross-cutting rules (capping, quiet hours, dedupe) are `Filter`s in front of the
  service, not `if` statements inside it.

## Domain rules that matter

- **Transactional vs promotional is a hard boundary.** Cancellations and gate changes
  bypass frequency caps and quiet hours. Price drops and rebooking prompts never do.
  This is a compliance line, not a preference.
- **Quiet hours use the traveler's *current* timezone**, which during a trip is the
  destination's, not their home timezone.
- **Idempotency:** upstream events arrive more than once. Every send is keyed and deduped.
- **Holdout group:** 5% of eligible users are deliberately not sent to, and the
  suppression is logged, so the team can measure whether messages actually cause repeat
  bookings.
- Every `Suppress` decision carries a human-readable reason. The audit trail of *why we
  didn't send* is as important as the sends.

## Build and run

```bash
sbt compile
sbt test
sbt run          # server on :8888, admin on :9990
```

TwitterServer's admin interface on `:9990` is the Actuator equivalent: metrics, thread
dumps, lint checks. Use it when debugging rather than adding logging.

## Testing conventions

- Unit-test the decision `Service` and each `Filter` directly, with no server running.
- One `FeatureTest` with `EmbeddedHttpServer` per endpoint for the wiring.
- `Await.result(f, 5.seconds)` in tests is fine. In production code it is a bug.
- Every new rule gets a test for the suppression case, not just the send case.

## Slice roadmap

Build in thin vertical slices. **The service stays runnable at the end of every slice.**
Do not scaffold ahead into future slices.

1. **One event, one rule.** `POST /decisions` takes a `FlightDelayed`, returns
   `Send` or `Suppress`. Hardcoded 30-minute threshold. In memory. One passing
   `FeatureTest`.
2. **Frequency capping as a `Filter`**, backed by an in-memory map.
3. **More event types**, which forces arbitration: priority ordering, and a decision about
   whether losers are dropped, deferred, or bundled.
4. **Quiet hours + destination timezone.**
5. **Message templates.** Move message text out of `DecisionService` into a small
   per-event-type lookup with placeholders, plus a renderer that fills them in from the
   event's fields. No persistence or UI yet - just extracted into one place.
6. **Transactional bypass** of caps and quiet hours.
7. **Persistence and idempotency** (DynamoDB via LocalStack).
8. **Delivery adapters** (SES / SNS via LocalStack), with retries.
9. **React console:** preferences, send log, and a dry-run screen showing the decision
   trace for a simulated event. ← *current slice*

## Open questions to resolve with the team later

- Finatra or raw Finagle?
- Kafka or SQS for event ingestion?
- Vavr, or modern plain Java, for the "functional Java" parts?