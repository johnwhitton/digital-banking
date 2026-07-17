# Graph Report - .  (2026-07-17)

## Corpus Check
- 130 files · ~77,069 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 923 nodes · 2395 edges · 72 communities (47 shown, 25 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 237 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `cb1f1164`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Persistence Acceptance Workflow|Persistence Acceptance Workflow]]
- [[_COMMUNITY_Operation Aggregate Transitions|Operation Aggregate Transitions]]
- [[_COMMUNITY_Acceptance Concurrency Tests|Acceptance Concurrency Tests]]
- [[_COMMUNITY_Acceptance Concurrency Tests|Acceptance Concurrency Tests]]
- [[_COMMUNITY_Phase 3A Evidence|Phase 3A Evidence]]
- [[_COMMUNITY_Operation Aggregate Transitions|Operation Aggregate Transitions]]
- [[_COMMUNITY_Application Composition|Application Composition]]
- [[_COMMUNITY_Control Plane API Security|Control Plane API Security]]
- [[_COMMUNITY_Quantity and Identifier Types|Quantity and Identifier Types]]
- [[_COMMUNITY_Digital Banking Engineering Companion, Volume II, Version 1.0.0|Digital Banking Engineering Companion, Volume II, Version 1.0.0]]
- [[_COMMUNITY_Persistence API Tests|Persistence API Tests]]
- [[_COMMUNITY_Java Standards Audit|Java Standards Audit]]
- [[_COMMUNITY_Chain Adapter Workflow|Chain Adapter Workflow]]
- [[_COMMUNITY_Acceptance Context Types|Acceptance Context Types]]
- [[_COMMUNITY_Token Operation Controller|Token Operation Controller]]
- [[_COMMUNITY_OpenAPI Contract Tests|OpenAPI Contract Tests]]
- [[_COMMUNITY_Transfer Delivery Plan|Transfer Delivery Plan]]
- [[_COMMUNITY_Participant Evidence Mapping|Participant Evidence Mapping]]
- [[_COMMUNITY_Canonical Command Model|Canonical Command Model]]
- [[_COMMUNITY_Transfer Architecture and Finality|Transfer Architecture and Finality]]
- [[_COMMUNITY_Command Canonicalization|Command Canonicalization]]
- [[_COMMUNITY_Financial Lifecycle Review|Financial Lifecycle Review]]
- [[_COMMUNITY_Engineering Skill Routing|Engineering Skill Routing]]
- [[_COMMUNITY_State Invariant Guidance|State Invariant Guidance]]
- [[_COMMUNITY_Documentation Evidence|Documentation Evidence]]
- [[_COMMUNITY_Graphify Usage and Exports|Graphify Usage and Exports]]
- [[_COMMUNITY_Finality and Signer Checks|Finality and Signer Checks]]
- [[_COMMUNITY_Graph Query Navigation|Graph Query Navigation]]
- [[_COMMUNITY_Planning Prompt Workflow|Planning Prompt Workflow]]
- [[_COMMUNITY_Graphify Exports and Labels|Graphify Exports and Labels]]
- [[_COMMUNITY_Canonical Command Model|Canonical Command Model]]
- [[_COMMUNITY_Transfer Architecture and Finality|Transfer Architecture and Finality]]
- [[_COMMUNITY_Graphify Build Pipeline|Graphify Build Pipeline]]
- [[_COMMUNITY_Semantic Extraction Contract|Semantic Extraction Contract]]
- [[_COMMUNITY_Graph Update Portability|Graph Update Portability]]
- [[_COMMUNITY_Action 05 Execution Gates|Action 05 Execution Gates]]
- [[_COMMUNITY_Java Control Plane Workflow|Java Control Plane Workflow]]
- [[_COMMUNITY_Plan Execution|Plan Execution]]
- [[_COMMUNITY_Spring Boot Entrypoint|Spring Boot Entrypoint]]
- [[_COMMUNITY_Transfer Architecture and Finality|Transfer Architecture and Finality]]
- [[_COMMUNITY_Transfer Chains and Finality|Transfer Chains and Finality]]
- [[_COMMUNITY_URL Ingestion and Watch|URL Ingestion and Watch]]
- [[_COMMUNITY_Cross Repository Graph Merge|Cross Repository Graph Merge]]
- [[_COMMUNITY_Media Transcription|Media Transcription]]
- [[_COMMUNITY_Graphify Provenance|Graphify Provenance]]
- [[_COMMUNITY_EVM Foundry and Web3j|EVM Foundry and Web3j]]
- [[_COMMUNITY_Native Solana SPL|Native Solana SPL]]
- [[_COMMUNITY_User Authority|User Authority]]
- [[_COMMUNITY_Query Source Validation|Query Source Validation]]
- [[_COMMUNITY_Bootstrap Plan|Bootstrap Plan]]
- [[_COMMUNITY_Publication Provenance|Publication Provenance]]
- [[_COMMUNITY_Health Configuration|Health Configuration]]
- [[_COMMUNITY_Phase 2 Evidence|Phase 2 Evidence]]
- [[_COMMUNITY_Lifecycle Plan Governance|Lifecycle Plan Governance]]
- [[_COMMUNITY_Phase 2 Decisions|Phase 2 Decisions]]
- [[_COMMUNITY_Local EVM Execution|Local EVM Execution]]
- [[_COMMUNITY_Sava Evaluation Gate|Sava Evaluation Gate]]
- [[_COMMUNITY_Classified Safe Failure Mapping|Classified Safe Failure Mapping]]
- [[_COMMUNITY_Durable External Effect Contracts|Durable External Effect Contracts]]
- [[_COMMUNITY_Participant Safe Response Projection|Participant Safe Response Projection]]
- [[_COMMUNITY_Durable API Contract|Durable API Contract]]
- [[_COMMUNITY_Application Maven Module|Application Maven Module]]
- [[_COMMUNITY_Control Plane Maven Module|Control Plane Maven Module]]
- [[_COMMUNITY_Domain Maven Module|Domain Maven Module]]
- [[_COMMUNITY_Persistence Maven Module|Persistence Maven Module]]
- [[_COMMUNITY_Parent Maven Reactor|Parent Maven Reactor]]

