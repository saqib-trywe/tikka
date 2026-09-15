package tikka.cli

import munit.CatsEffectSuite
import sttp.client4.*

/** Proves the platform's HTTP backend really sends; on Native this links and calls libcurl. */
class HttpBackendSmokeTest extends CatsEffectSuite:
  // Only failure is asserted: the exception type differs by platform (see the Native HttpBackend).
  test("a request to a closed port fails"):
    HttpBackend.resource.use: backend =>
      basicRequest
        .get(uri"http://127.0.0.1:1/")
        .send(backend)
        .attempt
        .map(result => assert(result.isLeft, result))
