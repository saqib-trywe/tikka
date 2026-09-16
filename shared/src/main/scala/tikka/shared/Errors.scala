package tikka.shared

/** The wire name of a rejection. MCP returns it in `structuredContent`, HTTP in the error body, the CLI on stderr. */
enum ErrorCode:
  case NotFound, UnknownProject, InvalidQuery, InvalidArgument, StaleVersion, EditMismatch, ClaimConflict, NotHolder,
    IssueClosed, OpenChildren, OpenBlockers, Cycle, CrossProjectEdge, ResolutionImmutable, ReopenBlocked, RankPrecision

object ErrorCode:
  extension (code: ErrorCode)
    def value: String = code match
      case NotFound            => "not_found"
      case UnknownProject      => "unknown_project"
      case InvalidQuery        => "invalid_query"
      case InvalidArgument     => "invalid_argument"
      case StaleVersion        => "stale_version"
      case EditMismatch        => "edit_mismatch"
      case ClaimConflict       => "claim_conflict"
      case NotHolder           => "not_holder"
      case IssueClosed         => "issue_closed"
      case OpenChildren        => "open_children"
      case OpenBlockers        => "open_blockers"
      case Cycle               => "cycle"
      case CrossProjectEdge    => "cross_project_edge"
      case ResolutionImmutable => "resolution_immutable"
      case ReopenBlocked       => "reopen_blocked"
      case RankPrecision       => "rank_precision"

/** Why `body_edits` could not be applied. */
enum EditMismatchKind:
  case Missing, Ambiguous

/** A rejection the caller can act on without asking another question, so each case carries the conflicting facts.
  *
  * These are values, not exceptions: a domain rejection is an ordinary outcome. Exceptions stay for defects — a corrupt
  * store, a migration that will not apply.
  */
enum DomainError:
  case NotFound(id: IssueId)
  case UnknownProject(requested: ProjectKey, known: List[ProjectKey])
  case InvalidArgument(argument: String, reason: String)
  case StaleVersion(current: Version, title: Option[Title], body: Option[Body], labels: Option[List[Label]])
  case EditMismatch(edit: BodyEdit, kind: EditMismatchKind)
  case ClaimConflict(holder: Assignee, since: Option[Timestamp])
  case NotHolder(actual: Option[Assignee])
  case IssueClosed(resolution: Resolution, at: Timestamp)
  case OpenChildren(children: List[IssueRef])
  case OpenBlockers(blockers: List[IssueRef])
  case Cycle(path: List[IssueId])
  case CrossProjectEdge(from: IssueId, to: IssueId)
  case ResolutionImmutable(current: Resolution)
  case ReopenBlocked(obstacles: List[IssueRef])
  case RankPrecision(before: Rank, after: Rank)

object DomainError:
  extension (error: DomainError)
    def code: ErrorCode = error match
      case NotFound(_)              => ErrorCode.NotFound
      case UnknownProject(_, _)     => ErrorCode.UnknownProject
      case InvalidArgument(_, _)    => ErrorCode.InvalidArgument
      case StaleVersion(_, _, _, _) => ErrorCode.StaleVersion
      case EditMismatch(_, _)       => ErrorCode.EditMismatch
      case ClaimConflict(_, _)      => ErrorCode.ClaimConflict
      case NotHolder(_)             => ErrorCode.NotHolder
      case IssueClosed(_, _)        => ErrorCode.IssueClosed
      case OpenChildren(_)          => ErrorCode.OpenChildren
      case OpenBlockers(_)          => ErrorCode.OpenBlockers
      case Cycle(_)                 => ErrorCode.Cycle
      case CrossProjectEdge(_, _)   => ErrorCode.CrossProjectEdge
      case ResolutionImmutable(_)   => ErrorCode.ResolutionImmutable
      case ReopenBlocked(_)         => ErrorCode.ReopenBlocked
      case RankPrecision(_, _)      => ErrorCode.RankPrecision
