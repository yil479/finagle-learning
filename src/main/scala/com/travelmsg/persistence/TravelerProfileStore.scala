package com.travelmsg.persistence

import com.travelmsg.domain.TravelerProfile
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import scala.jdk.CollectionConverters._

/**
 * Same shape as IdempotencyStore - a thin, blocking wrapper around a
 * second DynamoDB table, this one mapping travelerId to contact info.
 * Nothing in this project resolves that automatically yet (no upstream
 * profile system exists) - it's populated via TravelerProfileController's
 * registration endpoint.
 *
 * Blocking, same caveat as IdempotencyStore: callers must run these
 * inside a FuturePool.
 */
class TravelerProfileStore {

  private val TableName = "traveler-profiles"

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
        AttributeDefinition.builder().attributeName("travelerId").attributeType(ScalarAttributeType.S).build())
      .keySchema(KeySchemaElement.builder().attributeName("travelerId").keyType(KeyType.HASH).build())
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()
    try {
      client.createTable(request)
    } catch {
      case _: ResourceInUseException => // table already exists - nothing to do
    }
  }

  def save(profile: TravelerProfile): Unit = {
    val item = Map(
      "travelerId" -> AttributeValue.builder().s(profile.travelerId).build(),
      "email" -> AttributeValue.builder().s(profile.email).build(),
      "phone" -> AttributeValue.builder().s(profile.phone).build()
    ).asJava
    client.putItem(PutItemRequest.builder().tableName(TableName).item(item).build())
    ()
  }

  def find(travelerId: String): Option[TravelerProfile] = {
    val request = GetItemRequest
      .builder()
      .tableName(TableName)
      .key(Map("travelerId" -> AttributeValue.builder().s(travelerId).build()).asJava)
      .build()
    val response = client.getItem(request)
    if (!response.hasItem) {
      None
    } else {
      val item = response.item()
      Some(TravelerProfile(travelerId = travelerId, email = item.get("email").s(), phone = item.get("phone").s()))
    }
  }
}
