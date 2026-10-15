#!/usr/bin/env bash
# Creates the V1 backlog as GitHub issues in munl/MyCheckList.
# Needs the GitHub CLI:  brew install gh && gh auth login
# Safe to re-run: issues whose title already exists are skipped.
#
# NOTE (2026-10-15): the Items and Assignment issues below predate repeating lists.
# Items now live on a template, and ticking happens on per-day "occurrences".
# Stars and rewards were added too. docs/PROGRESS.md (Roadmap, Repeating lists,
# Stars and rewards) is the up-to-date spec. Update these issues before running.
set -euo pipefail

REPO="munl/MyCheckList"
MILESTONE="V1 API"

echo "Creating labels..."
gh label create security --repo "$REPO" --color B60205 --description "Auth, permissions, data protection" --force
gh label create api      --repo "$REPO" --color 1D76DB --description "REST endpoints"                       --force
gh label create infra    --repo "$REPO" --color 5319E7 --description "Build, CI, Docker, Kubernetes, hosting" --force
gh label create chore    --repo "$REPO" --color C5DEF5 --description "Setup and housekeeping"               --force

echo "Creating milestone..."
gh api "repos/$REPO/milestones" -f title="$MILESTONE" -f description="First usable version of the checklist API" >/dev/null 2>&1 \
  || echo "  (milestone already exists)"

EXISTING="$(gh issue list --repo "$REPO" --state all --limit 500 --json title -q '.[].title')"

issue() {
  local title="$1" labels="$2" body="$3"
  if grep -Fxq "$title" <<<"$EXISTING"; then
    echo "  skip: $title"
    return
  fi
  gh issue create --repo "$REPO" --title "$title" --label "$labels" --milestone "$MILESTONE" --body "$body" >/dev/null
  echo "  created: $title"
}

echo "Creating issues..."

issue "Authorization layer: per-checklist permission checks" "security,api" "$(cat <<'EOF'
**Do this first.** Every checklist, item and invite endpoint depends on it.

### What
A single place that decides whether the current user can perform an action on a checklist.

- `ChecklistAccessService.require(checklistId, user, minRole)` returns the membership or throws
- Role order: `VIEWER < EDITOR < OWNER`
- Non-members get **404 Not Found**, not 403, so list IDs can't be probed
- Members without enough role get **403 Forbidden**
- Archived checklists are read-only, except to unarchive
- **Super admin** bypasses the check; any write a super admin makes to a list they aren't a member of is written to `admin_audit_log`
- A `CurrentUser` argument resolver, so controllers get the `User` directly instead of the raw `Jwt`
- A global `@RestControllerAdvice` that returns RFC 7807 `ProblemDetail` errors with no stack traces or SQL in responses
- Bean Validation on all request bodies (`@NotBlank`, `@Size`, etc.)

### Acceptance criteria
- [ ] Unit tests for the role-order logic
- [ ] Integration tests: outsider gets 404, viewer gets 403 on writes, editor or owner allowed, super admin allowed and audited
- [ ] Error responses never include stack traces, SQL or internal class names
EOF
)"

issue "Checklists API: create, list, get, update, archive" "api" "$(cat <<'EOF'
Depends on: Authorization layer.

| Method | Path | Min role |
|---|---|---|
| POST | `/api/checklists` | any user (becomes OWNER) |
| GET | `/api/checklists` | your lists (members only), `?archived=true` for archived ones |
| GET | `/api/checklists/{id}` | VIEWER |
| PATCH | `/api/checklists/{id}` | EDITOR (title, description) |
| POST | `/api/checklists/{id}/archive` | OWNER |
| POST | `/api/checklists/{id}/unarchive` | OWNER |
| DELETE | `/api/checklists/{id}` | OWNER (hard delete; confirm with `?confirm=true`) |

Responses include the caller's own role and the member and item counts.

### Acceptance criteria
- [ ] Creating a list makes the creator its OWNER in the same transaction
- [ ] List and get return only lists the caller is a member of
- [ ] Each endpoint has an outsider test (404) and a too-low-role test (403)
- [ ] Title is 1–200 characters after trimming
EOF
)"

