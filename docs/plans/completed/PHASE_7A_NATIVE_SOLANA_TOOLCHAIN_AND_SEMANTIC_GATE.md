# Phase 7A — Native Solana Toolchain and Semantic Gate

Status: complete — verified native Apple Silicon semantic gate ready for closeout

## Outcome

Establish a reproducible, local-only native Solana/SVM tooling and semantic gate. The gate will prove the approved two-decimal USDZELLE mint, wallet-transfer, redemption-custody, and burn semantics with canonical SPL programs. It will not add a Java Solana adapter, public API, database migration, custom program, public-network access, or production custody.

## Authority and scope map

- Approved baseline and remote: `f744fe619de0d6cf1fc295cd4116e880aa00d803` on `main`, `git@github.com:johnwhitton/digital-banking.git`.
- Action Request 22 is the owning specification.
- `AGENTS.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` govern architecture and safety.
- ADR 0003 preserves native-chain mechanics behind adapters; ADR 0006 preserves distinct authority and signing boundaries; ADR 0008 preserves wallet-role resolution and immutable native attempt context.
- The Phase 6D completed plan and existing Compose/demo scripts govern compatibility with the Ethereum demo.
- Existing Graphify output is advisory navigation only. Direct sources and executable evidence govern this slice.
- The four reference PDFs are immutable contextual inputs. The Salus repository is out of scope and must not be accessed.

## Preflight evidence

Completed on 2026-07-18 before edits:

- `pwd` returned `/Users/johnwhitton/dev/johnwhitton/digital-banking`.
- Local `main`, `HEAD`, `origin/main`, and fetched remote `main` all equaled the approved baseline.
- `git status --short --branch` returned only `## main...origin/main`; the index and tracked worktree were clean.
- `origin` matched the approved SSH remote.
- The four PDFs retained their baseline modes and Git blob identities:
  - `digital-banking-engineering-companion.pdf`: `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`
  - `digital-banking-reference-implementation.pdf`: `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`
  - `stablecoin-settlement-reference-architecture.pdf`: `ebe456d6e71685aca63312c8d7466f17a2b86828`
  - `stablecoin-settlement-executive-brief.pdf`: `ae25c88118b3bb8356784d2f0f02f1096b034331`
- `.env.local-anvil` remained ignored, untracked, unstaged, mode `0600`, and was not read or printed.
- No repository Solana key or runtime material was present.
- Host: `Darwin arm64`. Existing Rust/Cargo: `cargo 1.96.0 (30a34c682 2026-05-25)`, host `aarch64-apple-darwin`.
- `solana`, `solana-test-validator`, and `spl-token` were not installed. No Solana/Agave Docker image or requested crate archive was cached. Docker was available; ShellCheck was not installed.
- Existing Compose, demo scripts, signer/wallet/chain ports, repository skills/prompts, living docs, accepted ADRs, plan guidance, and Phase 6D evidence were inspected once. No governing source requires a freeze authority or an Ethereum-shaped Solana boundary.

## Primary-source findings and proposed versions

### Validator and CLI

- Selected release: Anza Agave `v4.1.2`, published 2026-07-10 from the official Apache-2.0 repository and release page.
- Official Linux artifact: `solana-release-x86_64-unknown-linux-gnu.tar.bz2`, 218,318,551 bytes, SHA-256 `5991d027a686eb419a709a479178b33eb83501e8a2bfbf599a81a286bfcbf770`.
- Official native Apple Silicon artifact exists (`solana-release-aarch64-apple-darwin.tar.bz2`, SHA-256 `51a44318e6fb8be0cfa69cdfdb3252f4c76a5eb2866740694e91de3d2fc5a75b`). It was not part of the initial container proposal; the user later approved it as the exact native fallback after the measured AVX incompatibility below.
- The release publishes platform archives and installers, not an official multi-architecture validator runtime image. Repository and release searches found no official Anza image suitable for this gate.

### SPL Token tooling and program identifiers

- Selected CLI: `spl-token-cli 5.6.1`, published by the Anza team on crates.io, Apache-2.0, crate SHA-256 `b6d6ae3d3c38f4b85eee2762fb1e8da27f789c08f0b22f3c4b77c0593afdffa3`.
- The CLI declares Solana client `^4.0.0-rc.0` and Solana SDK `^3.0.0` families, which aligns with the Agave 4.x RPC/runtime family. Its `--locked` package lock will pin transitive crate versions and checksums during the image build.
- Original Token Program: `TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA`.
- Associated Token Account Program: `ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL`.
- Token-2022: `TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb` (comparison only; not selected or enabled).

