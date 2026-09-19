package tikka.core

import cats.data.NonEmptyList
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

  /** The time of the latest event on each issue's timeline, related events included, which is what `updated` means. */
  def updatedAtAmong(keys: NonEmptyList[Long]): ConnectionIO[Map[Long, Timestamp]] =
    (fr"""SELECT t.issue, MAX(e.at) FROM (
            SELECT issue_id AS issue, seq AS event FROM event WHERE""" ++ Fragments.in(fr"issue_id", keys) ++
      fr"""UNION ALL
           SELECT issue_id AS issue, event_seq AS event FROM event_related WHERE""" ++
      Fragments.in(fr"issue_id", keys) ++
      fr""") t JOIN event e ON e.seq = t.event
            GROUP BY t.issue""")
      .query[(Long, Timestamp)]
      .to[List]
      .map(_.toMap)

  def total(key: Long): ConnectionIO[Int] =
    sql"""SELECT COUNT(*) FROM event
          WHERE issue_id = $key OR seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key)"""
      .query[Int]
      .unique

  /** The latest `limit` events on an issue's timeline, oldest first within those. */
  def timeline(key: Long, limit: Int): ConnectionIO[List[Event]] =
    sql"""SELECT e.seq, e.at, e.actor, i.project_key, i.number, e.comment
          FROM event e JOIN issue i ON i.id = e.issue_id
          WHERE e.issue_id = $key
             OR e.seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key)
          ORDER BY e.seq DESC LIMIT $limit"""
      .query[Row]
      .to[List]
      .flatMap(rows => hydrate(rows.reverse))

  /** Every event in the store, oldest first. The export writes these. */
  def all: ConnectionIO[List[Event]] =
    withChanges(fr"ORDER BY e.seq")

  /** Events after a sequence number, across all issues. */
  def after(seq: Long, limit: Int): ConnectionIO[List[Event]] =
    withChanges(fr"WHERE e.seq > $seq ORDER BY e.seq LIMIT $limit")

  /** One issue's timeline after a sequence number, related events included. */
  def timelineAfter(key: Long, seq: Long, limit: Int): ConnectionIO[List[Event]] =
    withChanges(fr"""WHERE e.seq > $seq
                       AND (e.issue_id = $key OR e.seq IN (SELECT event_seq FROM event_related WHERE issue_id = $key))
                     ORDER BY e.seq LIMIT $limit""")

  private def withChanges(rest: Fragment): ConnectionIO[List[Event]] =
    (fr"""SELECT e.seq, e.at, e.actor, i.project_key, i.number, e.comment
          FROM event e JOIN issue i ON i.id = e.issue_id""" ++ rest)
      .query[Row]
      .to[List]
      .flatMap(hydrate)

  /** An event as the `event` table holds it, before its changes and related issues are hung off it. */
  private type Row = (EventSeq, Timestamp, Actor, ProjectKey, IssueNumber, Option[String])

  /** Fills in every row's changes and related issues in two queries, rather than two per row: a page of the feed is 200
    * events, and the live stream walks it on every write.
    */
  private def hydrate(rows: List[Row]): ConnectionIO[List[Event]] =
    NonEmptyList.fromList(rows.map(_._1)) match
      case None       => List.empty[Event].pure[ConnectionIO]
      case Some(seqs) =>
        (changesAmong(seqs), relatedAmong(seqs)).mapN: (changes, related) =>
          rows.map: (seq, at, actor, project, number, comment) =>
            Event(
              seq,
              at,
              actor,
              IssueId(project, number),
              related.getOrElse(seq, Nil),
              changes.getOrElse(seq, Nil),
              comment
            )

  private def relatedAmong(seqs: NonEmptyList[EventSeq]): ConnectionIO[Map[EventSeq, List[IssueId]]] =
    (fr"""SELECT r.event_seq, i.project_key, i.number FROM event_related r JOIN issue i ON i.id = r.issue_id
          WHERE""" ++ Fragments.in(fr"r.event_seq", seqs) ++ fr"ORDER BY r.event_seq, i.project_key, i.number")
      .query[(EventSeq, ProjectKey, IssueNumber)]
      .to[List]
      .map(_.groupMap(_._1)((_, project, number) => IssueId(project, number)))

  private def changesAmong(seqs: NonEmptyList[EventSeq]): ConnectionIO[Map[EventSeq, List[Change]]] =
    (fr"SELECT event_seq, field, op, old, new FROM change WHERE" ++ Fragments.in(fr"event_seq", seqs) ++
      fr"ORDER BY event_seq, ordinal")
      .query[(EventSeq, ChangeField, ChangeOp, Option[String], Option[String])]
      .to[List]
      .map(_.groupMap(_._1)((_, field, op, old, updated) => Change(field, op, old, updated)))
