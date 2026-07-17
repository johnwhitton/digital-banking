# Graph Report - digital-banking  (2026-07-17)

## Corpus Check
- 2 files · ~86,921 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1068 nodes · 3050 edges · 71 communities (43 shown, 28 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 266 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Durable Operation Persistence|Durable Operation Persistence]]
- [[_COMMUNITY_Operation Lifecycle Model|Operation Lifecycle Model]]
- [[_COMMUNITY_Persistence Acceptance Tests|Persistence Acceptance Tests]]
- [[_COMMUNITY_Delivery Worker Contracts|Delivery Worker Contracts]]
- [[_COMMUNITY_API Contract Models|API Contract Models]]
- [[_COMMUNITY_Delivery Recovery Tests|Delivery Recovery Tests]]
- [[_COMMUNITY_Spring Composition|Spring Composition]]
- [[_COMMUNITY_Command Canonicalization|Command Canonicalization]]
- [[_COMMUNITY_Phase 3A Persistence|Phase 3A Persistence]]
- [[_COMMUNITY_API Security Boundaries|API Security Boundaries]]
- [[_COMMUNITY_Exact Token Quantities|Exact Token Quantities]]
- [[_COMMUNITY_Problem Response Handling|Problem Response Handling]]
- [[_COMMUNITY_Standards Audit Corrections|Standards Audit Corrections]]
- [[_COMMUNITY_Chain Adapter Contracts|Chain Adapter Contracts]]
- [[_COMMUNITY_Transfer Roadmap|Transfer Roadmap]]
- [[_COMMUNITY_Financial State Invariants|Financial State Invariants]]
- [[_COMMUNITY_Repository Skill Coordination|Repository Skill Coordination]]
- [[_COMMUNITY_Reconciliation State Invariants|Reconciliation State Invariants]]
- [[_COMMUNITY_Documentation Evidence Gates|Documentation Evidence Gates]]
- [[_COMMUNITY_Graphify Navigation|Graphify Navigation]]
- [[_COMMUNITY_Architecture Finality Boundaries|Architecture Finality Boundaries]]
- [[_COMMUNITY_Graph Query Traversal|Graph Query Traversal]]
- [[_COMMUNITY_Prompt Planning Templates|Prompt Planning Templates]]
- [[_COMMUNITY_Graph Export Tooling|Graph Export Tooling]]
- [[_COMMUNITY_Transfer Saga Design|Transfer Saga Design]]
- [[_COMMUNITY_Graph Extraction Pipeline|Graph Extraction Pipeline]]
- [[_COMMUNITY_Semantic Extraction Schema|Semantic Extraction Schema]]
- [[_COMMUNITY_Incremental Graph Updates|Incremental Graph Updates]]
- [[_COMMUNITY_Core Architecture Documents|Core Architecture Documents]]
- [[_COMMUNITY_Action Execution Evidence|Action Execution Evidence]]
- [[_COMMUNITY_Java Spring Standards|Java Spring Standards]]
- [[_COMMUNITY_Plan Execution Workflow|Plan Execution Workflow]]
- [[_COMMUNITY_Local Chain Demonstrations|Local Chain Demonstrations]]
- [[_COMMUNITY_Graph URL Ingestion|Graph URL Ingestion]]
- [[_COMMUNITY_Cross Repository Graphs|Cross Repository Graphs]]
- [[_COMMUNITY_Media Transcription|Media Transcription]]
- [[_COMMUNITY_Graphify Publication Refresh|Graphify Publication Refresh]]
- [[_COMMUNITY_AI Tool Guardrails|AI Tool Guardrails]]
- [[_COMMUNITY_Phase 3B Publication Scope|Phase 3B Publication Scope]]
- [[_COMMUNITY_EVM Toolchain|EVM Toolchain]]
- [[_COMMUNITY_Solana Integration|Solana Integration]]
- [[_COMMUNITY_Escalation Authority|Escalation Authority]]
- [[_COMMUNITY_Query Source Validation|Query Source Validation]]
- [[_COMMUNITY_Bootstrap Plan|Bootstrap Plan]]
- [[_COMMUNITY_Reference Publication Integrity|Reference Publication Integrity]]
- [[_COMMUNITY_Java Spring Foundation|Java Spring Foundation]]
- [[_COMMUNITY_Phase 2 Evidence|Phase 2 Evidence]]
- [[_COMMUNITY_Domain Lifecycle Plan|Domain Lifecycle Plan]]
- [[_COMMUNITY_Phase 2 Decisions|Phase 2 Decisions]]
- [[_COMMUNITY_Companion Publication Metadata|Companion Publication Metadata]]
- [[_COMMUNITY_Engineering Companion Plan|Engineering Companion Plan]]
- [[_COMMUNITY_Publication Baseline|Publication Baseline]]
- [[_COMMUNITY_Local EVM Execution|Local EVM Execution]]
- [[_COMMUNITY_Solana Sava Evaluation|Solana Sava Evaluation]]
- [[_COMMUNITY_Safe Failure Classification|Safe Failure Classification]]
- [[_COMMUNITY_External Effect Contracts|External Effect Contracts]]
- [[_COMMUNITY_Participant Safe Projection|Participant Safe Projection]]
- [[_COMMUNITY_Transfer Demonstration Plan|Transfer Demonstration Plan]]
- [[_COMMUNITY_Durable API Contract|Durable API Contract]]
- [[_COMMUNITY_Application Maven Module|Application Maven Module]]
- [[_COMMUNITY_Control Plane Module|Control Plane Module]]
- [[_COMMUNITY_Domain Maven Module|Domain Maven Module]]
- [[_COMMUNITY_PostgreSQL Adapter Module|PostgreSQL Adapter Module]]
- [[_COMMUNITY_Parent Maven Build|Parent Maven Build]]
- [[_COMMUNITY_Control Plane Configuration|Control Plane Configuration]]

