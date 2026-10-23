---
name: review-pr
description: Review an open PR for risks that could break behaviour or lose data, let Jian decide fix / backlog / drop / discuss for each, post one PR thread per risk, then carry out the decisions and resolve the threads. Use when asked to review a PR, check a branch for risks, or run /review-pr.
argument-hint: "[PR number]"
---

# Review PR

A separate, read-only reviewer agent finds risks in a PR. Jian decides what happens to each one, and you (the main agent) carry out those decisions.

Write everything Jian reads in **plain, simple words**. Keep it short and skip filler. Ask questions **one at a time**.

Repo: `munl/MyCheckList`. Project board: user project `1` (owner `munl`).

## 1. Find the PR

- If a number was passed (`/review-pr 14`), use that PR.
- Otherwise use the PR for the current branch: `gh pr view --json number,title,body,headRefName,baseRefName,headRefOid,url`.
- **Stop** and tell Jian if:
  - the current branch is `main` and no number was passed
  - the branch has no open PR ("Open a PR first, then run /review-pr")
- Check out the PR branch locally (`gh pr checkout <n>`) so fixes can be made later. Stop if there are uncommitted changes.

## 2. Find the ticket(s) this PR is for

1. Read issue numbers from the branch name (e.g. `12-13-transfer-ownership` → #12, #13) and from the PR body (`Closes #12`, `#13`...).
2. If there are none, list the open issues, pick the best match for the branch name and PR title, and ask Jian to confirm before going on.

One PR usually covers a ticket pair: a **tests** ticket and its **build** ticket.

## 3. Gather context for the reviewer

Collect these and pass them to the reviewer (save large outputs to the scratchpad and pass the paths):

- **The changes:** `gh pr diff <n>` and the list of changed files.
- **This PR's tickets:** the full body of each ticket from step 2.
- **Other open tickets:** `gh issue list --state open --limit 200 --json number,title,body,labels`. Open tickets only. Closed tickets may be "won't do", so they don't count as cover.
- **Past decisions:** `docs/PROGRESS.md`, especially the "Decisions made" table.
- **Existing PR threads**, from Jian, teammates and earlier runs of this skill:
  ```
  gh api graphql -f query='query($o:String!,$r:String!,$n:Int!){repository(owner:$o,name:$r){pullRequest(number:$n){
    reviewThreads(first:100){nodes{id isResolved path line comments(first:20){nodes{databaseId author{login} body}}}}
    comments(first:100){nodes{author{login} body}}}}}' -f o=munl -f r=MyCheckList -F n=<n>
  ```

## 4. Run the reviewer agent

Spawn **one** `general-purpose` agent. Pass it the context from step 3, but **not** your own reasoning or opinion of the code, so it reviews with fresh eyes. Use this prompt (fill in the blanks):

> You are reviewing PR #<n> in a Kotlin / Spring Boot backend. **Read only: do not edit, commit or post anything.** You may read any file in the repo for context.
>
> This PR is for ticket(s): <numbers + bodies>. Changes: <diff path>. Other open tickets: <path>. Past decisions: `docs/PROGRESS.md`. Existing PR threads: <path>.
>
> Find risks: things in these changes that could **break behaviour, open a security hole, or lose or corrupt data**. Look for:
> - Bugs: wrong results, crashes, edge cases (empty lists, a member acting on themselves, last owner, duplicates, concurrent requests)
> - Access control: someone can see or do something their role shouldn't allow. All checklist access must go through `ChecklistAccessService.requireAccess(...)`
> - Data loss or corruption: deletes, clean-up, migrations, constraints
> - Breaking changes: an API response or behaviour changes for existing callers
> - Missing tests for changed behaviour (only when the gap could hide a real bug)
> - Code that goes against a decision in `PROGRESS.md`
>
> **Do not report** style, naming, formatting, refactors, "could be faster", or anything that won't break functionality. When unsure whether something is real, check the code before reporting it.
>
> **Skip a risk** (list it under "Skipped" instead) when:
> - another **open** ticket clearly plans that exact work. This PR's own tickets never count as cover
> - it matches a recorded decision in `PROGRESS.md`
> - an existing PR thread already raised it, or it was dropped or backlogged on an earlier run. Only re-raise it if a later change made it worse, and say why
>
> **Severity:**
> - 🔴 high: will break behaviour, is a security hole, or loses data
> - 🟡 medium: breaks in a less common case, or a missing test that could hide a real bug
>
> Report **every** 🔴 risk. Then add 🟡 risks, worst first, until there are 6 risks in total (more than 6 is fine when the 🔴 risks alone go over). End with one line like "+N more medium risks not shown" if you left any out.
>
> Use plain, simple words. Format each risk exactly like this:
> ```
> N. 🔴 <short title>
>    Where: <file>:<line>   (or "<file> (whole file)")
>    Why it's a risk: <one sentence>
>    Scenario: <a short, concrete story: who does what, and what goes wrong>
>    Suggested fix: <first idea, not final>
>    Also affects: <other code or tickets the fix touches; leave this line out if none>
> ```
> Then:
> ```
> Skipped
> - <risk in one line> → covered by #N   (or: "decided in PROGRESS.md: <topic>", or: "already raised in PR thread")
> ```
> If there are no risks, say so in one line and still give the Skipped list.

**Before showing the result to Jian, check it:**
- Each `Where:` line exists and the code there matches the description.
- Each "covered by #N" ticket really plans that work. If you doubt a match, say so next to the skipped line.

## 5. Show the risks and get decisions

Show the reviewer's list as-is (after your check). Then ask:

> Reply with a choice for each risk, e.g. `fix 1,3 · backlog 2 · drop 4 (fine for MVP) · discuss 5`

- **fix**: change it on this branch now.
- **backlog**: create a GitHub issue for later.
- **drop**: not a real risk, or accepted. Ask for a short reason if none was given.
- **discuss**: talk through solutions together.

If some risks aren't mentioned in the reply, ask about them. Don't assume a choice.

For **discuss** items, talk through options one question at a time and give a recommendation. Each one ends as **fix** (with the agreed approach), **backlog** or **drop**.

**Don't change anything until every risk has a final decision.**

If Jian says a "skipped" match looks wrong, treat that item as a new risk and ask for a decision on it too.

## 6. Post the review to the PR

Comments go out under Jian's GitHub account, so **show the exact text and wait for OK** before posting.

Post **one review** (one notification) with **one inline comment per risk**, on the risk's line in the PR diff (`side: RIGHT`). Each comment:

```
**🔴 <title>**

**Why it's a risk:** ...
**Scenario:** ...
**Also affects:** ...   (only if present)

**Decision:** Fix: <agreed approach> | Backlog (issue to follow) | Dropped: <reason>

<sub>Review drafted with Claude Code</sub>
```

Build the request as a JSON file in the scratchpad and post it:
```
gh api repos/munl/MyCheckList/pulls/<n>/reviews --method POST --input <file>
# {"commit_id": "<headRefOid>", "event": "COMMENT", "body": "<summary + Skipped list + footer>",
#  "comments": [{"path": "...", "line": 48, "side": "RIGHT", "body": "..."}]}
```

If a risk has no single line, or its line isn't part of the diff, post it as a **file-level** comment after the review instead:
```
gh api repos/munl/MyCheckList/pulls/<n>/comments --method POST \
  -f body="..." -f commit_id=<headRefOid> -f path=<file> -f subject_type=file
```

Then fetch the review threads again (GraphQL query from step 3) and note each risk's **thread id** and **first comment id** for step 8.

## 7. Carry out the decisions

### Fix
For each fix, in turn:
1. **Test first:** write a test that fails because of the risk, run it, and see it fail. Skip this only when there's nothing to test (wording, config, docs), and say why.
2. Fix the code so the test passes.
3. Run the full suite: `./gradlew test`. Everything must pass.
4. Show Jian the change (a short summary plus the diff) and get OK.
5. Commit **one commit per fix**, with a message naming the risk.

**Don't change an existing test to fit the code** without checking with Jian first.

After all fixes:
- **Short re-review:** spawn the reviewer once more, given **only the fix commits**' diff, and ask whether the fixes added any new risk (same rules and format). Show Jian any findings and repeat step 5 for them. Don't re-review after that; Jian will re-run `/review-pr` if they want a full review.
- **Ask before pushing:** "Push N fix commits to `<branch>`?" Then `git push`.

### Backlog
1. Check the open tickets again. If one already covers the risk, don't create an issue; link that ticket instead.
2. Draft each issue: a short title, then a body with Why / Scenario / Suggested fix and a link to the PR thread. Labels: `backlog` plus the area label (`api`, `tests`, `security`...). Single issues only, not split into tests/build pairs.
3. Show all the drafts and get OK, then create them:
   ```
   gh label create backlog --description "Found in review, to do later" --color FBCA04   # only if it doesn't exist yet
   gh issue create --title "..." --body "..." --label backlog --label api
   gh project item-add 1 --owner munl --url <issue url>
   ```

### Drop
Ask, for each drop: "Record this as a decision in `PROGRESS.md`?" If yes, add a row to the "Decisions made" table (Topic | Decision | Why). Commit it with the fixes, before pushing.

## 8. Close out the threads

For every risk thread, add a **new reply** (don't edit the first comment, so the history stays) and then resolve the thread:

| Decision | Reply |
| --- | --- |
| Fix | `Fixed in <short sha>: <what changed, one line>` |
| Backlog | `Moved to backlog → #<issue>` (or `Covered by #<existing ticket>`) |
| Drop | `Dropped: <reason>` (add `Recorded in PROGRESS.md` if it was) |

End each reply with `<sub>Review drafted with Claude Code</sub>`.

Show all the reply texts together and get **one OK**, then post them:
```
gh api repos/munl/MyCheckList/pulls/<n>/comments/<first comment id>/replies --method POST -f body="..."
gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -f id=<thread id>
```

Post fix replies only **after** the push, so the commit links work.

## 9. Wrap up

Tell Jian in a few lines: what was fixed (commits), backlog issues created (links), what was dropped, and that all threads are resolved. Give the PR link.
