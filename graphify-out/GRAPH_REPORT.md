# Graph Report - .  (2026-07-17)

## Corpus Check
- 5 files · ~73,193 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 889 nodes · 2325 edges · 75 communities (54 shown, 21 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 244 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Persistence Acceptance Workflow|Persistence Acceptance Workflow]]
- [[_COMMUNITY_Persistence API Tests|Persistence API Tests]]
- [[_COMMUNITY_Acceptance Concurrency Tests|Acceptance Concurrency Tests]]
- [[_COMMUNITY_Control Plane API Security|Control Plane API Security]]
- [[_COMMUNITY_Application Composition|Application Composition]]
- [[_COMMUNITY_Operation Aggregate Transitions|Operation Aggregate Transitions]]
- [[_COMMUNITY_Transfer Architecture and Finality|Transfer Architecture and Finality]]
- [[_COMMUNITY_Chain Ports and Attempts|Chain Ports and Attempts]]
- [[_COMMUNITY_Quantity and Identifier Types|Quantity and Identifier Types]]
- [[_COMMUNITY_Finality Response Tests|Finality Response Tests]]
- [[_COMMUNITY_Signing Request Tests|Signing Request Tests]]
- [[_COMMUNITY_Operation Lifecycle State|Operation Lifecycle State]]
- [[_COMMUNITY_Chain Adapter Workflow|Chain Adapter Workflow]]
- [[_COMMUNITY_Acceptance Context Types|Acceptance Context Types]]
- [[_COMMUNITY_Operation Attempts and Responses|Operation Attempts and Responses]]
- [[_COMMUNITY_Java Standards Audit|Java Standards Audit]]
- [[_COMMUNITY_API Contract and Security|API Contract and Security]]
- [[_COMMUNITY_Phase 3A Evidence|Phase 3A Evidence]]
- [[_COMMUNITY_Transfer Delivery Plan|Transfer Delivery Plan]]
- [[_COMMUNITY_Participant Evidence Mapping|Participant Evidence Mapping]]
- [[_COMMUNITY_Operation Response Tests|Operation Response Tests]]
- [[_COMMUNITY_Transfer Saga and Persistence|Transfer Saga and Persistence]]
- [[_COMMUNITY_Canonical Command Model|Canonical Command Model]]
- [[_COMMUNITY_Command Canonicalization|Command Canonicalization]]
- [[_COMMUNITY_Repository Governance|Repository Governance]]
- [[_COMMUNITY_PostgreSQL Atomic Outbox|PostgreSQL Atomic Outbox]]
- [[_COMMUNITY_Financial Lifecycle Review|Financial Lifecycle Review]]
- [[_COMMUNITY_Engineering Skill Routing|Engineering Skill Routing]]
- [[_COMMUNITY_State Invariant Guidance|State Invariant Guidance]]
- [[_COMMUNITY_Documentation Evidence|Documentation Evidence]]
- [[_COMMUNITY_Graphify Usage and Exports|Graphify Usage and Exports]]
- [[_COMMUNITY_Finality and Signer Checks|Finality and Signer Checks]]
- [[_COMMUNITY_Graph Query Navigation|Graph Query Navigation]]
- [[_COMMUNITY_OpenAPI Contract Tests|OpenAPI Contract Tests]]
- [[_COMMUNITY_Planning Prompt Workflow|Planning Prompt Workflow]]
- [[_COMMUNITY_Graphify Exports and Labels|Graphify Exports and Labels]]
- [[_COMMUNITY_Maven Module Boundaries|Maven Module Boundaries]]
- [[_COMMUNITY_Participant Evidence Port|Participant Evidence Port]]
- [[_COMMUNITY_Graphify Build Pipeline|Graphify Build Pipeline]]
- [[_COMMUNITY_Semantic Extraction Contract|Semantic Extraction Contract]]
- [[_COMMUNITY_Graph Update Portability|Graph Update Portability]]
- [[_COMMUNITY_Publication Provenance|Publication Provenance]]
- [[_COMMUNITY_Action 05 Execution Gates|Action 05 Execution Gates]]
- [[_COMMUNITY_Java Control Plane Workflow|Java Control Plane Workflow]]
- [[_COMMUNITY_Plan Execution|Plan Execution]]
- [[_COMMUNITY_Spring Boot Entrypoint|Spring Boot Entrypoint]]
- [[_COMMUNITY_Transfer Chains and Finality|Transfer Chains and Finality]]
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
- [[_COMMUNITY_Lifecycle Plan Governance|Lifecycle Plan Governance]]
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
- `Bank-to-Bank Stablecoin Transfer Demonstration` --semantically_similar_to--> `Five-Step Bank-to-Bank Workflow`  [INFERRED] [semantically similar]
  README.md → docs/TRANSFER_DEMO.md
