package tikka.core

import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import tikka.shared.*

import Codecs.given

/** An issue as the `issue` table holds it. `key` is the surrogate row id: edges and events join on it, while
  * `project_key` and `number` keep the issue id readable in `sqlite3`.
  */
private[core] final case class IssueRecord(
    key: Long,
    projectKey: ProjectKey,
    number: IssueNumber,
    title: Title,
    body: Body,
    resolution: Option[Resolution],
    closedAt: Option[Timestamp],
    assignee: Option[Assignee],
    claimedAt: Option[Timestamp],
    rank: Rank,
    version: Version,
    parentKey: Option[Long],
    created: Timestamp
) derives Read:
  def id: IssueId = IssueId(projectKey, number)

  def state: IssueState = (resolution, closedAt) match
    case (Some(value), Some(at)) => IssueState.Closed(value, at)
    case _                       => IssueState.Open

  def isOpen: Boolean = resolution.isEmpty

/** Every statement the core runs, as `ConnectionIO` so a mutation and its event share one transaction. */
private[core] object Queries:
  private val issueColumns =
    fr"id, project_key, number, title, body, resolution, closed_at, assignee, claimed_at, rank, version, parent_id, created"

  // Projects

  def projects: ConnectionIO[List[Project]] =
    sql"SELECT key, name, created FROM project ORDER BY key"
      .query[(ProjectKey, String, Timestamp)]
      .map(Project.apply)
      .to[List]

  def projectKeys: ConnectionIO[Set[ProjectKey]] =
    sql"SELECT key FROM project".query[ProjectKey].to[List].map(_.toSet)

  def project(key: ProjectKey): ConnectionIO[Option[Project]] =
    sql"SELECT key, name, created FROM project WHERE key = $key"
      .query[(ProjectKey, String, Timestamp)]
      .map(Project.apply)
      .option

  def insertProject(key: ProjectKey, name: String, at: Timestamp): ConnectionIO[Unit] =
    sql"INSERT INTO project (key, name, created, next_number) VALUES ($key, $name, $at, 1)".update.run.void

  /** Allocates inside the write transaction, so numbers increase and are never reused. */
  def takeNumber(key: ProjectKey): ConnectionIO[IssueNumber] =
    sql"UPDATE project SET next_number = next_number + 1 WHERE key = $key RETURNING next_number - 1"
      .query[IssueNumber]
      .unique

  // Issues

  def issue(id: IssueId): ConnectionIO[Option[IssueRecord]] =
    (fr"SELECT" ++ issueColumns ++ fr"FROM issue WHERE project_key = ${id.project} AND number = ${id.number}")
      .query[IssueRecord]
      .option

  def issueByKey(key: Long): ConnectionIO[Option[IssueRecord]] =
    (fr"SELECT" ++ issueColumns ++ fr"FROM issue WHERE id = $key").query[IssueRecord].option

  def insertIssue(
      id: IssueId,
      title: Title,
      body: Body,
      rank: Rank,
      parentKey: Option[Long],
      at: Timestamp
  ): ConnectionIO[Long] =
    sql"""INSERT INTO issue (project_key, number, title, body, status, resolution, closed_at, assignee, claimed_at,
                             rank, version, parent_id, created)
          VALUES (${id.project}, ${id.number}, $title, $body, 'open', NULL, NULL, NULL, NULL,
                  $rank, ${Version.first}, $parentKey, $at)
          RETURNING id"""
      .query[Long]
      .unique

  def setTitle(key: Long, title: Title): ConnectionIO[Unit] =
    sql"UPDATE issue SET title = $title WHERE id = $key".update.run.void

  def setBody(key: Long, body: Body): ConnectionIO[Unit] =
    sql"UPDATE issue SET body = $body WHERE id = $key".update.run.void

  def setRank(key: Long, rank: Rank): ConnectionIO[Unit] =
    sql"UPDATE issue SET rank = $rank WHERE id = $key".update.run.void

  def setParent(key: Long, parentKey: Option[Long]): ConnectionIO[Unit] =
    sql"UPDATE issue SET parent_id = $parentKey WHERE id = $key".update.run.void

  def bumpVersion(key: Long): ConnectionIO[Version] =
    sql"UPDATE issue SET version = version + 1 WHERE id = $key RETURNING version".query[Version].unique

  def closeIssue(key: Long, resolution: Resolution, at: Timestamp): ConnectionIO[Unit] =
    sql"UPDATE issue SET status = 'closed', resolution = $resolution, closed_at = $at WHERE id = $key".update.run.void

  def reopenIssue(key: Long): ConnectionIO[Unit] =
    sql"UPDATE issue SET status = 'open', resolution = NULL, closed_at = NULL WHERE id = $key".update.run.void

  /** The compare-and-set behind a claim: the row count says whether this caller took it. */
  def claimIfUnassigned(key: Long, assignee: Assignee, at: Timestamp): ConnectionIO[Int] =
    sql"UPDATE issue SET assignee = $assignee, claimed_at = $at WHERE id = $key AND assignee IS NULL".update.run

  def setAssignee(key: Long, assignee: Option[Assignee], at: Option[Timestamp]): ConnectionIO[Unit] =
    sql"UPDATE issue SET assignee = $assignee, claimed_at = $at WHERE id = $key".update.run.void

  // Labels

  def labels(key: Long): ConnectionIO[List[Label]] =
    sql"SELECT label FROM issue_label WHERE issue_id = $key ORDER BY label".query[Label].to[List]

  def addLabel(key: Long, label: Label): ConnectionIO[Int] =
    sql"INSERT OR IGNORE INTO issue_label (issue_id, label) VALUES ($key, $label)".update.run

  def removeLabel(key: Long, label: Label): ConnectionIO[Int] =
    sql"DELETE FROM issue_label WHERE issue_id = $key AND label = $label".update.run

  // Edges

  def parentOf(key: Long): ConnectionIO[Option[IssueRef]] =
    refs(fr"i.id = (SELECT parent_id FROM issue WHERE id = $key)").map(_.headOption)

  def children(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"i.parent_id = $key")

  def openChildren(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"i.parent_id = $key AND i.status = 'open'")

  def blockers(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"i.id IN (SELECT blocker_id FROM block_edge WHERE blocked_id = $key)")

  def openBlockers(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"i.id IN (SELECT blocker_id FROM block_edge WHERE blocked_id = $key) AND i.status = 'open'")

  def blocking(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"i.id IN (SELECT blocked_id FROM block_edge WHERE blocker_id = $key)")

  def blockerKeys(key: Long): ConnectionIO[List[Long]] =
    sql"SELECT blocker_id FROM block_edge WHERE blocked_id = $key".query[Long].to[List]

  def blockedKeys(key: Long): ConnectionIO[List[Long]] =
    sql"SELECT blocked_id FROM block_edge WHERE blocker_id = $key".query[Long].to[List]

  def addBlockEdge(blocker: Long, blocked: Long): ConnectionIO[Int] =
    sql"INSERT OR IGNORE INTO block_edge (blocker_id, blocked_id) VALUES ($blocker, $blocked)".update.run

  def removeBlockEdge(blocker: Long, blocked: Long): ConnectionIO[Int] =
    sql"DELETE FROM block_edge WHERE blocker_id = $blocker AND blocked_id = $blocked".update.run

  /** Is any issue blocking this one still open? A direct check: `ready` needs no transitive closure. */
  def isBlocked(key: Long): ConnectionIO[Boolean] =
    sql"""SELECT EXISTS (SELECT 1 FROM block_edge b JOIN issue i ON i.id = b.blocker_id
                         WHERE b.blocked_id = $key AND i.status = 'open')"""
      .query[Boolean]
      .unique

  private def refs(where: Fragment): ConnectionIO[List[IssueRef]] =
    (fr"SELECT i.project_key, i.number, i.title, i.resolution, i.closed_at FROM issue i WHERE" ++ where ++
      fr"ORDER BY i.project_key, i.number")
      .query[(ProjectKey, IssueNumber, Title, Option[Resolution], Option[Timestamp])]
      .map(toRef)
      .to[List]

  def refsFor(keys: List[Long]): ConnectionIO[List[IssueRef]] =
    NonEmptyList.fromList(keys) match
      case None       => List.empty[IssueRef].pure[ConnectionIO]
      case Some(some) => refs(Fragments.in(fr"i.id", some))

  private def toRef(
      row: (ProjectKey, IssueNumber, Title, Option[Resolution], Option[Timestamp])
  ): IssueRef =
    val (project, number, title, resolution, closedAt) = row
    val state = (resolution, closedAt) match
      case (Some(value), Some(at)) => IssueState.Closed(value, at)
      case _                       => IssueState.Open
    IssueRef(IssueId(project, number), title, state)

  // Mentions

  def replaceProseMentions(key: Long, targets: Set[IssueId]): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM mention WHERE source_id = $key AND source_kind = 'prose'".update.run
      _ <- targets.toList.traverse_(insertMention(key, "prose", _))
    yield ()

  def addCommentMentions(key: Long, targets: Set[IssueId]): ConnectionIO[Unit] =
    targets.toList.traverse_(insertMention(key, "comment", _))

  private def insertMention(key: Long, kind: String, target: IssueId): ConnectionIO[Unit] =
    sql"""INSERT OR IGNORE INTO mention (source_id, source_kind, target_key, target_number)
          VALUES ($key, $kind, ${target.project}, ${target.number})""".update.run.void

  /** The issues this one mentions, as refs — only those that exist; a forward reference waits. */
  def mentions(key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"""(i.project_key, i.number) IN
              (SELECT target_key, target_number FROM mention WHERE source_id = $key)""")

  def backlinks(id: IssueId, key: Long): ConnectionIO[List[IssueRef]] =
    refs(fr"""i.id IN (SELECT source_id FROM mention
                       WHERE target_key = ${id.project} AND target_number = ${id.number})
              AND i.id <> $key""")

  /** Every issue whose stored prose mentions the given key: used when a project is created. */
  def issuesWithProse: ConnectionIO[List[(Long, IssueId, Title, Body)]] =
    sql"SELECT id, project_key, number, title, body FROM issue"
      .query[(Long, ProjectKey, IssueNumber, Title, Body)]
      .map((key, project, number, title, body) => (key, IssueId(project, number), title, body))
      .to[List]

  def clearCommentMentions(key: Long): ConnectionIO[Unit] =
    sql"DELETE FROM mention WHERE source_id = $key AND source_kind = 'comment'".update.run.void

  /** Comments written to this issue. A related event's comment belongs to the issue it was written to. */
  def commentsOf(key: Long): ConnectionIO[List[Comment]] =
    sql"""SELECT seq, at, actor, comment FROM event
          WHERE issue_id = $key AND comment IS NOT NULL ORDER BY seq"""
      .query[(EventSeq, Timestamp, Actor, String)]
      .map(Comment.apply)
      .to[List]

  // Ranks

  def rankBelow(project: ProjectKey, rank: Rank): ConnectionIO[Option[Rank]] =
    sql"SELECT MAX(rank) FROM issue WHERE project_key = $project AND rank < $rank".query[Option[Rank]].unique

  def rankAbove(project: ProjectKey, rank: Rank): ConnectionIO[Option[Rank]] =
    sql"SELECT MIN(rank) FROM issue WHERE project_key = $project AND rank > $rank".query[Option[Rank]].unique

  // Listing, until the query grammar arrives

  def issuesIn(project: ProjectKey): ConnectionIO[List[IssueRecord]] =
    (fr"SELECT" ++ issueColumns ++ fr"FROM issue WHERE project_key = $project ORDER BY rank, number")
      .query[IssueRecord]
      .to[List]

  def childrenOf(parent: Long): ConnectionIO[List[IssueRecord]] =
    (fr"SELECT" ++ issueColumns ++ fr"FROM issue WHERE parent_id = $parent ORDER BY rank, number")
      .query[IssueRecord]
      .to[List]

  /** Open, unclaimed and with no open blocker: work anyone could take right now. */
  def ready(project: Option[ProjectKey]): ConnectionIO[List[IssueRecord]] =
    val scope = project.fold(fr"")(key => fr"AND project_key = $key")
    (fr"SELECT" ++ issueColumns ++ fr"""FROM issue
          WHERE status = 'open' AND assignee IS NULL""" ++ scope ++ fr"""
            AND NOT EXISTS (SELECT 1 FROM block_edge b JOIN issue blocker ON blocker.id = b.blocker_id
                            WHERE b.blocked_id = issue.id AND blocker.status = 'open')
          ORDER BY rank, number""")
      .query[IssueRecord]
      .to[List]

  // Full-text index

  def indexIssue(key: Long, title: Title, body: Body, comments: String): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM issue_fts WHERE rowid = $key".update.run
      _ <- sql"INSERT INTO issue_fts (rowid, title, body, comments) VALUES ($key, $title, $body, $comments)".update.run
    yield ()
