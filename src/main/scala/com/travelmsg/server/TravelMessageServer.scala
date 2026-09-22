package com.travelmsg.server

import com.travelmsg.http.{DecisionController, TravelerProfileController}
import com.twitter.finatra.http.HttpServer
import com.twitter.finatra.http.routing.HttpRouter

// `object ...Main extends ...Server` is the actual JVM entrypoint (has a
// main method via TwitterServer); the `class` is what tests instantiate
// directly via EmbeddedHttpServer, bypassing the process bootstrap.
object TravelMessageServerMain extends TravelMessageServer

class TravelMessageServer extends HttpServer {

  override def configureHttp(router: HttpRouter): Unit = {
    router.add[DecisionController]
    router.add[TravelerProfileController]
  }
}
