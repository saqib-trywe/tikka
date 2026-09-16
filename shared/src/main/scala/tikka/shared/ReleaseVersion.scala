package tikka.shared

/** A tikka release version, as set in the build. Distinct from an issue's [[Version]], which guards concurrent edits.
  */
opaque type ReleaseVersion = String

object ReleaseVersion:
  def apply(value: String): ReleaseVersion = value

  extension (version: ReleaseVersion) def value: String = version
