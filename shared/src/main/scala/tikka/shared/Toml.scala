package tikka.shared

/** A value in tikka's config files. */
enum TomlValue:
  case Str(value: String)
  case Num(value: Long)
  case Bool(value: Boolean)

/** A reader for the slice of TOML tikka's own files use: comments, blank lines, and top-level `key = value` with a
  * basic string, integer or boolean.
  *
  * Hand-written because the same reader has to run on the JVM, in the browser and in the Scala Native CLI, and no
  * maintained TOML library cross-builds to all three. Anything outside the subset is refused by name and line, so a
  * file that needs more fails loudly rather than being half-read.
  */
object Toml:
  def parse(text: String): Either[String, Map[String, TomlValue]] =
    val entries = text.linesIterator.zipWithIndex.map(readLine).toList
    entries.partitionMap(identity) match
      case (Nil, values)     => Right(values.flatten.toMap)
      case (problem :: _, _) => Left(problem)

  private def readLine(line: String, index: Int): Either[String, Option[(String, TomlValue)]] =
    val number = index + 1
    val content = stripComment(line).trim
    if content.isEmpty then Right(None)
    else if content.startsWith("[") then Left(s"line $number: tikka's config has no tables, but found '$content'")
    else
      content.split("=", 2) match
        case Array(key, value) =>
          readValue(value.trim, number).map(parsed => Some(key.trim -> parsed))
        case _ => Left(s"line $number: expected 'key = value', but found '$content'")

  private def readValue(value: String, line: Int): Either[String, TomlValue] =
    if value.startsWith("\"") then readString(value, line)
    else if value == "true" then Right(TomlValue.Bool(true))
    else if value == "false" then Right(TomlValue.Bool(false))
    else value.toLongOption.map(TomlValue.Num.apply).toRight(s"line $line: '$value' is not a string, number or boolean")

  private def readString(value: String, line: Int): Either[String, TomlValue] =
    val builder = StringBuilder()
    var index = 1
    var escaped = false
    var finished = false
    while index < value.length && !finished do
      val char = value.charAt(index)
      if escaped then
        builder.append(char match
          case 'n'   => '\n'
          case 't'   => '\t'
          case other => other)
        escaped = false
      else if char == '\\' then escaped = true
      else if char == '"' then finished = true
      else builder.append(char)
      index += 1
    if !finished then Left(s"line $line: the string is not closed")
    else if index < value.length then Left(s"line $line: unexpected text after the string")
    else Right(TomlValue.Str(builder.result()))

  /** A `#` inside a string is content, not a comment. */
  private def stripComment(line: String): String =
    val hash = line.indices.find: index =>
      line.charAt(index) == '#' && line.take(index).count(_ == '"') % 2 == 0
    hash.fold(line)(line.take)

  def string(values: Map[String, TomlValue], key: String): Either[String, Option[String]] =
    values.get(key) match
      case None                       => Right(None)
      case Some(TomlValue.Str(value)) => Right(Some(value))
      case Some(_)                    => Left(s"'$key' must be a string")

  def number(values: Map[String, TomlValue], key: String): Either[String, Option[Long]] =
    values.get(key) match
      case None                       => Right(None)
      case Some(TomlValue.Num(value)) => Right(Some(value))
      case Some(_)                    => Left(s"'$key' must be a number")
