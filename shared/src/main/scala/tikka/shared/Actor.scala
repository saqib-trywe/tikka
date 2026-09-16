package tikka.shared

/** Where a write came in. No write carries an actor argument: the surface knows. */
enum Surface:
  case Mcp, Cli, Web, Daemon

object Surface:
  extension (surface: Surface)
    def value: String = surface match
      case Mcp    => "mcp"
      case Cli    => "cli"
      case Web    => "web"
      case Daemon => "daemon"

/** Who made a write: the surface plus the client name it announced, as in `mcp:claude-code`.
  *
  * Not a person and not the assignee — parallel sessions often share a client name, which is why claims carry the
  * assignee as change data instead.
  */
final case class Actor(surface: Surface, client: Option[String]):
  def render: String = client.fold(surface.value)(name => s"${surface.value}:$name")

object Actor:
  def parse(value: String): Either[String, Actor] =
    val (surfaceText, clientText) = value.span(_ != ':')
    val client = Option(clientText.drop(1)).filter(_.nonEmpty)
    surfaceText match
      case "mcp"    => Right(Actor(Surface.Mcp, client))
      case "cli"    => Right(Actor(Surface.Cli, client))
      case "web"    => Right(Actor(Surface.Web, client))
      case "daemon" => Right(Actor(Surface.Daemon, client))
      case other    => Left(s"'$other' is not a surface: expected mcp, cli, web or daemon")
