package tikka.shared

opaque type Port = Int

object Port:
  def parse(value: Long): Either[String, Port] =
    if value >= 1 && value <= 65535 then Right(value.toInt) else Left(s"$value is not a port: expected 1 to 65535")

  private[tikka] def trusted(value: Int): Port = value

  extension (port: Port) def value: Int = port

/** `<home>/config.toml`, read by the daemon and the CLI alike.
  *
  * The port is fixed rather than discovered, because MCP client configs hardcode the URL.
  */
final case class DaemonConfig(port: Port)

object DaemonConfig:
  val defaultPort: Port = Port.trusted(7017)

  val default: DaemonConfig = DaemonConfig(defaultPort)

  def parse(text: String): Either[String, DaemonConfig] =
    for
      values <- Toml.parse(text)
      port <- Toml.number(values, "port")
      parsed <- port.fold(Right(defaultPort))(Port.parse)
    yield DaemonConfig(parsed)

/** A repository's `.tikka` file: the project a command means when it names none. */
final case class RepoBinding(project: ProjectKey)

object RepoBinding:
  def parse(text: String): Either[String, RepoBinding] =
    for
      values <- Toml.parse(text)
      project <- Toml.string(values, "project")
      key <- project.toRight("'.tikka' must set project, as in project = \"TIK\"")
      parsed <- ProjectKey.parse(key)
    yield RepoBinding(parsed)

  def render(binding: RepoBinding): String =
    s"""project = "${binding.project.value}"\n"""