issue "Members API: list, change role, remove, leave, transfer ownership" "api" "$(cat <<'EOF'
Depends on: Authorization layer.

| Method | Path | Min role |
|---|---|---|
| GET | `/api/checklists/{id}/members` | VIEWER |
| PATCH | `/api/checklists/{id}/members/{userId}` | OWNER (EDITOR ⇄ VIEWER) |
| DELETE | `/api/checklists/{id}/members/{userId}` | OWNER |
| POST | `/api/checklists/{id}/leave` | any member except the owner |
| POST | `/api/checklists/{id}/transfer-ownership` | OWNER (target must already be a member; old owner becomes EDITOR) |

### Acceptance criteria
- [ ] The owner can't be removed or demoted directly; only transfer works
- [ ] Removing a member unassigns them from items (the DB cascade already does this; add a test)
- [ ] Member responses expose only the id, display name, photo and role. **No emails**, except to the owner
EOF
)"

issue "Invitations API: invite by email, inbox, accept/decline, revoke" "api,security" "$(cat <<'EOF'
Depends on: Authorization layer, Members API.

| Method | Path | Who |
|---|---|---|
| POST | `/api/checklists/{id}/invitations` | EDITOR+ (role: EDITOR or VIEWER; only OWNER can invite EDITORs) |
| GET | `/api/checklists/{id}/invitations` | EDITOR+ (pending invites for this list) |
| DELETE | `/api/checklists/{id}/invitations/{inviteId}` | EDITOR+ (sets status to REVOKED) |
| GET | `/api/me/invitations` | the signed-in user's inbox (matched on verified email) |
| POST | `/api/me/invitations/{inviteId}/accept` | the invitee |
| POST | `/api/me/invitations/{inviteId}/decline` | the invitee |

### Rules
- Emails are lowercased and trimmed
- **The response is identical whether or not the email has an account** (no account enumeration)
- Inviting someone who is already a member returns 409
- Accepting creates the membership and sets the invite to ACCEPTED in one transaction
- Invites past `expires_at` are treated as EXPIRED
- Only a **verified** email can see and accept invites addressed to it
- Limit: at most 50 pending invites per list

### Acceptance criteria
- [ ] Tests: accept, decline, revoke, expired, duplicate pending, already a member, wrong user tries to accept (404)
- [ ] Sending real emails is out of scope (separate issue later)
EOF
)"

issue "Items API: add, edit, delete, reorder" "api" "$(cat <<'EOF'
Depends on: Authorization layer.

| Method | Path | Min role |
|---|---|---|
| GET | `/api/checklists/{id}/items` | VIEWER |
| POST | `/api/checklists/{id}/items` | EDITOR |
| PATCH | `/api/checklists/{id}/items/{itemId}` | EDITOR (title, notes, due date; requires `version`) |
| DELETE | `/api/checklists/{id}/items/{itemId}` | EDITOR |
| POST | `/api/checklists/{id}/items/{itemId}/move` | EDITOR (`afterItemId` or `beforeItemId`) |

### Rules
- New items go to the end (`max(position) + 1000`)
- Moving uses the midpoint between neighbours; renumber the list when the gap runs out
- Editing with a stale `version` returns **409 Conflict**
- The item must belong to the checklist in the URL, otherwise 404 (stops cross-list ID tricks)
- Limit: at most 1,000 items per list; title 1–500 characters

### Acceptance criteria
- [ ] Tests for ordering, stale-version conflict and an item from another list giving 404
EOF
)"

issue "Item assignment, completion and per-user views" "api" "$(cat <<'EOF'
Depends on: Items API, Members API.

| Method | Path | Min role |
|---|---|---|
| PUT | `/api/checklists/{id}/items/{itemId}/assignees` | EDITOR (replaces the full set: `userIds: []`) |
| POST | `/api/checklists/{id}/items/{itemId}/complete` | EDITOR, or any assignee |
| POST | `/api/checklists/{id}/items/{itemId}/reopen` | EDITOR |
| GET | `/api/checklists/{id}/items?assignee=me` / `=<userId>` / `=unassigned` | VIEWER |

