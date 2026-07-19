package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit V10 mapping for the settlement-only transfer companion. */
public final class PostgresSettlementTransferRepository
        implements SettlementTransferRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresSettlementTransferRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<SettlementTransfer> findById(TransferId transferId) {
        Objects.requireNonNull(transferId, "transferId");
        return Objects.requireNonNull(transactions.execute(status ->
                findInCurrentTransaction(transferId, Optional.empty())));
    }

    @Override
    public Optional<SettlementTransfer> findById(
            TransferId transferId, ParticipantScope sender) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(sender, "sender");
        return Objects.requireNonNull(transactions.execute(status ->
                findInCurrentTransaction(transferId, Optional.of(sender))));
    }

    @Override
    public void save(SettlementTransfer transfer, long expectedVersion) {
        Objects.requireNonNull(transfer, "transfer");
        if (expectedVersion < 0 || transfer.version() <= expectedVersion) {
            throw new IllegalArgumentException(
                    "settlement transfer version advance is invalid");
        }
        transactions.executeWithoutResult(status -> saveInCurrentTransaction(
                transfer, expectedVersion));
    }

    void insertAcceptedInCurrentTransaction(SettlementTransfer transfer) {
        if (transfer.status() != SettlementTransfer.Status.ACCEPTED
                || transfer.version() != 0 || transfer.boundaries().size() != 4
                || transfer.transitions().size() != 1) {
            throw new IllegalArgumentException(
                    "settlement companion is not an accepted parent");
        }
        insertParent(transfer);
        for (SettlementTransfer.Boundary boundary : transfer.boundaries()) {
            insertBoundary(transfer.transferId(), boundary);
        }
        insertTransition(transfer.transferId(), transfer.transitions().getFirst());
    }

    Optional<SettlementTransfer> findInCurrentTransaction(
            TransferId transferId, Optional<ParticipantScope> sender) {
        String owner = sender.isPresent()
                ? " AND sender_tenant_id = :tenantId"
                    + " AND sender_participant_id = :participantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT * FROM settlement_transfer
                        WHERE transfer_id = :transferId
                        """ + owner)
                .param("transferId", transferId.value());
        if (sender.isPresent()) {
            statement = statement
                    .param("tenantId", sender.orElseThrow().tenantId())
                    .param("participantId", sender.orElseThrow().participantId());
        }
        return statement.query(this::mapSeed).optional().map(this::hydrate);
    }

    void saveInCurrentTransaction(
            SettlementTransfer transfer, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE settlement_transfer
                        SET parent_status = :status,
                            aggregate_version = :newVersion,
                            updated_at = :updatedAt
                        WHERE transfer_id = :transferId
                          AND aggregate_version = :expectedVersion
                        """)
                .param("status", transfer.status().name())
                .param("newVersion", transfer.version())
                .param("updatedAt", utc(
                        transfer.transitions().getLast().recordedAt()))
                .param("transferId", transfer.transferId().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("settlement transfer version conflict");
        }
        for (SettlementTransfer.Boundary boundary : transfer.boundaries()) {
            int boundaryUpdated = jdbc.sql("""
                            UPDATE settlement_transfer_boundary
                            SET boundary_status = :status,
                                child_reference = :child,
                                evidence_reference = :evidence
                            WHERE transfer_id = :transferId
                              AND boundary_sequence = :sequence
                              AND boundary_id = :boundaryId
                              AND boundary_kind = :kind
                            """)
                    .param("status", boundary.status().name())
                    .param("child", boundary.child()
                            .map(SettlementTransfer.ChildReference::value).orElse(null))
                    .param("evidence", boundary.evidence()
                            .map(EvidenceRef::value).orElse(null))
                    .param("transferId", transfer.transferId().value())
                    .param("sequence", boundary.sequence())
                    .param("boundaryId", boundary.id().value())
                    .param("kind", boundary.kind().name())
                    .update();
            if (boundaryUpdated != 1) {
                throw new IllegalStateException(
                        "settlement boundary identity changed");
            }
        }
        transfer.transitions().stream()
                .filter(transition -> transition.version() > expectedVersion)
                .forEach(transition -> insertTransition(
                        transfer.transferId(), transition));
        transfer.conclusion().ifPresent(conclusion -> jdbc.sql("""
                        INSERT INTO settlement_transfer_conclusion (
                            transfer_id, reconciliation_status, completed,
                            evidence_reference, recorded_at)
                        VALUES (
                            :transferId, :conclusion, :completed,
                            :evidence, :recordedAt)
                        """)
                .param("transferId", transfer.transferId().value())
                .param("conclusion", conclusion.name())
                .param("completed", conclusion
                        == ReserveAccounting.ReconciliationStatus.RECONCILED)
                .param("evidence",
                        transfer.transitions().getLast().evidence().value())
                .param("recordedAt", utc(
                        transfer.transitions().getLast().recordedAt()))
                .update());
    }

    private void insertParent(SettlementTransfer transfer) {
        SettlementTransfer.AcceptedContext context = transfer.context();
        AssetUnit unit = context.tokenQuantity().unit();
        SettlementTransfer.RouteSnapshot sender = context.sender();
        SettlementTransfer.RouteSnapshot recipient = context.recipient();
        jdbc.sql("""
                        INSERT INTO settlement_transfer (
                            transfer_id, workflow_version, amount_cents,
                            asset_id, unit_id, unit_version, unit_scale,
                            unit_max_atomic, quantity_atomic,
                            sender_instruction_id, sender_instruction_version,
                            sender_tenant_id, sender_participant_id,
                            sender_bank_id, sender_bank_account_id,
                            sender_bank_account_reference,
                            sender_wallet_reference, sender_wallet_metadata_version,
                            recipient_instruction_id, recipient_instruction_version,
                            recipient_tenant_id, recipient_participant_id,
                            recipient_bank_id, recipient_bank_account_id,
                            recipient_bank_account_reference,
                            recipient_wallet_reference,
                            recipient_wallet_metadata_version,
                            admin_wallet_reference, admin_wallet_metadata_version,
                            settlement_network, contract_reference,
                            payout_policy_version, conversion_policy_version,
                            accounting_policy_version, fee_policy_version,
                            finality_policy_version, reconciliation_policy_version,
                            idempotency_key_digest, command_digest,
                            parent_status, aggregate_version, accepted_at, updated_at)
                        VALUES (
                            :transferId, :workflowVersion, :amountCents,
                            :assetId, :unitId, :unitVersion, :unitScale,
                            :unitMaxAtomic, :quantityAtomic,
                            :senderInstructionId, :senderInstructionVersion,
                            :senderTenantId, :senderParticipantId,
                            :senderBankId, :senderBankAccountId,
                            :senderBankAccountReference,
                            :senderWalletReference, :senderWalletVersion,
                            :recipientInstructionId, :recipientInstructionVersion,
                            :recipientTenantId, :recipientParticipantId,
                            :recipientBankId, :recipientBankAccountId,
                            :recipientBankAccountReference,
                            :recipientWalletReference, :recipientWalletVersion,
                            :adminWalletReference, :adminWalletVersion,
                            :network, :contractReference,
                            :payoutPolicyVersion, :conversionPolicyVersion,
                            :accountingPolicyVersion, :feePolicyVersion,
                            :finalityPolicyVersion, :reconciliationPolicyVersion,
                            :idempotencyKeyDigest, :commandDigest,
                            :parentStatus, 0, :acceptedAt, :acceptedAt)
                        """)
                .param("transferId", transfer.transferId().value())
                .param("workflowVersion", context.workflowVersion())
                .param("amountCents", context.usdAmount().value())
                .param("assetId", unit.assetId())
                .param("unitId", unit.unitId())
                .param("unitVersion", unit.version())
                .param("unitScale", unit.scale())
                .param("unitMaxAtomic", unit.maxAtomicUnits())
                .param("quantityAtomic", context.tokenQuantity().atomicUnits())
                .param("senderInstructionId", sender.instructionId())
                .param("senderInstructionVersion", sender.instructionVersion())
                .param("senderTenantId", sender.participant().tenantId())
                .param("senderParticipantId", sender.participant().participantId())
                .param("senderBankId", sender.bankId().value())
                .param("senderBankAccountId", sender.bankAccountId().value())
                .param("senderBankAccountReference",
                        sender.bankAccountReference().value())
                .param("senderWalletReference", sender.wallet().value())
                .param("senderWalletVersion", sender.walletMetadataVersion())
                .param("recipientInstructionId", recipient.instructionId())
                .param("recipientInstructionVersion", recipient.instructionVersion())
                .param("recipientTenantId", recipient.participant().tenantId())
                .param("recipientParticipantId", recipient.participant().participantId())
                .param("recipientBankId", recipient.bankId().value())
                .param("recipientBankAccountId", recipient.bankAccountId().value())
                .param("recipientBankAccountReference",
                        recipient.bankAccountReference().value())
                .param("recipientWalletReference", recipient.wallet().value())
                .param("recipientWalletVersion", recipient.walletMetadataVersion())
                .param("adminWalletReference", context.adminWallet().value())
                .param("adminWalletVersion", context.adminWalletMetadataVersion())
                .param("network", context.network().name())
                .param("contractReference", context.contractReference())
                .param("payoutPolicyVersion", context.payoutPolicyVersion())
                .param("conversionPolicyVersion", context.conversionPolicyVersion())
                .param("accountingPolicyVersion", context.accountingPolicyVersion())
                .param("feePolicyVersion", context.feePolicyVersion())
                .param("finalityPolicyVersion", context.finalityPolicyVersion())
                .param("reconciliationPolicyVersion",
                        context.reconciliationPolicyVersion())
                .param("idempotencyKeyDigest", context.idempotencyKeyDigest())
                .param("commandDigest", context.commandDigest())
                .param("parentStatus", transfer.status().name())
                .param("acceptedAt", utc(context.acceptedAt()))
                .update();
    }

    private void insertBoundary(
            TransferId transferId, SettlementTransfer.Boundary boundary) {
        jdbc.sql("""
                        INSERT INTO settlement_transfer_boundary (
                            transfer_id, boundary_sequence, boundary_id,
                            boundary_kind, boundary_status,
                            child_reference, evidence_reference)
                        VALUES (
                            :transferId, :sequence, :boundaryId,
                            :kind, :status, :child, :evidence)
                        """)
                .param("transferId", transferId.value())
                .param("sequence", boundary.sequence())
                .param("boundaryId", boundary.id().value())
                .param("kind", boundary.kind().name())
                .param("status", boundary.status().name())
                .param("child", boundary.child()
                        .map(SettlementTransfer.ChildReference::value).orElse(null))
                .param("evidence", boundary.evidence()
                        .map(EvidenceRef::value).orElse(null))
                .update();
    }

    private void insertTransition(
            TransferId transferId, SettlementTransfer.Transition transition) {
        jdbc.sql("""
                        INSERT INTO settlement_transfer_transition (
                            transfer_id, aggregate_version, transition_id,
                            from_status, to_status, boundary_id,
                            evidence_reference, recorded_at)
                        VALUES (
                            :transferId, :version, :transitionId,
                            :fromStatus, :toStatus, :boundaryId,
                            :evidence, :recordedAt)
                        """)
                .param("transferId", transferId.value())
                .param("version", transition.version())
                .param("transitionId", transition.id().value())
                .param("fromStatus", transition.from().name())
                .param("toStatus", transition.to().name())
                .param("boundaryId", transition.boundaryId()
                        .map(SettlementTransfer.BoundaryId::value).orElse(null))
                .param("evidence", transition.evidence().value())
                .param("recordedAt", utc(transition.recordedAt()))
                .update();
    }

    private Seed mapSeed(ResultSet row, int number) throws SQLException {
        AssetUnit unit = new AssetUnit(
                row.getString("asset_id"), row.getString("unit_id"),
                row.getInt("unit_version"), row.getInt("unit_scale"),
                exact(row, "unit_max_atomic"));
        SettlementTransfer.RouteSnapshot sender = route(row, "sender",
                SettlementTransfer.InstructionMode.ACQUISITION);
        SettlementTransfer.RouteSnapshot recipient = route(row, "recipient",
                SettlementTransfer.InstructionMode.AUTO_REDEEM);
        SettlementTransfer.AcceptedContext context =
                new SettlementTransfer.AcceptedContext(
                        row.getString("workflow_version"),
                        UsdCents.positive(exact(row, "amount_cents")),
                        TokenQuantity.ofAtomic(exact(row, "quantity_atomic"), unit),
                        sender, recipient,
                        new WalletReference(row.getString("admin_wallet_reference")),
                        row.getString("admin_wallet_metadata_version"),
                        SettlementNetwork.valueOf(
                                row.getString("settlement_network")),
                        row.getString("contract_reference"),
                        row.getString("payout_policy_version"),
                        row.getString("conversion_policy_version"),
                        row.getString("accounting_policy_version"),
                        row.getString("fee_policy_version"),
                        row.getString("finality_policy_version"),
                        row.getString("reconciliation_policy_version"),
                        row.getString("idempotency_key_digest"),
                        row.getString("command_digest"),
                        instant(row, "accepted_at"));
        return new Seed(
                new TransferId(row.getObject("transfer_id", UUID.class)),
                context,
                SettlementTransfer.Status.valueOf(row.getString("parent_status")),
                row.getLong("aggregate_version"));
    }

    private static SettlementTransfer.RouteSnapshot route(
            ResultSet row,
            String prefix,
            SettlementTransfer.InstructionMode mode) throws SQLException {
        return new SettlementTransfer.RouteSnapshot(
                row.getString(prefix + "_instruction_id"),
                row.getString(prefix + "_instruction_version"),
                new SettlementTransfer.Participant(
                        row.getString(prefix + "_tenant_id"),
                        row.getString(prefix + "_participant_id")),
                new SyntheticBankAccount.BankId(
                        row.getString(prefix + "_bank_id")),
                new SyntheticBankAccount.AccountId(
                        row.getString(prefix + "_bank_account_id")),
                new BankAccountReference(
                        row.getString(prefix + "_bank_account_reference")),
                new WalletReference(row.getString(prefix + "_wallet_reference")),
                row.getString(prefix + "_wallet_metadata_version"), mode);
    }

    private SettlementTransfer hydrate(Seed seed) {
        List<SettlementTransfer.Boundary> boundaries = jdbc.sql("""
                        SELECT boundary_sequence, boundary_id, boundary_kind,
                               boundary_status, child_reference, evidence_reference
                        FROM settlement_transfer_boundary
                        WHERE transfer_id = :transferId
                        ORDER BY boundary_sequence
                        """)
                .param("transferId", seed.transferId().value())
                .query((row, number) -> new SettlementTransfer.Boundary(
                        new SettlementTransfer.BoundaryId(
                                row.getObject("boundary_id", UUID.class)),
                        row.getInt("boundary_sequence"),
                        SettlementTransfer.BoundaryKind.valueOf(
                                row.getString("boundary_kind")),
                        SettlementTransfer.BoundaryStatus.valueOf(
                                row.getString("boundary_status")),
                        Optional.ofNullable(row.getString("child_reference"))
                                .map(SettlementTransfer.ChildReference::new),
                        Optional.ofNullable(row.getString("evidence_reference"))
                                .map(EvidenceRef::new)))
                .list();
        List<SettlementTransfer.Transition> transitions = jdbc.sql("""
                        SELECT aggregate_version, transition_id, from_status,
                               to_status, boundary_id, evidence_reference, recorded_at
                        FROM settlement_transfer_transition
                        WHERE transfer_id = :transferId
                        ORDER BY aggregate_version
                        """)
                .param("transferId", seed.transferId().value())
                .query((row, number) -> {
                    UUID boundaryId = row.getObject("boundary_id", UUID.class);
                    return new SettlementTransfer.Transition(
                            new SettlementTransfer.TransitionId(
                                    row.getObject("transition_id", UUID.class)),
                            row.getLong("aggregate_version"),
                            SettlementTransfer.Status.valueOf(
                                    row.getString("from_status")),
                            SettlementTransfer.Status.valueOf(
                                    row.getString("to_status")),
                            Optional.ofNullable(boundaryId)
                                    .map(SettlementTransfer.BoundaryId::new),
                            new EvidenceRef(row.getString("evidence_reference")),
                            instant(row, "recorded_at"));
                }).list();
        Optional<ReserveAccounting.ReconciliationStatus> conclusion = jdbc.sql("""
                        SELECT reconciliation_status
                        FROM settlement_transfer_conclusion
                        WHERE transfer_id = :transferId
                        """)
                .param("transferId", seed.transferId().value())
                .query(String.class).optional()
                .map(ReserveAccounting.ReconciliationStatus::valueOf);
        return SettlementTransfer.rehydrate(
                seed.transferId(), seed.context(), seed.status(), seed.version(),
                boundaries, transitions, conclusion);
    }

    private static java.math.BigInteger exact(ResultSet row, String column)
            throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column)
            throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record Seed(
            TransferId transferId,
            SettlementTransfer.AcceptedContext context,
            SettlementTransfer.Status status,
            long version) { }
}
