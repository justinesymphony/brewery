---
description: Keeps pull requests current with their base branch (merge commit, no history rewrite) and asks CI to re-run, so nobody updates branches by hand.
strict: true
on:
  push:
    branches: [master]
  schedule:
    - cron: "0 3 * * 1-5"
      timezone: "Europe/Paris"
  slash_command:
    name: update
    events: [pull_request_comment]
  workflow_dispatch:
  # No cooldown on purpose. gh-aw applies cooldown in pre_activation, before it
  # looks at the trigger, so it also silences /update and workflow_dispatch --
  # an engineer typing /update would get nothing back for 20 minutes. The
  # concurrency group below coalesces a burst of merges more precisely: the
  # newest run survives and the superseded ones are cancelled.
permissions:
  contents: read
  pull-requests: read
engine:
  id: gemini
  model: gemini-3.8-flash
concurrency:
  group: keep-prs-current
  cancel-in-progress: true
timeout-minutes: 15
max-turns: 30
max-ai-credits: 300
max-daily-ai-credits: 3000
network:
  # play.googleapis.com is Gemini CLI telemetry. It is not needed for the task,
  # but leaving it blocked fills the firewall log with denials and muddies the
  # diagnosis of real failures.
  allowed: [defaults, play.googleapis.com]
tools:
  # "safeoutputs *" is required: gh-aw exposes the safe-output tools as a CLI on
  # PATH and the agent reaches for that form first. Without it every write is
  # refused with "Tool execution denied by policy" after the agent has already
  # reasoned correctly -- an expensive way to do nothing.
  bash: ["cat candidates.json", "jq *", "safeoutputs *"]
safe-outputs:
  # Falls back to GITHUB_TOKEN so the workflow is testable before the bot PAT
  # exists. The fallback can update a same-repository branch, but it cannot
  # write to a fork head branch, and a comment it posts does not trigger other
  # workflows -- so CI will not re-run until AUTO_UPDATE_TOKEN is set.
  github-token: ${{ secrets.AUTO_UPDATE_TOKEN || secrets.GITHUB_TOKEN }}
  # No issue when a run fails. gh-aw files one per failed run by default, which
  # during a pilot fills the tracker with noise nobody triages -- the run log and
  # `gh aw audit` are where a failure actually gets diagnosed. To bring back only
  # the failures worth waking up for, replace false with a list of categories,
  # e.g. [agent_failure, max_ai_credits_exceeded].
  #
  # This also suppresses the blocked-compiler-version notification issue. That
  # check still runs and still fails the workflow; it just stops announcing
  # itself in an issue, so an upgrade demand will only be visible in the run log.
  #
  # report-failure-as-issue does not reach the two outputs below: each files its
  # own issue from its own step, so each is switched off on its own.
  report-failure-as-issue: false
  # The agent still reports a wall it cannot get past -- a refused safe output
  # (report_incomplete) or a capability the policy does not grant
  # (report_missing_tool). Those reports now land in the run summary and the
  # safe-outputs artifact instead of the issue tracker, so check `gh aw audit`
  # rather than waiting to be told. Set create-issue back to true for either one
  # to have it raise a ticket again.
  report-incomplete:
    create-issue: false
  report-missing-tool:
    create-issue: false
  threat-detection:
    engine: gemini
    # Sixth and last issue-filing path. The detection job logs its runs as an
    # issue of its own, which is how "[aw] Detection Runs" kept reappearing even
    # with every other switch off. Detection itself still runs and still blocks
    # the workflow on a finding; only the log issue is suppressed.
    report-as-issue: false
  update-pull-request:
    update-branch: true
    title: false
    body: false
    target: "*"
    max: 15
  add-comment:
    target: "*"
    max: 30
    footer: false
    replace-older-comments: true
steps:
  - name: Build the decision table
    env:
      GH_TOKEN: ${{ github.token }}
    run: |
      set -euo pipefail

      gh pr list --repo "$GITHUB_REPOSITORY" --state open --limit 200 \
        --json number,isDraft,isCrossRepository,reviewDecision,baseRefName,headRefName,maintainerCanModify,headRepositoryOwner,labels,updatedAt \
        | jq '[.[] | select(.isDraft == false)]' > /tmp/open.json

      : > /tmp/rows.jsonl
      for row in $(jq -r '.[] | @base64' /tmp/open.json); do
        get() { echo "$row" | base64 --decode | jq -r "$1"; }
        n=$(get .number); base=$(get .baseRefName)
        owner=$(get .headRepositoryOwner.login); head=$(get .headRefName)

        # How far behind its base is this head? The compare API is the only
        # reliable answer: mergeStateStatus reports BEHIND only when the base
        # branch requires branches to be up to date, which ours does not.
        behind=$(gh api "repos/$GITHUB_REPOSITORY/compare/$base...$owner:$head" \
                   --jq '.behind_by' 2>/dev/null || echo null)

        # mergeStateStatus is computed lazily and comes back UNKNOWN in bulk
        # queries. Asking for a single pull request forces the computation.
        state=UNKNOWN
        for _ in 1 2 3; do
          state=$(gh pr view "$n" --repo "$GITHUB_REPOSITORY" \
                    --json mergeStateStatus --jq .mergeStateStatus)
          [ "$state" != UNKNOWN ] && break
          sleep 2
        done

        echo "$row" | base64 --decode \
          | jq -c --argjson b "${behind:-null}" --arg s "$state" \
              '. + {behindBy: $b, mergeStateStatus: $s}' >> /tmp/rows.jsonl
      done

      jq -s '.' /tmp/rows.jsonl > "$GITHUB_WORKSPACE/candidates.json"
      echo "candidates: $(jq length "$GITHUB_WORKSPACE/candidates.json")"
      jq -r '.[] | "  #\(.number) behind=\(.behindBy) state=\(.mergeStateStatus) review=\(.reviewDecision)"' \
        "$GITHUB_WORKSPACE/candidates.json"