## God Nodes (most connected - your core abstractions)
1. `PostgresOperationRepository` - 45 edges
2. `TokenOperation` - 39 edges
3. `PostgresOperationRepositoryTest` - 31 edges
4. `EvidenceRef` - 30 edges
5. `DeliveryOutcome` - 29 edges
6. `PostgresOperationDeliveryQueueTest` - 28 edges
7. `OperationDelivery` - 27 edges
8. `Phase 3A Durable API and Persistence Plan` - 20 edges
9. `OperationDeliveryQueue` - 20 edges
10. `TokenOperationLifecycleTest` - 19 edges

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

## Communities (71 total, 28 thin omitted)

### Community 0 - "Durable Operation Persistence"
Cohesion: 0.05
Nodes (47): AcceptanceRequest, TokenOperationApplicationService, InMemoryRepository, ScopedKey, SequentialIds, StoredAcceptance, TokenOperationApplicationServiceTest, CountingIds (+39 more)

### Community 1 - "Operation Lifecycle Model"
Cohesion: 0.05
Nodes (33): OperationAcceptance, Arguments, FinalityType, List, Map, MethodSource, AttemptId, EvidenceRef (+25 more)

### Community 2 - "Persistence Acceptance Tests"
Cohesion: 0.05
Nodes (20): AfterAll, ScopedKey, TokenOperationServiceTest, BeforeAll, Callable, CommandCanonicalizerTest, DeliveryWorkerConfigurationTest, CountDownLatch (+12 more)

### Community 3 - "Delivery Worker Contracts"
Cohesion: 0.08
Nodes (23): Classification, EmptyQueue, DeliveryOutcome, OperationDelivery, ClaimBatch, OperationDeliveryQueue, QueueMeasurements, MutableCounts (+15 more)

### Community 4 - "API Contract Models"
Cohesion: 0.05
Nodes (25): AcceptanceRequest, ParticipantPrincipal, AcceptanceRequest, TokenOperationController, AssetView, AttemptView, FinalityHistories, FinalityView (+17 more)

### Community 5 - "Delivery Recovery Tests"
Cohesion: 0.09
Nodes (9): DeliveryWorkerMetricsTest, DeliveryPollResult, DeliveryRetryPolicy, DeliveryRetryPolicyTest, OperationDeliveryFailureReporter, OperationDeliveryHandler, OperationDeliveryWorkerTest, HikariDataSource (+1 more)

### Community 6 - "Spring Composition"
Cohesion: 0.07
Nodes (27): AssetUnitCatalog, Bean, ClockPort, ApplicationConfiguration, DeliveryWorkerConfiguration, DeliveryWorkerLifecycle, DeliveryWorkerMetrics, DeliveryWorkerProperties (+19 more)

### Community 7 - "Command Canonicalization"
Cohesion: 0.06
Nodes (13): TokenOperationService, BurnCommand, CanonicalCommand, CanonicalCommandMetadata, CommandCanonicalizer, CommandDigest, MintCommand, ParticipantScope (+5 more)

### Community 8 - "Phase 3A Persistence"
Cohesion: 0.07
Nodes (43): AI-Assisted Engineering Toolchain Plan, Atomic Durable Acceptance Transaction, Framework-free Acceptance and Status Boundary, Dependency and Binary Boundary Evidence, Minimal Dependency Disposition, Final 302-test Verification Evidence, Deterministic Flyway Persistence Schema, Pending Git Closeout (+35 more)

### Community 9 - "API Security Boundaries"
Cohesion: 0.09
Nodes (17): InvalidRequestException, CapturedOutput, ParticipantResolver, TokenOperationApiFailureBoundaryTest, TokenOperationApiIntegrationTest, DirtiesContext, ExtendWith, HandlerMethodArgumentResolver (+9 more)

