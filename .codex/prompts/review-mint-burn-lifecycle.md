# Review a mint/burn lifecycle change

Review the proposed change against the lifecycle and failure semantics in `docs/DESIGN.md`.

Verify that:

- commands and externally visible effects are idempotent;
- workflow state is durable before irreversible work;
- retries cannot duplicate mint, burn, ledger posting, or settlement effects;
- ambiguous submissions become explicit states and are reconciled before retry;
- blockchain, legal, customer-visible, and accounting finality remain distinct;
- signer authorization is narrow, auditable, and outside the domain model;
- exact amounts, asset identities, and rounding rules cross boundaries without loss.

Return findings by severity with precise file references, followed by residual risks and the smallest safe verification set.
