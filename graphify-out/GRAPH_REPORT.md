# Graph Report - .  (2026-07-16)

## Corpus Check
- 4 files · ~63,861 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 893 nodes · 2348 edges · 75 communities (55 shown, 20 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 252 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Persistence Acceptance Workflow|Persistence Acceptance Workflow]]
- [[_COMMUNITY_Persistence Acceptance Tests|Persistence Acceptance Tests]]
- [[_COMMUNITY_Commands and Canonicalization|Commands and Canonicalization]]
- [[_COMMUNITY_Application Composition|Application Composition]]
- [[_COMMUNITY_Chain Ports and Attempts|Chain Ports and Attempts]]
- [[_COMMUNITY_Control Plane API Security|Control Plane API Security]]
- [[_COMMUNITY_Operation Response Model|Operation Response Model]]
- [[_COMMUNITY_API Integration Tests|API Integration Tests]]
- [[_COMMUNITY_Operation Lifecycle State|Operation Lifecycle State]]
- [[_COMMUNITY_Operation Aggregate Transitions|Operation Aggregate Transitions]]
- [[_COMMUNITY_Chain Adapter Workflow|Chain Adapter Workflow]]
- [[_COMMUNITY_Quantity and Identifier Types|Quantity and Identifier Types]]
- [[_COMMUNITY_Transfer Chains and Finality|Transfer Chains and Finality]]
- [[_COMMUNITY_Participant API Control|Participant API Control]]
- [[_COMMUNITY_Acceptance Context Types|Acceptance Context Types]]
- [[_COMMUNITY_API Contract and Security|API Contract and Security]]
- [[_COMMUNITY_Signing Request Tests|Signing Request Tests]]
- [[_COMMUNITY_Phase 3A Evidence|Phase 3A Evidence]]
- [[_COMMUNITY_Repository Governance|Repository Governance]]
- [[_COMMUNITY_Transfer Delivery Plan|Transfer Delivery Plan]]
- [[_COMMUNITY_Transfer Architecture Core|Transfer Architecture Core]]
- [[_COMMUNITY_Finality Lifecycle Tests|Finality Lifecycle Tests]]
- [[_COMMUNITY_Signer Port Contracts|Signer Port Contracts]]
- [[_COMMUNITY_PostgreSQL Atomic Outbox|PostgreSQL Atomic Outbox]]
- [[_COMMUNITY_Financial Lifecycle Review|Financial Lifecycle Review]]
- [[_COMMUNITY_Engineering Skill Routing|Engineering Skill Routing]]
- [[_COMMUNITY_State Invariant Guidance|State Invariant Guidance]]
- [[_COMMUNITY_Documentation Evidence|Documentation Evidence]]
- [[_COMMUNITY_Graphify Usage and Exports|Graphify Usage and Exports]]
- [[_COMMUNITY_OpenAPI Contract Tests|OpenAPI Contract Tests]]
- [[_COMMUNITY_Finality and Signer Checks|Finality and Signer Checks]]
- [[_COMMUNITY_Graph Query Navigation|Graph Query Navigation]]
- [[_COMMUNITY_Transfer Identity and Exactness|Transfer Identity and Exactness]]
- [[_COMMUNITY_Participant Evidence Mapping|Participant Evidence Mapping]]
- [[_COMMUNITY_Planning Prompt Workflow|Planning Prompt Workflow]]
- [[_COMMUNITY_Graphify Exports and Labels|Graphify Exports and Labels]]
- [[_COMMUNITY_Maven Module Boundaries|Maven Module Boundaries]]
- [[_COMMUNITY_Transfer Saga and Persistence|Transfer Saga and Persistence]]
- [[_COMMUNITY_Phase 3 Delivery Sequence|Phase 3 Delivery Sequence]]
- [[_COMMUNITY_Graphify Build Pipeline|Graphify Build Pipeline]]
- [[_COMMUNITY_Semantic Extraction Contract|Semantic Extraction Contract]]
- [[_COMMUNITY_Graph Update Portability|Graph Update Portability]]
- [[_COMMUNITY_Transfer Demonstrations|Transfer Demonstrations]]
- [[_COMMUNITY_Publication Provenance|Publication Provenance]]
- [[_COMMUNITY_Action 05 Execution Gates|Action 05 Execution Gates]]
- [[_COMMUNITY_Java Control Plane Workflow|Java Control Plane Workflow]]
- [[_COMMUNITY_Plan Execution|Plan Execution]]
- [[_COMMUNITY_Spring Boot Entrypoint|Spring Boot Entrypoint]]
- [[_COMMUNITY_URL Ingestion and Watch|URL Ingestion and Watch]]
- [[_COMMUNITY_Cross Repository Graph Merge|Cross Repository Graph Merge]]
- [[_COMMUNITY_Media Transcription|Media Transcription]]
- [[_COMMUNITY_Operation State Enum|Operation State Enum]]
- [[_COMMUNITY_Graphify Provenance|Graphify Provenance]]
- [[_COMMUNITY_EVM Foundry and Web3j|EVM Foundry and Web3j]]
- [[_COMMUNITY_Native Solana SPL|Native Solana SPL]]
- [[_COMMUNITY_User Authority|User Authority]]
- [[_COMMUNITY_Query Source Validation|Query Source Validation]]
- [[_COMMUNITY_Bootstrap Plan|Bootstrap Plan]]
- [[_COMMUNITY_Health Configuration|Health Configuration]]
- [[_COMMUNITY_Phase 2 Evidence|Phase 2 Evidence]]
- [[_COMMUNITY_Phase 2 Decisions|Phase 2 Decisions]]
- [[_COMMUNITY_Local EVM Execution|Local EVM Execution]]
- [[_COMMUNITY_Sava Evaluation Gate|Sava Evaluation Gate]]
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
7. `IdempotencyKey` - 19 edges
8. `TokenOperationApiIntegrationTest` - 19 edges
9. `AttemptId` - 18 edges
10. `OperationId` - 18 edges

