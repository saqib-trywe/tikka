package tikka.shared

import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*

/** Proves the test framework and circe run on every platform the contract builds for. */
class PlatformSmokeTest extends munit.FunSuite:
  private final case class Probe(name: String, count: Int) derives Codec.AsObject

  test("circe round-trips a derived codec"):
    val probe = Probe("tikka", 1)
    assertEquals(decode[Probe](probe.asJson.noSpaces), Right(probe))

  test("the build version is generated"):
    assert(BuildVersion.current.value.nonEmpty)
