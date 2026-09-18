package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO

/** The JVM build leaves the way it arrived: the runtime's own shutdown is sound here, and the Native build's reason for
  * skipping libc's exit handlers does not apply.
  */
object Exit:
  def finish(code: ExitCode): IO[ExitCode] = IO.pure(code)
