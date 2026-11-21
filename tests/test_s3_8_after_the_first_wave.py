"""S3.8 — after the first wave's reviews: T1's specs on the check that the daemon decides nothing and on the default spec
timeout (Bora reads these), and T6's on a `just spec` its timeout ended.

Seam: the recipes and the check themselves, run on a throwaway copy of the checkout under build/, as S2.8's and S3.0's
are. The check is `kp-rules`' `DecidesNothingSpec`, run through `just spec`; it reads the main sources of kp-driver,
kp-daemon, kp-seat-api, kp-engine and kp-ops, the rules and the seat's permissions data, and the allow-list
`build-support/decides-nothing.yaml`.
"""
import pathlib
import sys
import time
import unittest

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import copy_of_the_checkout, end_group, findings, just, plant, run_of, start_just  # noqa: E402

ALLOW_LIST = "build-support/decides-nothing.yaml"

# Review-026's follow-ups (Collite/keporkak#53), each form planted in kp-daemon and in kp-ops: the timed waits the check
# did not name — a condition's two, a selector's, a socket's — and an observation equal to a value of the rules' caps or
# engagement, written as `==`, as `!=` and as a `when` over a difference.
FORMS = {
    "timer": [
        "fun graceNanos(lock: java.util.concurrent.locks.Lock) = lock.newCondition().awaitNanos(2_000_000_000)",
        "fun graceUntil(condition: java.util.concurrent.locks.Condition, until: java.util.Date) = condition.awaitUntil(until)",
        "fun graceSelect(selector: java.nio.channels.Selector, millis: Long) = selector.select(millis)",
        "fun graceSocket(socket: java.net.Socket, millis: Int) { socket.soTimeout = millis }",
    ],
    "comparison": [
        "fun atTheCap(blocks: Long, cap: Long) = blocks == cap",
        "fun belowTheGate(blocks: Long, rules: RuleSet) = blocks != rules.engagement.caps[\"stop_gate_blocks\"]",
        "fun atTheGate(used: Long, stopGateBlocks: Long) = when (used.minus(stopGateBlocks)) { 0L -> true; else -> false }",
    ],
}
MODULES = ("kp-daemon", "kp-ops")

# A spec that never ends, in the module that got its ProjectConfig in S3.0 and runs fastest: kp-log.
NEVER_ENDS = ("package kp.planted\n\nimport io.kotest.core.spec.style.StringSpec\n\n"
              "class NeverEndsSpec : StringSpec({\n"
              "    \"a spec that never ends, suspended\" { kotlinx.coroutines.awaitCancellation() }\n"
              "})\n")


class AfterTheFirstWave(unittest.TestCase):
    def test_the_check_lists_each_timed_wait_of_issue_53_and_an_observation_equal_to_a_value_of_the_rules_caps_or_engagement_each_planted_in_kp_daemon_and_kp_ops(self):
        copy = copy_of_the_checkout(self)
        planted = {}
        for module in MODULES:
            for shape, forms in FORMS.items():
                path, numbers = plant(copy, module, "Planted" + shape.capitalize(), forms)
                planted.update({(module, shape, form): (path, number) for form, number in zip(forms, numbers)})

        red = just(copy, "spec", "DecidesNothingSpec")
        found = findings(red.stdout)
        reported = {(path, int(line), shape) for path, line, shape, *_ in found}

        self.assertNotEqual(red.returncode, 0, red.stdout[-3000:])
        self.assertEqual([key for key, (path, line) in planted.items() if (path, line, key[1]) not in reported], [], red.stdout[-6000:])

        # The same lines, each with an entry and a reason: green.
        listed = yaml.safe_load((copy / ALLOW_LIST).read_text())
        for path, _, shape, inside, code, line in {f for f in found if "/planted/" in f[0]}:
            listed["exceptions"].setdefault(path, []).append({"shape": shape, "in": inside, "code": code, "line": line, "reason": "planted by the spec, listed"})
        (copy / ALLOW_LIST).write_text(yaml.safe_dump(listed, allow_unicode=True, sort_keys=False, width=200))
        green = just(copy, "spec", "DecidesNothingSpec")

        self.assertEqual(green.returncode, 0, green.stdout[-6000:])

    def test_the_default_spec_timeout_ends_a_spec_that_never_ends_with_no_KP_SPEC_TIMEOUT_set_in_a_module_that_got_its_ProjectConfig_in_S3_0(self):
        copy = copy_of_the_checkout(self)
        spec = copy / "kp-log/src/test/kotlin/kp/planted/NeverEndsSpec.kt"
        spec.parent.mkdir(parents=True)
        spec.write_text(NEVER_ENDS)

        run = just(copy, "spec", "kp-log:NeverEndsSpec", environment={"KP_SPEC_TIMEOUT": None}, timeout=900)

        self.assertEqual(run.returncode, 1, run.stdout[-4000:])
        self.assertRegex(run.stdout, r"kp-log · NeverEndsSpec — a spec that never ends, suspended — failed\n\s+\S*TestTimeoutException: Test '[^']+' did not complete within 5m\b")


class AJustSpecItsTimeoutEnded(unittest.TestCase):
    """T6 (a slice below T1's specs): a `just spec` that its timeout ended — S3.0's ran past the test's 1800 s and left its
    Gradle run going — leaves no process of its run behind. The run is ended as `just()` ends one past its timeout."""

    def test_after_a_just_spec_that_its_timeout_ended_no_process_of_its_run_is_left(self):
        copy = copy_of_the_checkout(self)
        began = copy / "build/a-spec-began"
        # A spec its timeout cannot end, as kp-facts' was before it had a ProjectConfig: it keeps to the coroutine's own
        # thread and blocks it, so the timeout's cancellation is never seen.
        spec = copy / "kp-log/src/test/kotlin/kp/planted/DeafSpec.kt"
        spec.parent.mkdir(parents=True)
        spec.write_text(
            "package kp.planted\n\nimport io.kotest.core.spec.style.StringSpec\nimport java.io.File\n\n"
            "class DeafSpec : StringSpec({\n"
            "    blockingTest = false\n"
            f"    \"a spec deaf to its timeout\" {{ File(\"{began}\").writeText(\"began\"); while (true) Thread.sleep(60_000) }}\n"
            "})\n")
        run = start_just(copy, "spec", "kp-log:DeafSpec", environment={"KP_SPEC_TIMEOUT": "2s"})
        waited = time.monotonic()
        while not began.exists() and run.poll() is None:
            self.assertLess(time.monotonic() - waited, 600, "the spec never began")
            time.sleep(0.5)
        running = run_of(copy)

        output = end_group(run)
        time.sleep(2)  # anything of the run still at work would show now

        self.assertTrue(began.exists(), output[-4000:])
        self.assertGreater(len(running), 2, running)  # just, spec.py, the Gradle client and the spec's test worker were at work
        self.assertEqual(run_of(copy), [], output[-4000:])


if __name__ == "__main__":
    unittest.main()