### Java client recommendation (no artifact requested in Phase 7A)

- Provisional Phase 7B recommendation: community-supported Sava `25.8.0`, Apache-2.0, present in Maven Central as `software.sava:sava-core:25.8.0`; its POM has one runtime dependency, Bouncy Castle `bcprov-jdk18on 1.85`.
- Sava is named by Solana's community Java client documentation and exposes byte-oriented transaction/RPC primitives compatible with the repository's separate signing-authority boundary. Native program clients are separately published as `software.sava:solana-programs` (latest observed `25.0.2`), so exact Phase 7B coordinates must be compatibility-tested before approval.
- Credible alternative `com.mmorrell:solanaj:1.28.0` is MIT-licensed and actively released, but its combined RPC/transaction/key surface and legacy RPC examples are less aligned with exact serialization, current blockhash lifetime, signer isolation, and independent observation requirements.
- No Java dependency is authorized or proposed for Phase 7A. Community documentation is not a first-party Java SDK compatibility guarantee.

## Preliminary decisions to prove and record in ADR 0010

- Use native SVM transactions, Ed25519 signatures, recent blockhashes, slots, and commitments; reject Neon/EVM compatibility.
- Use the original Token Program. The approved semantics require no Token-2022 extension, while Token-2022 would add account-size, extension, client-decoding, compatibility, and operational complexity.
- Use canonical associated token accounts and checked mint/transfer/burn instructions.
- ADMIN is mint authority and owns the redemption associated token account. Default to no freeze authority. Token-account owner/delegate semantics govern transfer and burn; no permanent delegate simulates an EVM role.
- Use exact integer base units: `100.00 USDZELLE` is `10000` with mint decimals `2`.
- No custom Rust/SBF program is needed because the canonical Token and Associated Token Account programs supply the approved behavior. Rust, Anchor, Pinocchio, Codama, and custom program IDs remain out of scope.
- Future durable adapters must retain native message/signature/blockhash attempt lineage, inquire by an already-derived signature after ambiguous submission, and create a new native attempt only after blockhash expiry requires a new message/signature.

## Required artifact/image approval checkpoint

At the checkpoint, no image, release archive, or crate had been pulled, downloaded, or installed. The user approved the following exact repository-controlled image proposal on 2026-07-18 with `Approved`:

| Item | Immutable identity | Source and license | Architecture/cache | Required use |
| --- | --- | --- | --- | --- |
| Official Rust builder/runtime image | `docker.io/library/rust:1.96.0-bookworm@sha256:5e2214abe154fe26e39f64488952e5c991eeed1d6d6da7cc8381ae83927f0cfc` (multi-architecture index); selected Linux/amd64 manifest `sha256:c993d32d95cc146bd12c84d66f0b924a6a96f3988325f39c144f2f9893dea120` | Docker Official Image from `rust-lang/docker-rust`; Rust is MIT OR Apache-2.0 and bundled Debian components retain their upstream licenses | Multi-architecture image, used as Linux/amd64; not cached | One base image for building the pinned SPL CLI and running the official Agave Linux binaries, avoiding additional base images and unpinned OS package installation |
| Debian `libudev-dev` | `252.39-1~deb12u2`, amd64 package SHA-256 `b8795a05b96799cb25ed3677eef8e8b1f1facdf07ad4527e06ef8f82ca164fbd` | Official Debian Bookworm `systemd` source package; LGPL-2.1-or-later | Linux/amd64; unavailable until the base image is pulled, therefore not cached | Supplies the development link required by the SPL CLI's default `solana-remote-wallet`/`hidapi` Linux build feature |
| Debian `libudev1` | `252.39-1~deb12u2`, amd64 package SHA-256 `6f7e08f11c4809d2e5998c775f7b423ab5ec2ce0309494b4a2615037052245e2` | Official Debian Bookworm `systemd` source package; LGPL-2.1-or-later | Linux/amd64; unavailable until the base image is pulled, therefore not cached | Exact runtime dependency of the pinned `libudev-dev` package and built SPL CLI |
| Agave release archive | `v4.1.2`, `solana-release-x86_64-unknown-linux-gnu.tar.bz2`, SHA-256 `5991d027a686eb419a709a479178b33eb83501e8a2bfbf599a81a286bfcbf770` | `anza-xyz/agave` official GitHub release; Apache-2.0 | Linux/amd64; not cached. Apple Silicon host support is through Docker Desktop's Linux/amd64 emulation | Supplies the exact `solana`, `solana-keygen`, and `solana-test-validator` release toolchain |
| SPL Token CLI crate | `spl-token-cli 5.6.1`, SHA-256 `b6d6ae3d3c38f4b85eee2762fb1e8da27f789c08f0b22f3c4b77c0593afdffa3` | Official Anza-team crates.io publication from `solana-program/token-2022`; Apache-2.0 | Built for Linux/amd64 inside the pinned image; not cached | Supplies the canonical checked token CLI used by the positive and negative semantic proof |

