package com.travelmsg.config

import com.twitter.app.GlobalFlag

/**
 * The endpoint every AWS SDK client (DynamoDB, SES, SNS) points at -
 * LocalStack by default, since this project never talks to real AWS.
 *
 * A `GlobalFlag`, not a constructor parameter: five independent classes
 * (four DynamoDB stores, plus MessageSender) all need this same value, and
 * none of them are wired together by hand - Guice constructs each one on
 * its own. Threading one value through five separate constructors (and
 * updating every Guice injection site) would be a lot of plumbing for one
 * shared setting. A GlobalFlag is Finagle's answer to exactly this: declare
 * it once, as a plain object, and read it from anywhere with no DI wiring
 * at all - `AwsEndpoint()` works the same whether it's called from a
 * Guice-constructed class or a test.
 *
 * Real production services use this same mechanism for things like this -
 * a config value that many unrelated components need, set once at process
 * startup (here, via `-com.travelmsg.config.AwsEndpoint=http://...` on the
 * command line) rather than hardcoded per environment.
 */
object AwsEndpoint
    extends GlobalFlag[String](
      "http://localhost:4566",
      "Endpoint URL for AWS SDK clients (DynamoDB, SES, SNS) - defaults to LocalStack for local development")
