package tikka.shared

object Render:
  def row(issue: Issue): String = s"${issue.id.value}  open  ${issue.title.value}"

  def rows(result: SearchResult): String =
    if result.issues.isEmpty then s"no issues match `${result.effective_query}`"
    else result.issues.map(row).mkString("\n")
