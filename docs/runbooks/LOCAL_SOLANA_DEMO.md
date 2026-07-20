# Local Solana Demo Runbook

## Purpose and safety boundary

This runbook operates the Phase 7F synthetic, local-only Solana environment.
Demo A proves the configured user-held acquisition, hold, and redemption
lifecycle. Demo B proves the configured settlement-only transfer. Both reuse
the Phase 6A-7E implementation and exact `USD 100.00` / `10000` atomic-unit
contract; they do not add a workflow, endpoint, migration, or chain semantic.

Do not use real funds, personal data, production credentials, a public Solana
cluster, a hosted RPC endpoint, or a funded address. This is a disposable
technical proof, not real banking, reserve attestation, production custody,
legal/accounting/customer finality, compliance, or production readiness.

## Approved host-native topology

Run from the repository root as a non-root user. Required local prerequisites
are Java `25.0.2`, Docker Engine with Compose v2, `curl`, `jq`, OpenSSL, `lsof`,
the executable Maven wrapper, and the already provisioned repository-local
Agave `4.1.2` and SPL Token CLI `5.6.1` described in
[`scripts/solana/README.md`](../../scripts/solana/README.md).

Agave and the packaged Java control plane run on the host. Compose runs only
PostgreSQL using the approved cached immutable reference below and
`pull_policy: never`:

```text
postgres:17.10-alpine3.23@sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4
```

Do not pull or substitute an image, install a tool, or enable a public-network
fallback without separate approval. The exact host bindings are:

- PostgreSQL: `127.0.0.1:15432`;
- private Agave RPC/faucet: `127.0.0.1:8899` and `127.0.0.1:9900`; and
- packaged control plane: `127.0.0.1:18080`.

The dedicated Compose project is `digital-banking-phase7f-solana`. Generated
credentials, disposable keypairs, ledger, cluster/mint metadata, PID ownership
records, logs, and redacted summaries remain beneath ignored mode-`0700`
`.demo-runtime/solana/`; secret and evidence files use mode `0600`.

## Environment handoff

Only one local demo environment may run at a time. Before starting Solana,
preserve and stop the Phase 6D Ethereum project explicitly:

```bash
scripts/demo/stop.sh
```

Neither Solana start nor reset stops Ethereum automatically. In particular,
`scripts/demo/solana/reset.sh --yes` does not stop the Phase 6D project. If
Solana preflight reports Ethereum as running, the safe correction is
`scripts/demo/stop.sh`, followed by a new Solana start. Use reset only when
destructive removal of the selected environment's named state is intended.

## Prerequisites, start, and status

```bash
scripts/demo/solana/prerequisites.sh
scripts/demo/solana/start.sh
scripts/demo/solana/status.sh
```

`start.sh` packages the application offline with tests skipped, verifies the
exact cached PostgreSQL image and pinned native tools, creates a fresh private
classic-SPL two-decimal mint plus canonical USER_1, USER_2, and ADMIN associated
accounts, starts PostgreSQL and the host processes, and waits for all local
identity and readiness checks. It preserves an existing named state; use reset
only when destruction is explicitly intended.

`status.sh` prints only bounded aggregate evidence: network identity, finalized
observation height, mint reference, exact bank/ledger/custody amounts, token
balances/supply, confirmed-effect counts, latest parent summaries,
payout-before-burn ordering, and reconciliation. It excludes credentials,
private keys, raw messages/transactions, signatures, arbitrary account lookup,
and unrestricted audit records.

## What start creates and where state lives

| Runtime or state | Created or used by start | Persistence boundary |
| --- | --- | --- |
| PostgreSQL | Docker service in project `digital-banking-phase7f-solana` | Named Phase 7F database volume; ordinary stop preserves it |
| Private Agave | Host-native loopback validator | Ledger and exact process ownership under ignored `.demo-runtime/solana/` |
| Classic-SPL asset | Two-decimal mint and canonical USER_1, USER_2, and ADMIN associated accounts | Retained in the private ledger; no custom program is deployed |
| Spring control plane | Packaged Java 25 host process | Durable business state remains in PostgreSQL |
| Runtime identity/evidence | Credentials, keypairs, cluster/mint metadata, logs, and redacted summaries | Ignored `.demo-runtime/solana/`; ordinary stop preserves it |