## Surprising Connections (you probably didn't know these)
- `Portable Graph Artifacts` --semantically_similar_to--> `Portable Relative-path Manifest`  [INFERRED] [semantically similar]
  docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md → .codex/skills/graphify/references/update.md
- `Regulated Control-plane Invariants` --semantically_similar_to--> `Durable Internal Business Truth`  [INFERRED] [semantically similar]
  AGENTS.md → docs/DESIGN.md
- `Pending Outbox Is Not Execution` --semantically_similar_to--> `Outbox Worker Deferred to Phase 3B`  [INFERRED] [semantically similar]
  SECURITY.md → docs/adr/0004-postgresql-jdbc-flyway-atomic-outbox.md
- `Phase 3A Durable Acceptance and Read-Back` --semantically_similar_to--> `Phase 3A Durable Acceptance and Read-Back`  [INFERRED] [semantically similar]
  README.md → docs/IMPLEMENTATION.md
- `Phase 3B Worker and Recovery` --semantically_similar_to--> `Phase 3B Asynchronous Worker and Recovery`  [INFERRED] [semantically similar]
  README.md → docs/IMPLEMENTATION.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Current Phase 3A Durable Acceptance Boundary** — docs_design_postgresql_durable_acceptance, docs_implementation_phase_3a_durable_acceptance, docs_transfer_demo_planned_bank_to_bank_stablecoin_transfer [EXTRACTED 1.00]
- **Separate Ethereum and Solana Demonstrations** — docs_design_ethereum_semantics, docs_design_solana_semantics, docs_implementation_phase_5_ethereum_slice, docs_implementation_phase_6_solana_slice, docs_transfer_demo_separate_completion_claims [EXTRACTED 1.00]
- **Planned Transfer Delivery Sequence** — active_zelle_share_readiness_and_transfer_roadmap_phase_3b_worker_recovery, active_zelle_share_readiness_and_transfer_roadmap_phase_3c_transfer_slice, active_zelle_share_readiness_and_transfer_roadmap_parent_transfer_resource, active_zelle_share_readiness_and_transfer_roadmap_five_step_transfer_workflow [EXTRACTED 1.00]
- **Documentation Closeout Gates** — active_zelle_share_readiness_and_transfer_roadmap_graphify_refresh, active_zelle_share_readiness_and_transfer_roadmap_validation_review, active_zelle_share_readiness_and_transfer_roadmap_commit_push_gate, active_zelle_share_readiness_and_transfer_roadmap_evidence_log [EXTRACTED 1.00]

