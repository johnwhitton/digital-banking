# ADR 0010: Native Solana toolchain and classic SPL Token semantics

- Status: accepted
- Date: 2026-07-19
- Owners: repository owner; control-plane and Solana adapter engineering
- Supersedes: None
- Superseded by: None

## Context

ADR 0003 selects native SVM semantics and classic SPL Token first, but it does not select an executable local toolchain or prove the concrete mint, token-account, transfer, redemption-custody, burn, blockhash, signature, slot, and commitment behavior needed before a Java adapter is introduced.

The exact approved Linux/amd64 Agave 4.1.2 container built successfully, including `spl-token-cli 5.6.1`, but the validator exited before RPC startup because Docker Desktop's x86 emulation on the Apple Silicon host did not expose required AVX support. The official native `aarch64-apple-darwin` release runs on the target host. The current business requirement needs no Token-2022 extension and no custom program.

The pinned SPL CLI's online path resolves its own fresh recent blockhash. It also accepts decimal UI input that must not define the future adapter's exact-quantity boundary. The gate must therefore assert parsed checked instructions and on-chain integer amounts, distinguish the transaction's actual recent blockhash from a pre-submission expiry-window observation, and carry those constraints forward to Phase 7B.

## Decision

- Use the official native Anza Agave `v4.1.2` Apple Silicon archive and repository-local `spl-token-cli 5.6.1`, both verified by complete SHA-256 before installation under ignored `.solana-tools/`. Use the approved existing Rust/Cargo 1.96.0 toolchain only; install nothing globally.
- Bind the disposable validator only to loopback and keep generated Ed25519 keys under ignored mode-`0700` `.solana-runtime/` directories with mode-`0600` files. Reject symlinks and symlinked parents for controlled tool/runtime paths. Signal only the saved PID whose exact command, scoped ledger, and saved RPC identity match; a pre-identity failure may clean up only the exact process recorded as starting. No private material or credential enters source, command output, or evidence.
- Use the original Token Program `TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA` and canonical Associated Token Account Program `ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL`. Do not enable Token-2022, freeze authority, permanent delegate, transfer hooks, confidential transfer, metadata, or another extension.
- ADMIN is the mint authority. A distinct ADMIN redemption wallet owns the redemption associated token account. USER_1 and USER_2 own their canonical associated token accounts. Token-account ownership authorizes transfer and burn; no EVM-shaped role or caller-selected wallet is introduced.
- Prove `100.00 USDZELLE` as exactly the decimal string `100`, mint decimals `2`, and parsed on-chain atomic amount `10000` in one `mintToChecked`, two `transferChecked`, and one `burnChecked` instruction. Verify exact supply and every account balance at each boundary and zero supply/balances after burn.
- Reject unauthorized mint, transfer, and burn attempts plus an account/mint mismatch using a second valid classic-SPL mint. Accept each negative proof only when its stable native error matches the expected authority or mint/account classification, record that sanitized classification, and prove both mint supplies and all balances remain unchanged. The CLI is only a semantic harness: future Java code must resolve decimal input through the versioned asset/unit catalog, construct integer atomic quantities without floating point or CLI UI parsing, and reject excess precision before native message construction.
- Persist sanitized gate evidence for actual signatures, transaction recent blockhashes, slots, finalized commitment, checked instruction types, and exact quantities. Also record whether each actual blockhash remains valid at observation plus the latest pre-submission blockhash and `lastValidBlockHeight` window. Phase 7B must own and persist the exact chosen blockhash and last-valid height before signing; an ambiguous response reuses the already-derived signature during that lifetime and creates a new attempt only after expiry requires a new message/signature.
- Add no Java dependency, Java adapter module, API, migration, custom Rust/SBF program, Neon runtime, public network, Compose service, or production custody claim. Sava remains the provisional Phase 7B Java-client candidate and requires a separately authorized executable compatibility gate before dependency acceptance.

## Alternatives considered

### Keep the Linux/amd64 container

Rejected on the approved host. The exact validator binary demonstrated an AVX requirement that the emulator cannot satisfy; ports, health checks, retries, or Compose networking cannot correct missing CPU instructions.

### Build Agave from source or add another image

Rejected for this slice. The official native archive is smaller, verified, and sufficient. A source build or substitute image adds toolchain, provenance, cache, and maintenance surface without changing the semantics under test.

### Token-2022 or a custom Rust program

Rejected until a concrete extension or on-chain invariant requires it. Classic SPL Token and canonical associated accounts enforce the approved mint, transfer, custody, and owner-authorized burn behavior.

### Neon or EVM-shaped Solana roles

Rejected by ADR 0003. Native accounts, owners, instructions, Ed25519 signatures, recent blockhashes, slots, and commitments must stay visible rather than being flattened into Ethereum concepts.

## Consequences

- Phase 7A has a deterministic local semantic proof without adding a chain adapter or changing either product API.
- The Ethereum Compose environment remains byte-for-byte unchanged and the Solana gate has its own scoped ignored state and reset.
- Classic SPL behavior is sufficient for the next mint-parity slice; any extension or custom program now requires new evidence and an ADR.
- The semantic harness exposes a useful limitation: checked on-chain amounts are authoritative, while future application input conversion cannot delegate exactness to the SPL CLI's decimal parser.
- Phase 7B can evaluate Java clients against a concrete native message/evidence contract without treating the CLI, RPC response, signature, or commitment as business truth.

## Validation

```bash
sh -n scripts/solana/*.sh
scripts/solana/bootstrap.sh
scripts/solana/semantic-gate.sh --yes
scripts/solana/status.sh --json
git diff --check
```

The semantic gate must pass twice from scoped clean reset state. Each run must prove the exact checked instruction sequence and quantities, canonical account/program ownership, classified authority/wrong-mint failures with unchanged state, actual signature/blockhash/slot identity, live blockhash validity observation, finalized commitment, zero final supply/balances, restrictive key/evidence modes, and safe validator cleanup. Maven is not part of this gate because Phase 7A changes no Maven-controlled source or build file.