- `Portable Graph Artifacts` --semantically_similar_to--> `Portable Relative-path Manifest`  [INFERRED] [semantically similar]
  docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md → .codex/skills/graphify/references/update.md
- `Pending Outbox Is Not Execution` --semantically_similar_to--> `Outbox Worker Deferred to Phase 3B`  [INFERRED] [semantically similar]
  SECURITY.md → docs/adr/0004-postgresql-jdbc-flyway-atomic-outbox.md
- `Four Separate Finalities` --semantically_similar_to--> `Blockchain, Legal, Customer, and Accounting Finalities`  [INFERRED] [semantically similar]
  README.md → docs/DESIGN.md
- `Health-only Actuator Exposure` --conceptually_related_to--> `Durable API Security Boundary`  [INFERRED]
  control-plane/src/main/resources/application.yaml → SECURITY.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Implementation Standards Governance Chain** — agents_digital_banking_repository_guidance, docs_implementation_digital_banking_implementation_plan_and_current_state, docs_implementation_standards_java_and_spring_implementation_standards, active_java_spring_implementation_standards_audit_java_spring_implementation_standards_audit_plan, reviews_phase_3a_implementation_standards_review_phase_3a_java_spring_implementation_standards_review [EXTRACTED 1.00]
- **Required Phase 3B Boundary Corrections** — docs_implementation_phase_3b_asynchronous_worker_and_delivery_recovery, reviews_phase_3a_implementation_standards_review_important_i_01_participant_status_projection, reviews_phase_3a_implementation_standards_review_important_i_02_internal_failure_classification, reviews_phase_3a_implementation_standards_review_required_corrections_before_phase_3b [EXTRACTED 1.00]

## Communities (75 total, 21 thin omitted)

### Community 0 - "Persistence Acceptance Workflow"
Cohesion: 0.07
Nodes (33): AcceptanceRequest, InMemoryRepository, ScopedKey, SequentialIds, StoredAcceptance, TokenOperationApplicationServiceTest, AttemptId, BeforeEach (+25 more)

### Community 1 - "Persistence API Tests"
Cohesion: 0.06
Nodes (22): InMemoryOperationRepository, ScopedKey, StoredAcceptance, TokenOperationServiceTest, CapturedOutput, CommandCanonicalizerTest, DigitalBankingApplicationTests, HealthReadinessSmokeTests (+14 more)

### Community 2 - "Acceptance Concurrency Tests"
Cohesion: 0.09
Nodes (11): AfterAll, BeforeAll, Callable, BurnCommand, MintCommand, TokenOperationCommand, CountDownLatch, CyclicBarrier (+3 more)

### Community 3 - "Control Plane API Security"
Cohesion: 0.08
Nodes (21): AcceptanceRequest, ApiExceptionHandler, ParticipantPrincipal, AcceptanceRequest, TokenOperationController, TokenOperationResponse, UnauthenticatedPrincipalException, IdempotencyConflictException (+13 more)

### Community 4 - "Application Composition"
Cohesion: 0.10
Nodes (18): TokenOperationApplicationService, TokenOperationService, AssetUnit, Bean, ClockPort, ApplicationConfiguration, SecurityConfiguration, DataSource (+10 more)

### Community 5 - "Operation Aggregate Transitions"
Cohesion: 0.19
Nodes (5): OperationAcceptance, EvidenceRef, RetryAuthorization, TokenOperation, T

### Community 6 - "Transfer Architecture and Finality"
Cohesion: 0.09
Nodes (25): Durable Internal Business Truth, Chain Adapter Capability Contract, Digital Banking Reference Implementation Design, Ethereum Native Semantics, Blockchain, Legal, Customer, and Accounting Finalities, Independent Observation and Reconciliation, Signer and Custody Authority Port, Solana Native Semantics (+17 more)