## Communities (75 total, 20 thin omitted)

### Community 0 - "Persistence Acceptance Workflow"
Cohesion: 0.07
Nodes (34): AcceptanceRequest, InMemoryRepository, ScopedKey, SequentialIds, StoredAcceptance, TokenOperationApplicationServiceTest, AttemptId, BeforeEach (+26 more)

### Community 1 - "Persistence Acceptance Tests"
Cohesion: 0.07
Nodes (19): AfterAll, InMemoryOperationRepository, ScopedKey, StoredAcceptance, TokenOperationServiceTest, AssetUnit, BeforeAll, Callable (+11 more)

### Community 2 - "Commands and Canonicalization"
Cohesion: 0.06
Nodes (13): BurnCommand, CanonicalCommand, CanonicalCommandMetadata, CommandCanonicalizer, CommandDigest, MintCommand, ParticipantScope, TokenOperationCommand (+5 more)

### Community 3 - "Application Composition"
Cohesion: 0.11
Nodes (17): TokenOperationApplicationService, TokenOperationService, Bean, ClockPort, ApplicationConfiguration, SecurityConfiguration, DataSource, ReferenceAssetConfiguration (+9 more)

### Community 4 - "Chain Ports and Attempts"
Cohesion: 0.11
Nodes (16): AttemptId, EvidenceRef, OperationAttempt, OperationId, AttemptRepository, AttemptIdentity, ChainPort, InquiryResult (+8 more)

### Community 5 - "Control Plane API Security"
Cohesion: 0.14
Nodes (13): ApiExceptionHandler, UnauthenticatedPrincipalException, IdempotencyConflictException, OperationNotFoundException, UnknownAssetUnitException, UnsupportedRequestContractException, Exception, ExceptionHandler (+5 more)

### Community 6 - "Operation Response Model"
Cohesion: 0.11
Nodes (12): AttemptView, FinalityView, FinalityRecord, FinalityStatus, Instant, List, Map, OperationTransition (+4 more)

### Community 7 - "API Integration Tests"
Cohesion: 0.13
Nodes (11): CapturedOutput, PostgresApiIntegrationSupport, TokenOperationApiIntegrationTest, DirtiesContext, DynamicPropertyRegistry, DynamicPropertySource, ExtendWith, HikariDataSource (+3 more)

### Community 8 - "Operation Lifecycle State"
Cohesion: 0.21
Nodes (6): Arguments, MethodSource, TokenOperationLifecycleTest, OperationState, ParameterizedTest, Stream

### Community 9 - "Operation Aggregate Transitions"
Cohesion: 0.19
Nodes (5): OperationAcceptance, RetryAuthorization, TokenOperation, TokenOperationEvidenceTest, T

### Community 10 - "Chain Adapter Workflow"
Cohesion: 0.10
Nodes (21): Chain Adapter Change Agent Interface, Ambiguity Retry Rules, Business-Facing Chain Lifecycle, Chain Adapter Change Skill, EVM Adapter Semantics, Independent Observation, Independent Signer Port, Local Disposable Networks (+13 more)

### Community 11 - "Quantity and Identifier Types"
Cohesion: 0.22
Nodes (4): AssetUnit, TokenQuantity, TokenQuantityTest, BigInteger

### Community 12 - "Transfer Chains and Finality"
Cohesion: 0.13
Nodes (20): Chain Adapter Capability Contract, Ethereum Native Semantics, Blockchain, Legal, Customer, and Accounting Finalities, Independent Observation and Reconciliation, Signer and Custody Authority Port, Solana Native Semantics, Phase 4 Signing Boundary, Phase 5 Ethereum Vertical Slice (+12 more)

