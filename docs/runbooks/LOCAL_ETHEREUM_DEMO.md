# Local Ethereum Demo Runbook

## Purpose and safety boundary

This runbook operates the Phase 6D synthetic, local-only Ethereum environment. Demo A proves the configured user-held acquisition/hold/redemption lifecycle. Demo B proves the configured settlement-only transfer. Both use fake bank accounts, publicly known disposable Anvil development identities, exact `USD 100.00`, and local chain ID `31337`.

Do not use real funds, personal data, production credentials, a public testnet/mainnet, a hosted RPC endpoint, or a funded address. These demonstrations prove bounded technical workflow, evidence, restart, and reconciliation behavior; they do not establish real banking, reserves, legal/accounting/customer finality, compliance, or production readiness.

## Approved prerequisites and images

Run from the repository root on macOS or Linux with:

- Docker Engine and Docker Compose v2;
- JDK 25 (`JAVA_HOME=/opt/homebrew/opt/openjdk` on the verified workstation);
- the executable committed Maven wrapper;
- Foundry `forge` and `cast` 1.5.1 plus locally available Solidity 0.8.25 for `forge build --offline`;
- `curl`, `jq`, and OpenSSL; and
- a non-root host user so the mounted-secret runtime identity cannot resolve to UID `0`; and
- the ignored `.env.local-anvil` described below.

The environment is pinned to exactly these approved multi-platform image indexes. Compose uses `pull_policy: never`. Bootstrap requires the approved PostgreSQL reference to be present in the local cache and never pulls it; only an absent approved Foundry or Temurin reference may be pulled.

| Purpose | Exact image | License |
| --- | --- | --- |
| PostgreSQL | `postgres:17.10-alpine3.23@sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4` | PostgreSQL License |
| Foundry/Anvil/deployer | `ghcr.io/foundry-rs/foundry:v1.5.1@sha256:3a70bfa9bd2c732a767bb60d12c8770b40e8f9b6cca28efc4b12b1be81c7f28e` | MIT and Apache-2.0 |
| Java runtime | `eclipse-temurin:25.0.2_10-jre-alpine-3.23@sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888` | GPLv2 with Classpath Exception; applicable container-source notices include Apache-2.0 material |

Do not substitute a tag, pull another image, install a package, or enable a public registry/network fallback without separate approval.

## Environment handoff

Only one local demo environment may run at a time. Before starting Ethereum,
preserve and stop the Solana environment explicitly:

```bash
scripts/demo/solana/stop.sh
```

Neither Ethereum start nor reset stops Solana automatically. If the other
environment is reported as running, the corrective action is the command above,
followed by a new Ethereum start. Use reset only when destructive removal of
the selected environment's named state is intended.

## Prepare local identities

Copy the safe field names and expected public addresses, then populate only the standard publicly known Anvil development fixture keys through a trusted local method:

```bash
cp .env.example .env.local-anvil
chmod 600 .env.local-anvil
```

The file must contain exactly the ten required mappings for `CONTRACT_OWNER`, `ADMIN`, four bank identities, and four user-custody identities. Keep the expected addresses unchanged. Do not print, paste into a command line, log, attach, stage, or commit the values. Never fund these addresses or reuse the file outside this disposable chain.

Confirm Git ignores it and the prerequisite check passes:

```bash
git check-ignore .env.local-anvil
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/demo/prerequisites.sh
```

Bootstrap generates random PostgreSQL, sender-bearer, operator-bearer, and run identity files only when missing. They remain under ignored mode-`0700` `.demo-runtime/`; secret files use mode `0600`. No value should be displayed.

