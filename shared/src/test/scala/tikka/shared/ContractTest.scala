package tikka.shared

/** The contract's parsing rules, checked on every platform the contract builds for. */
class ContractTest extends munit.FunSuite:
  test("a project key is 2-10 uppercase characters starting with a letter"):
    assert(ProjectKey.parse("TIK").isRight)
    assert(ProjectKey.parse("WF2").isRight)
    assert(ProjectKey.parse("T").isLeft)
    assert(ProjectKey.parse("tik").isLeft)
    assert(ProjectKey.parse("2TIK").isLeft)
    assert(ProjectKey.parse("TOOLONGKEY1").isLeft)

  test("an issue id round-trips through its rendered form"):
    val parsed = IssueId.parse("TIK-42")
    assertEquals(parsed.map(_.render), Right("TIK-42"))

  test("an issue id refuses padded, lowercase and zero numbers"):
    assert(IssueId.parse("TIK-042").isLeft)
    assert(IssueId.parse("tik-42").isLeft)
    assert(IssueId.parse("TIK-0").isLeft)
    assert(IssueId.parse("TIK").isLeft)

  test("labels are lowercased so no two differ only by case"):
    assertEquals(Label.parse("Bug").map(_.value), Label.parse("bug").map(_.value))

  test("an actor renders as surface and client"):
    assertEquals(Actor(Surface.Mcp, Some("claude-code")).render, "mcp:claude-code")
    assertEquals(Actor(Surface.Cli, None).render, "cli")
    assertEquals(Actor.parse("mcp:claude-code"), Right(Actor(Surface.Mcp, Some("claude-code"))))

  test("a timestamp must be ISO-8601 UTC with milliseconds"):
    assert(Timestamp.parse("2026-09-16T10:02:03.004Z").isRight)
    assert(Timestamp.parse("2026-09-16T10:02:03Z").isLeft)

  test("config reads a port and falls back to the default"):
    assertEquals(DaemonConfig.parse("port = 9000\n").map(_.port.value), Right(9000))
    assertEquals(DaemonConfig.parse("# nothing here\n").map(_.port.value), Right(DaemonConfig.defaultPort.value))
    assert(DaemonConfig.parse("port = 99999\n").isLeft)

  test("a repo binding round-trips through its file text"):
    val binding = ProjectKey.parse("TIK").map(RepoBinding.apply)
    assertEquals(binding.map(RepoBinding.render).flatMap(RepoBinding.parse), binding)

  test("a repo binding without a project is refused"):
    assert(RepoBinding.parse("").isLeft)

  test("the TOML subset reads strings, numbers, booleans and comments"):
    val parsed = Toml.parse("""
      |# a comment
      |port = 7017
      |project = "TIK"   # trailing comment
      |quoted = "a # hash"
      |flag = true
      |""".stripMargin)
    assertEquals(
      parsed,
      Right(
        Map(
          "port" -> TomlValue.Num(7017),
          "project" -> TomlValue.Str("TIK"),
          "quoted" -> TomlValue.Str("a # hash"),
          "flag" -> TomlValue.Bool(true)
        )
      )
    )

  test("the TOML subset refuses what it cannot read, naming the line"):
    assert(Toml.parse("[server]\nport = 1\n").left.exists(_.contains("line 1")))
    assert(Toml.parse("port\n").left.exists(_.contains("line 1")))
    assert(Toml.parse("project = \"TIK\n").isLeft)