If approved, the repository Dockerfile will use only that digest-pinned Rust base, install only the two exact Debian packages above, verify the Agave archive checksum, and install exactly `spl-token-cli =5.6.1` with Cargo's locked dependency graph. The build will contact only Docker Hub for the approved image, Debian's official Bookworm repository for those two packages, GitHub Releases for the approved Agave archive, and crates.io for the approved crate and its lock-pinned transitive crates. It will not pull Ubuntu, a separate Debian image, a third-party validator image, Java artifacts, JavaScript tooling, or additional images. No user-global installation will occur.

## Initial container implementation plan (superseded by measured host incompatibility)

The steps below preserve the first approved proposal as historical evidence. The container source changes were removed after the exact validator failed its runtime feasibility gate; none are present in the final diff.

1. Add a minimal repository-controlled `infra/solana/Dockerfile` and provenance README, with a Linux/amd64 image built from the approved artifacts.
2. Add an optional `solana` Compose profile. Preserve default and Ethereum profiles; bind host RPC only to `127.0.0.1:8899`.
3. Add focused `scripts/solana/` bootstrap, semantic gate, status, stop, and confirmed reset behavior, reusing repository demo conventions where their semantics remain valid.
4. Generate fee-payer, ADMIN/mint-authority, ADMIN-redemption, USER_1, and USER_2 Ed25519 keypairs only in `.demo-runtime/solana/keys/` with directory mode `0700` and file mode `0600`; never print or stage private material.
5. Use test-first shell assertions for checked instructions, exact supply/balances, authority failures, wrong mint/decimals, canonical ATA stability/ownership, loopback RPC, native signatures/slots/commitments, and final zero state.
6. Prove bootstrap idempotency and run the clean semantic flow exactly twice after first success.
7. Add ADR 0010 and synchronize only the required living documents and plan indexes.
8. On the stable diff, run shell/Compose/Markdown/security checks, the smallest Ethereum configuration/readiness smoke, one Ponytail review, one independent review, and one Graphify attempt capped at 60 seconds. Maven is not applicable unless a Maven-controlled file changes.
9. Complete the plan, enforce the staging allowlist, create the single approved commit, push non-force, and verify clean remote synchronization.

## Container feasibility result and revised approval checkpoint

The exact approved image built successfully. BuildKit verified both Debian package checksums and the Agave archive checksum; `spl-token-cli 5.6.1` compiled from its locked graph. Image self-checks returned:

- `solana-cli 4.1.2 (src:182084b8; feat:c763ae0a, client:Agave)`
- `solana-test-validator 4.1.2 (src:182084b8; feat:c763ae0a, client:Agave)`
- `spl-token-cli 5.6.1`

The runtime gate then failed consistently before RPC startup. The validator's own log reported `Incompatible CPU detected: missing AVX support. Please build from source on the target`. The host is `Darwin arm64`; the approved official Linux artifact is x86_64 and Docker Desktop's emulation does not expose the AVX capability required by this Agave build. Compose health checks consequently received connection failures at the container's loopback RPC endpoint. Retrying, changing ports, or changing Compose networking cannot correct a CPU-instruction incompatibility.

The user approved the native fallback on 2026-07-19 with `Approved`. The smallest safe fallback is repository-local native tooling on the existing Apple Silicon host:

- add the official Agave `v4.1.2` `solana-release-aarch64-apple-darwin.tar.bz2`, SHA-256 `51a44318e6fb8be0cfa69cdfdb3252f4c76a5eb2866740694e91de3d2fc5a75b`, Apache-2.0, from the already inspected official release;
- build the already approved `spl-token-cli 5.6.1` crate natively with existing `cargo 1.96.0` into ignored `.solana-tools/`, using a repository-local `CARGO_HOME`; and
- remove the unusable container/Compose source changes, retain the Ethereum Compose file semantically unchanged, and run the validator explicitly on `127.0.0.1` through scoped native scripts.

No new image, package, Java dependency, JavaScript runtime, global installation, public network, or substitute version was proposed. The user approved the exact native archive and repository-local fallback on 2026-07-19.

## Implemented native design