## God Nodes (most connected - your core abstractions)
1. `PostgresOperationRepository` - 45 edges
2. `TokenOperation` - 39 edges
3. `PostgresOperationRepositoryTest` - 31 edges
4. `EvidenceRef` - 30 edges
5. `Phase 3A Durable API and Persistence Plan` - 20 edges
6. `TokenOperationLifecycleTest` - 19 edges
7. `TokenOperationApiIntegrationTest` - 19 edges
8. `AttemptId` - 18 edges
9. `OperationId` - 18 edges
10. `TokenQuantity` - 16 edges

## Surprising Connections (you probably didn't know these)
- `Portable Graph Artifacts` --semantically_similar_to--> `Portable Relative-path Manifest`  [INFERRED] [semantically similar]
  docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md → .codex/skills/graphify/references/update.md
- `Pending Outbox Is Not Execution` --semantically_similar_to--> `Outbox Worker Deferred to Phase 3B`  [INFERRED] [semantically similar]
  SECURITY.md → docs/adr/0004-postgresql-jdbc-flyway-atomic-outbox.md
- `Fresh Verification Evidence` --semantically_similar_to--> `Documentation Evidence Contract`  [INFERRED] [semantically similar]
  .codex/prompts/verification-handoff.md → .codex/skills/digital-banking-doc-sync/SKILL.md
- `Bounded Autonomous Execution Policy` --references--> `Security Policy`  [EXTRACTED]
  AUTONOMOUS_EXECUTION_POLICY.md → SECURITY.md
- `AI-Assisted Engineering Toolchain Plan` --references--> `Security Policy`  [EXTRACTED]
  docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md → SECURITY.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Engineering Companion Publication and Live-Status Evidence Boundary** — reference_readme_digital_banking_engineering_companion, reference_readme_evidence_snapshot_commit, reference_readme_live_status_authorities [EXTRACTED 1.00]
- **Documentation-Only Engineering Companion Publication Slice** — active_engineering_companion_publication_authorized_changes, readme_volume_ii_publication_status, docs_implementation_volume_ii_published_status, reference_readme_publication_index [EXTRACTED 1.00]

## Communities (72 total, 25 thin omitted)

### Community 0 - "Persistence Acceptance Workflow"
Cohesion: 0.07
Nodes (40): AcceptanceRequest, InMemoryRepository, ScopedKey, StoredAcceptance, TokenOperationApplicationServiceTest, AssetUnit, AttemptId, CanonicalCommandMetadata (+32 more)

