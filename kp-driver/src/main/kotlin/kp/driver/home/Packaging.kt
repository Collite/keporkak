package kp.driver.home

import kotlinx.serialization.json.JsonObject
import kp.driver.Outcome

/** A profile's machine part: the values its frontmatter gives the packaging data's placeholders (contracts.md §10). */
class Profile private constructor(val name: String, val values: Map<String, Any?>) {
    companion object {
        fun read(name: String, text: String): Outcome<Profile> = TODO("T2")
    }
}

/**
 * The packaging data, `agents/packaging/claude.yaml` (contracts.md §10, T2): each neutral `deny:` word as rules with
 * named placeholders, the MCP servers a persona may name, by address, and the three hooks' events.
 */
class Packaging private constructor() {
    /** The `deny:` words compiled for one session: the content of `permissions.json`. */
    fun permissions(words: List<String>, profile: Profile, places: Places, defaults: PermissionDefaults): Outcome<JsonObject> = TODO("T2")

    companion object {
        fun read(text: String): Outcome<Packaging> = TODO("T2")
    }
}
