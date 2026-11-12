---
name: qa-verify
description: QA's playbook for verifying a named list item on the live target, deploying a stage's pin and smoke-testing it, rolling back a failed roll, and driving a page in a scripted browser. Use on every qa-request and deploy-request.
---

# QA playbook

A request names a stage, a task and a DONE item — what to verify. Read the task and the DONE line in the stage list
first; how you verify is yours. `just` recipes first: where the repository or olymp has one for the job, call it.

## Verify a candidate (`qa-request`)
1. `kp-qa up <stage> <image>` — a `qa-<stage>` namespace, labelled with the stage and a time-to-live, with the
   candidate's pods. Wait for readiness; a pod that never becomes ready is a `fail` with the events as the pointer.
2. Run the check the list names, as the identity it names: `just as <identity> <recipe>` (or
   `kp-cred run <identity>@<target> -- <command>`). Never obtain a token another way.
3. Save the fixture exactly where the list says; note the command and the counts.
4. `kp-qa down <stage>` — always, pass or fail. Confirm the namespace is gone.
5. `qa-report pass|fail` with pointers: the fixture path, the command, the counts, what you saw on a fail.

## Deploy a pin (`deploy-request`)
1. Ask the daemon for the `roll` — the GitOps change for this stage's pin and nothing else. Note the previous pin.
2. Wait for the rollout to complete (`just verify-deploy` or the profile's recipe). Do not poll in a loop.
3. Run the stage's smoke. Pass → `deploy-done` with the pointers.
4. Fail → ask the daemon to revert the roll to the previous pin, confirm the previous state serves, then
   `deploy-failed` with what you saw. You never attempt a fix.

## A page (`browse`)
Write the scenario as a Playwright script — headed, your own profile, signed in through
`kp-cred browser <identity>@<target>`. Keep the trace and screenshots in scratch and report their paths. Only when a
script cannot reach the thing: computer use, and where this office's class is B, `computer-use-session` first.

## Always
One job at a time. Clean up before you answer. Credentials never appear in a command you quote, a fixture or a
message. The demo cluster is not yours. `kp-qa janitor` drops any `qa-*` namespace past its time-to-live.

(`kp-qa`, `kp-cred` and `just as` are Keporkak's helpers — rulebook 80 SE-20; they are built in the daemon's plan.)
