package tikka.cli

import cats.effect.IO
import cats.effect.Resource
import sttp.client4.Backend
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/** The JVM build, used for development, talks to the daemon through the JDK's HTTP client. */
object HttpBackend:
  def resource: Resource[IO, Backend[IO]] = HttpClientCatsBackend.resource[IO]()
