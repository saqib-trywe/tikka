package tikka.core

import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import scala.annotation.tailrec

/** The two acyclic graphs: hierarchy and blocking.
  *
  * Both checks answer "can `candidate` already be reached from `root`?", because that is exactly the edge that would
  * close a loop. The reachable path comes back with the answer, so a rejection can name it.
  *
  * Reachability and the path are two steps on purpose. Blocking is many-to-many, so a graph with diamonds in it holds
  * exponentially many distinct paths, and a single recursive query carrying its path along with it enumerates every one
  * of them before it can answer "no cycle" — which is the common case, on the write path, inside the write transaction.
  * Deduplicating on the issue instead visits each one once; the path is only walked out when there is a cycle to name.
  * Deduplicating also means a store hand-edited into a cycle answers rather than looping forever.
  */
private[core] object Graph:
  /** `root` and everything under it, as a path of row keys, when `candidate` is among them. */
  def hierarchyPath(root: Long, candidate: Long): ConnectionIO[Option[List[Long]]] =
    path(
      root,
      candidate,
      sql"""WITH RECURSIVE reach(id) AS (
              SELECT $root
              UNION
              SELECT i.id FROM issue i JOIN reach r ON i.parent_id = r.id
            )
            SELECT 1 FROM reach WHERE id = $candidate""",
      children
    )

  /** `root` and everything it blocks, directly or through a chain. */
  def blockingPath(root: Long, candidate: Long): ConnectionIO[Option[List[Long]]] =
    path(
      root,
      candidate,
      sql"""WITH RECURSIVE reach(id) AS (
              SELECT $root
              UNION
              SELECT b.blocked_id FROM block_edge b JOIN reach r ON b.blocker_id = r.id
            )
            SELECT 1 FROM reach WHERE id = $candidate""",
      blocked
    )

  /** Asks the cheap question first, and only walks the graph when the answer is yes. */
  private def path(
      root: Long,
      candidate: Long,
      reaches: Fragment,
      step: NonEmptyList[Long] => ConnectionIO[List[(Long, Long)]]
  ): ConnectionIO[Option[List[Long]]] =
    reaches
      .query[Int]
      .option
      .flatMap:
        case None    => Option.empty[List[Long]].pure[ConnectionIO]
        case Some(_) => walk(root, candidate, step)

  /** Breadth-first from `root`, remembering how each issue was first reached, so the path reads back off that trail.
    * One query per level, and each issue is left in the trail once.
    */
  private def walk(
      root: Long,
      candidate: Long,
      step: NonEmptyList[Long] => ConnectionIO[List[(Long, Long)]]
  ): ConnectionIO[Option[List[Long]]] =
    def loop(frontier: List[Long], trail: Map[Long, Long]): ConnectionIO[Map[Long, Long]] =
      NonEmptyList.fromList(frontier) match
        case Some(current) if !trail.contains(candidate) =>
          step(current).flatMap: edges =>
            val (reached, extended) = edges.foldLeft((List.empty[Long], trail)):
              case ((next, seen), (from, to)) =>
                // The first way in is kept, so the trail holds the shortest path to each issue.
                if to == root || seen.contains(to) then (next, seen) else (to :: next, seen.updated(to, from))
            loop(reached.reverse, extended)
        case _ => trail.pure[ConnectionIO]

    if root == candidate then Option(List(root)).pure[ConnectionIO]
    else loop(List(root), Map.empty).map(trail => Option.when(trail.contains(candidate))(back(root, candidate, trail)))

  /** The trail read backwards, so the path comes out from `root` to `candidate`. */
  private def back(root: Long, candidate: Long, trail: Map[Long, Long]): List[Long] =
    @tailrec def climb(at: Long, path: List[Long]): List[Long] =
      if at == root then root :: path
      else
        trail.get(at) match
          case Some(previous) => climb(previous, at :: path)
          case None           => at :: path
    climb(candidate, Nil)

  /** The children of everything in the frontier, as (parent, child). */
  private def children(frontier: NonEmptyList[Long]): ConnectionIO[List[(Long, Long)]] =
    (fr"SELECT parent_id, id FROM issue WHERE" ++ Fragments.in(fr"parent_id", frontier))
      .query[(Long, Long)]
      .to[List]

  /** What everything in the frontier blocks, as (blocker, blocked). */
  private def blocked(frontier: NonEmptyList[Long]): ConnectionIO[List[(Long, Long)]] =
    (fr"SELECT blocker_id, blocked_id FROM block_edge WHERE" ++ Fragments.in(fr"blocker_id", frontier))
      .query[(Long, Long)]
      .to[List]
