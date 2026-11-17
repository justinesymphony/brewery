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
  cooldown: 20m
permissions:
  contents: read
  pull-requests: read
engine:
  id: gemini
  model: gemini-3-flash-preview
concurrency:
  group: keep-prs-current
  cancel-in-progress: true
timeout-minutes: 15
max-turns: 30
max-ai-credits: 300
max-daily-ai-credits: 3000
network: defaults
tools:
  bash: ["cat candidates.json", "jq *"]
safe-outputs:
  # Falls back to GITHUB_TOKEN so the workflow is testable before the bot PAT
  # exists. The fallback can update a same-repository branch, but it cannot
  # write to a fork head branch, and a comment it posts does not trigger other
  # workflows -- so CI will not re-run until AUTO_UPDATE_TOKEN is set.
  github-token: ${{ secrets.AUTO_UPDATE_TOKEN || secrets.GITHUB_TOKEN }}
  threat-detection:
    engine: gemini
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
