package tikka.core

import cats.effect.IO
import munit.CatsEffectSuite

/** Proves munit-cats-effect runs; replaced by real core tests in the domain milestone. */
class RuntimeSmokeTest extends CatsEffectSuite:
  test("IO runs under the test framework"):
    IO.pure(21).map(_ * 2).assertEquals(42)
