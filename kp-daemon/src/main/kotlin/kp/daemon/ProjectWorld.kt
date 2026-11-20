package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.FactSource
import java.nio.file.Path

/** The service's world (contracts.md §4.4, Amendment 32): `kp-facts` over the office's checkouts and the fold. */
class ProjectWorld private constructor(val reads: String) : FactSource {
    override fun observe(fact: FactRef, stage: String?): Datum? = null

    /** Reads the fold of [office] from now on. */
    fun reading(office: Office) = Unit

    companion object {
        fun of(rules: InForceRules, office: String, project: Path, path: String? = System.getenv("PATH"), say: (String) -> Unit = Journal::say): ProjectWorld = ProjectWorld("")
    }
}
