package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

/**
 * KP/P3/S3.11 T5 (Collite/keporkak#63, 2): `just apps prove` passes a step GitHub must refuse only on GitHub's own
 * refusal, in GitHub's words — as recorded on Collite/kp-toy#14 — and any other failure says so; its new step passes
 * only when the office's `gh` says, in its words, that it finds no login. The proof itself is live, on the toy: these
 * are the words it reads.
 */
class AppsProveSpec : FreeSpec({
    "a push to the protected default branch is GitHub's refusal only when GitHub says so; a credential, the network or a push that is no fast-forward is another failure" {
        val refused = "remote: error: GH013: Repository rule violations found for refs/heads/master.\nremote: - Cannot update this protected ref.\n ! [remote rejected] HEAD -> master (push declined due to repository rule violations)"

        AppsProve.refusedByGitHub(AppsProve.PUSH_REFUSED, 1, refused) shouldBe true
        AppsProve.refusedByGitHub(AppsProve.PUSH_REFUSED, 0, refused) shouldBe false
        AppsProve.refusedByGitHub(AppsProve.PUSH_REFUSED, 128, "fatal: Authentication failed for 'https://github.com/Collite/kp-toy.git/'") shouldBe false
        AppsProve.refusedByGitHub(AppsProve.PUSH_REFUSED, 128, "fatal: unable to access 'https://github.com/Collite/kp-toy.git/': Could not resolve host: github.com") shouldBe false
        AppsProve.refusedByGitHub(AppsProve.PUSH_REFUSED, 1, " ! [rejected]        HEAD -> master (non-fast-forward)") shouldBe false
    }

    "the coder's approval of its own pull request is GitHub's refusal only when GitHub says so; gh with no token is another failure" {
        AppsProve.refusedByGitHub(AppsProve.APPROVAL_REFUSED, 1, "failed to create review: GraphQL: Can not approve your own pull request (addPullRequestReview)") shouldBe true
        AppsProve.refusedByGitHub(AppsProve.APPROVAL_REFUSED, 1, "gh (keporkak): no GitHub token: the daemon answered 403: github.seats gives the role coder no App") shouldBe false
    }

    "the office's gh by its full path finds no login only when it says so" {
        AppsProve.noLogin(1, "You are not logged into any GitHub hosts. To log in, run: gh auth login") shouldBe true
        AppsProve.noLogin(0, "github.com\n  ✓ Logged in to github.com account someone (keyring)") shouldBe false
        AppsProve.noLogin(1, "github.com\n  X Failed to log in to github.com account someone (/home/x/.config/gh/hosts.yml)") shouldBe false
    }
})