- `.solana-tools/` is ignored mode-`0700` repository-local tooling state. Bootstrap downloads only the approved Agave and SPL crate top-level artifacts, verifies their complete SHA-256 values, requires the exact existing Rust/Cargo 1.96.0 toolchain, extracts Agave, and builds the SPL CLI from its published lockfile without global installation.
- `.solana-runtime/` is ignored disposable state. Bootstrap generates only missing fee-payer, mint-authority, redemption-owner, USER_1, USER_2, primary-mint, and negative-fixture-mint keypairs under `0700` directories/`0600` files, writes only public identities separately, and binds validator RPC to `127.0.0.1:8899`.
- Bootstrap/status/stop/reset are POSIX shell commands. All controlled paths reject symlinks and symlinked parents before writes/removal. Reset requires `--yes`, resolves the exact repository runtime path, verifies the saved PID's exact validator command/scoped ledger and RPC identity before signaling it, and removes only `.solana-runtime/`. Pre-identity startup cleanup is permitted only for that exact recorded starting process. It preserves tools, Ethereum state, images, caches, source, Git data, and `.env.local-anvil`.
- The semantic gate resets scoped runtime state, provisions the pinned tools/keys, runs the validator inside the gate lifetime, and stops it after sanitized evidence is complete. This also avoids leaving a daemon behind in managed execution environments that reap descendant processes when a command exits.
- Validator startup pre-creates its mode-`0600` log before forking, records a starting PID, and publishes the RPC identity only through validated temporary files plus atomic rename. A failed identity query leaves no empty identity and retains startup ownership for exact cleanup.
- The original Token Program and canonical Associated Token Account Program create one two-decimal USDZELLE mint and the distinct ADMIN-redemption, USER_1, and USER_2 associated accounts. ADMIN alone mints; token-account owners alone transfer/burn; no freeze authority, extension, delegate, Neon, or custom program exists.
- Positive execution proves exactly `10000` base units in `mintToChecked`, USER_1-to-USER_2 `transferChecked`, USER_2-to-ADMIN-redemption `transferChecked`, and ADMIN-owner `burnChecked`. It records actual signatures/recent blockhashes/slots/finalized commitment, actual-blockhash validity at observation, the pre-submission latest-blockhash/`lastValidBlockHeight` window, and exact balances/supply at each boundary.
- Negative execution rejects unauthorized mint, transfer, and burn plus a valid-second-mint account mismatch. Every failure must match its stable authority/account-mint classification before its sanitized outcome is recorded, and the proof confirms both supplies and all balances remain unchanged. The pinned CLI accepted an extra-precision UI input instead of rejecting it; therefore CLI decimal parsing is explicitly not the future exactness boundary. Phase 7B must reject excess precision through the versioned asset/unit catalog and construct integer atomic quantities.
- `scripts/solana/README.md` records commands, artifact provenance/licenses, modes, evidence, and cleanup. ADR 0010 records the accepted classic-SPL/toolchain/authority/exactness/blockhash decisions. README, design, implementation roadmap, transfer-demo contract, ADR index, and this plan are synchronized; no API, Java, Maven, database, OpenAPI, Compose, Rust program, PDF, or Graphify file changes.

## Validation ledger

