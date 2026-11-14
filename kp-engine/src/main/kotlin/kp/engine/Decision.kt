package kp.engine

/** The construction boundary only. Judgement, reaction and folding belong to S1.2. */
class Decision private constructor() {
    internal companion object {
        fun create(): Decision = Decision()
    }
}
