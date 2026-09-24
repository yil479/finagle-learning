package com.travelmsg.persistence

import com.travelmsg.domain.MessageTemplate
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import scala.jdk.CollectionConverters._

/**
 * Same shape as TravelerProfileStore - blocking DynamoDB wrapper, callers
 * must run these inside a FuturePool. Seeds sensible (not yet "appealing")
 * defaults for all four event types on startup if the table is empty, so
 * the system works correctly out of the box - the whole point of this
 * feature is that someone can then improve them via `save`, at runtime,
 * with no code change or redeploy.
 */
class MessageTemplateStore {

  private val TableName = "message-templates"

  private val client: DynamoDbClient = DynamoDbClient
    .builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
    .build()

  createTableIfMissing()
  seedDefaultsIfMissing()

  private def createTableIfMissing(): Unit = {
    val request = CreateTableRequest
      .builder()
      .tableName(TableName)
      .attributeDefinitions(
        AttributeDefinition.builder().attributeName("eventType").attributeType(ScalarAttributeType.S).build())
      .keySchema(KeySchemaElement.builder().attributeName("eventType").keyType(KeyType.HASH).build())
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()
    try {
      client.createTable(request)
    } catch {
      case _: ResourceInUseException => // table already exists - nothing to do
    }
  }

  private def seedDefaultsIfMissing(): Unit = {
    val defaults = List(
      MessageTemplate(
        eventType = "FlightDelayed",
        emailSubject = "Your flight is delayed",
        emailBodyText = "Flight {flightId} is delayed {delayMinutes} minutes.",
        emailBodyHtml = "<p>Flight <strong>{flightId}</strong> is delayed <strong>{delayMinutes}</strong> minutes.</p>",
        smsBody = "Flight {flightId} is delayed {delayMinutes} minutes."
      ),
      MessageTemplate(
        eventType = "PriceDropped",
        emailSubject = "Price drop on your trip",
        emailBodyText = "Price for trip {tripId} dropped from {oldPrice} to {newPrice}.",
        emailBodyHtml =
          "<p>Price for trip <strong>{tripId}</strong> dropped from {oldPrice} to <strong>{newPrice}</strong>.</p>",
        smsBody = "Price for trip {tripId} dropped from {oldPrice} to {newPrice}."
      ),
      MessageTemplate(
        eventType = "TripStartingSoon",
        emailSubject = "Your trip starts soon",
        emailBodyText = "Your trip {tripId} starts in {hoursUntilDeparture} hours.",
        emailBodyHtml = "<p>Your trip <strong>{tripId}</strong> starts in <strong>{hoursUntilDeparture}</strong> hours.</p>",
        smsBody = "Your trip {tripId} starts in {hoursUntilDeparture} hours."
      ),
      MessageTemplate(
        eventType = "FlightCancelled",
        emailSubject = "Your flight has been cancelled",
        emailBodyText = "Flight {flightId} is cancelled.",
        emailBodyHtml = "<p>Flight <strong>{flightId}</strong> is cancelled.</p>",
        smsBody = "Flight {flightId} is cancelled."
      )
    )
    defaults.foreach { template =>
      if (find(template.eventType).isEmpty) save(template)
    }
  }

  def save(template: MessageTemplate): Unit = {
    val item = Map(
      "eventType" -> AttributeValue.builder().s(template.eventType).build(),
      "emailSubject" -> AttributeValue.builder().s(template.emailSubject).build(),
      "emailBodyText" -> AttributeValue.builder().s(template.emailBodyText).build(),
      "emailBodyHtml" -> AttributeValue.builder().s(template.emailBodyHtml).build(),
      "smsBody" -> AttributeValue.builder().s(template.smsBody).build()
    ).asJava
    client.putItem(PutItemRequest.builder().tableName(TableName).item(item).build())
    ()
  }

  def find(eventType: String): Option[MessageTemplate] = {
    val request = GetItemRequest
      .builder()
      .tableName(TableName)
      .key(Map("eventType" -> AttributeValue.builder().s(eventType).build()).asJava)
      .build()
    val response = client.getItem(request)
    if (!response.hasItem) {
      None
    } else {
      val item = response.item()
      Some(
        MessageTemplate(
          eventType = eventType,
          emailSubject = item.get("emailSubject").s(),
          emailBodyText = item.get("emailBodyText").s(),
          emailBodyHtml = item.get("emailBodyHtml").s(),
          smsBody = item.get("smsBody").s()
        ))
    }
  }
}
