package com.travelmsg.persistence

import com.travelmsg.domain.DecisionLogEntry
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import scala.jdk.CollectionConverters._

/**
 * Same shape as the other stores - blocking DynamoDB wrapper, callers must
 * run these inside a FuturePool. Unlike IdempotencyStore/TravelerProfileStore,
 * this table has a *sort key* (`timestamp`) alongside its partition key
 * (`travelerId`), because we need to list every entry for one traveler,
 * not just look up a single item - that's what `Query` is for, versus the
 * `GetItem` the other stores use.
 */
class DecisionLogStore {

  private val TableName = "decision-log"

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
        AttributeDefinition.builder().attributeName("travelerId").attributeType(ScalarAttributeType.S).build(),
        AttributeDefinition.builder().attributeName("timestamp").attributeType(ScalarAttributeType.S).build()
      )
      .keySchema(
        KeySchemaElement.builder().attributeName("travelerId").keyType(KeyType.HASH).build(),
        KeySchemaElement.builder().attributeName("timestamp").keyType(KeyType.RANGE).build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()
    try {
      client.createTable(request)
    } catch {
      case _: ResourceInUseException => // table already exists - nothing to do
    }
  }

  def save(entry: DecisionLogEntry): Unit = {
    val item = Map(
      "travelerId" -> AttributeValue.builder().s(entry.travelerId).build(),
      "timestamp" -> AttributeValue.builder().s(entry.timestamp).build(),
      "eventId" -> AttributeValue.builder().s(entry.eventId).build(),
      "eventType" -> AttributeValue.builder().s(entry.eventType).build(),
      "decision" -> AttributeValue.builder().s(entry.decision).build(),
      "detail" -> AttributeValue.builder().s(entry.detail).build()
    ).asJava
    client.putItem(PutItemRequest.builder().tableName(TableName).item(item).build())
    ()
  }

  def findByTraveler(travelerId: String): List[DecisionLogEntry] = {
    val request = QueryRequest
      .builder()
      .tableName(TableName)
      .keyConditionExpression("travelerId = :tid")
      .expressionAttributeValues(Map(":tid" -> AttributeValue.builder().s(travelerId).build()).asJava)
      .build()
    client
      .query(request)
      .items
      .asScala
      .map { item =>
        DecisionLogEntry(
          travelerId = travelerId,
          timestamp = item.get("timestamp").s(),
          eventId = item.get("eventId").s(),
          eventType = item.get("eventType").s(),
          decision = item.get("decision").s(),
          detail = item.get("detail").s()
        )
      }
      .toList
  }
}
