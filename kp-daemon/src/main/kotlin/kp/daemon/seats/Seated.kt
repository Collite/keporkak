package kp.daemon.seats

import kp.daemon.InForceRules
import kp.daemon.Office
import kp.daemon.RuleFiles
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.Operations
import kp.rules.duration
import kp.seatapi.SeatApi
import java.nio.file.Path
import java.time.Duration
import kotlin.time.toJavaDuration

/**
 * An office that drives real seats (S2.4; architecture.md §2, §4): its mailroom, the seats in front of the driver, and
 * the seat API on [listen] in front of the mailroom. The seats perform the seat operations and answer the seats' facts;
 * [world] and [operations] the rest. [seats] is made with the rules the office starts on.
 */
class Seated private constructor(val office: Office, val seats: Seats, val api: SeatApi) : AutoCloseable {
    /** The seat API's address, `host:port`: what each session is given for its messaging server and its hooks. */
    val listen: String get() = "${api.address.hostString}:${api.address.port}"

    /**
     * Each seat the state holds live and the driver does not reported gone, then the watch every `watch.every` of the
     * office in `pool/offices.yaml` — or [every], when the caller sets it (null: no watch).
     */
    fun start(every: Duration? = watchEvery()) = seats.start(every)

    /** The office's `watch.every` (contracts.md §9, Amendment 22). */
    fun watchEvery(): Duration? = office.rules.ruleSet.pool.offices[office.name]?.watchEvery?.let(::duration)?.toJavaDuration()

    /** The seats stopped first — they die with the daemon — then the API. The office is its daemon's to close. */
    override fun close() {
        seats.close()
        api.close()
    }

    companion object {
        fun start(
            name: String, project: Path, root: Path, world: FactSource, operations: Operations, clock: Clock, listen: String,
            seats: (startingRules: () -> InForceRules?) -> Seats,
        ): Seated {
            var started: Office? = null
            val rules = RuleFiles(root)
            val made = seats { started?.rules ?: InForceRules.of(rules.lastRead.ifEmpty { rules.read() }).first }
            val office = try {
                Office.start(name, project, root, made.facts(world), made.operations(operations), made, clock)
            } catch (error: Exception) {
                made.close()
                throw error
            }
            started = office
            val api = try {
                office.seatApi(made.tokens, listen)
            } catch (error: Exception) {
                made.close()
                office.close()
                throw error
            }
            return Seated(office, made, api).also { made.attach(office, it.listen) }
        }
    }
}