### Rules
- Completion uses `completeIfOpen`: the first caller wins and later callers get **409** with who completed it
- Assignees must be members (the DB enforces this; map the FK error to 400)
- Item responses include their assignees (avoid N+1 queries)

### Acceptance criteria
- [ ] Concurrency test: two users complete the same item at once and exactly one succeeds
- [ ] Filter tests for me, a specific user and unassigned
EOF
)"

issue "Admin API with audit logging" "api,security" "$(cat <<'EOF'
Depends on: Authorization layer.

- `GET /api/admin/users` (already exists), plus paging and search by email
- `GET /api/admin/checklists`: all lists, paged
- `DELETE /api/admin/checklists/{id}`, `DELETE /api/admin/users/{id}`
- `GET /api/admin/audit-log`: paged, newest first
- Every admin write records the actor, action, target table, target id and a JSON summary in `admin_audit_log`

### Acceptance criteria
- [ ] Non-admins get 403 on every `/api/admin/**` route (test each one)
- [ ] Each admin write produces exactly one audit row
EOF
)"

issue "Set up Firebase project and verify /api/me with a real token" "chore" "$(cat <<'EOF'
- [ ] Create a Firebase project and enable Authentication with the Google provider
- [ ] Put the Project ID into `FIREBASE_PROJECT_ID` (local env, `k8s/api.yaml`)
- [ ] Set `APP_SUPER_ADMIN_EMAILS` to your Google email
- [ ] From the Android app (or a small test client), get an ID token and call `GET /api/me`
- [ ] Confirm `platformRole` is `SUPER_ADMIN` for your account and `USER` for a second account
- [ ] Turn on 2-step verification for the super admin Google account
EOF
)"

issue "CI: run tests on every push and pull request" "infra" "$(cat <<'EOF'
- [ ] GitHub Actions workflow: JDK 21 and `./gradlew test` (Testcontainers works on `ubuntu-latest`)
- [ ] Cache Gradle dependencies
- [ ] Protect `main`: require CI to pass before merging
- [ ] Enable Dependabot for Gradle, Docker and GitHub Actions dependencies
EOF
)"

issue "API documentation with OpenAPI / Swagger UI" "api" "$(cat <<'EOF'
- [ ] Add springdoc-openapi (check its Spring Boot 4 compatibility)
- [ ] Swagger UI at `/swagger-ui` for local and dev only; disabled in production
- [ ] Document the bearer auth scheme so requests can be tried with a Firebase token
- [ ] Export `openapi.json` so the Android or KMP client can generate its models
EOF
)"

issue "Security hardening before going online" "security,infra" "$(cat <<'EOF'
- [ ] Real secrets for the database (never committed); Kubernetes Secrets or the host's secret store
- [ ] `sslmode=require` for the Neon connection
- [ ] HTTPS only (handled by the host or Cloudflare)
- [ ] Rate limiting per user and per IP (e.g. Bucket4j, or at the edge with Cloudflare)
- [ ] Limit request body size and paging sizes
- [ ] Make sure logs never contain tokens or full request bodies
- [ ] Return only the fields the client needs; no emails of other users except to owners
- [ ] Review the actuator exposure (health only)
EOF
)"

issue "Deploy online: Neon Postgres + container host" "infra" "$(cat <<'EOF'
Depends on: Security hardening.

- [ ] Create a Neon project and database, then point `DB_URL` at it (`?sslmode=require`)
- [ ] Choose a host: Cloudflare Containers, or k3s on Oracle Cloud's free tier for Kubernetes
- [ ] Build the image in CI and push it to GitHub Container Registry (ghcr.io)
- [ ] Set up the deploy with env vars `FIREBASE_PROJECT_ID`, `APP_SUPER_ADMIN_EMAILS`, `DB_*`
- [ ] Smoke test `/actuator/health` and `/api/me`
EOF
)"

echo "Done. View them with: gh issue list --repo $REPO"
