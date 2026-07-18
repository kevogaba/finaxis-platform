---
name: claude-codex-orchestrator
description: "Orchestrate implementation between Claude subagents and Codex using task-aware model routing, review, and verification"
---

# Claude Codex Orchestrator

Claude Code sessions only. Codex and other harnesses must skip this skill and never self-delegate. Further constraint only use this skill when main model of Opus or Fable class otherwise skip.

Claude Opus or Fable is the orchestrator. It owns judgment, design, task decomposition, specification, review, verification, and final delivery.

Use cheaper models for bounded work:

* **Haiku:** file discovery, inventories, simple summaries, convention checks
* **Sonnet:** repository analysis, implementation planning, test-failure analysis, preliminary review
* **Codex:** implementation, refactors, fixes, tests, migrations, CI, tooling, and bulk exploration

Rationale: GPT and Claude lower models usually the better, faster and cheaper models at writing/implementing code; Claude Sonnet or Fable wins at ergonomics — judgment, design, spec-writing, review, orchestration. So Codex types, Claude thinks, verifies and validates.

The primary Claude model always performs final review.

## Routing

Delegate to Codex when the request is already a clear work order:

* implementation from a frozen specification
* refactors and mechanical migrations
* reproducible bug fixes
* test writing and coverage work
* CI, dependency, script, and tooling changes
* repository exploration where reading greatly exceeds the final answer

Keep in Claude:

* architecture, API, domain, security, tenancy, and UX decisions
* ambiguous tasks where writing the specification is the main work
* tiny obvious edits, usually under 20 lines
* secrets, 1Password, MCP-only tools, releases, pushes, and remote mutations
* destructive or irreversible operations
* final review and verification

For mixed tasks:

1. Claude resolves ambiguity and freezes the design.
2. Codex implements.
3. Claude reviews and verifies.
4. Claude designs first, freezes spec, delegates build-out.

Heuristic: prompt reads as a work order → delegate; writing it forces decisions → design, Claude.

Use `$maintainer-orchestrator` for portfolio or multi-repository work.

## Codex Model Selection

Classify each delegated task independently.

### Trivial — `gpt-5.4`

Use for low-risk, obvious, localized work:

* one or a few files
* established repository pattern
* simple configuration, test, rename, or documentation change
* easy verification and rollback

Do not delegate when doing the change directly would be cheaper.

### Medium — `gpt-5.5`, high reasoning

Default for substantive implementation:

* several related files
* moderate debugging or repository exploration
* bounded refactors
* integration with existing abstractions
* meaningful test work
* one module or subsystem

### Heavy — `gpt-5.6-terra`, high reasoning

Use when correctness requires deep reasoning:

* concurrency, retries, ordering, idempotency, or eventual consistency
* security, authorization, tenancy, or data-integrity changes
* complex migrations
* broad architectural or cross-module refactors
* difficult root-cause analysis
* large context with interacting constraints

Large line count alone does not make a task heavy. Repetitive work with clear rules may remain medium.

## Classification Heuristics

Consider:

* ambiguity
* scope
* architectural impact
* security and data risk
* concurrency or distributed-system behavior
* verification difficulty
* reversibility
* available repository patterns
* prior failed attempts

Start with the cheapest model likely to succeed.

Escalate when the model misunderstands architecture, violates constraints, discovers wider scope, or repeatedly treats symptoms instead of causes:

```text
gpt-5.4 → gpt-5.5 high → gpt-5.6-terra high
```

Prefer splitting a heavy task into:

1. one difficult investigation or foundational change
2. several medium or trivial follow-up tasks

## Invoke

Always use a temporary prompt file.

### Trivial

```bash
P=$(mktemp)
cat >"$P" <<'EOF'
<goal, repo, paths, constraints, non-goals, verification, output shape>
EOF

command codex exec --yolo \
  -C <repo> \
  -m gpt-5.4 \
  -o /tmp/codex-last.md \
  - <"$P" 2>/dev/null
```

### Medium