### Community 1 - "Operation Aggregate Transitions"
Cohesion: 0.06
Nodes (25): OperationAcceptance, Arguments, FinalityType, Instant, List, MethodSource, AttemptId, EvidenceRef (+17 more)

### Community 2 - "Acceptance Concurrency Tests"
Cohesion: 0.07
Nodes (17): InMemoryOperationRepository, ScopedKey, StoredAcceptance, TokenOperationServiceTest, BeforeEach, CommandCanonicalizerTest, DigitalBankingApplicationTests, HealthReadinessSmokeTests (+9 more)

### Community 3 - "Acceptance Concurrency Tests"
Cohesion: 0.08
Nodes (12): AfterAll, BeforeAll, Callable, BurnCommand, CommandCanonicalizer, MintCommand, TokenOperationCommand, CountDownLatch (+4 more)

### Community 4 - "Phase 3A Evidence"
Cohesion: 0.06
Nodes (49): AI-Assisted Engineering Toolchain Plan, Atomic Durable Acceptance Transaction, Framework-free Acceptance and Status Boundary, Dependency and Binary Boundary Evidence, Minimal Dependency Disposition, Final 302-test Verification Evidence, Deterministic Flyway Persistence Schema, Pending Git Closeout (+41 more)

### Community 5 - "Operation Aggregate Transitions"
Cohesion: 0.08
Nodes (16): SequentialIds, CountingIds, Override, AttemptIdentity, ChainCapabilities, ChainPort, InquiryResult, NativeIdentity (+8 more)

### Community 6 - "Application Composition"
Cohesion: 0.10
Nodes (20): TokenOperationService, AssetUnitCatalog, Bean, ClockPort, ApplicationConfiguration, SecurityConfiguration, DataSource, ReferenceAssetConfiguration (+12 more)

### Community 7 - "Control Plane API Security"
Cohesion: 0.13
Nodes (15): ApiExceptionHandler, UnauthenticatedPrincipalException, IdempotencyConflictException, OperationNotFoundException, UnknownAssetUnitException, UnsupportedRequestContractException, Exception, ExceptionHandler (+7 more)

### Community 8 - "Quantity and Identifier Types"
Cohesion: 0.18
Nodes (5): AssetUnit, TokenQuantity, TokenQuantityTest, BigInteger, PortContractTest

### Community 9 - "Digital Banking Engineering Companion, Volume II, Version 1.0.0"
Cohesion: 0.11
Nodes (23): Authorized Documentation and Graphify Publication Changes, Publication Validation and Closeout Gate, Companion Metadata: Volume II, Version 1.0.0, Published 2026-07-16, 40 Pages, One Final Refresh of Three Tracked Graphify Artifacts, Engineering Companion Publication Plan, Verified Publication Baseline: main at cb1f1164bb1d604dbfbaec6727ed2f899231c812, Digital Banking Implementation Plan and Current State, Live Status Governed by Current Plan, README, ADRs, Source, and Tests (+15 more)

### Community 10 - "Persistence API Tests"
Cohesion: 0.12
Nodes (13): InvalidRequestException, CapturedOutput, ParticipantResolver, TokenOperationApiFailureBoundaryTest, ExtendWith, HandlerMethodArgumentResolver, IllegalArgumentException, MethodParameter (+5 more)

### Community 11 - "Java Standards Audit"
Cohesion: 0.12
Nodes (21): Java/Spring Implementation Standards Audit Plan, Phase 3A Audit Validation Evidence, Action Request 07 Scope, Focused RED-GREEN Validation Evidence, I-01 Participant Projection Correction, I-02 Failure Classification Correction, Action Request 07 Intentional Deferrals, Phase 3A API Boundary Corrections Plan (+13 more)

### Community 12 - "Chain Adapter Workflow"
Cohesion: 0.10
Nodes (21): Chain Adapter Change Agent Interface, Ambiguity Retry Rules, Business-Facing Chain Lifecycle, Chain Adapter Change Skill, EVM Adapter Semantics, Independent Observation, Independent Signer Port, Local Disposable Networks (+13 more)

### Community 13 - "Acceptance Context Types"
Cohesion: 0.13
Nodes (3): ParticipantPrincipal, OperationAcceptanceContext, String

### Community 14 - "Token Operation Controller"
Cohesion: 0.22
Nodes (9): AcceptanceRequest, AcceptanceRequest, TokenOperationController, TokenOperationResponse, TokenOperationApplicationService, GetMapping, IdempotencyKey, ParticipantPrincipal (+1 more)

