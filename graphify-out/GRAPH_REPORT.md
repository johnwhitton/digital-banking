# Graph Report - .  (2026-07-16)

## Corpus Check
- Corpus is ~42,911 words - fits in a single context window. You may not need a graph.

## Summary
- 531 nodes · 1252 edges · 35 communities (28 shown, 7 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 206 edges (avg confidence: 0.82)
- Semantic extraction used in-session Codex subagents; token usage is unavailable from the subagent interface and is not represented as zero-cost evidence.

## Integrity and Provenance Limitations
- Built from the reviewed Action Request 03 working tree atop `4adc096d`; no `built_at_commit` marker is emitted because these artifacts ship in the same commit. The 98 relative content hashes in `manifest.json` are the freshness check.
- Raw Graphify 0.8.47 diagnostics observed 163 dangling edges, 31 exact duplicate edges, and 89 undirected same-endpoint collapses before construction. The final graph has unique node IDs, 0 dangling edges, and 0 self-loops.
- 24 of 531 nodes lack `source_file`; 237 lack `source_location`, primarily shared AST types and semantic Markdown concepts. Use direct source for precise verification.
- The graph is advisory navigation output. Repository policy, architecture, ADRs, plans, source, dependency rules, and executable tests remain authoritative.

## Community Hubs (Navigation)
- [[_COMMUNITY_Operation Lifecycle Core|Operation Lifecycle Core]]
- [[_COMMUNITY_Quantity and Canonicalization|Quantity and Canonicalization]]
- [[_COMMUNITY_Application Acceptance Workflow|Application Acceptance Workflow]]
- [[_COMMUNITY_Identity and Signing Contracts|Identity and Signing Contracts]]
- [[_COMMUNITY_Attempts and Chain Ports|Attempts and Chain Ports]]
- [[_COMMUNITY_Graphify Usage and Exports|Graphify Usage and Exports]]
- [[_COMMUNITY_Repository Governance|Repository Governance]]
- [[_COMMUNITY_Commands and Policy|Commands and Policy]]
- [[_COMMUNITY_Chain Adapter Workflow|Chain Adapter Workflow]]
- [[_COMMUNITY_Architecture and Toolchains|Architecture and Toolchains]]
- [[_COMMUNITY_Graphify Build Pipeline|Graphify Build Pipeline]]
- [[_COMMUNITY_Durable Operation Design|Durable Operation Design]]
- [[_COMMUNITY_Financial Lifecycle Review|Financial Lifecycle Review]]
- [[_COMMUNITY_Engineering Skill Routing|Engineering Skill Routing]]
- [[_COMMUNITY_State Invariant Guidance|State Invariant Guidance]]
- [[_COMMUNITY_Documentation Evidence|Documentation Evidence]]
- [[_COMMUNITY_Finality and Signer Checks|Finality and Signer Checks]]
- [[_COMMUNITY_Planning Prompt Workflow|Planning Prompt Workflow]]
- [[_COMMUNITY_Graph Update Portability|Graph Update Portability]]
- [[_COMMUNITY_Control Plane Thesis|Control Plane Thesis]]
- [[_COMMUNITY_Idempotency Conflict|Idempotency Conflict]]
- [[_COMMUNITY_Signing Trust Boundary|Signing Trust Boundary]]
- [[_COMMUNITY_Health Configuration|Health Configuration]]
- [[_COMMUNITY_Graphify Provenance|Graphify Provenance]]
- [[_COMMUNITY_User Authority|User Authority]]
- [[_COMMUNITY_Application Maven Module|Application Maven Module]]
- [[_COMMUNITY_Control Plane Maven Module|Control Plane Maven Module]]
- [[_COMMUNITY_Domain Maven Module|Domain Maven Module]]
- [[_COMMUNITY_Maven Reactor Root|Maven Reactor Root]]

## God Nodes (most connected - your core abstractions)
1. `TokenOperation` - 49 edges
2. `EvidenceRef` - 35 edges
3. `OperationId` - 30 edges
4. `TokenQuantity` - 21 edges
5. `AttemptId` - 20 edges
6. `TokenOperationLifecycleTest` - 19 edges
7. `CanonicalCommand` - 18 edges
8. `AssetUnit` - 18 edges
9. `TokenOperationCommand` - 16 edges
10. `Financial and State Invariants Skill` - 15 edges

## Surprising Connections (you probably didn't know these)
- `Portable Graph Artifacts` --semantically_similar_to--> `Portable Relative-path Manifest`  [INFERRED] [semantically similar]
  docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md → .codex/skills/graphify/references/update.md
- `Plain Domain Boundary` --semantically_similar_to--> `Enforced Inner Module Boundaries`  [INFERRED] [semantically similar]
  .codex/skills/java-spring-control-plane-change/SKILL.md → docs/adr/0001-maven-reactor-and-module-boundaries.md
- `Durable API Contract` --semantically_similar_to--> `Durable Token-operation API Boundary`  [INFERRED] [semantically similar]
  .codex/skills/java-spring-control-plane-change/SKILL.md → docs/DESIGN.md
- `Repository Completion Gate` --semantically_similar_to--> `Plan Evidence States`  [INFERRED] [semantically similar]
  AGENTS.md → .codex/skills/plan-execution/SKILL.md
- `Java-owned Regulated Control Plane` --semantically_similar_to--> `Regulated Control Plane`  [INFERRED] [semantically similar]
  AGENTS.md → docs/DESIGN.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Repository Review and Handoff Templates** — prompts_check_architecture_doc_sync_architecture_documentation_synchronization, prompts_review_chain_adapter_chain_adapter_review, prompts_review_mint_burn_lifecycle_mint_burn_lifecycle_review, prompts_verification_handoff_verification_handoff [EXTRACTED 1.00]
- **Durable Financial Effect Integrity** — financial_state_invariants_skill_exact_quantity_invariant, financial_state_invariants_skill_idempotency_invariant, financial_state_invariants_skill_stable_operation_identity, financial_state_invariants_skill_attempt_identity, financial_state_invariants_skill_append_only_state_transition, financial_state_invariants_skill_ambiguous_effect_invariant, financial_state_invariants_skill_signing_authority_invariant, financial_state_invariants_skill_independent_finality_records, financial_state_invariants_skill_reconciliation_invariant [EXTRACTED 1.00]
- **Graphify Full Pipeline** — graphify_skill_file_detection, graphify_skill_structural_ast_extraction, graphify_skill_semantic_subagent_extraction, graphify_skill_semantic_extraction_cache, graphify_skill_graph_build_cluster_analysis, graphify_skill_graph_health_check, graphify_skill_community_labeling, graphify_skill_graph_outputs [EXTRACTED 1.00]
- **Durable Token-operation Assurance** — docs_design_token_operation_aggregate, docs_design_scoped_idempotency, docs_design_exact_token_quantity, docs_design_asynchronous_lifecycle, docs_design_four_finalities [EXTRACTED 1.00]
- **Native Adapter Isolation** — docs_design_chain_adapter_capability, adr_0002_evm_foundry_and_web3j_foundry_web3j_decision, adr_0003_native_solana_spl_token_native_solana_decision, agents_native_chain_boundaries [INFERRED 0.95]
- **Evidence-governed AI Workflow** — active_ai_assisted_engineering_toolchain_toolchain_plan, readme_ai_assisted_engineering, docs_implementation_ai_assisted_workflow, agents_instruction_precedence, active_ai_assisted_engineering_toolchain_authoritative_source_validation [EXTRACTED 1.00]

## Communities (35 total, 7 thin omitted)

### Community 0 - "Operation Lifecycle Core"
Cohesion: 0.09
Nodes (21): Arguments, FinalityStatus, FinalityType, Instant, List, Map, MethodSource, EvidenceRef (+13 more)

### Community 1 - "Quantity and Canonicalization"
Cohesion: 0.09
Nodes (13): AssetUnit, TokenQuantity, TokenQuantityTest, BigInteger, CommandCanonicalizer, CommandCanonicalizerTest, MintCommand, DataOutputStream (+5 more)

### Community 2 - "Application Acceptance Workflow"
Cohesion: 0.08
Nodes (16): OperationAcceptance, TokenOperationService, CountingIds, InMemoryOperationRepository, ScopedKey, StoredAcceptance, TokenOperationServiceTest, BeforeEach (+8 more)

### Community 3 - "Identity and Signing Contracts"
Cohesion: 0.07
Nodes (10): CommandDigest, IdempotencyKey, DigitalBankingApplication, Object, OperationAcceptanceContext, Override, SignerPort, SigningDecision (+2 more)

### Community 4 - "Attempts and Chain Ports"
Cohesion: 0.10
Nodes (17): AttemptId, OperationAttempt, OperationId, Optional, AttemptRepository, AttemptIdentity, ChainCapabilities, ChainPort (+9 more)

### Community 5 - "Graphify Usage and Exports"
Cohesion: 0.07
Nodes (31): Commit Hook Integration, Existing Graph Fast Path, Graphify Skill, Graphify Honesty Rules, Graphify Interpreter Guard, Persistent Knowledge Graph, Query Path and Explain, URL Add and Folder Watch (+23 more)

### Community 6 - "Repository Governance"
Cohesion: 0.11
Nodes (27): Authoritative-source Query Validation, AI-Assisted Engineering Toolchain Plan, Immutable Reference Publications Gate, Phase 2 Acceptance Evidence, Domain and Operation Lifecycle Plan, Architectural Invariants, Repository Completion Gate, Repository Instruction Precedence (+19 more)

### Community 7 - "Commands and Policy"
Cohesion: 0.13
Nodes (6): BurnCommand, ParticipantScope, TokenOperationCommand, OperationKind, PolicyApprovalPort, PolicyDecision

### Community 8 - "Chain Adapter Workflow"
Cohesion: 0.10
Nodes (21): Chain Adapter Change Agent Interface, Ambiguity Retry Rules, Business-Facing Chain Lifecycle, Chain Adapter Change Skill, EVM Adapter Semantics, Independent Observation, Independent Signer Port, Local Disposable Networks (+13 more)

### Community 9 - "Architecture and Toolchains"
Cohesion: 0.11
Nodes (20): Digital Banking Bootstrap Plan, Enforced Inner Module Boundaries, Maven Reactor and Module Boundaries, EVM Development with Foundry and Web3j, Local-only EVM Execution, Single EVM-native Toolchain, Avoid Unnecessary Custom Solana Program, Native Solana Integration with SPL Token (+12 more)

### Community 10 - "Graphify Build Pipeline"
Cohesion: 0.11
Nodes (20): Community Labeling, Graphify File Detection, Graph Build Cluster and Analysis, Graph Health Check, Graphify Outputs, Semantic Extraction Cache, Semantic Subagent Extraction, Structural AST Extraction (+12 more)

### Community 11 - "Durable Operation Design"
Cohesion: 0.17
Nodes (16): Concrete Phase 2 Decisions, Exact and Reconcilable Financial State, Durable Token-operation API Boundary, Asynchronous Operation Lifecycle, Evidence-gated Delivery, Exact Token Quantity, Four Independent Finalities, Independent Observation and Reconciliation (+8 more)

### Community 12 - "Financial Lifecycle Review"
Cohesion: 0.18
Nodes (11): Ambiguous Effect Invariant, Exact Quantity Invariant, Idempotency Invariant, Ambiguous-Effect Handling, Exact-Money Types, Durable Workflow Before Effects, Exact Boundary Amounts, Idempotent External Effects (+3 more)

### Community 13 - "Engineering Skill Routing"
Cohesion: 0.20
Nodes (10): Digital Banking Doc Sync Agent Interface, Digital Banking Engineering Agent Interface, Digital Banking Document Synchronization Skill, Document Ownership Matrix, Living Document Synchronization, Digital Banking Engineering Skill, Durable Operation Result Shape, External Response Is Evidence (+2 more)

### Community 14 - "State Invariant Guidance"
Cohesion: 0.20
Nodes (10): Financial State Invariants Agent Interface, Append-Only State Transition, Attempt Identity, Financial and State Invariants Skill, Minimum Financial Invariant Test Matrix, Reconciliation Invariant, Sensitive Data Invariant, Stable Operation Identity (+2 more)

### Community 15 - "Documentation Evidence"
Cohesion: 0.20
Nodes (10): Documentation Evidence Contract, Immutable Reference Publications, Public Case Study Boundary, Salus Read-Only Provenance, Planned Scaffolded Implemented Verified Vocabulary, Digital Banking Graphify Guardrails, Completion Handoff Contents, Fresh Verification Evidence (+2 more)

### Community 16 - "Finality and Signer Checks"
Cohesion: 0.22
Nodes (9): Independent Finality Records, Signing Authority Invariant, Architecture and Documentation Synchronization, Durable Asynchronous Mint/Burn, Environment Safety, Four Distinct Finalities, Plain-Java Domain Purity, Signer Isolation (+1 more)

### Community 17 - "Planning Prompt Workflow"
Cohesion: 0.25
Nodes (8): Bounded Test-First Slice, Architecture Claim Updates, Authority and Risk Stop Conditions, Bounded Change Plan, Bounded Execution Scope, Failing-Then-Passing Tests, Repository Authority Sources, Repository Prompt Templates

### Community 18 - "Graph Update Portability"
Cohesion: 0.50
Nodes (5): Portable Graph Artifacts, Cluster-only Refresh, Incremental Graph Update, Portable Relative-path Manifest, Replace on Re-extract

### Community 19 - "Control Plane Thesis"
Cohesion: 0.50
Nodes (4): Java-owned Regulated Control Plane, Durable Internal Business Truth, Regulated Control Plane, Architectural Thesis

### Community 21 - "Signing Trust Boundary"
Cohesion: 0.50
Nodes (4): Signer and Custody Authority Port, Non-transitive Trust Boundaries, Phase 4 Signing Boundary, Signing Authority Boundary

### Community 22 - "Health Configuration"
Cohesion: 1.00
Nodes (3): Minimal Java and Spring Foundation, Control-plane Application Configuration, Health and Readiness Only

## Knowledge Gaps
- **68 isolated node(s):** `digital-banking-application`, `digital-banking-control-plane`, `digital-banking-domain`, `io.github.johnwhitton:digital-banking-parent`, `Repository Authority Sources` (+63 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `TokenOperation` connect `Operation Lifecycle Core` to `Quantity and Canonicalization`, `Application Acceptance Workflow`, `Identity and Signing Contracts`, `Attempts and Chain Ports`, `Commands and Policy`?**
  _High betweenness centrality (0.042) - this node is a cross-community bridge._
- **Why does `Graphify Skill` connect `Graphify Usage and Exports` to `Graphify Build Pipeline`, `Documentation Evidence`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Why does `Documentation Evidence Contract` connect `Documentation Evidence` to `Engineering Skill Routing`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `EvidenceRef` (e.g. with `.finalityHistoryCannotReturnToUnassessedOrMoveBackward()` and `.followUpAttemptCannotPredateTheSubmissionPendingTransition()`) actually correct?**
  _`EvidenceRef` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `digital-banking-application`, `digital-banking-control-plane`, `digital-banking-domain` to the rest of the system?**
  _99 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Operation Lifecycle Core` be split into smaller, more focused modules?**
  _Cohesion score 0.08806655192197362 - nodes in this community are weakly interconnected._
- **Should `Quantity and Canonicalization` be split into smaller, more focused modules?**
  _Cohesion score 0.08956228956228957 - nodes in this community are weakly interconnected._
