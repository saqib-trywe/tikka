package tikka.core

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import tikka.shared.*

import Codecs.given

/** One write's worth of event: the fields it moved, the timelines it also belongs on, and the comment explaining it.
  *
  * `related` holds issues whose edges the write changed, or that it unblocked or blocked again. Mentions never relate,
  * so a backlink appears without disturbing the mentioned issue's timeline.
  */
private[core] final case class EventDraft(
    changes: List[Change],
    related: Set[Long],
    comment: Option[String]
):
  def isEmpty: Boolean = changes.isEmpty && comment.isEmpty

private[core] object EventDraft:
  val empty: EventDraft = EventDraft(Nil, Set.empty, None)

  def set(field: ChangeField, old: Option[String], updated: Option[String]): Change =
    Change(field, ChangeOp.Set, old, updated)

  def add(field: ChangeField, value: String): Change = Change(field, ChangeOp.Add, None, Some(value))

  def remove(field: ChangeField, value: String): Change = Change(field, ChangeOp.Remove, Some(value), None)

private[core] object Events:
  /** Writes the event, unless the write changed nothing and carried no comment. */
  def record(subject: Long, actor: Actor, at: Timestamp, draft: EventDraft): ConnectionIO[Option[EventSeq]] =
    if draft.isEmpty then Option.empty[EventSeq].pure[ConnectionIO]
    else
      for
        seq <- sql"""INSERT INTO event (issue_id, at, actor, comment)
                     VALUES ($subject, $at, $actor, ${draft.comment}) RETURNING seq"""
          .query[EventSeq]
          .unique
        _ <- draft.changes.zipWithIndex.traverse_((change, ordinal) => insertChange(seq, ordinal, change))
        _ <- draft.related.filterNot(_ == subject).toList.traverse_(insertRelated(seq, _))
      yield Some(seq)

  private def insertChange(seq: EventSeq, ordinal: Int, change: Change): ConnectionIO[Unit] =
    sql"""INSERT INTO change (event_seq, ordinal, field, op, old, new)
          VALUES ($seq, $ordinal, ${change.field}, ${change.op}, ${change.old}, ${change.updated})""".update.run.void

  private def insertRelated(seq: EventSeq, issue: Long): ConnectionIO[Unit] =
    sql"INSERT OR IGNORE INTO event_related (event_seq, issue_id) VALUES ($seq, $issue)".update.run.void

  /** The time of the latest event on an issue's timeline, which is what `updated` means. */
  def updatedAt(key: Long, fallback: Timestamp): ConnectionIO[Timestamp] =
    sql"""SELECT MAX(at) FROM event
          WHERE issue_id = $key OR seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key)"""
      .query[Option[Timestamp]]
      .unique
      .map(_.getOrElse(fallback))

  def total(key: Long): ConnectionIO[Int] =
    sql"""SELECT COUNT(*) FROM event
          WHERE issue_id = $key OR seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key)"""
      .query[Int]
      .unique

  /** The latest `limit` events on an issue's timeline, oldest first within those. */
  def timeline(key: Long, limit: Int): ConnectionIO[List[Event]] =
    for
      rows <- sql"""SELECT e.seq, e.at, e.actor, i.project_key, i.number, e.comment
                    FROM event e JOIN issue i ON i.id = e.issue_id
                    WHERE e.issue_id = $key
                       OR e.seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key)
                    ORDER BY e.seq DESC LIMIT $limit"""
        .query[(EventSeq, Timestamp, Actor, ProjectKey, IssueNumber, Option[String])]
        .to[List]
      changes <- rows.reverse.traverse(row => changesOf(row._1).map(row -> _))
    yield changes.map: (row, rowChanges) =>
      val (seq, at, actor, project, number, comment) = row
      Event(seq, at, actor, IssueId(project, number), rowChanges, comment)

  /** Every event in the store, oldest first. The export writes these; the HTTP feed will read them by sequence. */
  def all: ConnectionIO[List[Event]] =
    for
      rows <- sql"""SELECT e.seq, e.at, e.actor, i.project_key, i.number, e.comment
                    FROM event e JOIN issue i ON i.id = e.issue_id ORDER BY e.seq"""
        .query[(EventSeq, Timestamp, Actor, ProjectKey, IssueNumber, Option[String])]
        .to[List]
      withChanges <- rows.traverse(row => changesOf(row._1).map(row -> _))
    yield withChanges.map: (row, changes) =>
      val (seq, at, actor, project, number, comment) = row
      Event(seq, at, actor, IssueId(project, number), changes, comment)

  private def changesOf(seq: EventSeq): ConnectionIO[List[Change]] =
    sql"SELECT field, op, old, new FROM change WHERE event_seq = $seq ORDER BY ordinal"
      .query[(ChangeField, ChangeOp, Option[String], Option[String])]
      .map(Change.apply)
      .to[List]
