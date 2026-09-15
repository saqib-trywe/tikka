package tikka.cli

import cats.effect.IO
import cats.effect.Resource
import sttp.client4.Backend
import sttp.client4.curl.cats.CurlCatsBackend

/** The Native build uses libcurl: Ember on Native links s2n-tls and libidn2 even for plain HTTP.
  *
  * A refused connection surfaces as a plain `RuntimeException` ("Command failed with status COULDNT_CONNECT"), not
  * sttp's `ConnectException` as on the JVM, so detecting an unreachable daemon must handle both.
  */
object HttpBackend:
  def resource: Resource[IO, Backend[IO]] = Resource.make(IO(CurlCatsBackend[IO]()))(_.close())