### Community 13 - "Participant API Control"
Cohesion: 0.20
Nodes (7): AcceptanceRequest, ParticipantPrincipal, AcceptanceRequest, TokenOperationController, TokenOperationResponse, GetMapping, PostMapping

### Community 14 - "Acceptance Context Types"
Cohesion: 0.15
Nodes (3): OperationAcceptanceContext, ChainCapabilities, String

### Community 15 - "API Contract and Security"
Cohesion: 0.15
Nodes (17): Framework-free Acceptance and Status Boundary, Design-first OpenAPI Contract, Deny-by-default Participant API, Accept Burn Operation, Accept Mint Operation, Versioned Acceptance Request, HTTP 202 Durable Acceptance Semantics, Durable Token Operations OpenAPI Contract (+9 more)

### Community 16 - "Signing Request Tests"
Cohesion: 0.18
Nodes (4): CountingIds, Object, Override, SigningRequest

### Community 17 - "Phase 3A Evidence"
Cohesion: 0.21
Nodes (16): Dependency and Binary Boundary Evidence, Minimal Dependency Disposition, Final 302-test Verification Evidence, Pending Git Closeout, Graphify Refresh and Query Evidence, Phase 3B Processing Deferral, Phase 3A Durable API and Persistence Plan, Phase 3A Preflight Evidence (+8 more)

### Community 18 - "Repository Governance"
Cohesion: 0.18
Nodes (14): AI-Assisted Engineering Toolchain Plan, Domain and Operation Lifecycle Plan, Outbox Worker Deferred to Phase 3B, Repository Completion Gate, Repository Instruction Precedence, Digital Banking Repository Guidance, Signing Secrets and Network Boundaries, Bounded Autonomous Execution Policy (+6 more)

### Community 19 - "Transfer Delivery Plan"
Cohesion: 0.19
Nodes (14): Asynchronous Saga with Narrow Transactions, Documentation-Only Scope, Ethereum-First Chain Sequence, Zelle Share Readiness and Transfer Roadmap Execution Plan, Five-Step Stablecoin Transfer Workflow, Intentional Deferrals, Asynchronous Parent Transfer Resource, Verified Phase 3A Boundary (+6 more)

### Community 20 - "Transfer Architecture Core"
Cohesion: 0.18
Nodes (14): Regulated Control-plane Invariants, Durable Internal Business Truth, Digital Banking Reference Implementation Design, Control-Plane Trust Boundaries, Digital Banking Implementation Plan and Current State, Evidence-Gated Delivery, Phase 3C Transfer Aggregate and Mock Banks, Planned Bank-to-Bank Stablecoin Transfer Demonstration (+6 more)

### Community 22 - "Signer Port Contracts"
Cohesion: 0.20
Nodes (3): PortContractTest, SignerPort, SigningDecision

### Community 23 - "PostgreSQL Atomic Outbox"
Cohesion: 0.29
Nodes (11): Atomic Durable Acceptance Transaction, Deterministic Flyway Persistence Schema, Retry-free Idempotency Concurrency, Explicit PostgreSQL Operation Repository, Atomic Acceptance Outbox, Explicit Spring JDBC Mapping, Forward-only Normalized Flyway Schema, PostgreSQL Behavioral Database (+3 more)

### Community 24 - "Financial Lifecycle Review"
Cohesion: 0.18
Nodes (11): Ambiguous Effect Invariant, Exact Quantity Invariant, Idempotency Invariant, Ambiguous-Effect Handling, Exact-Money Types, Durable Workflow Before Effects, Exact Boundary Amounts, Idempotent External Effects (+3 more)

### Community 25 - "Engineering Skill Routing"
Cohesion: 0.20
Nodes (10): Digital Banking Doc Sync Agent Interface, Digital Banking Engineering Agent Interface, Digital Banking Document Synchronization Skill, Document Ownership Matrix, Living Document Synchronization, Digital Banking Engineering Skill, Durable Operation Result Shape, External Response Is Evidence (+2 more)

