package kp.daemon.hero

/**
 * The restart (S1.6 T6): a walk's daemon killed after each line of its log and started again on what was written, the
 * walk then played to its end. [linesOfTheWalk] lines, [cuts] restarts; [endingElsewhere] names each cut whose end
 * differs from the walk's uninterrupted end.
 */
class Restarts private constructor(val linesOfTheWalk: Int, val cuts: Int, val endingElsewhere: List<String>) {
    companion object {
        fun afterEveryLineOf(walk: String): Restarts = TODO("S1.6 T6: the restart, after every line of $walk's log")
    }
}