`scripts/demo/solana/stop.sh` stops only the exactly owned Java, Agave, and
PostgreSQL runtime while preserving the database, ledger, and ignored metadata.
`scripts/demo/solana/reset.sh --yes` irrecoverably removes only the named Phase
7F database/network and ignored runtime state. It preserves the approved image,
pinned tools, source, and Git data.

## Demo A — user-held acquisition, hold, and redemption

Begin from clean initialized state: USER_1 has `10000` synthetic cents, USER_2
has zero, and all ledger, token, supply, custody, and effect positions are zero.

```bash
scripts/demo/solana/demo-user-held.sh
```

The command uses only the existing Phase 6B REST resources and generated sender
identity. It proves one exact acquisition, a held USER_1 balance and supply of
`10000`, exact replay with no second effect, later redemption custody, one
payout before one burn, final reconciliation, USER_1's bank balance restored to
`10000`, and zero final user/ADMIN balances, custody, liabilities, reserve, and
supply. Its final line identifies a redacted ignored summary.

## Explicit reset before Demo B

Reset is destructive and requires the literal confirmation flag:

```bash
scripts/demo/solana/reset.sh --yes
scripts/demo/solana/start.sh
```

It irrecoverably removes only the named Phase 7F PostgreSQL volume/network and
ignored `.demo-runtime/solana/` state. It preserves source, Git data, images,
Maven/dependency caches, `.solana-tools/`, the default `.solana-runtime/`,
`.env.local-anvil`, and all unrelated Docker resources.

## Demo B — settlement-only transfer with forced recipient `AUTO_REDEEM`

```bash
scripts/demo/solana/demo-settlement-only.sh
```

The command uses the existing Phase 6C transfer resource and registered
`AUTO_REDEEM` route. It proves exactly one withdrawal, mint, USER_1-to-USER_2
transfer, redemption-custody transfer, payout, and burn; sender/recipient bank
balances move `10000 -> 0` and `0 -> 10000` cents; all final token, supply,
reserve, liability, and custody positions are zero; the parent is reconciled;
and replay returns the same transfer without a duplicate effect.

## Restart and retained-state evidence

The deterministic recovery command requires clean initialized state. It starts
the control plane with delivery disabled, durably accepts one acquisition with
zero confirmed effects, stops only the exact owned Java process while
PostgreSQL/private Agave remain available, restarts delivery, and proves the
same workflow reconciles with one withdrawal, one mint, and no duplicate on
replay:

```bash
scripts/demo/solana/reset.sh --yes
scripts/demo/solana/start.sh
scripts/demo/solana/demo-restart-recovery.sh
```

For ordinary retained-state operation, `stop.sh` stops only the exactly owned
host processes and PostgreSQL container without deleting the ledger, database,
or ignored runtime metadata. A later start must retain the cluster/mint,
workflow identity, bank/accounting positions, token balances/supply, and effect
counts:

```bash
scripts/demo/solana/status.sh
scripts/demo/solana/stop.sh
scripts/demo/solana/start.sh
scripts/demo/solana/status.sh
```

## Troubleshooting without leaking secrets

- Use `status.sh` and `wait-ready.sh` first; both are bounded and read-only.
- If a workflow times out, exits nonzero, or enters manual review, preserve the
  state and record only the printed parent ID and failed assertion. Do not reset
  or blindly replay.
- Inspect only the relevant scoped local log after confirming no configuration
  value is present. Never attach `.demo-runtime/solana/`, private key files,
  tokens, raw transactions/messages, signatures, or validator ledger data.
- A prerequisite failure is a stop condition. Agave, Java, and PostgreSQL may
  not have started; a subsequent `runtime is absent` message is expected when
  `start.sh` stopped during preflight. Do not pull an image, install or
  substitute a tool, change a tag, or use a public RPC endpoint.
- A readiness failure must not be bypassed. Confirm only the documented
  loopback ports, exact PID/command ownership, cluster identity, mint policy,
  classic Token/ATA programs, canonical accounts, and finalized status.

## Safe stop and destructive teardown

Preserve state:

```bash
scripts/demo/solana/stop.sh
```

Permanently destroy only the Phase 7F named state when authorized:

```bash
scripts/demo/solana/reset.sh --yes
```

After reset, the approved cached image and pinned tools remain. The next start
creates new random local credentials, a clean PostgreSQL schema, private ledger,
cluster identity, mint, associated accounts, and run identity.
