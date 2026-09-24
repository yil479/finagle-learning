package com.travelmsg.delivery

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model._
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest

import java.net.URI

/**
 * Blocking wrapper around the SES and SNS SDK clients, pointed at
 * LocalStack - same pattern as IdempotencyStore/TravelerProfileStore.
 * Callers must run these inside a FuturePool, never directly on the
 * Netty event loop.
 */
class MessageSender {

  private val FromAddress = "noreply@travelmsg.example.com"

  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
  private val endpoint = URI.create("http://localhost:4566")

  private val sesClient: SesClient = SesClient
    .builder()
    .endpointOverride(endpoint)
    .region(Region.US_EAST_1)
    .credentialsProvider(credentials)
    .build()

  private val snsClient: SnsClient = SnsClient
    .builder()
    .endpointOverride(endpoint)
    .region(Region.US_EAST_1)
    .credentialsProvider(credentials)
    .build()

  // LocalStack's SES (like real SES outside production access) refuses to
  // send from an address that hasn't been verified first. This only needs
  // to happen once, so it runs at construction time, not per-send.
  sesClient.verifyEmailIdentity(VerifyEmailIdentityRequest.builder().emailAddress(FromAddress).build())

  // Sends both a plain-text and an HTML body together - standard email
  // practice: clients/screen readers that don't render HTML fall back to
  // the plain-text part, instead of showing broken markup or nothing.
  def sendEmail(to: String, subject: String, textBody: String, htmlBody: String): Unit = {
    val request = SendEmailRequest
      .builder()
      .source(FromAddress)
      .destination(Destination.builder().toAddresses(to).build())
      .message(
        Message
          .builder()
          .subject(Content.builder().data(subject).build())
          .body(
            Body
              .builder()
              .text(Content.builder().data(textBody).build())
              .html(Content.builder().data(htmlBody).build())
              .build())
          .build())
      .build()
    sesClient.sendEmail(request)
    ()
  }

  def sendSms(to: String, body: String): Unit = {
    val request = PublishRequest.builder().phoneNumber(to).message(body).build()
    snsClient.publish(request)
    ()
  }
}
