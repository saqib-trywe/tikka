package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import tikka.shared.BuildVersion

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** `tikka daemon …`. The daemon is a JVM process, so these start `java` on its jar or ask the service manager to.
  *
  * Nothing starts the daemon implicitly: a service manager gives crash restart and start at login, and `run` keeps it
  * in the foreground for development.
  */
object DaemonControl:
  enum Platform:
    case MacOs, Linux, Unsupported

  object Platform:
    def of(osName: String): Platform =
      if osName.startsWith("Mac") then MacOs else if osName.startsWith("Linux") then Linux else Unsupported

    def current: Platform = of(Option(System.getProperty("os.name")).getOrElse(""))

  /** What a service manager is told, and the exact commands that drive it. */
  final case class Service(
      file: Path,
      definition: String,
      install: List[List[String]],
      start: List[String],
      stop: List[String],
      restart: List[String]
  )

  def jar(settings: Settings): Path = settings.home.resolve("lib").resolve("tikka-daemon.jar")

  def logFile(settings: Settings): Path = settings.home.resolve("logs").resolve("daemon.log")

  /** `$JAVA_HOME/bin/java` if it exists, then `java` on `PATH`. Its absolute path is recorded at install, so a later
    * change of default JDK never silently changes what the service runs.
    */
  def java(environment: Environment): Option[Path] =
    environment.variables
      .get("JAVA_HOME")
      .map(home => Path.of(home, "bin", "java"))
      .filter(Files.isExecutable)
      .orElse(Processes.which("java", environment.variables.get("PATH")))

  def jarCommand(java: Path, jar: Path, arguments: List[String]): List[String] =
    List(java.toString, "--enable-native-access=ALL-UNNAMED", "-jar", jar.toString) ++ arguments

  // Foreground: run, export, restore

  def run(context: Context): IO[ExitCode] = throughJar(context, List("run"))

  /** JSON Lines from the store, read-only, working whether or not a daemon is running. */
  def exportTo(context: Context, file: String): IO[ExitCode] =
    throughJar(context, List("export", context.environment.workingDirectory.resolve(file).toString))

  /** Puts a snapshot back with the daemon stopped, moving the current store aside first. */
  def restore(context: Context, snapshot: String): IO[ExitCode] =
    throughJar(context, List("restore", context.environment.workingDirectory.resolve(snapshot).toString))

  private def throughJar(context: Context, arguments: List[String]): IO[ExitCode] =
    prerequisites(context) match
      case Left(problem)      => context.environment.err(problem).as(ExitCode(1))
      case Right((java, jar)) =>
        context.processes
          .interactive(jarCommand(java, jar, arguments), Map("TIKKA_HOME" -> context.settings.home.toString))
          .map(ExitCode(_))

  private def prerequisites(context: Context): Either[String, (Path, Path)] =
    val daemonJar = jar(context.settings)
    for
      _ <- Either.cond(
        Files.isRegularFile(daemonJar),
        (),
        s"no daemon jar at $daemonJar; install tikka with scripts/install.sh"
      )
      found <- java(context.environment).toRight("no java found: set JAVA_HOME or put java on PATH (JDK 21 or newer)")
    yield (found, daemonJar)

  // The service manager

  /** One service per home, so a development home and the real one never replace each other. */
  def label(settings: Settings, environment: Environment): String =
    if environment.variables.contains("TIKKA_HOME") then s"dev.tikka.daemon.${suffix(settings.home)}"
    else "dev.tikka.daemon"

  private def suffix(home: Path): String =
    Option(home.getFileName)
      .map(_.toString)
      .getOrElse("home")
      .toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")

  def service(
      platform: Platform,
      environment: Environment,
      settings: Settings,
      java: Path,
      userId: String
  ): Either[String, Service] =
    val name = label(settings, environment)
    val command = jarCommand(java, jar(settings), List("run"))
    val home = Path.of(environment.variables.getOrElse("HOME", "."))
    platform match
      case Platform.MacOs =>
        val file = home.resolve("Library").resolve("LaunchAgents").resolve(s"$name.plist")
        val domain = s"gui/$userId"
        Right(
          Service(
            file,
            launchdPlist(name, command, settings.home, logFile(settings)),
            install = List(List("launchctl", "bootstrap", domain, file.toString)),
            start = List("launchctl", "bootstrap", domain, file.toString),
            stop = List("launchctl", "bootout", s"$domain/$name"),
            restart = List("launchctl", "kickstart", "-k", s"$domain/$name")
          )
        )
      case Platform.Linux =>
        val unit = if name == "dev.tikka.daemon" then "tikka" else s"tikka-${suffix(settings.home)}"
        val configDir = environment.variables.get("XDG_CONFIG_HOME").map(Path.of(_)).getOrElse(home.resolve(".config"))
        val file = configDir.resolve("systemd").resolve("user").resolve(s"$unit.service")
        Right(
          Service(
            file,
            systemdUnit(command, settings.home, logFile(settings)),
            install = List(
              List("systemctl", "--user", "daemon-reload"),
              List("systemctl", "--user", "enable", "--now", unit)
            ),
            start = List("systemctl", "--user", "start", unit),
            stop = List("systemctl", "--user", "stop", unit),
            restart = List("systemctl", "--user", "restart", unit)
          )
        )
      case Platform.Unsupported =>
        Left("tikka's daemon service supports macOS (launchd) and Linux (systemd) only")

  /** A launchd agent: start at login, restart if it exits, logs appended under the home. */
  def launchdPlist(label: String, command: List[String], home: Path, log: Path): String =
    val arguments = command.map(argument => s"    <string>${xml(argument)}</string>").mkString("\n")
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
       |<plist version="1.0">
       |<dict>
       |  <key>Label</key>
       |  <string>${xml(label)}</string>
       |  <key>ProgramArguments</key>
       |  <array>
       |$arguments
       |  </array>
       |  <key>EnvironmentVariables</key>
       |  <dict>
       |    <key>TIKKA_HOME</key>
       |    <string>${xml(home.toString)}</string>
       |  </dict>
       |  <key>RunAtLoad</key>
       |  <true/>
       |  <key>KeepAlive</key>
       |  <true/>
       |  <key>StandardOutPath</key>
       |  <string>${xml(log.toString)}</string>
       |  <key>StandardErrorPath</key>
       |  <string>${xml(log.toString)}</string>
       |</dict>
       |</plist>
       |""".stripMargin

  /** A systemd user unit: started with the user's session, restarted on failure, logs appended under the home. */
  def systemdUnit(command: List[String], home: Path, log: Path): String =
    s"""[Unit]
       |Description=tikka daemon (${home})
       |
       |[Service]
       |Environment=TIKKA_HOME=${home}
       |ExecStart=${command.map(quoteSystemd).mkString(" ")}
       |Restart=on-failure
       |StandardOutput=append:${log}
       |StandardError=append:${log}
       |
       |[Install]
       |WantedBy=default.target
       |""".stripMargin

  private def xml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def quoteSystemd(argument: String): String =
    if argument.exists(_.isWhitespace) then "\"" + argument.replace("\"", "\\\"") + "\"" else argument

  def install(context: Context): IO[ExitCode] =
    prerequisites(context) match
      case Left(problem)    => context.environment.err(problem).as(ExitCode(1))
      case Right((java, _)) =>
        userId(context).flatMap: user =>
          service(Platform.current, context.environment, context.settings, java, user) match
            case Left(problem)     => context.environment.err(problem).as(ExitCode(1))
            case Right(definition) =>
              for
                _ <- IO.blocking:
                  Files.createDirectories(definition.file.getParent)
                  Files.createDirectories(logFile(context.settings).getParent)
                  Files.write(definition.file, definition.definition.getBytes(UTF_8))
                _ <- context.environment.out(s"wrote ${definition.file}")
                code <- runAll(context, definition.install)
                _ <- context.environment.out(
                  if code == ExitCode.Success then s"tikka daemon installed; logs in ${logFile(context.settings)}"
                  else "the service manager refused; see its output above"
                )
              yield code

  def start(context: Context): IO[ExitCode] = managed(context)(service => List(service.start))

  def stop(context: Context): IO[ExitCode] = managed(context)(service => List(service.stop))

  def restart(context: Context): IO[ExitCode] = managed(context)(service => List(service.restart))

  private def managed(context: Context)(commands: Service => List[List[String]]): IO[ExitCode] =
    prerequisites(context) match
      case Left(problem)    => context.environment.err(problem).as(ExitCode(1))
      case Right((java, _)) =>
        userId(context).flatMap: user =>
          service(Platform.current, context.environment, context.settings, java, user) match
            case Left(problem)                                       => context.environment.err(problem).as(ExitCode(1))
            case Right(definition) if !Files.exists(definition.file) =>
              context.environment
                .err("the tikka daemon service is not installed; run `tikka daemon install`")
                .as(ExitCode(1))
            case Right(definition) => runAll(context, commands(definition))

  private def runAll(context: Context, commands: List[List[String]]): IO[ExitCode] =
    commands match
      case Nil             => IO.pure(ExitCode.Success)
      case command :: rest =>
        context.processes
          .capture(command, None)
          .flatMap: (status, output) =>
            val shown = if output.trim.nonEmpty then context.environment.err(output.trim) else IO.unit
            shown *> (if status == 0 then runAll(context, rest) else IO.pure(ExitCode(1)))

  private def userId(context: Context): IO[String] =
    context.processes.capture(List("id", "-u"), None).map((_, output) => output.trim)

  /** Who holds the home's lock, and whether the daemon answers. */
  def status(context: Context): IO[ExitCode] =
    val lock = context.settings.home.resolve("daemon.lock")
    for
      holder <- IO.blocking(
        Option.when(Files.exists(lock))(String(Files.readAllBytes(lock), UTF_8).trim).filter(_.nonEmpty)
      )
      meta <- context.api.meta
      code <- meta match
        case Right(answer) =>
          val mismatch =
            if answer.version != BuildVersion.current.value then s" (this CLI is ${BuildVersion.current.value})" else ""
          context.environment
            .out(
              s"running at ${context.settings.baseUrl}, version ${answer.version}$mismatch; ${holder.getOrElse("no lock file")}"
            )
            .as(ExitCode.Success)
        case Left(_) =>
          context.environment.out(s"not running at ${context.settings.baseUrl}").as(ExitCode(3))
    yield code

  def logs(context: Context, lines: Int): IO[ExitCode] =
    val file = logFile(context.settings)
    IO.blocking(Files.exists(file))
      .flatMap:
        case false => context.environment.err(s"no daemon log at $file yet").as(ExitCode(1))
        case true  =>
          IO.blocking(Files.readAllLines(file, UTF_8).asScala.toList.takeRight(lines))
            .flatMap(tail => context.environment.out(tail.mkString("\n")))
            .as(ExitCode.Success)
