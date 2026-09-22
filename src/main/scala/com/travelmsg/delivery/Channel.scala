package com.travelmsg.delivery

sealed trait Channel

object Channel {
  case object Email extends Channel
  case object Sms extends Channel
}
