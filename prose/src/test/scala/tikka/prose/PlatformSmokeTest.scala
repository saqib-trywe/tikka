package tikka.prose

import laika.api.MarkupParser
import laika.format.Markdown

/** Proves Laika parses markdown on every platform prose builds for. */
class PlatformSmokeTest extends munit.FunSuite:
  test("Laika parses markdown"):
    val parsed = MarkupParser.of(Markdown).build.parse("# Heading\n\nSee `TIK-1`.")
    assert(parsed.isRight, parsed)