### Community 15 - "OpenAPI Contract Tests"
Cohesion: 0.19
Nodes (5): Class, OpenApiContractTest, Object, Set, SuppressWarnings

### Community 16 - "Transfer Delivery Plan"
Cohesion: 0.19
Nodes (14): Asynchronous Saga with Narrow Transactions, Documentation-Only Scope, Ethereum-First Chain Sequence, Zelle Share Readiness and Transfer Roadmap Execution Plan, Five-Step Stablecoin Transfer Workflow, Intentional Deferrals, Asynchronous Parent Transfer Resource, Verified Phase 3A Boundary (+6 more)

### Community 17 - "Participant Evidence Mapping"
Cohesion: 0.23
Nodes (7): AssetView, AttemptView, FinalityHistories, FinalityView, TransitionView, OperationAttempt, OperationTransition

### Community 18 - "Canonical Command Model"
Cohesion: 0.18
Nodes (4): CanonicalCommand, CanonicalCommandMetadata, PolicyApprovalPort, PolicyDecision

### Community 19 - "Transfer Architecture and Finality"
Cohesion: 0.22
Nodes (13): Append-Only Compensation, Asynchronous Operation Lifecycle, Exact Amount and Unit Representation, Scoped Idempotency Contract, PostgreSQL Durable Acceptance and Outbox, Provider-Neutral Process Manager, Planned Transfer Aggregate, Asynchronous Saga with Narrow Transactions (+5 more)

### Community 21 - "Financial Lifecycle Review"
Cohesion: 0.18
Nodes (11): Ambiguous Effect Invariant, Exact Quantity Invariant, Idempotency Invariant, Ambiguous-Effect Handling, Exact-Money Types, Durable Workflow Before Effects, Exact Boundary Amounts, Idempotent External Effects (+3 more)

### Community 22 - "Engineering Skill Routing"
Cohesion: 0.20
Nodes (10): Digital Banking Doc Sync Agent Interface, Digital Banking Engineering Agent Interface, Digital Banking Document Synchronization Skill, Document Ownership Matrix, Living Document Synchronization, Digital Banking Engineering Skill, Durable Operation Result Shape, External Response Is Evidence (+2 more)

### Community 23 - "State Invariant Guidance"
Cohesion: 0.20
Nodes (10): Financial State Invariants Agent Interface, Append-Only State Transition, Attempt Identity, Financial and State Invariants Skill, Minimum Financial Invariant Test Matrix, Reconciliation Invariant, Sensitive Data Invariant, Stable Operation Identity (+2 more)

### Community 24 - "Documentation Evidence"
Cohesion: 0.20
Nodes (10): Documentation Evidence Contract, Immutable Reference Publications, Public Case Study Boundary, Salus Read-Only Provenance, Planned Scaffolded Implemented Verified Vocabulary, Digital Banking Graphify Guardrails, Completion Handoff Contents, Fresh Verification Evidence (+2 more)

### Community 25 - "Graphify Usage and Exports"
Cohesion: 0.20
Nodes (10): Commit Hook Integration, Existing Graph Fast Path, Graphify Skill, Graphify Honesty Rules, Graphify Interpreter Guard, Persistent Knowledge Graph, Query Path and Explain, CLAUDE.md Integration (+2 more)

### Community 26 - "Finality and Signer Checks"
Cohesion: 0.22
Nodes (9): Independent Finality Records, Signing Authority Invariant, Architecture and Documentation Synchronization, Durable Asynchronous Mint/Burn, Environment Safety, Four Distinct Finalities, Plain-Java Domain Purity, Signer Isolation (+1 more)

### Community 27 - "Graph Query Navigation"
Cohesion: 0.25
Nodes (9): Breadth-First Graph Traversal, Constrained Graph-Vocabulary Expansion, Depth-First Graph Traversal, Graph-Only Answering, Node Explanation, Query Path and Explain Reference, Reflection Work Memory, Saved Query Feedback Loop (+1 more)

### Community 28 - "Planning Prompt Workflow"
Cohesion: 0.25
Nodes (8): Bounded Test-First Slice, Architecture Claim Updates, Authority and Risk Stop Conditions, Bounded Change Plan, Bounded Execution Scope, Failing-Then-Passing Tests, Repository Authority Sources, Repository Prompt Templates

