package com.travelmsg.persistence

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import scala.jdk.CollectionConverters._

/**
 * Thin wrapper around the DynamoDB SDK, pointed at LocalStack (see
 * docker-compose.yml at the project root) instead of real AWS - this
 * project never talks to an actual AWS account. All the client-building
 * and table-creation boilerplate lives here so IdempotencyFilter only
 * needs to know "hasSeen" and "markSeen."
 *
 * IMPORTANT: every method here makes a real blocking network call.
 * `DynamoDbClient` (not `DynamoDbAsyncClient`) is synchronous - it
 * doesn't return until the HTTP response comes back. Calling these
 * directly from a Filter's `apply` would block Finagle's Netty event
 * loop - exactly the gotcha your CLAUDE.md flagged back in Slice 1: "Any
 * blocking call must go in a FuturePool." IdempotencyFilter is where
 * that finally gets used for real.
 */
class IdempotencyStore {

  private val TableName = "idempotency-keys"

  private val client: DynamoDbClient = DynamoDbClient
    .builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
    .build()

  createTableIfMissing()

  private def createTableIfMissing(): Unit = {
    val request = CreateTableRequest
      .builder()
      .tableName(TableName)
      .attributeDefinitions(
        AttributeDefinition.builder().attributeName("eventId").attributeType(ScalarAttributeType.S).build())
      .keySchema(KeySchemaElement.builder().attributeName("eventId").keyType(KeyType.HASH).build())
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()
    try {
      client.createTable(request)
    } catch {
      case _: ResourceInUseException => // table already exists - nothing to do
    }
  }

  // Blocking - callers must run this inside a FuturePool, never directly
  // on the Netty event loop.
  def hasSeen(eventId: String): Boolean = {
    val request = GetItemRequest
      .builder()
      .tableName(TableName)
      .key(Map("eventId" -> AttributeValue.builder().s(eventId).build()).asJava)
      .build()
    client.getItem(request).hasItem
  }

  // Blocking - same caveat as hasSeen.
  def markSeen(eventId: String): Unit = {
    val request = PutItemRequest
      .builder()
      .tableName(TableName)
      .item(Map("eventId" -> AttributeValue.builder().s(eventId).build()).asJava)
      .build()
    client.putItem(request)
    ()
  }
}
