# Check architecture and documentation synchronization

Compare the implementation with `AGENTS.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/adr/`, and the active execution plan.

Report only evidence-backed drift. Check module direction, plain-Java domain purity, exact-money types, durable asynchronous mint/burn transitions, signer isolation, four distinct finality concepts, ambiguous-effect handling, reconciliation ownership, and environment safety. Name the file and claim that must change; do not silently rewrite an architectural decision.