### Community 10 - "Exact Token Quantities"
Cohesion: 0.10
Nodes (8): AssetUnit, TokenQuantity, TokenQuantityTest, BigInteger, PortContractTest, SignerPort, SigningDecision, SigningRequest

### Community 11 - "Problem Response Handling"
Cohesion: 0.13
Nodes (14): ApiExceptionHandler, UnauthenticatedPrincipalException, IdempotencyConflictException, OperationNotFoundException, UnknownAssetUnitException, UnsupportedRequestContractException, Exception, ExceptionHandler (+6 more)

### Community 12 - "Standards Audit Corrections"
Cohesion: 0.12
Nodes (21): Java/Spring Implementation Standards Audit Plan, Phase 3A Audit Validation Evidence, Action Request 07 Scope, Focused RED-GREEN Validation Evidence, I-01 Participant Projection Correction, I-02 Failure Classification Correction, Action Request 07 Intentional Deferrals, Phase 3A API Boundary Corrections Plan (+13 more)

### Community 13 - "Chain Adapter Contracts"
Cohesion: 0.10
Nodes (21): Chain Adapter Change Agent Interface, Ambiguity Retry Rules, Business-Facing Chain Lifecycle, Chain Adapter Change Skill, EVM Adapter Semantics, Independent Observation, Independent Signer Port, Local Disposable Networks (+13 more)

### Community 14 - "Transfer Roadmap"
Cohesion: 0.19
Nodes (14): Asynchronous Saga with Narrow Transactions, Documentation-Only Scope, Ethereum-First Chain Sequence, Zelle Share Readiness and Transfer Roadmap Execution Plan, Five-Step Stablecoin Transfer Workflow, Intentional Deferrals, Asynchronous Parent Transfer Resource, Verified Phase 3A Boundary (+6 more)

### Community 15 - "Financial State Invariants"
Cohesion: 0.18
Nodes (11): Ambiguous Effect Invariant, Exact Quantity Invariant, Idempotency Invariant, Ambiguous-Effect Handling, Exact-Money Types, Durable Workflow Before Effects, Exact Boundary Amounts, Idempotent External Effects (+3 more)

### Community 16 - "Repository Skill Coordination"
Cohesion: 0.20
Nodes (10): Digital Banking Doc Sync Agent Interface, Digital Banking Engineering Agent Interface, Digital Banking Document Synchronization Skill, Document Ownership Matrix, Living Document Synchronization, Digital Banking Engineering Skill, Durable Operation Result Shape, External Response Is Evidence (+2 more)

### Community 17 - "Reconciliation State Invariants"
Cohesion: 0.20
Nodes (10): Financial State Invariants Agent Interface, Append-Only State Transition, Attempt Identity, Financial and State Invariants Skill, Minimum Financial Invariant Test Matrix, Reconciliation Invariant, Sensitive Data Invariant, Stable Operation Identity (+2 more)

### Community 18 - "Documentation Evidence Gates"
Cohesion: 0.20
Nodes (10): Documentation Evidence Contract, Immutable Reference Publications, Public Case Study Boundary, Salus Read-Only Provenance, Planned Scaffolded Implemented Verified Vocabulary, Digital Banking Graphify Guardrails, Completion Handoff Contents, Fresh Verification Evidence (+2 more)

### Community 19 - "Graphify Navigation"
Cohesion: 0.20
Nodes (10): Commit Hook Integration, Existing Graph Fast Path, Graphify Skill, Graphify Honesty Rules, Graphify Interpreter Guard, Persistent Knowledge Graph, Query Path and Explain, CLAUDE.md Integration (+2 more)

### Community 20 - "Architecture Finality Boundaries"
Cohesion: 0.22
Nodes (9): Independent Finality Records, Signing Authority Invariant, Architecture and Documentation Synchronization, Durable Asynchronous Mint/Burn, Environment Safety, Four Distinct Finalities, Plain-Java Domain Purity, Signer Isolation (+1 more)

### Community 21 - "Graph Query Traversal"
Cohesion: 0.25
Nodes (9): Breadth-First Graph Traversal, Constrained Graph-Vocabulary Expansion, Depth-First Graph Traversal, Graph-Only Answering, Node Explanation, Query Path and Explain Reference, Reflection Work Memory, Saved Query Feedback Loop (+1 more)

### Community 22 - "Prompt Planning Templates"
Cohesion: 0.25
Nodes (8): Bounded Test-First Slice, Architecture Claim Updates, Authority and Risk Stop Conditions, Bounded Change Plan, Bounded Execution Scope, Failing-Then-Passing Tests, Repository Authority Sources, Repository Prompt Templates