## Start and inspect

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/demo/start.sh
scripts/demo/status.sh
```

`start.sh` performs the offline contract build, packages the application with tests skipped, verifies the exact cached/approved images, builds the minimal runtime image, starts the named environment, deploys or verifies the contract, waits for readiness, and confirms exact loopback bindings. It does not reset an existing database or chain volume.

The expected services are healthy PostgreSQL, Anvil, and control plane; the one-shot deployer is successfully exited. Host ports are exactly `127.0.0.1:8080` and `127.0.0.1:8545`. `status.sh` prints only redacted aggregate bank, ledger, custody, supply, effect-count, latest-workflow, ordering, reconciliation, and local contract/network evidence.

## What start creates and where state lives

| Runtime or state | Created or used by start | Persistence boundary |
| --- | --- | --- |
| PostgreSQL | Docker service on the named internal network | Named Phase 6D database volume; ordinary stop preserves it |
| Anvil | Docker service with the fixed local genesis | Named Phase 6D chain volume; ordinary stop preserves it |
| Contract deployer | One-shot Foundry container | Deploys or verifies `LocalReferenceToken` and ADMIN roles, then exits |
| Spring control plane | Packaged non-root Docker service | Durable business state remains in PostgreSQL |
| Runtime identity/evidence | Generated credentials, run identity, logs, and redacted summaries | Ignored `.demo-runtime/`; ordinary stop preserves it |
| Local fixture keys | Read from ignored `.env.local-anvil` | Preserved by both stop and reset |

`scripts/demo/stop.sh` stops the named services and preserves the PostgreSQL and
Anvil volumes plus ignored runtime files. `scripts/demo/reset.sh --yes`
irrecoverably removes only the named Phase 6D database, chain, network, and
runtime evidence. It does not remove approved images, source, Git data, or
`.env.local-anvil`, and it does not stop the Solana environment.

## Demo A — user-held acquisition, hold, and redemption

Begin from clean initialized state: `USER_1` has `10,000` synthetic cents, `USER_2` has zero, and all ledger/token/effect positions are zero.

```bash
scripts/demo/demo-user-held.sh
```

The command calls only the Phase 6B REST resources using the generated sender bearer and stable run-scoped idempotency keys. It asserts:

1. acquisition debits exactly `10,000` cents once and mints exactly `10,000` atomic units to `USER_WALLET_1`;
2. the held checkpoint has reserve asset and circulating liability of `10,000` cents, zero pending-mint liability, user balance/supply of `10,000`, and reconciled acquisition;
3. later redemption transfers exactly `10,000` units to ADMIN custody, pays out exactly `10,000` cents before burn, and burns exactly once;
4. the final bank balance returns to `10,000` cents while all reserve/liability/custody/user/ADMIN/supply positions return to zero; and
5. replay returns the same acquisition/redemption resources with no second payout or burn.

The final line names a redacted ignored summary under `.demo-runtime/output/user-held-<run-id>/summary.json`.

## Explicit reset before Demo B

Demo commands never hide cleanup. Reset is destructive and irrecoverably removes only the named Phase 6D PostgreSQL/Anvil workflow state and ignored runtime evidence:

```bash
scripts/demo/reset.sh --yes
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/demo/start.sh
```

Running `reset.sh` without exactly `--yes` refuses the operation. Reset does not remove images, source, `.env.local-anvil`, Git data, Maven caches, or unrelated Docker resources.

## Demo B — settlement-only transfer with forced recipient `AUTO_REDEEM`

```bash
scripts/demo/demo-settlement-only.sh
```

The command calls the existing Phase 6C transfer POST/GET resources. It asserts one exact `USD 100.00` registered route with exactly one each of withdrawal, mint, user-wallet transfer, redemption-custody transfer, payout, and burn. The terminal state must be:

- sender bank `0` cents and recipient bank `10,000` cents;
- zero pending-mint, reserve, circulating, redemption-payable, ADMIN-custody, user, ADMIN, and total-supply positions;
- completed bank, blockchain, and accounting dimensions plus `RECONCILED`; and
- exact replay of the same transfer ID with all six confirmed-effect counts still equal to one.

The final line names a redacted ignored summary under `.demo-runtime/output/settlement-<run-id>/summary.json`.

## Restart and persistence evidence

The recovery command requires clean initialized state. It first recreates only the control plane with the existing delivery worker disabled, accepts and proves one durable non-terminal acquisition with zero confirmed effects, stops that container while PostgreSQL and Anvil continue, then recreates it with delivery enabled. The same workflow must complete with one withdrawal, one mint, reconciliation, and no duplicate on replay:

```bash
scripts/demo/reset.sh --yes
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/demo/start.sh
scripts/demo/demo-restart-recovery.sh
```

To prove whole-stack persistence after that completed nonzero state, compare the redacted output around a normal stop/start:

```bash
scripts/demo/status.sh
scripts/demo/stop.sh
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/demo/start.sh
scripts/demo/status.sh
```

The contract address, block height, workflow ID, bank/ledger/token balances, total supply, and confirmed-effect counts must remain unchanged. `stop.sh` preserves the named PostgreSQL and Anvil volumes plus ignored runtime files.

## Troubleshooting without leaking secrets

- Run `scripts/demo/status.sh` for the safe aggregate view and `scripts/demo/wait-ready.sh` for bounded readiness.
- If a demo times out, exits nonzero, or reports manual review, preserve the state. Record only the printed workflow identifier and failing assertion; do not reset or retry blindly.
- Review only the relevant named service log after confirming it does not contain local configuration values. Do not attach raw logs, `.env.local-anvil`, `.demo-runtime/`, signed bytes, RPC state, or transaction bodies.
- A prerequisite failure lists all missing tools. A digest failure means the exact approved image is absent or mismatched; do not substitute another tag.
- A deployment failure requires chain `31337`, the fixed public address map, the existing compiled contract artifact, and verified ADMIN minter/burner roles. Do not pass any key on a command line.
- A readiness failure must not be bypassed. The expected topology has an internal Compose network, loopback host bindings, a successfully exited deployer, and healthy state-loaded Anvil before the control plane starts.

## Safe stop and destructive teardown

Preserve state for later inspection:

```bash
scripts/demo/stop.sh
```

Permanently destroy only the named Phase 6D workflow/database/chain state when authorized:

```bash
scripts/demo/reset.sh --yes
```

After reset, `.env.local-anvil` and the approved images remain. A subsequent `start.sh` creates fresh random runtime credentials/run identity, a clean PostgreSQL schema, and a clean fixed-genesis Anvil chain.
