"""S3.8 — after the first wave's reviews: T1's specs on the check that the daemon decides nothing and on the default spec
timeout (Bora reads these), and T6's on a `just spec` its timeout ended.

Seam: the recipes and the check themselves, run on the throwaway copy of the checkout under build/ that the specs of
S2.8, S3.0 and S3.8 share — and the default timeout's on a copy of its own, its run started as the suite is collected so
that its five minutes pass while the rest of the suite runs (S3.8 T6). The check is `kp-rules`' `DecidesNothingSpec`,
run through `just spec`; it reads the main sources of kp-driver, kp-daemon, kp-seat-api, kp-engine and kp-ops, the rules
and the seat's permissions data, and the allow-list `build-support/decides-nothing.yaml`.
"""
import atexit
import os
import pathlib
import shutil
import subprocess
import sys
import time
import unittest

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import end_group, findings, just, keep, new_copy, plant, run_of, shared_copy, start_just  # noqa: E402

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


class DefaultTimeoutRun:
    """The run of `just spec kp-log:NeverEndsSpec` with no KP_SPEC_TIMEOUT, on a copy of its own: started once, as the suite
    is collected ([load_tests]), and read by the spec that holds the default; ended and removed when the suite ends."""
    started = None

    @classmethod
    def start(cls):
        if cls.started is not None:
            return
        copy = new_copy("s3.8-default-timeout-")
        spec = copy / "kp-log/src/test/kotlin/kp/planted/NeverEndsSpec.kt"
        spec.parent.mkdir(parents=True)
        spec.write_text(NEVER_ENDS)
        output = (copy / "build/default-timeout.log").open("w+")
        run = subprocess.Popen([shutil.which("just") or "just", "--justfile", str(copy / "justfile"), "--working-directory", str(copy), "spec", "kp-log:NeverEndsSpec"],
                               cwd=copy, env={name: value for name, value in os.environ.items() if name != "KP_SPEC_TIMEOUT"},
                               stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        cls.started = (copy, run, output, time.monotonic())
        atexit.register(cls.close)

    @classmethod
    def result(cls, timeout):
        """What the run printed and its exit, once it has ended — at most [timeout] seconds after it began."""
        cls.start()
        copy, run, output, began = cls.started
        try:
            run.wait(timeout=max(1, timeout - (time.monotonic() - began)))
        except subprocess.TimeoutExpired:
            end_group(run)
        output.seek(0)
        return run.returncode, output.read()

    @classmethod
    def close(cls):
        copy, run, output, _ = cls.started
        if run.poll() is None:
            end_group(run)
        output.close()
        shutil.rmtree(copy, ignore_errors=True)


def load_tests(loader, tests, pattern):
    """unittest's hook as the suite is collected: the default timeout's five-minute run starts here (S3.8 T6)."""
    DefaultTimeoutRun.start()
    return tests


class AfterTheFirstWave(unittest.TestCase):
    def test_the_check_lists_each_timed_wait_of_issue_53_and_an_observation_equal_to_a_value_of_the_rules_caps_or_engagement_each_planted_in_kp_daemon_and_kp_ops(self):
        copy = shared_copy()
        keep(self, copy, ALLOW_LIST)
        planted = {}
        for module in MODULES:
            for shape, forms in FORMS.items():
                path, numbers = plant(copy, module, "Planted" + shape.capitalize(), forms, test=self)
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
        # Planted in kp-log of a copy of its own and run with no KP_SPEC_TIMEOUT: `just spec kp-log:NeverEndsSpec`,
        # started as the suite was collected ([DefaultTimeoutRun]).
        code, output = DefaultTimeoutRun.result(timeout=900)

        self.assertEqual(code, 1, output[-4000:])
        self.assertRegex(output, r"kp-log · NeverEndsSpec — a spec that never ends, suspended — failed\n\s+\S*TestTimeoutException: Test '[^']+' did not complete within 5m\b")


class AJustSpecItsTimeoutEnded(unittest.TestCase):
    """T6 (a slice below T1's specs): a `just spec` that its timeout ended leaves no process of its run behind. S3.0's ran
    past the test's 1800 s, and `subprocess.run`'s timeout killed `just` alone, outright: its Gradle run went on. Here it
    is ended the same way — `just` killed, nothing else told — and `spec.py`, whose `just` is gone, stops its run."""

    def test_after_a_just_spec_that_its_timeout_ended_no_process_of_its_run_is_left(self):
        copy = shared_copy()
        began = copy / "build/a-spec-began"
        keep(self, copy, "build/a-spec-began", "kp-log/src/test/kotlin/kp/planted")
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
        self.addCleanup(end_group, run)
        waited = time.monotonic()
        while not began.exists() and run.poll() is None:
            self.assertLess(time.monotonic() - waited, 600, "the spec never began")
            time.sleep(0.5)
        running = run_of(copy)

        run.kill()  # as `subprocess.run` ends a run past its timeout: `just` alone, outright
        run.wait()
        until = time.monotonic() + 150
        while run_of(copy) and time.monotonic() < until:
            time.sleep(2)

        self.assertTrue(began.exists())
        self.assertGreater(len(running), 2, running)  # just, spec.py, the Gradle client and the spec's test worker were at work
        self.assertEqual(run_of(copy), [])


if __name__ == "__main__":
    unittest.main()
