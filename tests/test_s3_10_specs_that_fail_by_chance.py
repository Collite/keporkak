"""KP/P3/S3.10 — the specs that fail by chance (D-106): T1's first sentence (Bora reads this).

Seam: `just many`, which runs one spec many times over — alone, then in its module's whole run — with a second build busy
beside each run, on a throwaway copy of the working tree, a patch applied to the copy when one is given. The three specs:
`TheSeatsSpec`'s first case and `TheThinThreadSpec`'s first case, in kp-daemon, and S3.0's never-ending spec — the
Python suite's, which plants a spec that never ends in kp-log and runs it in every module's test task. Each is run
KP_MANY times each way, and once with a patch that breaks what it holds: `tests/fixtures/s3_10/`. It runs on demand —
`KP_MANY=<n> build/python/bin/python -m unittest test_s3_10_specs_that_fail_by_chance` in tests/ — and the hand-off
gives a run with its count: one pass of the three, alone, whole and broken, takes about forty minutes, and CI's job is
bounded at twenty. `just check` runs the recipe's own refusals, and the slices of T5 and T6 that follow.
"""
import os
import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import ROOT, TEST_INPUTS, findings, gradle, just, keep, plant, shared_copy  # noqa: E402
from test_s3_6_walker import ORDERS_APART, copy_of_the_rules, failures, named, walker  # noqa: E402

COUNT = int(os.environ.get("KP_MANY", "0"))
FIXTURES = "tests/fixtures/s3_10"

# Each of the three, and the patch that breaks what it holds — in the daemon, or in the specs' timeout.
THE_THREE = {
    "kp-daemon:TheSeatsSpec/a message to an idle seat is its next prompt*": f"{FIXTURES}/the-prompt-without-its-message.patch",
    "kp-daemon:TheThinThreadSpec/a step of a real seat is waited for*": f"{FIXTURES}/a-refusal-without-its-requirement.patch",
    "python:test_s3_0_retro_checks.TheChecksP2sRetroAskedFor.test_a_spec_that_never_ends_fails_by_the_default_timeout_in_every_module_that_has_specs":
        f"{FIXTURES}/kp-logs-timeout-not-the-builds.patch",
}


class TheSpecsThatFailedByChance(unittest.TestCase):
    @unittest.skipUnless(COUNT, "on demand: KP_MANY=<n> runs each of the three n times alone and n times in its module's whole run")
    def test_a_spec_that_reads_what_a_seat_or_the_daemon_wrote_waits_for_that_record_by_the_log_never_for_a_time_the_three_pass_every_run_alone_in_their_modules_whole_run_and_with_a_second_build_busy_and_still_fail_when_what_they_hold_is_broken(self):
        for spec, broken in THE_THREE.items():
            with self.subTest(spec):
                many = just(ROOT, "many", spec, str(COUNT), timeout=7200)
                red = just(ROOT, "many", spec, "1", broken, timeout=3600)

                self.assertEqual(many.returncode, 0, many.stdout[-6000:])
                self.assertIn(f"— alone {COUNT} of {COUNT} passed · in its module's whole run {COUNT} of {COUNT} passed · a second build busy beside each run",
                              many.stdout)
                self.assertEqual(red.returncode, 1, red.stdout[-6000:])
                self.assertRegex(red.stdout, re.compile(r"— alone 0 of 1 passed · in its module's whole run 0 of 1 passed"))

    def test_the_recipe_runs_nothing_for_a_spec_it_cannot_name_or_a_patch_that_does_not_apply_and_says_so(self):
        nothing = just(ROOT, "many", "kp-daemon:NoSuchSpecAnywhere", "1")
        unknown = just(ROOT, "many", "python:test_no_such_module.Tests.test_none", "1")
        unapplied = just(ROOT, "many", "kp-model:SkeletonSpec", "1", f"{FIXTURES}/../d85-rule-edit.patch")

        self.assertEqual((nothing.returncode, unknown.returncode, unapplied.returncode), (3, 3, 2), nothing.stdout + unknown.stdout + unapplied.stdout)
        self.assertIn("names no spec, or more than one module's — nothing was run", nothing.stdout)
        self.assertIn("the patch does not apply to the working tree — nothing was run", unapplied.stdout)


class TheHeroAndTheWalker(unittest.TestCase):
    """T5's slices (Collite/keporkak#56), for the next developer."""

    def test_the_walk_of_d85s_case_itself_fails_on_the_rules_as_they_were_before_d85_naming_two_orders(self):
        rules = copy_of_the_rules(self, edit="d85-taken-out")

        lines = walker(rules, rules / "rules/walks/seat-ends-by-operation.yaml")

        self.assertEqual(len(failures(lines)), 1, "\n".join(lines[-20:]))
        self.assertRegex(failures(lines)[0], ORDERS_APART)
        (first, first_end), (other, other_end) = named(failures(lines)[0])
        self.assertNotEqual(first, other)
        self.assertNotEqual(first_end, other_end)
        self.assertEqual(sorted(first), sorted(other))

    def test_the_hero_task_names_the_walker_among_its_inputs_so_a_change_to_it_alone_runs_the_task_again(self):
        copy = shared_copy()
        script = copy / "build/test-inputs.gradle"
        script.write_text(TEST_INPUTS)

        listed = gradle(copy, "--quiet", "--init-script", str(script), "kpTestInputs")

        self.assertEqual(listed.returncode, 0, listed.stdout[-4000:])
        self.assertIn("INPUT :kp-daemon:hero scripts/check-rules.py", listed.stdout.splitlines())



