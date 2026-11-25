package kp.daemon

import kp.daemon.seats.PoolFacts

/**
 * The four facts the daemon reports when they change (contracts.md §4.4, Amendment 45; S3.4): a stub that reports
 * nothing yet — T5 makes it.
 */
class Reports(private val office: Office, private val project: ProjectWorld?, private val pool: PoolFacts) {
    fun started() = Unit
    fun watched() = Unit
    fun moved() = Unit
    fun reloaded() = Unit
    fun vendorReported() = Unit
    fun turnEnded() = Unit
}