### Community 7 - "Chain Ports and Attempts"
Cohesion: 0.16
Nodes (11): AttemptIdentity, ChainCapabilities, ChainPort, InquiryResult, NativeIdentity, Observation, ObservationRequest, PreparedAttempt (+3 more)

### Community 8 - "Quantity and Identifier Types"
Cohesion: 0.20
Nodes (5): AssetUnit, TokenQuantity, TokenQuantityTest, BigInteger, PortContractTest

### Community 9 - "Finality Response Tests"
Cohesion: 0.18
Nodes (6): FinalityStatus, FinalityType, List, FinalityRecord, OperationTransition, TokenOperationEvidenceTest

### Community 10 - "Signing Request Tests"
Cohesion: 0.13
Nodes (5): CountingIds, Object, Override, SigningDecision, SigningRequest

### Community 11 - "Operation Lifecycle State"
Cohesion: 0.27
Nodes (4): MethodSource, TokenOperationLifecycleTest, OperationState, ParameterizedTest

### Community 12 - "Chain Adapter Workflow"
Cohesion: 0.10
Nodes (21): Chain Adapter Change Agent Interface, Ambiguity Retry Rules, Business-Facing Chain Lifecycle, Chain Adapter Change Skill, EVM Adapter Semantics, Independent Observation, Independent Signer Port, Local Disposable Networks (+13 more)

### Community 13 - "Acceptance Context Types"
Cohesion: 0.15
Nodes (3): OperationAcceptanceContext, SignerPort, String

### Community 14 - "Operation Attempts and Responses"
Cohesion: 0.18
Nodes (7): Instant, AttemptId, OperationAttempt, OperationId, AttemptRepository, ClockPort, IdGenerator

### Community 15 - "Java Standards Audit"
Cohesion: 0.14
Nodes (17): Java/Spring Implementation Standards Audit Plan, Phase 3A Audit Validation Evidence, Digital Banking Repository Guidance, Digital Banking Implementation Plan and Current State, Phase 3A Durable Acceptance and Read-Back, Phase 3B Asynchronous Worker and Delivery Recovery, Classified Safe Failure Mapping, Durable External-Effect Contracts (+9 more)

### Community 16 - "API Contract and Security"
Cohesion: 0.15
Nodes (17): Framework-free Acceptance and Status Boundary, Design-first OpenAPI Contract, Deny-by-default Participant API, Accept Burn Operation, Accept Mint Operation, Versioned Acceptance Request, HTTP 202 Durable Acceptance Semantics, Durable Token Operations OpenAPI Contract (+9 more)

### Community 17 - "Phase 3A Evidence"
Cohesion: 0.23
Nodes (15): Dependency and Binary Boundary Evidence, Minimal Dependency Disposition, Final 302-test Verification Evidence, Pending Git Closeout, Graphify Refresh and Query Evidence, Phase 3B Processing Deferral, Phase 3A Durable API and Persistence Plan, Phase 3A Preflight Evidence (+7 more)

### Community 18 - "Transfer Delivery Plan"
Cohesion: 0.19
Nodes (14): Asynchronous Saga with Narrow Transactions, Documentation-Only Scope, Ethereum-First Chain Sequence, Zelle Share Readiness and Transfer Roadmap Execution Plan, Five-Step Stablecoin Transfer Workflow, Intentional Deferrals, Asynchronous Parent Transfer Resource, Verified Phase 3A Boundary (+6 more)

### Community 19 - "Participant Evidence Mapping"
Cohesion: 0.22
Nodes (8): AssetView, AttemptView, FinalityHistories, FinalityView, TransitionView, FinalityRecord, OperationAttempt, OperationTransition

### Community 20 - "Operation Response Tests"
Cohesion: 0.22
Nodes (5): Arguments, Map, StateChange, Set, Stream

### Community 21 - "Transfer Saga and Persistence"
Cohesion: 0.20
Nodes (14): Append-Only Compensation, Asynchronous Operation Lifecycle, Exact Amount and Unit Representation, Scoped Idempotency Contract, PostgreSQL Durable Acceptance and Outbox, Provider-Neutral Process Manager, Planned Transfer Aggregate, Asynchronous Saga with Narrow Transactions (+6 more)

### Community 22 - "Canonical Command Model"
Cohesion: 0.18
Nodes (4): CanonicalCommand, CanonicalCommandMetadata, PolicyApprovalPort, PolicyDecision

