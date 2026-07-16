# Review a chain adapter change

Read the chain-port contracts and architecture decisions before reviewing the adapter.

Check common protocol translation, deterministic request identity, signer boundary, transport timeouts, ambiguous submission outcomes, native evidence normalization, and reconciliation queries. For EVM adapters, check chain ID, fee and nonce ownership, replacement, receipts/logs, confirmation depth, and reorganization handling. For Solana adapters, check recent-blockhash or durable-nonce lifetime, instructions and accounts/PDAs, signatures, slots, commitment, expiry, and inquiry behavior. Confirm that protocol SDK types and transport concerns do not enter the plain-Java domain.

Return concrete correctness or boundary findings first. Treat local disposable chain/validator use, no real funds, no public network configuration, and signer authority separated from application policy as safety requirements.
