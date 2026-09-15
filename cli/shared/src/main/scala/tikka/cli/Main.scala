package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.std.Console
import tikka.shared.BuildVersion

/** Placeholder entry point: the scaffold proves the CLI links and starts within budget. */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case List("--version") => IO.println(s"tikka ${BuildVersion.current.value}").as(ExitCode.Success)
    case _                 => Console[IO].errorln("usage: tikka --version").as(ExitCode(2))