### Community 23 - "Command Canonicalization"
Cohesion: 0.22
Nodes (3): CommandCanonicalizer, CommandDigest, DataOutputStream

### Community 24 - "Repository Governance"
Cohesion: 0.20
Nodes (11): AI-Assisted Engineering Toolchain Plan, Outbox Worker Deferred to Phase 3B, Bounded Autonomous Execution Policy, Visible ASCII Idempotency Key, Participant-scoped Operation Read, Dependency and Native-code Review, Idempotency Key Protection, Non-production Security Boundary (+3 more)

### Community 25 - "PostgreSQL Atomic Outbox"
Cohesion: 0.29
Nodes (11): Atomic Durable Acceptance Transaction, Deterministic Flyway Persistence Schema, Retry-free Idempotency Concurrency, Explicit PostgreSQL Operation Repository, Atomic Acceptance Outbox, Explicit Spring JDBC Mapping, Forward-only Normalized Flyway Schema, PostgreSQL Behavioral Database (+3 more)

### Community 26 - "Financial Lifecycle Review"
Cohesion: 0.18
Nodes (11): Ambiguous Effect Invariant, Exact Quantity Invariant, Idempotency Invariant, Ambiguous-Effect Handling, Exact-Money Types, Durable Workflow Before Effects, Exact Boundary Amounts, Idempotent External Effects (+3 more)

### Community 27 - "Engineering Skill Routing"
Cohesion: 0.20
Nodes (10): Digital Banking Doc Sync Agent Interface, Digital Banking Engineering Agent Interface, Digital Banking Document Synchronization Skill, Document Ownership Matrix, Living Document Synchronization, Digital Banking Engineering Skill, Durable Operation Result Shape, External Response Is Evidence (+2 more)

### Community 28 - "State Invariant Guidance"
Cohesion: 0.20
Nodes (10): Financial State Invariants Agent Interface, Append-Only State Transition, Attempt Identity, Financial and State Invariants Skill, Minimum Financial Invariant Test Matrix, Reconciliation Invariant, Sensitive Data Invariant, Stable Operation Identity (+2 more)

### Community 29 - "Documentation Evidence"
Cohesion: 0.20
Nodes (10): Documentation Evidence Contract, Immutable Reference Publications, Public Case Study Boundary, Salus Read-Only Provenance, Planned Scaffolded Implemented Verified Vocabulary, Digital Banking Graphify Guardrails, Completion Handoff Contents, Fresh Verification Evidence (+2 more)

### Community 30 - "Graphify Usage and Exports"
Cohesion: 0.20
Nodes (10): Commit Hook Integration, Existing Graph Fast Path, Graphify Skill, Graphify Honesty Rules, Graphify Interpreter Guard, Persistent Knowledge Graph, Query Path and Explain, CLAUDE.md Integration (+2 more)

### Community 31 - "Finality and Signer Checks"
Cohesion: 0.22
Nodes (9): Independent Finality Records, Signing Authority Invariant, Architecture and Documentation Synchronization, Durable Asynchronous Mint/Burn, Environment Safety, Four Distinct Finalities, Plain-Java Domain Purity, Signer Isolation (+1 more)

### Community 32 - "Graph Query Navigation"
Cohesion: 0.25
Nodes (9): Breadth-First Graph Traversal, Constrained Graph-Vocabulary Expansion, Depth-First Graph Traversal, Graph-Only Answering, Node Explanation, Query Path and Explain Reference, Reflection Work Memory, Saved Query Feedback Loop (+1 more)

### Community 33 - "OpenAPI Contract Tests"
Cohesion: 0.29
Nodes (3): Class, OpenApiContractTest, SuppressWarnings

### Community 34 - "Planning Prompt Workflow"
Cohesion: 0.25
Nodes (8): Bounded Test-First Slice, Architecture Claim Updates, Authority and Risk Stop Conditions, Bounded Change Plan, Bounded Execution Scope, Failing-Then-Passing Tests, Repository Authority Sources, Repository Prompt Templates

### Community 35 - "Graphify Exports and Labels"
Cohesion: 0.25
Nodes (8): Community Labeling, Graphify Outputs, Extra Exports and Benchmark Reference, Neo4j and FalkorDB Exports, MCP Server Export, Token Reduction Benchmark, SVG and GraphML Exports, Wiki Export