# Review-029's follow-ups (Collite/keporkak#59) and S3.8's Found line, each planted in kp-daemon and in kp-ops: S2.4's
# grace written as an HTTP request's timeout, the timed waits the check did not name, and a cap reached through a lambda's
# parameter or a membership test.
TIMED = [
    "fun graceOfARequest(uri: java.net.URI, grace: java.time.Duration) = java.net.http.HttpRequest.newBuilder(uri).timeout(grace).build()",
    "fun graceOfAConnect(socket: java.net.Socket, address: java.net.SocketAddress, millis: Int) = socket.connect(address, millis)",
    "fun graceOfAnExchange(exchanger: java.util.concurrent.Exchanger<String>, n: Long, unit: java.util.concurrent.TimeUnit) = exchanger.exchange(\"v\", n, unit)",
    "fun graceOfAll(pool: java.util.concurrent.ExecutorService, tasks: List<java.util.concurrent.Callable<Int>>, n: Long, unit: java.util.concurrent.TimeUnit) = pool.invokeAll(tasks, n, unit)",
    "fun graceOfAny(pool: java.util.concurrent.ExecutorService, tasks: List<java.util.concurrent.Callable<Int>>, n: Long, unit: java.util.concurrent.TimeUnit) = pool.invokeAny(tasks, n, unit)",
    "fun graceOfAPhase(phaser: java.util.concurrent.Phaser, n: Long, unit: java.util.concurrent.TimeUnit) = phaser.awaitAdvanceInterruptibly(1, n, unit)",
    "fun graceOfAFirst(deque: java.util.concurrent.LinkedBlockingDeque<String>, n: Long, unit: java.util.concurrent.TimeUnit) = deque.pollFirst(n, unit)",
    "fun graceOfAnOffer(deque: java.util.concurrent.LinkedBlockingDeque<String>, n: Long, unit: java.util.concurrent.TimeUnit) = deque.offerFirst(\"v\", n, unit)",
    "fun graceOfATransfer(queue: java.util.concurrent.LinkedTransferQueue<String>, n: Long, unit: java.util.concurrent.TimeUnit) = queue.tryTransfer(\"v\", n, unit)",
    "fun graceOfQuiescence(pool: java.util.concurrent.ForkJoinPool, n: Long, unit: java.util.concurrent.TimeUnit) = pool.awaitQuiescence(n, unit)",
]
CAPPED = [
    "fun atTheCapByLet(blocks: Long, cap: Long) = cap.let { blocks == it }",
    "fun atTheGateByLet(blocks: Long, rules: RuleSet) = rules.engagement.caps[\"stop_gate_blocks\"]?.let { it == blocks }",
    "fun atAnyCap(blocks: Long, engagement: Map<String, Long>) = engagement.values.any { it == blocks }",
    "fun atTheCapByPair(blocks: Long, cap: Long) = (blocks to cap).let { (a, b) -> a == b }",
    "fun inTheCaps(blocks: Long, cap: Long) = blocks in listOf(cap)",
    "fun containsTheCap(blocks: Long, cap: Long) = setOf(cap).contains(blocks)",
    "fun whenInTheCaps(blocks: Long, cap: Long) = when (blocks) { in setOf(cap) -> true; else -> false }",
]


class TheCheck(unittest.TestCase):
    """T6's slices (Collite/keporkak#59), for the next developer: the check that the daemon decides nothing."""

    def test_the_check_names_each_timed_wait_of_issue_59_and_sees_a_cap_through_a_lambdas_parameter_or_a_membership_test_each_planted_in_kp_daemon_and_kp_ops(self):
        copy = shared_copy()
        keep(self, copy, "build-support/decides-nothing.yaml")
        planted = {}
        for module in ("kp-daemon", "kp-ops"):
            for shape, forms in (("timer", TIMED), ("comparison", CAPPED)):
                path, numbers = plant(copy, module, "Planted" + shape.capitalize(), forms, test=self)
                planted.update({(module, shape, form): (path, number) for form, number in zip(forms, numbers)})

        red = just(copy, "spec", "DecidesNothingSpec")
        reported = {(path, int(line), shape) for path, line, shape, *_ in findings(red.stdout)}

        self.assertNotEqual(red.returncode, 0, red.stdout[-3000:])
        self.assertEqual([key for key, (path, line) in planted.items() if (path, line, key[1]) not in reported], [], red.stdout[-6000:])


if __name__ == "__main__":
    unittest.main()
