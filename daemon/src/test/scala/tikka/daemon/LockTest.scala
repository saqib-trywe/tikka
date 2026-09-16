package tikka.daemon

import cats.effect.IO

/** One daemon per home: the lock is what makes a second one fail immediately rather than fight for the writer. */
class LockTest extends DaemonFixtures:
  home.test("a second holder is refused, and told who has it"): value =>
    Lock
      .acquire(value)
      .use: _ =>
        for
          second <- Lock.acquire(value).use_.attempt
          holder <- Lock.holder(value)
        yield
          assert(second.left.exists(_.isInstanceOf[HomeInUse]), second)
          assert(holder.exists(_.contains("pid=")), holder)
          assert(holder.exists(_.contains(s"port=${value.config.port.value}")), holder)

  home.test("the lock is released when the daemon stops"): value =>
    for
      _ <- Lock.acquire(value).use_
      second <- Lock.acquire(value).use_.attempt
    yield assert(second.isRight, second)