### Community 36 - "Maven Module Boundaries"
Cohesion: 0.33
Nodes (6): No Speculative Adapter Modules, Domain and Application Enforcer Boundaries, Committed Maven Reactor, ADR 0001 Maven Reactor and Module Boundaries, Accepted ADR Lifecycle, Architecture Decision Records

### Community 38 - "Graphify Build Pipeline"
Cohesion: 0.40
Nodes (6): Graphify File Detection, Graph Build Cluster and Analysis, Graph Health Check, Semantic Extraction Cache, Semantic Subagent Extraction, Structural AST Extraction

### Community 39 - "Semantic Extraction Contract"
Cohesion: 0.33
Nodes (6): Extraction Confidence Rubric, Deterministic Node ID Format, Extraction Subagent Prompt Specification, Semantic Similarity Edges, Source File Portability, Sparse Hyperedges

### Community 40 - "Graph Update Portability"
Cohesion: 0.50
Nodes (5): Portable Graph Artifacts, Cluster-only Refresh, Incremental Graph Update, Portable Relative-path Manifest, Replace on Re-extract

### Community 41 - "Publication Provenance"
Cohesion: 0.50
Nodes (4): Immutable Reference Publications Gate, Publication-to-design Traceability, Immutable Publication Provenance, Reference Publications Index

### Community 42 - "Action 05 Execution Gates"
Cohesion: 0.83
Nodes (4): Single Commit and Authorized Push Gate, Execution Evidence Log, Graphify Navigation Artifact Refresh, Validation and Independent Review

### Community 43 - "Java Control Plane Workflow"
Cohesion: 0.50
Nodes (4): Java Spring Control Plane Change Interface, Java and Spring Control-Plane Change, Plain Domain Boundary, Test-first Control-Plane Workflow

### Community 44 - "Plan Execution"
Cohesion: 0.50
Nodes (4): Plan Execution Interface, Plan Evidence States, Plan Execution, Restartable Reviewable Slices

### Community 46 - "Transfer Chains and Finality"
Cohesion: 0.67
Nodes (4): Ethereum Local Transfer Demonstration, Separate Ethereum and Solana Completion Claims, Solana Local Transfer Demonstration, Wallet, Key, and Configuration Safety

### Community 47 - "URL Ingestion and Watch"
Cohesion: 0.50
Nodes (4): URL Add and Folder Watch, Add URL and Watch Folder Reference, Folder Watch, URL Ingestion

### Community 48 - "Cross Repository Graph Merge"
Cohesion: 0.50
Nodes (4): Cross-Repository Graph Merge, GitHub Repository Clone, GitHub Clone and Cross-Repo Merge Reference, Monorepo Subgraph Merge

### Community 49 - "Media Transcription"
Cohesion: 0.50
Nodes (4): Graph-Derived Whisper Prompt, Transcripts as Documents, Video and Audio Transcription Reference, Whisper Transcription

## Knowledge Gaps
- **88 isolated node(s):** `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain`, `Repository Authority Sources`, `Plain-Java Domain Purity` (+83 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **21 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PostgresOperationRepository` connect `Persistence Acceptance Workflow` to `Finality Response Tests`, `Acceptance Concurrency Tests`, `Application Composition`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `EvidenceRef` connect `Operation Aggregate Transitions` to `Participant Evidence Port`, `Chain Ports and Attempts`, `Quantity and Identifier Types`, `Finality Response Tests`, `Signing Request Tests`, `Operation Lifecycle State`, `Operation Attempts and Responses`, `Canonical Command Model`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `TokenOperation` connect `Operation Aggregate Transitions` to `Acceptance Concurrency Tests`, `Chain Ports and Attempts`, `Quantity and Identifier Types`, `Finality Response Tests`, `Operation Lifecycle State`, `Acceptance Context Types`, `Operation Attempts and Responses`, `Operation Response Tests`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **What connects `digital-banking-application`, `OperationAcceptance`, `digital-banking-domain` to the rest of the system?**
  _129 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Persistence Acceptance Workflow` be split into smaller, more focused modules?**
  _Cohesion score 0.06837786661056175 - nodes in this community are weakly interconnected._
- **Should `Persistence API Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.06442058496853018 - nodes in this community are weakly interconnected._
- **Should `Acceptance Concurrency Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.09014675052410902 - nodes in this community are weakly interconnected._