### Community 23 - "Graph Export Tooling"
Cohesion: 0.25
Nodes (8): Community Labeling, Graphify Outputs, Extra Exports and Benchmark Reference, Neo4j and FalkorDB Exports, MCP Server Export, Token Reduction Benchmark, SVG and GraphML Exports, Wiki Export

### Community 24 - "Transfer Saga Design"
Cohesion: 0.33
Nodes (6): Asynchronous Saga with Narrow Transactions, BPM and Durable-Workflow Boundary, Five-Step Evidence and Retry Contract, Five-Step Bank-to-Bank Workflow, Asynchronous Transfer API, Transfer Aggregate Identity and Authority

### Community 25 - "Graph Extraction Pipeline"
Cohesion: 0.40
Nodes (6): Graphify File Detection, Graph Build Cluster and Analysis, Graph Health Check, Semantic Extraction Cache, Semantic Subagent Extraction, Structural AST Extraction

### Community 26 - "Semantic Extraction Schema"
Cohesion: 0.33
Nodes (6): Extraction Confidence Rubric, Deterministic Node ID Format, Extraction Subagent Prompt Specification, Semantic Similarity Edges, Source File Portability, Sparse Hyperedges

### Community 27 - "Incremental Graph Updates"
Cohesion: 0.50
Nodes (5): Portable Graph Artifacts, Cluster-only Refresh, Incremental Graph Update, Portable Relative-path Manifest, Replace on Re-extract

### Community 28 - "Core Architecture Documents"
Cohesion: 0.90
Nodes (5): ADR 0005: PostgreSQL Operation Delivery Worker and Lease Recovery, Architecture Decision Records, Digital Banking Reference Implementation Design, Digital Banking Implementation Plan and Current State, Digital Banking Reference Implementation

### Community 29 - "Action Execution Evidence"
Cohesion: 0.83
Nodes (4): Single Commit and Authorized Push Gate, Execution Evidence Log, Graphify Navigation Artifact Refresh, Validation and Independent Review

### Community 30 - "Java Spring Standards"
Cohesion: 0.50
Nodes (4): Java Spring Control Plane Change Interface, Java and Spring Control-Plane Change, Plain Domain Boundary, Test-first Control-Plane Workflow

### Community 31 - "Plan Execution Workflow"
Cohesion: 0.50
Nodes (4): Plan Execution Interface, Plan Evidence States, Plan Execution, Restartable Reviewable Slices

### Community 32 - "Local Chain Demonstrations"
Cohesion: 0.67
Nodes (4): Ethereum Local Transfer Demonstration, Separate Ethereum and Solana Completion Claims, Solana Local Transfer Demonstration, Wallet, Key, and Configuration Safety

### Community 33 - "Graph URL Ingestion"
Cohesion: 0.50
Nodes (4): URL Add and Folder Watch, Add URL and Watch Folder Reference, Folder Watch, URL Ingestion

### Community 34 - "Cross Repository Graphs"
Cohesion: 0.50
Nodes (4): Cross-Repository Graph Merge, GitHub Repository Clone, GitHub Clone and Cross-Repo Merge Reference, Monorepo Subgraph Merge

### Community 35 - "Media Transcription"
Cohesion: 0.50
Nodes (4): Graph-Derived Whisper Prompt, Transcripts as Documents, Video and Audio Transcription Reference, Whisper Transcription

### Community 36 - "Graphify Publication Refresh"
Cohesion: 0.67
Nodes (3): Authorized Documentation and Graphify Publication Changes, Publication Validation and Closeout Gate, One Final Refresh of Three Tracked Graphify Artifacts

## Knowledge Gaps
- **92 isolated node(s):** `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain`, `Repository Authority Sources`, `Plain-Java Domain Purity` (+87 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **28 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `TokenOperation` connect `Operation Lifecycle Model` to `Exact Token Quantities`, `API Contract Models`?**
  _High betweenness centrality (0.017) - this node is a cross-community bridge._
- **Why does `TokenOperationApiIntegrationTest` connect `API Security Boundaries` to `Spring Composition`?**
  _High betweenness centrality (0.012) - this node is a cross-community bridge._
- **What connects `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain` to the rest of the system?**
  _131 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Durable Operation Persistence` be split into smaller, more focused modules?**
  _Cohesion score 0.05249343832020997 - nodes in this community are weakly interconnected._
- **Should `Operation Lifecycle Model` be split into smaller, more focused modules?**
  _Cohesion score 0.05358126721763085 - nodes in this community are weakly interconnected._
- **Should `Persistence Acceptance Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.05352968676951847 - nodes in this community are weakly interconnected._
- **Should `Delivery Worker Contracts` be split into smaller, more focused modules?**
  _Cohesion score 0.07757860711137232 - nodes in this community are weakly interconnected._