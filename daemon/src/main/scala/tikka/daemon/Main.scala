package tikka.daemon

import cats.effect.IO
import cats.effect.IOApp
import tikka.shared.BuildVersion

/** Placeholder entry point: the scaffold proves the assembly jar runs. */
object Main extends IOApp.Simple:
  def run: IO[Unit] = IO.println(s"tikka daemon ${BuildVersion.current.value}")
