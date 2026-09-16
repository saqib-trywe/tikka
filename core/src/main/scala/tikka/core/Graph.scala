package tikka.core

import doobie.*
import doobie.implicits.*

/** The two acyclic graphs: hierarchy and blocking.
  *
  * Both checks answer "can `candidate` already be reached from `root`?", because that is exactly the edge that would
  * close a loop. The reachable path comes back with the answer, so a rejection can name it.
  */
private[core] object Graph:
  /** `root` and everything under it, as a path of row keys, when `candidate` is among them. */
  def hierarchyPath(root: Long, candidate: Long): ConnectionIO[Option[List[Long]]] =
    sql"""WITH RECURSIVE reach(id, path) AS (
            SELECT $root, CAST($root AS TEXT)
            UNION ALL
            SELECT i.id, r.path || ',' || i.id FROM issue i JOIN reach r ON i.parent_id = r.id
          )
          SELECT path FROM reach WHERE id = $candidate LIMIT 1"""
      .query[String]
      .option
      .map(_.map(parse))

  /** `root` and everything it blocks, directly or through a chain. */
  def blockingPath(root: Long, candidate: Long): ConnectionIO[Option[List[Long]]] =
    sql"""WITH RECURSIVE reach(id, path) AS (
            SELECT $root, CAST($root AS TEXT)
            UNION ALL
            SELECT b.blocked_id, r.path || ',' || b.blocked_id
            FROM block_edge b JOIN reach r ON b.blocker_id = r.id
          )
          SELECT path FROM reach WHERE id = $candidate LIMIT 1"""
      .query[String]
      .option
      .map(_.map(parse))

  private def parse(path: String): List[Long] = path.split(",").toList.flatMap(_.toLongOption)
