package kp.mailroom

/**
 * S2.4 — a wake is an operation of its own (contracts.md §4.4, Amendment 22): a message delivered to an idle seat has a
 * `seat.wake` among the operations it caused. A spec about another operation of that message reads its lines without it.
 */
fun List<String>.withoutWakes(): List<String> = filterNot { it.startsWith("seat.wake ") }
