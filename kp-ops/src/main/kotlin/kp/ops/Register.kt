package kp.ops

import java.nio.file.Path

/**
 * `register.rebuild` (contracts.md §7, Amendment 36): `scripts/build-register.py` of the keporkak checkout [script]
 * names, run with [python] on the project checkout, for the [efforts] — each with its folder — in `offices.yaml`'s
 * order. `ok` once `REGISTER.md` is written; `failed` with what the script said.
 */
class Register(private val script: Path, private val python: String = "python3") {
    fun rebuild(project: Path, efforts: List<Pair<String, String>>): Performed = Performed.Failed("register.rebuild is not performed yet")
}