---

# Keep pull requests current with their base branch

You keep open pull requests up to date with their base branch so that engineers
never update branches by hand before merging. You never rewrite history: taking
in base-branch changes always means a new merge commit, created by GitHub when
you request a branch update.

This run was triggered by `${{ github.event_name }}`.

## Input

`candidates.json` holds every open non-draft pull request, already resolved.
Read it with `cat candidates.json` and filter with `jq`. Do not try to fetch
pull requests yourself — everything you need is in that file.

Fields that decide the outcome:

- `behindBy` — commits the head is behind its base. `0` means already current;
  `null` means the comparison failed, so skip that pull request silently.
- `mergeStateStatus` — `DIRTY` means conflicting. Any other value is mergeable.
- `isCrossRepository` — `true` when the head is in a fork.
- `maintainerCanModify` — only meaningful when `isCrossRepository` is `true`.
- `reviewDecision`, `labels`, `baseRefName`, `headRefName`.

## Eligibility

Skip silently, with no comment: anything whose `behindBy` is `0` or `null`, and
anything carrying the label `no-auto-update`.

A pull request is **ready** when `reviewDecision` is `APPROVED`, or when it
carries the label `auto-update`.

A pull request is **unreachable** when `isCrossRepository` is `true` and
`maintainerCanModify` is `false` — nothing can update its branch. Note that
`maintainerCanModify` is also `false` for same-repository pull requests, where
it means nothing at all; never treat those as unreachable.

## What to do

- **Triggered by `push`, `workflow_dispatch`, or `/update` with no pull request
  in context** — for each **ready** pull request whose `behindBy` is above `0`,
  whose `mergeStateStatus` is not `DIRTY`, and which is not unreachable:
  request one `update-pull-request` with `update_branch` set, then one
  `add-comment` whose body is exactly `/run`, which starts CI. Nothing else.
- **Triggered by `/update` on one pull request** — treat that pull request as
  ready even without approval or label, and consider only that one.
- **Triggered by `schedule`** — update every stale pull request, ready or not,
  but do **not** comment `/run`. Reviewers get a fresh branch and no CI is spent.
- **Ready and conflicting** (`mergeStateStatus` is `DIRTY`) — do not request a
  branch update. Post one comment:

  > **Auto-update:** `<base>` cannot be merged into `<branch>` automatically
  > because of conflicts. To resolve: `git fetch origin <base> && git merge
  > origin/<base>`, then push.

- **Ready and unreachable** — post one comment asking the author to tick
  "Allow edits by maintainers", and do nothing else.

## Reporting

For each pull request you updated, post one comment in exactly this shape; it
replaces your previous one:

> **Auto-update:** merged `<base>` into `<branch>` and asked CI to run.
> Reason: <n> commits behind, <approved | labelled auto-update>.

Never comment on a pull request you did not act on, except for the two cases
above. Never close, retitle, label, merge, or push to a pull request. Respect
the `max` limits: if more pull requests qualify than you may update, take the
most recently updated first and say in the run summary how many were left for
the next run.

Finish by stating, in the run summary, how many pull requests you updated, how
many you skipped, and the reason for each skip.

## Emitting the outputs

Issue each safe output as its **own** command. Do not chain them with `&&` or
`;` — compound commands are refused by the sandbox policy even when the
individual command is allowed, and a refusal here wastes the whole run.

Correct, one call per command:

```
safeoutputs update_pull_request '{"pull_request_number":7,"update_branch":true}'
safeoutputs add_comment '{"item_number":7,"body":"/run"}'
```

Post the `/run` comment **separately from** the explanatory comment, and send
`/run` as a comment whose body is exactly those four characters, so CI matches
it. If a safe-output call is refused twice, stop and call `report_incomplete`
with the refusal text rather than trying further variants.
