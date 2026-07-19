# Native local Solana gates

These scripts establish the Phase 7A native-SVM semantic gate and the public fixture boundary for Phase 7B's opt-in Java mint on the approved Apple Silicon development host. They do not change a business API, use a public Solana cluster, or establish production custody or settlement.

## Pinned provenance

| Artifact | Immutable identity | Source | License and use |
| --- | --- | --- | --- |
| Anza Agave CLI and local validator | `v4.1.2`, `solana-release-aarch64-apple-darwin.tar.bz2`, SHA-256 `51a44318e6fb8be0cfa69cdfdb3252f4c76a5eb2866740694e91de3d2fc5a75b` | <https://github.com/anza-xyz/agave/releases/tag/v4.1.2> | Apache-2.0; supplies only the native `solana`, `solana-keygen`, and loopback `solana-test-validator` commands. |
| SPL Token CLI | `spl-token-cli 5.6.1` crate, SHA-256 `b6d6ae3d3c38f4b85eee2762fb1e8da27f789c08f0b22f3c4b77c0593afdffa3` | <https://crates.io/crates/spl-token-cli/5.6.1> | Apache-2.0; built from its published locked dependency graph with the approved existing Rust/Cargo 1.96.0 toolchain. |

Bootstrap downloads only those two approved top-level artifacts, verifies both complete SHA-256 values before use, and installs them only under ignored mode-`0700` `.solana-tools/`. Cargo's repository-local home verifies the lock-pinned transitive crate checksums. Nothing is installed into the user toolchain or a global path. The earlier Linux/amd64 container was rejected after the exact built Agave binary reported that Docker Desktop emulation lacked required AVX support; the root Compose topology therefore remains unchanged.

## Commands and state

Run on Darwin `arm64` as a non-root user with the exact Rust/Cargo 1.96.0 toolchain, `curl`, `jq`, `shasum`, and `tar`:

```bash
scripts/solana/bootstrap.sh
scripts/solana/status.sh --json
scripts/solana/phase7b-fixture.sh
scripts/solana/semantic-gate.sh --yes
scripts/solana/stop.sh
scripts/solana/reset.sh --yes
```

`bootstrap.sh` is idempotent. It verifies or installs the pinned tools, generates only missing disposable local Ed25519 keypairs, enforces directory mode `0700` and key/file mode `0600`, and binds validator RPC only to `127.0.0.1:8899`. All controlled paths reject symlinks and symlinked parents before writes or removal. Startup records the exact validator PID/command before readiness; status and cleanup require that command plus the saved RPC identity, while a failed pre-identity startup can stop only the exact recorded process. A normal terminal keeps that validator available until `stop.sh` or confirmed reset.

`semantic-gate.sh --yes` starts from a scoped clean `.solana-runtime/`, proves one classic SPL Token mint/transfer/redemption/burn path with exact `10000` base units at two decimals, checks canonical associated token accounts and finalized native evidence, and creates a second valid classic-SPL mint for the wrong-mint/account failure. It accepts negative cases only when their stable native error classification matches the expected authority or account-mint boundary, proves both mint supplies and all balances remain unchanged, writes a sanitized ignored evidence summary, then stops the validator. `reset.sh --yes` removes only ignored `.solana-runtime/`; it preserves `.solana-tools/`, Ethereum state, images, dependency caches, source, Git data, and `.env.local-anvil`.

`phase7b-fixture.sh` requires the scoped validator to be running on loopback and a fresh ledger. It funds only the disposable fee payer, creates the configured classic two-decimal mint with zero supply and no pre-created destination account, then prints only the public cluster, mint, destination-owner, fee-payer, and mint-authority identities needed by the conditional Java integration gate or `local-solana` profile. Use an absolute `LOCAL_SOLANA_RUNTIME_ROOT` and key-file paths when Maven or the packaged application runs from a module or another working directory. The profile additionally requires private/local PostgreSQL and the existing participant identity boundary; it does not create a public signing or chain-control endpoint.

The focused Java gate is disabled unless `LOCAL_SOLANA_REAL_GATE=true` and all printed public identities are supplied explicitly. It uses the unchanged mint API/lifecycle, persists the recent blockhash before signing, forces one lost submission response, reconstructs the signer/adapter/handler, observes by the retained public signature, and asserts exactly one finalized `10000`-atomic-unit effect. Stop the validator afterward with `scripts/solana/stop.sh`; rerun `bootstrap.sh` before another fixture because bootstrap replaces only the ignored local ledger.

Private key material stays under ignored `.solana-runtime/keys/`, is never printed, and must never be staged. Under `local-solana`, only the isolated local signer reads those mode-`0600` files; it verifies the configured public identity and role, signs the exact bounded message with JDK Ed25519, and gives Sava only public signature material. The signer retains only a bound public signature outcome under ignored `.solana-runtime/signing-results/` using directory mode `0700` and file mode `0600`; restart inquiry re-verifies that signature against the exact durable message and never signs again. The retained evidence contains public local identities, signatures, slots, blockhash observations, instruction types, and exact balances only. The scripts and Java profile contain no mainnet, devnet, testnet, hosted RPC, credential, real asset, or funded production address.