### Community 29 - "Graphify Exports and Labels"
Cohesion: 0.25
Nodes (8): Community Labeling, Graphify Outputs, Extra Exports and Benchmark Reference, Neo4j and FalkorDB Exports, MCP Server Export, Token Reduction Benchmark, SVG and GraphML Exports, Wiki Export

### Community 31 - "Transfer Architecture and Finality"
Cohesion: 0.33
Nodes (6): Chain Adapter Capability Contract, Ethereum Native Semantics, Blockchain, Legal, Customer, and Accounting Finalities, Independent Observation and Reconciliation, Signer and Custody Authority Port, Solana Native Semantics

### Community 32 - "Graphify Build Pipeline"
Cohesion: 0.40
Nodes (6): Graphify File Detection, Graph Build Cluster and Analysis, Graph Health Check, Semantic Extraction Cache, Semantic Subagent Extraction, Structural AST Extraction

### Community 33 - "Semantic Extraction Contract"
Cohesion: 0.33
Nodes (6): Extraction Confidence Rubric, Deterministic Node ID Format, Extraction Subagent Prompt Specification, Semantic Similarity Edges, Source File Portability, Sparse Hyperedges

### Community 34 - "Graph Update Portability"
Cohesion: 0.50
Nodes (5): Portable Graph Artifacts, Cluster-only Refresh, Incremental Graph Update, Portable Relative-path Manifest, Replace on Re-extract

### Community 35 - "Action 05 Execution Gates"
Cohesion: 0.83
Nodes (4): Single Commit and Authorized Push Gate, Execution Evidence Log, Graphify Navigation Artifact Refresh, Validation and Independent Review

### Community 36 - "Java Control Plane Workflow"
Cohesion: 0.50
Nodes (4): Java Spring Control Plane Change Interface, Java and Spring Control-Plane Change, Plain Domain Boundary, Test-first Control-Plane Workflow

### Community 37 - "Plan Execution"
Cohesion: 0.50
Nodes (4): Plan Execution Interface, Plan Evidence States, Plan Execution, Restartable Reviewable Slices

### Community 39 - "Transfer Architecture and Finality"
Cohesion: 0.67
Nodes (4): Durable Internal Business Truth, Digital Banking Reference Implementation Design, Control-Plane Trust Boundaries, Planned Bank-to-Bank Stablecoin Transfer Demonstration

### Community 40 - "Transfer Chains and Finality"
Cohesion: 0.67
Nodes (4): Ethereum Local Transfer Demonstration, Separate Ethereum and Solana Completion Claims, Solana Local Transfer Demonstration, Wallet, Key, and Configuration Safety

### Community 41 - "URL Ingestion and Watch"
Cohesion: 0.50
Nodes (4): URL Add and Folder Watch, Add URL and Watch Folder Reference, Folder Watch, URL Ingestion

### Community 42 - "Cross Repository Graph Merge"
Cohesion: 0.50
Nodes (4): Cross-Repository Graph Merge, GitHub Repository Clone, GitHub Clone and Cross-Repo Merge Reference, Monorepo Subgraph Merge

### Community 43 - "Media Transcription"
Cohesion: 0.50
Nodes (4): Graph-Derived Whisper Prompt, Transcripts as Documents, Video and Audio Transcription Reference, Whisper Transcription

## Knowledge Gaps
- **95 isolated node(s):** `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain`, `Repository Authority Sources`, `Plain-Java Domain Purity` (+90 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `EvidenceRef` connect `Operation Aggregate Transitions` to `Quantity and Identifier Types`, `Canonical Command Model`, `Operation Aggregate Transitions`, `Canonical Command Model`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `PostgresOperationRepository` connect `Persistence Acceptance Workflow` to `Operation Aggregate Transitions`, `Acceptance Concurrency Tests`, `Application Composition`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **Why does `TokenOperation` connect `Operation Aggregate Transitions` to `Quantity and Identifier Types`, `Operation Aggregate Transitions`, `Acceptance Concurrency Tests`, `Acceptance Context Types`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **What connects `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain` to the rest of the system?**
  _135 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Persistence Acceptance Workflow` be split into smaller, more focused modules?**
  _Cohesion score 0.06575091575091575 - nodes in this community are weakly interconnected._
- **Should `Operation Aggregate Transitions` be split into smaller, more focused modules?**
  _Cohesion score 0.06478715459297983 - nodes in this community are weakly interconnected._
- **Should `Acceptance Concurrency Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.06713286713286713 - nodes in this community are weakly interconnected._