### Community 26 - "State Invariant Guidance"
Cohesion: 0.20
Nodes (10): Financial State Invariants Agent Interface, Append-Only State Transition, Attempt Identity, Financial and State Invariants Skill, Minimum Financial Invariant Test Matrix, Reconciliation Invariant, Sensitive Data Invariant, Stable Operation Identity (+2 more)

### Community 27 - "Documentation Evidence"
Cohesion: 0.20
Nodes (10): Documentation Evidence Contract, Immutable Reference Publications, Public Case Study Boundary, Salus Read-Only Provenance, Planned Scaffolded Implemented Verified Vocabulary, Digital Banking Graphify Guardrails, Completion Handoff Contents, Fresh Verification Evidence (+2 more)

### Community 28 - "Graphify Usage and Exports"
Cohesion: 0.20
Nodes (10): Commit Hook Integration, Existing Graph Fast Path, Graphify Skill, Graphify Honesty Rules, Graphify Interpreter Guard, Persistent Knowledge Graph, Query Path and Explain, CLAUDE.md Integration (+2 more)

### Community 29 - "OpenAPI Contract Tests"
Cohesion: 0.28
Nodes (3): Class, OpenApiContractTest, SuppressWarnings

### Community 30 - "Finality and Signer Checks"
Cohesion: 0.22
Nodes (9): Independent Finality Records, Signing Authority Invariant, Architecture and Documentation Synchronization, Durable Asynchronous Mint/Burn, Environment Safety, Four Distinct Finalities, Plain-Java Domain Purity, Signer Isolation (+1 more)

### Community 31 - "Graph Query Navigation"
Cohesion: 0.25
Nodes (9): Breadth-First Graph Traversal, Constrained Graph-Vocabulary Expansion, Depth-First Graph Traversal, Graph-Only Answering, Node Explanation, Query Path and Explain Reference, Reflection Work Memory, Saved Query Feedback Loop (+1 more)

### Community 32 - "Transfer Identity and Exactness"
Cohesion: 0.29
Nodes (8): Exact Quantity and Idempotency Rules, Exact Amount and Unit Representation, Scoped Idempotency Contract, Asynchronous Transfer API, Transfer Aggregate Identity and Authority, Canonical String Quantity, Visible ASCII Idempotency Key, Idempotency Key Protection

### Community 33 - "Participant Evidence Mapping"
Cohesion: 0.29
Nodes (4): AssetView, FinalityHistories, TransitionView, OperationTransition

### Community 34 - "Planning Prompt Workflow"
Cohesion: 0.25
Nodes (8): Bounded Test-First Slice, Architecture Claim Updates, Authority and Risk Stop Conditions, Bounded Change Plan, Bounded Execution Scope, Failing-Then-Passing Tests, Repository Authority Sources, Repository Prompt Templates

### Community 35 - "Graphify Exports and Labels"
Cohesion: 0.25
Nodes (8): Community Labeling, Graphify Outputs, Extra Exports and Benchmark Reference, Neo4j and FalkorDB Exports, MCP Server Export, Token Reduction Benchmark, SVG and GraphML Exports, Wiki Export

### Community 36 - "Maven Module Boundaries"
Cohesion: 0.29
Nodes (7): No Speculative Adapter Modules, Domain and Application Enforcer Boundaries, Committed Maven Reactor, ADR 0001 Maven Reactor and Module Boundaries, Accepted ADR Lifecycle, Architecture Decision Records, Java and Module Boundaries

### Community 37 - "Transfer Saga and Persistence"
Cohesion: 0.38
Nodes (7): Append-Only Compensation, Asynchronous Operation Lifecycle, PostgreSQL Durable Acceptance and Outbox, Provider-Neutral Process Manager, Planned Transfer Aggregate, Asynchronous Saga with Narrow Transactions, BPM and Durable-Workflow Boundary

### Community 38 - "Phase 3 Delivery Sequence"
Cohesion: 0.40
Nodes (6): Next Bounded Slice, Phase 2 Domain and Operation Lifecycle, Phase 3A Durable Acceptance and Read-Back, Phase 3B Asynchronous Worker and Recovery, Phase 3A Durable Acceptance and Read-Back, Phase 3B Worker and Recovery