| State | Command/check | Result |
| --- | --- | --- |
| passed | Git/remote/baseline preflight | All authorities equal `f744fe619de0d6cf1fc295cd4116e880aa00d803`; clean tracked state |
| passed | PDF Git mode/blob preservation | Four baseline blob identities unchanged; no PDF content opened or hashed |
| passed | Local tool and image inventory | No Solana tools or cached Solana/Agave images; selected Rust image and SPL CLI crate not cached |
| passed | Primary-source metadata inspection | Exact Agave release asset digest, image manifests, SPL crate checksum/dependencies, Sava release/Maven metadata recorded |
| passed | Artifact/image approval | User approved the exact recorded artifact set on 2026-07-18; no substitutions or additional images are authorized |
| passed | Approved image build | Exact checksums and CLI versions passed; local image manifest list `sha256:acaaa564dd4e74a62b9ee728b5d29e412a1b3a6d2977f642013474e4163f7db6` |
| failed | Container runtime feasibility | Agave exited before RPC startup because x86_64 emulation lacks required AVX support; port/network changes are not relevant |
| passed | Native Apple Silicon artifact approval | User approved the exact official aarch64 archive and repository-local build fallback on 2026-07-19 |
| passed | Preliminary semantic proof | One pre-review run completed the positive flow; its evidence was treated as exploratory because later review changed the harness |
| passed | One Ponytail stable-diff review | No speculative module, dependency, wrapper, custom program, or other in-scope simplification was found |
| passed | One independent stable-diff review | Reported one Critical, three Important, and two Minor findings; all valid findings were remediated without a second review cycle |
| passed | Review-remediation safety checks | Direct and parent-symlink probes were refused; exact PID/command/RPC ownership, failure cleanup, all native-tool idempotency checks, valid wrong-mint fixture, classified negative outcomes, and restrictive log/state/evidence modes are enforced |
| failed | First post-review focused semantic attempt | Valid wrong-mint rejection returned stable native `AccountInvalidMint`; the expected classification was corrected, the scoped validator stopped safely, and this run did not count |
| passed | Atomic pre-identity failure check | A simulated RPC identity failure retained `validator.starting` and created neither final nor temporary identity/response files |
| failed | Repetition after atomic-identity change | The first run passed; the second exposed a background-child race in which `validator.log` was not yet present for parent-side `chmod`; exact startup cleanup stopped the process and left failed/stopped evidence |
| passed | Focused launch remediation | Pre-creating mode-`0600` `validator.log` removed the race; standalone bootstrap reported healthy, then the managed runner reaped its child and status truthfully reported stale before scoped stop cleared metadata |
| passed | Final `scripts/solana/semantic-gate.sh --yes` pair | Two independent clean-reset runs on the unchanged final script revision returned exit `0` and passed the exact checked `10000`-base-unit mint/transfers/burn, four classified negative cases, finalized evidence, zero primary/fixture supply and balances, `0700`/`0600` modes, no transient output, and stopped state |
| passed | Shell/reset/ignored-state checks | `sh -n` passed all six executable scripts; unconfirmed reset refused; `.solana-tools/`, `.solana-runtime/`, and `.env.local-anvil` remain ignored/unindexed; `.env.local-anvil` remains mode `0600` and unread |
| passed | Ethereum compatibility smoke | `compose.yaml`, Docker/demo/contract/Maven/Java source remained byte-for-byte unchanged; Compose config parsed with safe placeholders. Cache inventory found only the pinned Foundry image, so no unapproved pull, control-plane rebuild, or runtime start was attempted |
| passed | Focused security/integrity scans | No public Solana RPC URL, private-key literal, raw key output, non-loopback bind, PDF change, Maven-controlled change, executable-bit surprise, or Graphify artifact entered the diff; the four PDFs retain baseline mode/blob identities |
| tooling_deferred | Sole `/opt/homebrew/bin/gtimeout 60 graphify . --update --no-viz` attempt | Stopped immediately because 117 documentation/publication/image inputs require an unavailable LLM API key; no tracked Graphify artifact or `.graphify_*` transient changed, and the attempt was not retried |
| passed | Markdown links and remote fence | All eight changed Markdown files resolve their local targets; fresh fetch left `HEAD`, `origin/main`, and `FETCH_HEAD` equal to approved baseline `f744fe619de0d6cf1fc295cd4116e880aa00d803` on the approved SSH origin. Exact allowlist staging, cached-diff inspection, the single commit, push, and synchronization check follow this plan freeze |

## Intentional deferrals

- Phase 7B: separately approve and implement the smallest Java Solana adapter compatibility slice, including exact Sava program-client coordinates.
- Durable Solana operation persistence, APIs, orchestration, and reconciliation remain future authorized slices.
- Token-2022 extensions, freeze behavior, custom programs, production signing/custody, public networks, and parity claims are not implied by this gate.

## Closeout decisions

- Maven was not run: no Maven wrapper, POM, Java, Spring, database, OpenAPI, contract, or other Maven-controlled file changed. The Phase 6D 503-test offline reactor remains the latest full-build evidence.
- The sole Ponytail and independent reviews are complete. No second review or remediation cycle was started.
- The root Compose topology remains unchanged. The native Solana gate has its own ignored tooling/runtime state and performed no public-network transaction.
- No PDF was opened, rendered, extracted, regenerated, or staged. No Salus repository or asset was accessed.
- Phase 7B remains the next bounded recommendation: prove Java 25 client compatibility and the smallest native Solana mint-parity adapter without accepting a dependency, API, public network, production custody, or broader product path in this action.

## Source record

- <https://github.com/anza-xyz/agave/releases/tag/v4.1.2>
- <https://github.com/anza-xyz/agave>
- <https://crates.io/crates/spl-token-cli/5.6.1>
- <https://github.com/solana-program/token-2022>
- <https://solana.com/docs/tokens/basics>
- <https://solana.com/docs/clients/community/java>
- <https://github.com/sava-software/sava>
- <https://repo1.maven.org/maven2/software/sava/sava-core/maven-metadata.xml>
- <https://github.com/skynetcap/solanaj>
