package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO

import scala.scalanative.posix.unistd

/** Leaves without running libc's exit handlers.
  *
  * libcurl initialises OpenSSL, which registers an `atexit` handler that frees its global tables. On Linux that handler
  * runs on the main thread while another thread may still be running its own TLS destructors, and the two free the same
  * per-thread state: "double free or corruption", or a hang when the corruption leaves malloc's arena lock held. That
  * is TIK-4, in both of its faces, and it is why the crash always lands after the command has printed its answer.
  *
  * Nothing those handlers do matters to a process the kernel is about to reclaim, so the CLI skips them. What is
  * already written has to reach the file descriptors first, because `_exit` discards anything still buffered.
  */
object Exit:
  def finish(code: ExitCode): IO[ExitCode] =
    IO.blocking:
      System.out.flush()
      System.err.flush()
      unistd._exit(code.code)
      code
