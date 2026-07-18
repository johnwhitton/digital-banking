# Produce a verification handoff

Re-run the applicable checks immediately before reporting completion. Do not rely on earlier output.

Include:

- branch, commit SHA, remote, and push status;
- changed structure and the boundaries it establishes;
- exact commands run with pass/fail results;
- PDF filenames and SHA-256 checksums when references change;
- Salus-derived asset dispositions when repository tooling changes;
- decisions made, blockers, residual risks, and the next bounded scope.
- confirmation that the owning plan records its final status and was moved to `docs/plans/completed/` before the action commit, or the exact blocker/restart condition and blocked-plan path.

Do not claim a test, hook, build, commit, or push succeeded without current command output.