### Community 39 - "Graphify Build Pipeline"
Cohesion: 0.40
Nodes (6): Graphify File Detection, Graph Build Cluster and Analysis, Graph Health Check, Semantic Extraction Cache, Semantic Subagent Extraction, Structural AST Extraction

### Community 40 - "Semantic Extraction Contract"
Cohesion: 0.33
Nodes (6): Extraction Confidence Rubric, Deterministic Node ID Format, Extraction Subagent Prompt Specification, Semantic Similarity Edges, Source File Portability, Sparse Hyperedges

### Community 41 - "Graph Update Portability"
Cohesion: 0.50
Nodes (5): Portable Graph Artifacts, Cluster-only Refresh, Incremental Graph Update, Portable Relative-path Manifest, Replace on Re-extract

### Community 42 - "Transfer Demonstrations"
Cohesion: 0.40
Nodes (5): Five-Step Evidence and Retry Contract, Five-Step Bank-to-Bank Workflow, Bank-to-Bank Stablecoin Transfer Demonstration, Ethereum Local Vertical Slice, Solana Local Vertical Slice

### Community 43 - "Publication Provenance"
Cohesion: 0.50
Nodes (4): Immutable Reference Publications Gate, Publication-to-design Traceability, Immutable Publication Provenance, Reference Publications Index

### Community 44 - "Action 05 Execution Gates"
Cohesion: 0.83
Nodes (4): Single Commit and Authorized Push Gate, Execution Evidence Log, Graphify Navigation Artifact Refresh, Validation and Independent Review

### Community 45 - "Java Control Plane Workflow"
Cohesion: 0.50
Nodes (4): Java Spring Control Plane Change Interface, Java and Spring Control-Plane Change, Plain Domain Boundary, Test-first Control-Plane Workflow

### Community 46 - "Plan Execution"
Cohesion: 0.50
Nodes (4): Plan Execution Interface, Plan Evidence States, Plan Execution, Restartable Reviewable Slices

### Community 48 - "URL Ingestion and Watch"
Cohesion: 0.50
Nodes (4): URL Add and Folder Watch, Add URL and Watch Folder Reference, Folder Watch, URL Ingestion

### Community 49 - "Cross Repository Graph Merge"
Cohesion: 0.50
Nodes (4): Cross-Repository Graph Merge, GitHub Repository Clone, GitHub Clone and Cross-Repo Merge Reference, Monorepo Subgraph Merge

### Community 50 - "Media Transcription"
Cohesion: 0.50
Nodes (4): Graph-Derived Whisper Prompt, Transcripts as Documents, Video and Audio Transcription Reference, Whisper Transcription

## Knowledge Gaps
- **81 isolated node(s):** `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain`, `Repository Authority Sources`, `Plain-Java Domain Purity` (+76 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **20 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PostgresOperationRepository` connect `Persistence Acceptance Workflow` to `Persistence Acceptance Tests`, `Application Composition`, `Operation Response Model`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `EvidenceRef` connect `Chain Ports and Attempts` to `Commands and Canonicalization`, `Operation Lifecycle State`, `Operation Aggregate Transitions`, `Signing Request Tests`, `Finality Lifecycle Tests`, `Signer Port Contracts`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `TokenOperation` connect `Operation Aggregate Transitions` to `Commands and Canonicalization`, `Chain Ports and Attempts`, `Operation Response Model`, `Operation Lifecycle State`, `Quantity and Identifier Types`, `Acceptance Context Types`, `Finality Lifecycle Tests`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **What connects `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain` to the rest of the system?**
  _120 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Persistence Acceptance Workflow` be split into smaller, more focused modules?**
  _Cohesion score 0.06823335394763966 - nodes in this community are weakly interconnected._
- **Should `Persistence Acceptance Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.06905671466353218 - nodes in this community are weakly interconnected._
- **Should `Commands and Canonicalization` be split into smaller, more focused modules?**
  _Cohesion score 0.05939716312056738 - nodes in this community are weakly interconnected._