```bash
P=$(mktemp)
cat >"$P" <<'EOF'
<goal, repo, paths, constraints, non-goals, verification, output shape>
EOF

command codex exec --yolo \
  -C <repo> \
  -m gpt-5.5 \
  -c model_reasoning_effort="high" \
  -o /tmp/codex-last.md \
  - <"$P" 2>/dev/null
```

### Heavy

```bash
P=$(mktemp)
cat >"$P" <<'EOF'
<goal, repo, paths, context, invariants, constraints, non-goals, risks,
verification, output shape>
EOF

command codex exec --yolo \
  -C <repo> \
  -m gpt-5.6-terra \
  -c model_reasoning_effort="high" \
  -o /tmp/codex-last.md \
  - <"$P" 2>/dev/null
```

Pin the model and reasoning effort explicitly. Pin fast mode too when the installed CLI exposes a supported setting.

Additional rules:

* use `command codex` to bypass shell wrappers
* if unavailable on `PATH`, use `fnm exec --using default -- codex`
* suppress stderr unless debugging the invocation
* read the `-o` file; do not ingest the JSONL stream
* use unique output files for parallel runs
* parallelize only independent, non-overlapping tasks
* add `--skip-git-repo-check` outside a Git repository
* keep prompts tightly scoped

## Follow-Ups

Use `resume` for bounded corrections:

```bash
P2=$(mktemp)
cat >"$P2" <<'EOF'
<review findings, required corrections, verification command>
EOF

(
  cd <repo> &&
  command codex exec resume --last \
    --dangerously-bypass-approvals-and-sandbox \
    -o /tmp/codex-last.md \
    - <"$P2" 2>/dev/null
)
```

Start a fresh run when:

* the original specification was wrong
* scope changed materially
* a higher model tier is required
* retained context is anchoring the model to a bad approach
* the task moved to another repository

After two failed correction rounds, stop delegating, reassess the task, and either implement directly or rewrite the specification from first principles.

## Prompt Contract

Codex has no Claude session context. Every prompt must include:

* concrete goal
* repository and relevant paths
* required architectural context
* frozen decisions
* invariants and constraints
* protected areas
* non-goals
* exact verification commands
* required output format

Require Codex to report:

* approach
* files changed
* tests and checks run
* exact results
* assumptions
* blockers or unresolved issues

For risky work, also include migration ordering, compatibility, rollback, idempotency, failure handling, and observability expectations. Spec quality decides the success.

## Token-Efficient Delegation

Use only the stages that add value:

1. Haiku locates files or conventions.
2. Sonnet analyzes the subsystem or prepares a plan.
3. Opus or Fable freezes decisions.
4. Codex implements.
5. Sonnet may perform a bounded preliminary review.
6. Opus or Fable performs final review and verification.

Do not send the same large context to several models. Avoid having multiple agents independently solve the same task.

## Verify (Claude Always)

Claude always inspects the actual changes; judge like a contributor PR:

```bash
git status -sb
git diff --stat
git diff
```

Verify:

* requested behavior
* architecture and module boundaries
* security, tenancy, transaction, and data invariants
* unrelated changes
* tests and static analysis
* migrations and generated files
* configuration and documentation
* absence of committed secrets

Codex summaries, claims and reported tests are evidence and advisory, not proof; run focused tests yourself or demand proof output.

For large changes, review in this order:

1. migrations and schemas
2. public contracts
3. security and authorization
4. domain and transaction logic
5. integrations
6. tests
7. configuration
8. documentation

Run `$autoreview` before shipping when required.

## Safety

Codex may edit local files and run local commands inside the scoped repository.

Codex must not independently:

* push or merge
* create or modify pull requests
* publish packages or releases
* access or rotate secrets
* modify production systems
* run destructive database operations
* alter remote state

`--yolo` relaxes the local sandbox only. It does not expand task scope or authorize remote operations.

## Economics

Use the least expensive model that can complete the task reliably:

* Haiku for discovery
* Sonnet for bounded analysis
* `gpt-5.4` for trivial implementation
* `gpt-5.5` high for normal substantive work
* `gpt-5.6-terra` high for genuinely difficult work
* Opus or Fable for judgment and final accountability

Avoid unnecessary delegation, duplicated context, repeated fresh runs, expensive models for mechanical work, and trusting summaries without reviewing the diff.
