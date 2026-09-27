# Reading This Repo

This is a map of the codebase, folder by folder, in plain terms.
It also covers what a real production Finagle repo tends to look like, since this project was built specifically to prepare for reading one.

## The shape of the project

Everything lives under `src/main/scala/com/travelmsg/`, split into eight small packages.
Each one has exactly one job.
Here they are, in the order you'd actually want to read them if you were new to this code.

### `http/` — the front door

This is where HTTP requests come in and JSON goes out.
`DecisionController.scala` defines the routes (`POST /decisions`, `GET /travelers/:traveler_id/log`, and so on).
`TravelerProfileController.scala` and `MessageTemplateController.scala` do the same for their own routes.

Spring comparison: this is your `@RestController` layer.
The difference is there's no annotation scanning here — every route is registered by hand, as a line of code that runs once at startup.

The controllers are deliberately thin.
They decode JSON into a request object, hand it to something else, and encode the result back into JSON.
No business logic lives here on purpose — that's what makes the business logic testable without spinning up a whole HTTP server.

### `domain/` — the data itself

This is the actual shape of the business: `TravelEvent` (a flight delay, a price drop, a cancellation), `Decision` (`Send` or `Suppress`), `TravelerProfile`, `MessageTemplate`, `DecisionLogEntry`.

Spring comparison: these are your entities or DTOs, except immutable — nothing in here has a setter, and a "changed" value is really a new value built with `.copy(...)`.

Most of these are `sealed trait` plus `case class` pairs.
That combination is how Scala expresses "this is one of a fixed, known set of possibilities" — the compiler will refuse to compile a `match` that forgets to handle one of them.
This is the one idea worth understanding before anything else in this codebase makes sense.

### `filter/` — the rules that decide whether something happens at all

`IdempotencyFilter`, `FrequencyCapFilter`, `PriorityArbitrationFilter`, `QuietHoursFilter`.
Each one wraps around the "real" decision-making step and can let a request through unchanged, or override it.

Spring comparison: this is your interceptor/middleware layer — think `HandlerInterceptor` or a servlet filter chain.
The difference is there's no framework registering these for you.
They're composed by hand, in `DecisionController.scala`, as a literal chain of function calls: `idempotencyFilter.andThen(frequencyCapFilter).andThen(...)`.

Order matters here, and it's not arbitrary — read the comments on `PriorityArbitrationFilter` and in `DecisionController` for why each one sits where it does.

### `service/` — the actual decision

`DecisionService.scala` is the one place that answers "given this event, should we send something, and if so what does it say?"
`MessageTemplates.scala` and `DecisionLogger.scala` support that.

This is the core business logic, and it's intentionally isolated from HTTP entirely — you can unit-test `DecisionService` by calling it directly, no server involved.

### `delivery/` — actually sending something

`DeliveryService.scala` is what turns a `Send` decision into a real email or SMS.
`MessageSender.scala` is the thin wrapper around the AWS SDK clients (SES for email, SNS for SMS).
`Channel.scala` is just the `Email`/`Sms` marker type.

Spring comparison: this is an adapter to an external system, the same role a `JavaMailSender` wrapper or a payment-gateway client would play.

This package is also where retries live, since that's specifically about the external call being flaky, not about deciding what to send.

### `persistence/` — where things are actually stored

`IdempotencyStore`, `TravelerProfileStore`, `DecisionLogStore`, `MessageTemplateStore`.
Each one is a small, direct wrapper around a DynamoDB table (running locally via LocalStack, not real AWS).

Spring comparison: these are your repository/DAO classes.

Every method on these classes is a **blocking** call — a real network round trip that waits for a response.
That's why every call site elsewhere wraps them in a `FuturePool` (see the next section) instead of calling them directly.

### `trace/` — watching the decision happen, without being part of it

`Trace.scala` lets any filter or service record a note ("FrequencyCapFilter: not capped, recording send") without needing to change what it returns.
It's built on Finagle's `Contexts.local` — a piece of request-scoped storage that correctly survives being passed through `.map`/`.flatMap` chains, the way a plain `ThreadLocal` would not.

This only actually does anything during a dry-run request; on a real request, every `Trace.record(...)` call is a no-op.

### `server/` — turning all of this into a running process

`TravelMessageServer.scala` is genuinely tiny: it just lists which controllers exist.
Guice (Google's dependency-injection library, the same one Spring's own DI is often compared to) wires up everything each controller needs automatically, based on constructor parameters — there's no XML, no `@Configuration` class, just constructors.

## The one idiom that ties it all together

