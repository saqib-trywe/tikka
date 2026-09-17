package tikka.cli

import cats.Show
import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Console
import fs2.Stream

import java.io.EOFException
import java.nio.charset.Charset
import java.nio.file.Path

/** An environment whose output is recorded, so a test can drive the whole CLI and read what it printed. */
final case class Recorded(environment: Environment, output: Ref[IO, Vector[String]], errors: Ref[IO, Vector[String]]):
  def out: IO[String] = output.get.map(_.mkString("\n"))

  def err: IO[String] = errors.get.map(_.mkString("\n"))

object Recorded:
  def apply(variables: Map[String, String], directory: Path, input: List[String] = Nil): IO[Recorded] =
    for
      output <- Ref.of[IO, Vector[String]](Vector.empty)
      errors <- Ref.of[IO, Vector[String]](Vector.empty)
    yield
      val console = new Console[IO]:
        def readLineWithCharset(charset: Charset): IO[String] = IO.raiseError(EOFException())
        def print[A](a: A)(implicit S: Show[A]): IO[Unit] = output.update(_ :+ S.show(a))
        def println[A](a: A)(implicit S: Show[A]): IO[Unit] = output.update(_ :+ S.show(a))
        def error[A](a: A)(implicit S: Show[A]): IO[Unit] = errors.update(_ :+ S.show(a))
        def errorln[A](a: A)(implicit S: Show[A]): IO[Unit] = errors.update(_ :+ S.show(a))
      Recorded(Environment(variables, directory, console, Stream.emits(input)), output, errors)
