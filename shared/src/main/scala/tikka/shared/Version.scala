package tikka.shared

/** A tikka release version, as set in the build. */
opaque type Version = String

object Version:
  def apply(value: String): Version = value

  extension (version: Version) def value: String = version