Almost everything in this codebase returns a `com.twitter.util.Future[T]`, not a plain `T`.
That's Finagle's version of "this value isn't ready yet, but here's a promise it will be."
It is **not** the same type as `scala.concurrent.Future` — mixing the two up is the single most common mistake when starting with this stack.

A blocking call (anything touching `persistence/` or `delivery/`) gets wrapped in a `FuturePool { ... }`, which runs it on a separate thread pool instead of the main request-handling threads.
Everything else composes with `.map`/`.flatMap`, the same way you'd chain `.then()` calls on a JavaScript Promise, or use a for-comprehension.

---

# Reading a Production Finagle Repo

This toy project was deliberately kept small: one service, one `build.sbt`, everything in one package tree.
A real Finagle codebase at a company will look different in a few specific ways.
Here's what to expect, and where to start looking.

## What's usually bigger

**It's rarely one project.**
Most companies running Finagle at scale have either a monorepo with dozens of independently-deployable services, or many small repos, each one being a single service.
Don't expect to find "the" entry point by scrolling — you'll need to find the specific service you care about first.

**The build system is often not plain sbt.**
Bazel and Pants (both build systems designed for huge monorepos) are common at companies with a lot of Scala.
Instead of one `build.sbt`, expect `BUILD` or `BUILD.bazel` files scattered through the tree, one per module.
The ideas are the same — dependencies, compile targets — just expressed differently.

**APIs are often defined in Thrift, not JSON over HTTP.**
Finagle grew up at Twitter around Thrift, a language-neutral way of describing an API's shape (methods, request/response types) in a `.thrift` file, which then generates client and server code in whatever language you need.
If you see a `.thrift` file, that's the actual contract — read it before reading any of the generated Scala, the same way you'd read an OpenAPI spec before diving into generated client code.
`ThriftMux` (Finagle's Thrift-over-Mux protocol) server/client setup will look similar in spirit to this project's `HttpServer`/`HttpRouter`, but built from a `.thrift`-generated interface instead of hand-written case classes.

**Observability is real, not a toy.**
This project's `Trace` object writes to an in-memory list.
A production system will have `StatsReceiver` calls throughout (counters, gauges, latency histograms) and real distributed tracing wired through Finagle's own tracing support, usually shipping to something like Zipkin.
Expect to see metric names and trace annotations sprinkled through filters, the same way `Trace.record` is sprinkled through this project's filters — same idea, real infrastructure behind it.

**Configuration is a first-class thing.**
Instead of hardcoded values like this project's `http://localhost:4566`, expect command-line flags (via `com.twitter.util.App`'s `flag[...]` mechanism) or a config-fetching service, since the same binary typically runs in multiple environments (dev, staging, prod) with different settings.

## What stays the same

- **`Service[Req, Rep]` and `Filter`** are still the core abstractions — a real service's request pipeline is still, underneath everything else, a chain of filters wrapped around a service, composed with `andThen`.
- **`com.twitter.util.Future`**, not `scala.concurrent.Future`, still shows up everywhere.
- **Guice-based dependency injection** (via Finatra, if the service is HTTP-facing) still wires things up through constructors, no XML.
- **`sealed trait` + `case class`** is still how a closed set of possibilities gets modeled, especially for anything resembling a decision or a state machine.

## How to actually read one, in order

1. **Find the entry point.** Search for `extends App`, `extends TwitterServer`, or a class whose name ends in `Main` or `ServerMain`. This is the `TravelMessageServerMain` equivalent — usually a handful of lines that just wires everything else together.
2. **Find the routes or Thrift interface.** For an HTTP service, look for something calling `router.add[...]`, same as this project's `TravelMessageServer.configureHttp`. For a Thrift service, find the `.thrift` file first — it's shorter than the generated code and tells you the actual API shape.
3. **Follow one endpoint all the way through**, the same order this repo's own `http/` → `filter/` → `service/` → `delivery/`/`persistence/` layering suggests. Pick the simplest-looking endpoint, not the most important one, and trace it from the controller/handler down to wherever it stops.
4. **Read the tests before the implementation**, if you're unsure what something is supposed to do. A `FeatureTest` (same shape as this project's `DecisionControllerFeatureTest`) shows you real request/response pairs, which is usually faster to understand than reading business logic cold.
5. **Expect Filters everywhere**, doing the job this project's `filter/` package does — auth, rate limiting, logging, tracing, retries. If something seems to happen "by magic" between a request coming in and your handler running, it's almost always a Filter registered somewhere in server setup, not framework magic.
