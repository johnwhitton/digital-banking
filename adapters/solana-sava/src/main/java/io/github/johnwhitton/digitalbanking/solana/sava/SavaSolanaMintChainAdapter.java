package io.github.johnwhitton.digitalbanking.solana.sava;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.encoding.Base58;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TokenBalance;
import software.sava.rpc.json.http.response.Tx;
import software.sava.rpc.json.http.response.TxStatus;

/** Durable local-only classic-SPL mint adapter. Sava types remain inside this module. */
public final class SavaSolanaMintChainAdapter
        implements ChainPort, WalletTransferChainPort {

    private static final String SIGNATURE_ENCODING = "solana-ed25519-64-byte";
    private static final BigInteger MAX_U64 = new BigInteger("18446744073709551615");

    private final SolanaMintAttemptStore attempts;
    private final SolanaRpcClient submissionClient;
    private final SolanaRpcClient observationClient;
    private final SubmissionTransport submissionTransport;
    private final Configuration configuration;
    private final Clock clock;
    private final SolanaMintTransactionCodec codec = new SolanaMintTransactionCodec();

    public static SavaSolanaMintChainAdapter local(
            DataSource dataSource, Configuration configuration, Clock clock) {
        Objects.requireNonNull(configuration, "configuration");
        SolanaRpcClient submission = client(configuration);
        SolanaRpcClient observation = client(configuration);
        return new SavaSolanaMintChainAdapter(
                dataSource, submission, observation,
                base64 -> submission.sendTransaction(base64, 0).join(),
                configuration, clock);
    }

    public SavaSolanaMintChainAdapter(
            DataSource dataSource,
            SolanaRpcClient submissionClient,
            SolanaRpcClient observationClient,
            Configuration configuration,
            Clock clock) {
        this(dataSource, submissionClient, observationClient,
                base64 -> submissionClient.sendTransaction(base64, 0).join(),
                configuration, clock);
    }

    SavaSolanaMintChainAdapter(
            DataSource dataSource,
            SolanaRpcClient submissionClient,
            SolanaRpcClient observationClient,
            SubmissionTransport submissionTransport,
            Configuration configuration,
            Clock clock) {
        attempts = new SolanaMintAttemptStore(dataSource);
        this.submissionClient = Objects.requireNonNull(
                submissionClient, "submissionClient");
        this.observationClient = Objects.requireNonNull(
                observationClient, "observationClient");
        this.submissionTransport = Objects.requireNonNull(
                submissionTransport, "submissionTransport");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        verifyCluster(submissionClient, "submission");
        verifyCluster(observationClient, "observation");
    }

    @Override
    public ChainCapabilities capabilities(String routeVersion) {
        if (!configuration.policyVersion().equals(routeVersion)) {
            throw new IllegalArgumentException("unsupported local Solana policy version");
        }
        return new ChainCapabilities(true, true, true);
    }

    @Override
    public PreparedAttempt prepare(
            UUID deliveryId, TokenOperation operation, OperationAttempt attempt) {
        if (operation.kind() == OperationKind.BURN) {
            return prepareBurn(deliveryId, operation, attempt);
        }
        validateMint(operation, attempt);
        Optional<SolanaMintAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), attempt.attemptId());
        if (existing.isPresent()) {
            SolanaMintAttemptStore.AttemptRow retained = existing.orElseThrow();
            if (!retained.deliveryId().equals(deliveryId)
                    || !retained.amount().equals(operation.quantity().atomicUnits())) {
                throw new IllegalStateException(
                        "Solana attempt is already bound to different acceptance context");
            }
            return prepared(retained);
        }

        PublicKey mintAddress = key(configuration.mintAddress(), "mintAddress");
        PublicKey destinationOwner = key(
                configuration.destinationOwner(), "destinationOwner");
        PublicKey feePayer = key(configuration.feePayerPublicKey(), "feePayerPublicKey");
        PublicKey mintAuthority = key(
                configuration.mintAuthorityPublicKey(), "mintAuthorityPublicKey");
        AccountInfo<byte[]> mintAccount = requiredAccount(
                observationClient.getAccountInfo(
                        Commitment.FINALIZED, mintAddress).join(),
                "configured mint");
        validateMintAccount(mintAccount, mintAddress, mintAuthority);

        PublicKey destinationAta = SolanaMintTransactionCodec.associatedTokenAddress(
                destinationOwner, mintAddress);
        AccountInfo<byte[]> tokenAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, destinationAta).join();
        boolean ataExisted = tokenAccount != null && tokenAccount.data() != null;
        BigInteger preBalance = BigInteger.ZERO;
        if (ataExisted) {
            validateTokenAccount(tokenAccount, destinationAta, destinationOwner, mintAddress);
            preBalance = unsigned(TokenAccount.read(
                    destinationAta, tokenAccount.data()).amount());
        }
        BigInteger preSupply = unsigned(Mint.read(
                mintAddress, mintAccount.data()).supply());
        BigInteger feeBalance = unsigned(
                submissionClient.getBalance(Commitment.FINALIZED, feePayer)
                        .join().lamports());
        if (feeBalance.compareTo(configuration.minimumFeePayerLamports()) < 0) {
            throw new IllegalStateException("local Solana fee payer has insufficient funds");
        }
        LatestBlockHash latest = submissionClient.getLatestBlockHash(
                configuration.preparationCommitment().nativeCommitment()).join();
        if (latest.lastValidBlockHeight() < 0
                || submissionClient.getBlockHeight(
                        configuration.preparationCommitment().nativeCommitment())
                        .join().height() > latest.lastValidBlockHeight()) {
            throw new IllegalStateException("local Solana blockhash validity is incoherent");
        }
        SolanaMintTransactionCodec.PreparedMessage message = codec.prepare(
                feePayer, mintAuthority, destinationOwner, mintAddress,
                latest.blockHash(), operation.quantity().atomicUnits(),
                configuration.decimals(), !ataExisted);
        if (!message.destinationAta().equals(destinationAta)) {
            throw new IllegalStateException("derived associated token account changed");
        }

        Optional<UUID> replacementParent = attempt.predecessor().map(predecessor ->
                attempts.find(operation.operationId(), predecessor)
                        .orElseThrow(() -> new IllegalStateException(
                                "Solana replacement predecessor was not found"))
                        .nativeAttemptId());
        int replacementSequence = replacementParent.isEmpty() ? 0
                : attempts.find(operation.operationId(), attempt.predecessor().orElseThrow())
                        .orElseThrow().replacementSequence() + 1;
        SolanaMintAttemptStore.Draft draft = new SolanaMintAttemptStore.Draft(
                operation.operationId(), attempt.attemptId(), deliveryId, UUID.randomUUID(),
                replacementParent, replacementSequence,
                SolanaMintAttemptStore.EffectKind.MINT,
                Optional.empty(),
                configuration.clusterIdentity(),
                routeSnapshot(operation), SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58(),
                SolanaMintTransactionCodec.ATA_PROGRAM.toBase58(),
                configuration.mintAddress(), Optional.empty(), Optional.empty(),
                Optional.empty(), configuration.destinationOwner(),
                destinationAta.toBase58(), ataExisted, configuration.decimals(),
                operation.quantity().atomicUnits(), preSupply, preBalance,
                signer(configuration.feePayerKeyAlias(), SigningRequest.KeyRole.FEE_PAYER,
                        configuration.feePayerKeyVersion(),
                        configuration.feePayerPublicKey()),
                signer(configuration.mintAuthorityKeyAlias(),
                        SigningRequest.KeyRole.MINT_AUTHORITY,
                        configuration.mintAuthorityKeyVersion(),
                        configuration.mintAuthorityPublicKey()),
                configuration.policyVersion(), configuration.maximumFeeLamports(),
                latest.blockHash(), latest.lastValidBlockHeight(),
                message.unsignedTransaction(), message.messageSha256(),
                message.instructionSha256());
        return prepared(attempts.prepare(draft, now()));
    }

    private PreparedAttempt prepareBurn(
            UUID deliveryId, TokenOperation operation, OperationAttempt attempt) {
        validateBurn(operation, attempt);
        Optional<SolanaMintAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), attempt.attemptId());
        if (existing.isPresent()) {
            SolanaMintAttemptStore.AttemptRow retained = existing.orElseThrow();
            if (!retained.deliveryId().equals(deliveryId)
                    || retained.effectKind() != SolanaMintAttemptStore.EffectKind.BURN
                    || !retained.redemptionRegistryVersion().equals(
                            Optional.of(configuration.walletRegistryVersion()))
                    || !retained.amount().equals(operation.quantity().atomicUnits())) {
                throw new IllegalStateException(
                        "Solana burn attempt is bound to different acceptance context");
            }
            return prepared(retained);
        }

        PublicKey mintAddress = key(configuration.mintAddress(), "mintAddress");
        PublicKey adminOwner = key(configuration.redemptionOwner(), "redemptionOwner");
        PublicKey feePayer = key(configuration.feePayerPublicKey(), "feePayerPublicKey");
        AccountInfo<byte[]> mintAccount = requiredAccount(observationClient.getAccountInfo(
                Commitment.FINALIZED, mintAddress).join(), "configured mint");
        validateMintAccountForBurn(mintAccount, mintAddress);
        PublicKey adminAta = SolanaMintTransactionCodec.associatedTokenAddress(
                adminOwner, mintAddress);
        AccountInfo<byte[]> adminAccount = requiredAccount(observationClient.getAccountInfo(
                Commitment.FINALIZED, adminAta).join(),
                "configured ADMIN redemption associated token account");
        validateTokenAccount(adminAccount, adminAta, adminOwner, mintAddress);
        BigInteger preAdminBalance = unsigned(TokenAccount.read(
                adminAta, adminAccount.data()).amount());
        BigInteger amount = operation.quantity().atomicUnits();
        if (preAdminBalance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "local Solana ADMIN redemption account has insufficient balance");
        }
        BigInteger preSupply = unsigned(Mint.read(
                mintAddress, mintAccount.data()).supply());
        if (preSupply.compareTo(amount) < 0) {
            throw new IllegalStateException("local Solana mint supply is insufficient");
        }
        requireFundedFeePayer(feePayer);
        LatestBlockHash latest = latestUsableBlockhash();
        SolanaMintTransactionCodec.PreparedBurnMessage message = codec.prepareBurn(
                feePayer, adminOwner, mintAddress, latest.blockHash(), amount,
                configuration.decimals());
        if (!message.sourceAta().equals(adminAta)) {
            throw new IllegalStateException(
                    "derived ADMIN redemption associated token account changed");
        }
        Optional<UUID> replacementParent = attempt.predecessor().map(predecessor ->
                attempts.find(operation.operationId(), predecessor)
                        .orElseThrow(() -> new IllegalStateException(
                                "Solana burn replacement predecessor was not found"))
                        .nativeAttemptId());
        int replacementSequence = replacementParent.isEmpty() ? 0
                : attempts.find(operation.operationId(), attempt.predecessor().orElseThrow())
                        .orElseThrow().replacementSequence() + 1;
        SolanaMintAttemptStore.Draft draft = new SolanaMintAttemptStore.Draft(
                operation.operationId(), attempt.attemptId(), deliveryId, UUID.randomUUID(),
                replacementParent, replacementSequence,
                SolanaMintAttemptStore.EffectKind.BURN,
                Optional.of(configuration.walletRegistryVersion()),
                configuration.clusterIdentity(), routeSnapshot(operation),
                SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58(),
                SolanaMintTransactionCodec.ATA_PROGRAM.toBase58(),
                configuration.mintAddress(), Optional.of(adminOwner.toBase58()),
                Optional.of(adminAta.toBase58()), Optional.of(preAdminBalance),
                adminOwner.toBase58(), adminAta.toBase58(), true,
                configuration.decimals(), amount, preSupply, preAdminBalance,
                signer(configuration.feePayerKeyAlias(), SigningRequest.KeyRole.FEE_PAYER,
                        configuration.feePayerKeyVersion(),
                        configuration.feePayerPublicKey()),
                signer(configuration.burnAuthorityKeyAlias(),
                        SigningRequest.KeyRole.BURN_AUTHORITY,
                        configuration.burnAuthorityKeyVersion(),
                        configuration.redemptionOwner()),
                configuration.policyVersion(), configuration.maximumFeeLamports(),
                latest.blockHash(), latest.lastValidBlockHeight(),
                message.unsignedTransaction(), message.messageSha256(),
                message.instructionSha256());
        return prepared(attempts.prepare(draft, now()));
    }

    @Override
    public PreparedAttempt prepare(
            UUID deliveryId, WalletTransferOperation operation) {
        validateTransfer(operation);
        Optional<SolanaMintAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), operation.attemptId());
        if (existing.isPresent()) {
            SolanaMintAttemptStore.AttemptRow retained = existing.orElseThrow();
            if (!retained.deliveryId().equals(deliveryId)
                    || retained.effectKind()
                            != SolanaMintAttemptStore.EffectKind.TRANSFER
                    || !retained.amount().equals(operation.quantity().atomicUnits())) {
                throw new IllegalStateException(
                        "Solana attempt is already bound to different acceptance context");
            }
            return prepared(retained);
        }

        PublicKey mintAddress = key(configuration.mintAddress(), "mintAddress");
        PublicKey sourceOwner = key(operation.source().normalizedAddress(), "sourceOwner");
        PublicKey destinationOwner = key(
                operation.destination().normalizedAddress(), "destinationOwner");
        PublicKey feePayer = key(configuration.feePayerPublicKey(), "feePayerPublicKey");
        PublicKey mintAuthority = key(
                configuration.mintAuthorityPublicKey(), "mintAuthorityPublicKey");
        AccountInfo<byte[]> mintAccount = requiredAccount(
                observationClient.getAccountInfo(
                        Commitment.FINALIZED, mintAddress).join(),
                "configured mint");
        validateMintAccount(mintAccount, mintAddress, mintAuthority);

        PublicKey sourceAta = SolanaMintTransactionCodec.associatedTokenAddress(
                sourceOwner, mintAddress);
        AccountInfo<byte[]> sourceAccount = requiredAccount(
                observationClient.getAccountInfo(
                        Commitment.FINALIZED, sourceAta).join(),
                "configured source associated token account");
        validateTokenAccount(sourceAccount, sourceAta, sourceOwner, mintAddress);
        BigInteger preSourceBalance = unsigned(TokenAccount.read(
                sourceAta, sourceAccount.data()).amount());
        if (preSourceBalance.compareTo(operation.quantity().atomicUnits()) < 0) {
            throw new IllegalStateException(
                    "local Solana source has insufficient token balance");
        }

        PublicKey destinationAta = SolanaMintTransactionCodec.associatedTokenAddress(
                destinationOwner, mintAddress);
        AccountInfo<byte[]> destinationAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, destinationAta).join();
        boolean destinationExisted = destinationAccount != null
                && destinationAccount.data() != null;
        BigInteger preDestinationBalance = BigInteger.ZERO;
        if (destinationExisted) {
            validateTokenAccount(
                    destinationAccount, destinationAta, destinationOwner, mintAddress);
            preDestinationBalance = unsigned(TokenAccount.read(
                    destinationAta, destinationAccount.data()).amount());
        }
        BigInteger preSupply = unsigned(Mint.read(
                mintAddress, mintAccount.data()).supply());
        requireFundedFeePayer(feePayer);
        LatestBlockHash latest = latestUsableBlockhash();
        SolanaMintTransactionCodec.PreparedTransferMessage message =
                codec.prepareTransfer(
                        feePayer, sourceOwner, destinationOwner, mintAddress,
                        latest.blockHash(), operation.quantity().atomicUnits(),
                        configuration.decimals(), !destinationExisted);
        if (!message.sourceAta().equals(sourceAta)
                || !message.destinationAta().equals(destinationAta)) {
            throw new IllegalStateException("derived associated token account changed");
        }

        SolanaMintAttemptStore.Draft draft = new SolanaMintAttemptStore.Draft(
                operation.operationId(), operation.attemptId(), deliveryId,
                UUID.randomUUID(), Optional.empty(), 0,
                SolanaMintAttemptStore.EffectKind.TRANSFER,
                Optional.empty(),
                configuration.clusterIdentity(), routeSnapshot(operation),
                SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58(),
                SolanaMintTransactionCodec.ATA_PROGRAM.toBase58(),
                configuration.mintAddress(), Optional.of(sourceOwner.toBase58()),
                Optional.of(sourceAta.toBase58()), Optional.of(preSourceBalance),
                destinationOwner.toBase58(), destinationAta.toBase58(),
                destinationExisted, configuration.decimals(),
                operation.quantity().atomicUnits(), preSupply,
                preDestinationBalance,
                signer(configuration.feePayerKeyAlias(),
                        SigningRequest.KeyRole.FEE_PAYER,
                        configuration.feePayerKeyVersion(),
                        configuration.feePayerPublicKey()),
                transferSigner(operation),
                configuration.policyVersion(), configuration.maximumFeeLamports(),
                latest.blockHash(), latest.lastValidBlockHeight(),
                message.unsignedTransaction(), message.messageSha256(),
                message.instructionSha256());
        return prepared(attempts.prepare(draft, now()));
    }

    @Override
    public List<SigningRequirement> requiredSigners(AttemptIdentity identity) {
        SolanaMintAttemptStore.AttemptRow attempt = find(identity);
        return List.of(requirement(0, attempt.feePayer()),
                requirement(1, attempt.mintAuthority()));
    }

    @Override
    public Set<Integer> retainedSignatureOrders(AttemptIdentity identity) {
        find(identity);
        return attempts.signatureOrders(identity.operationId(), identity.attemptId());
    }

    @Override
    public Optional<SignedAttempt> findSignedAttempt(AttemptIdentity identity) {
        return attempts.find(identity.operationId(), identity.attemptId())
                .filter(row -> row.transactionSignature() != null)
                .filter(row -> attempts.signatures(
                        row.operationId(), row.attemptId()).size() == 2)
                .map(this::signedAttempt);
    }

    @Override
    public SignedAttempt attachSignature(
            AttemptIdentity identity, AuthorizedSignature signature) {
        Objects.requireNonNull(signature, "signature");
        SolanaMintAttemptStore.AttemptRow current = find(identity);
        List<SolanaMintAttemptStore.SignatureRow> retained = attempts.signatures(
                current.operationId(), current.attemptId());
        if (retained.size() >= 2) {
            return signedAttempt(current);
        }
        int order = retained.size();
        SolanaMintAttemptStore.SignerContext expected = order == 0
                ? current.feePayer() : current.mintAuthority();
        if (!signature.expectedSignerReference().equals(expected.publicKey())
                || !SIGNATURE_ENCODING.equals(signature.encoding())
                || signature.bytes().length != 64) {
            throw new IllegalArgumentException(
                    "authorized Solana signature metadata does not match signer order");
        }
        byte[] message = SolanaMintTransactionCodec.serializedMessage(
                current.unsignedTransaction());
        PublicKey publicKey = key(expected.publicKey(), "signerPublicKey");
        if (!publicKey.verifySignature(message, signature.bytes())) {
            throw new IllegalArgumentException(
                    "authorized Solana signature does not verify for durable message");
        }
        String primary = order == 0 ? Base58.encode(signature.bytes()) : null;
        SolanaMintAttemptStore.AttemptRow changed = attempts.attachSignature(
                current, new SolanaMintAttemptStore.SignatureDraft(
                        order, expected.keyRole(), expected.keyAlias(),
                        expected.keyVersion(), expected.publicKey(), signature.bytes(),
                        sha256(signature.bytes()), signature.encoding()), primary, now());
        if (order == 1) {
            SolanaMintTransactionCodec.SignedTransaction assembled = assemble(changed);
            if (!assembled.primarySignature().equals(changed.transactionSignature())) {
                throw new IllegalStateException(
                        "assembled transaction identity differs from durable primary signature");
            }
        }
        return signedAttempt(changed);
    }

    @Override
    public SubmissionResult submitOnce(SignedAttempt signedAttempt) {
        Objects.requireNonNull(signedAttempt, "signedAttempt");
        SolanaMintAttemptStore.AttemptRow current = attempts.find(
                signedAttempt.operationId(), signedAttempt.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "signed Solana mint attempt was not found"));
        if (!signedAttempt.nativeIdentity().value().equals(
                current.transactionSignature())) {
            throw new IllegalArgumentException("signed Solana native identity changed");
        }
        if (current.status() != SolanaMintAttemptStore.AttemptStatus.SIGNED) {
            return retainedSubmission(current);
        }
        try {
            verifyCluster(submissionClient, "submission");
            long height = submissionClient.getBlockHeight(
                    configuration.preparationCommitment().nativeCommitment()).join().height();
            if (height > current.lastValidBlockHeight()) {
                SolanaMintAttemptStore.SubmissionClaim claim = attempts.claimSubmission(
                        current, now());
                if (!claim.claimed()) {
                    return retainedSubmission(claim.attempt());
                }
                return retainedSubmission(attempts.recordSubmission(
                        claim.attempt(), SolanaMintAttemptStore.AttemptStatus.EXPIRED,
                        "blockhash-expired-before-submit", now()));
            }
        } catch (RuntimeException unavailable) {
            return new SubmissionResult(
                    SubmissionClassification.RETRYABLE_NO_EFFECT, null,
                    evidence(current, "submission-preflight-unavailable"));
        }
        SolanaMintAttemptStore.SubmissionClaim claim = attempts.claimSubmission(current, now());
        if (!claim.claimed()) {
            return retainedSubmission(claim.attempt());
        }
        try {
            SolanaMintTransactionCodec.SignedTransaction assembled = assemble(claim.attempt());
            String response = submissionTransport.send(assembled.base64());
            SolanaMintAttemptStore.AttemptStatus status =
                    assembled.primarySignature().equals(response)
                    ? SolanaMintAttemptStore.AttemptStatus.ACCEPTED
                    : SolanaMintAttemptStore.AttemptStatus.AMBIGUOUS;
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(), status,
                    status == SolanaMintAttemptStore.AttemptStatus.ACCEPTED
                            ? "rpc-accepted" : "rpc-signature-mismatch", now()));
        } catch (RuntimeException failure) {
            SolanaMintAttemptStore.AttemptStatus status = deterministicRpcRejection(failure)
                    ? SolanaMintAttemptStore.AttemptStatus.REJECTED
                    : SolanaMintAttemptStore.AttemptStatus.AMBIGUOUS;
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(), status,
                    status == SolanaMintAttemptStore.AttemptStatus.REJECTED
                            ? structuredSafeCode(failure) : "rpc-response-unavailable", now()));
        }
    }

    @Override
    public InquiryResult inquire(AttemptIdentity identity) {
        SolanaMintAttemptStore.AttemptRow attempt = find(identity);
        NativeIdentity nativeIdentity = new NativeIdentity(attempt.transactionSignature());
        try {
            TxStatus status = submissionClient.getSignatureStatuses(
                    List.of(attempt.transactionSignature()), true).join()
                    .get(attempt.transactionSignature());
            if (status != null && !status.nil()) {
                return inquiry(attempt, nativeIdentity, RetrySafety.REQUIRES_OBSERVATION,
                        "inquiry-signature-found");
            }
            Tx transaction = observationClient.getTransaction(
                    Commitment.FINALIZED, attempt.transactionSignature()).join();
            if (transaction != null) {
                return inquiry(attempt, nativeIdentity, RetrySafety.REQUIRES_OBSERVATION,
                        "inquiry-transaction-found");
            }
            long height = observationClient.getBlockHeight(
                    configuration.observationCommitment().nativeCommitment()).join().height();
            if (height > attempt.lastValidBlockHeight()) {
                attempts.recordObservation(attempt, new SolanaMintAttemptStore.ObservationDraft(
                        SolanaMintAttemptStore.ObservationStatus.EXPIRED,
                        configuration.observationCommitment().name().toLowerCase(Locale.ROOT),
                        Optional.empty(), Optional.empty(), Optional.empty(), false,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(),
                        evidence(attempt, "expired-unobserved").value()), now());
                return inquiry(attempt, nativeIdentity, RetrySafety.UNSAFE,
                        "inquiry-expired-unobserved");
            }
            return inquiry(attempt, nativeIdentity, RetrySafety.REQUIRES_OBSERVATION,
                    "inquiry-absent-still-valid");
        } catch (RuntimeException unavailable) {
            return inquiry(attempt, nativeIdentity, RetrySafety.REQUIRES_OBSERVATION,
                    "inquiry-unavailable");
        }
    }

    @Override
    public Observation observe(ObservationRequest request) {
        SolanaMintAttemptStore.AttemptRow attempt = attempts.find(
                request.operationId(), request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Solana mint attempt was not found"));
        if (!request.nativeIdentity().value().equals(attempt.transactionSignature())
                || !request.policyVersion().equals(attempt.policyVersion())) {
            throw new IllegalArgumentException(
                    "Solana observation identity or policy does not match attempt");
        }
        ObservationEvidence observed;
        try {
            observed = observeNative(attempt);
        } catch (RuntimeException unavailable) {
            observed = ObservationEvidence.pending("observer-unavailable");
        }
        EvidenceRef evidence = evidence(attempt, observed.evidenceKind());
        attempts.recordObservation(attempt, observed.toDraft(evidence.value()), now());
        return new Observation(
                attempt.operationId(), attempt.attemptId(),
                new NativeIdentity(attempt.transactionSignature()),
                observed.classification(), attempt.policyVersion(), now(),
                List.of(evidence));
    }

    private ObservationEvidence observeNative(SolanaMintAttemptStore.AttemptRow attempt) {
        if (attempt.effectKind() == SolanaMintAttemptStore.EffectKind.BURN) {
            return observeBurnNative(attempt);
        }
        if (attempt.effectKind() == SolanaMintAttemptStore.EffectKind.TRANSFER) {
            return observeTransferNative(attempt);
        }
        TxStatus status = observationClient.getSignatureStatuses(
                List.of(attempt.transactionSignature()), true).join()
                .get(attempt.transactionSignature());
        if (status == null || status.nil()) {
            return ObservationEvidence.pending("signature-status-absent");
        }
        if (status.confirmationStatus() != Commitment.FINALIZED) {
            return ObservationEvidence.pending("signature-not-finalized");
        }
        if (status.error() != null) {
            return ObservationEvidence.reverted(
                    status.slot(), "finalized-transaction-failed",
                    status.error().getClass().getSimpleName());
        }
        Tx transaction = observationClient.getTransaction(
                Commitment.FINALIZED, attempt.transactionSignature()).join();
        if (transaction == null || transaction.meta() == null) {
            return ObservationEvidence.pending("finalized-transaction-unavailable");
        }
        if (transaction.meta().error() != null) {
            return ObservationEvidence.reverted(
                    transaction.slot(), "finalized-metadata-failed",
                    transaction.meta().error().getClass().getSimpleName());
        }
        boolean identityMatches = Base58.encode(
                software.sava.core.tx.Transaction.getId(transaction.data()))
                .equals(attempt.transactionSignature());
        boolean instructionsMatch = identityMatches && codec.matchesExpectedInstructions(
                transaction.data(), key(attempt.feePayer().publicKey(), "feePayer"),
                key(attempt.mintAuthority().publicKey(), "mintAuthority"),
                key(attempt.destinationOwner(), "destinationOwner"),
                key(attempt.mintAddress(), "mintAddress"), attempt.amount(),
                attempt.decimals(), !attempt.ataExisted());
        AccountInfo<byte[]> mintAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, key(attempt.mintAddress(), "mintAddress")).join();
        AccountInfo<byte[]> tokenAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, key(attempt.destinationAta(), "destinationAta")).join();
        if (mintAccount == null || mintAccount.data() == null
                || tokenAccount == null || tokenAccount.data() == null) {
            return ObservationEvidence.mismatched(
                    transaction.slot(), transaction.blockTime(), instructionsMatch,
                    Optional.empty(), Optional.empty(), "finalized-account-missing");
        }
        boolean accountsMatch;
        try {
            validateMintAccount(mintAccount,
                    key(attempt.mintAddress(), "mintAddress"),
                    key(attempt.mintAuthority().publicKey(), "mintAuthority"));
            validateTokenAccount(tokenAccount,
                    key(attempt.destinationAta(), "destinationAta"),
                    key(attempt.destinationOwner(), "destinationOwner"),
                    key(attempt.mintAddress(), "mintAddress"));
            accountsMatch = true;
        } catch (RuntimeException mismatch) {
            accountsMatch = false;
        }
        BigInteger supply = unsigned(Mint.read(
                key(attempt.mintAddress(), "mintAddress"), mintAccount.data()).supply());
        BigInteger balance = unsigned(TokenAccount.read(
                key(attempt.destinationAta(), "destinationAta"),
                tokenAccount.data()).amount());
        BigInteger supplyDelta = supply.subtract(attempt.preMintSupply());
        BigInteger balanceDelta = balance.subtract(attempt.preDestinationBalance());
        boolean exact = accountsMatch && instructionsMatch
                && supplyDelta.equals(attempt.amount())
                && balanceDelta.equals(attempt.amount());
        return exact
                ? ObservationEvidence.confirmed(
                        transaction.slot(), transaction.blockTime(), supply, balance,
                        supplyDelta, balanceDelta)
                : ObservationEvidence.mismatched(
                        transaction.slot(), transaction.blockTime(), instructionsMatch,
                        Optional.of(supply), Optional.of(balance),
                        "finalized-mint-effect-mismatch");
    }

    private ObservationEvidence observeTransferNative(
            SolanaMintAttemptStore.AttemptRow attempt) {
        TxStatus status = observationClient.getSignatureStatuses(
                List.of(attempt.transactionSignature()), true).join()
                .get(attempt.transactionSignature());
        if (status == null || status.nil()) {
            return ObservationEvidence.pending("signature-status-absent");
        }
        if (status.confirmationStatus() != Commitment.FINALIZED) {
            return ObservationEvidence.pending("signature-not-finalized");
        }
        if (status.error() != null) {
            return ObservationEvidence.reverted(
                    status.slot(), "finalized-transaction-failed",
                    status.error().getClass().getSimpleName());
        }
        Tx transaction = observationClient.getTransaction(
                Commitment.FINALIZED, attempt.transactionSignature()).join();
        if (transaction == null || transaction.meta() == null) {
            return ObservationEvidence.pending("finalized-transaction-unavailable");
        }
        if (transaction.meta().error() != null) {
            return ObservationEvidence.reverted(
                    transaction.slot(), "finalized-metadata-failed",
                    transaction.meta().error().getClass().getSimpleName());
        }
        PublicKey feePayer = key(attempt.feePayer().publicKey(), "feePayer");
        PublicKey sourceOwner = key(
                attempt.sourceOwner().orElseThrow(), "sourceOwner");
        PublicKey destinationOwner = key(
                attempt.destinationOwner(), "destinationOwner");
        PublicKey mint = key(attempt.mintAddress(), "mintAddress");
        boolean identityMatches = Base58.encode(
                software.sava.core.tx.Transaction.getId(transaction.data()))
                .equals(attempt.transactionSignature());
        boolean instructionsMatch = identityMatches
                && codec.matchesExpectedTransferInstructions(
                        transaction.data(), feePayer, sourceOwner,
                        destinationOwner, mint, attempt.amount(),
                        attempt.decimals(), !attempt.ataExisted());
        Optional<TransactionTokenBalances> transactionBalances =
                transferTokenBalances(transaction, sourceOwner, destinationOwner,
                        mint, key(attempt.sourceAta().orElseThrow(), "sourceAta"),
                        key(attempt.destinationAta(), "destinationAta"),
                        attempt.ataExisted(), attempt.decimals());
        if (transactionBalances.isEmpty()) {
            return ObservationEvidence.mismatchedTransfer(
                    transaction.slot(), transaction.blockTime(), instructionsMatch,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), "finalized-transaction-token-balances-missing");
        }
        AccountInfo<byte[]> mintAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        PublicKey sourceAta = key(attempt.sourceAta().orElseThrow(), "sourceAta");
        PublicKey destinationAta = key(attempt.destinationAta(), "destinationAta");
        AccountInfo<byte[]> sourceAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, sourceAta).join();
        AccountInfo<byte[]> destinationAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, destinationAta).join();
        if (mintAccount == null || mintAccount.data() == null
                || sourceAccount == null || sourceAccount.data() == null
                || destinationAccount == null || destinationAccount.data() == null) {
            return ObservationEvidence.mismatchedTransfer(
                    transaction.slot(), transaction.blockTime(), instructionsMatch,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    transactionBalances,
                    "finalized-account-missing");
        }
        boolean accountsMatch;
        try {
            validateMintAccountForBurn(mintAccount, mint);
            validateTokenAccount(sourceAccount, sourceAta, sourceOwner, mint);
            validateTokenAccount(
                    destinationAccount, destinationAta, destinationOwner, mint);
            accountsMatch = true;
        } catch (RuntimeException mismatch) {
            accountsMatch = false;
        }
        BigInteger supply = unsigned(Mint.read(mint, mintAccount.data()).supply());
        BigInteger sourceBalance = unsigned(TokenAccount.read(
                sourceAta, sourceAccount.data()).amount());
        BigInteger destinationBalance = unsigned(TokenAccount.read(
                destinationAta, destinationAccount.data()).amount());
        BigInteger supplyDelta = supply.subtract(attempt.preMintSupply());
        TransactionTokenBalances nativeBalances = transactionBalances.orElseThrow();
        BigInteger sourceDelta = nativeBalances.preSource()
                .subtract(nativeBalances.postSource());
        BigInteger destinationDelta = nativeBalances.postDestination()
                .subtract(nativeBalances.preDestination());
        boolean transactionExact = nativeBalances.preSource().equals(
                        attempt.preSourceBalance().orElseThrow())
                && nativeBalances.preDestination().equals(
                        attempt.preDestinationBalance())
                && sourceDelta.equals(attempt.amount())
                && destinationDelta.equals(attempt.amount());
        boolean exact = accountsMatch && instructionsMatch
                && supplyDelta.signum() == 0
                && transactionExact;
        return exact
                ? ObservationEvidence.confirmedTransfer(
                        transaction.slot(), transaction.blockTime(), supply,
                        sourceBalance, destinationBalance, sourceDelta,
                        destinationDelta, nativeBalances)
                : ObservationEvidence.mismatchedTransfer(
                        transaction.slot(), transaction.blockTime(), instructionsMatch,
                        Optional.of(supply), Optional.of(sourceBalance),
                        Optional.of(destinationBalance), transactionBalances,
                        "finalized-transfer-effect-mismatch");
    }

    private ObservationEvidence observeBurnNative(
            SolanaMintAttemptStore.AttemptRow attempt) {
        TxStatus status = observationClient.getSignatureStatuses(
                List.of(attempt.transactionSignature()), true).join()
                .get(attempt.transactionSignature());
        if (status == null || status.nil()) {
            return ObservationEvidence.pending("signature-status-absent");
        }
        if (status.confirmationStatus() != Commitment.FINALIZED) {
            return ObservationEvidence.pending("signature-not-finalized");
        }
        if (status.error() != null) {
            return ObservationEvidence.reverted(
                    status.slot(), "finalized-transaction-failed",
                    status.error().getClass().getSimpleName());
        }
        Tx transaction = observationClient.getTransaction(
                Commitment.FINALIZED, attempt.transactionSignature()).join();
        if (transaction == null || transaction.meta() == null) {
            return ObservationEvidence.pending("finalized-transaction-unavailable");
        }
        if (transaction.meta().error() != null) {
            return ObservationEvidence.reverted(
                    transaction.slot(), "finalized-metadata-failed",
                    transaction.meta().error().getClass().getSimpleName());
        }
        PublicKey feePayer = key(attempt.feePayer().publicKey(), "feePayer");
        PublicKey adminOwner = key(
                attempt.sourceOwner().orElseThrow(), "adminOwner");
        PublicKey mint = key(attempt.mintAddress(), "mintAddress");
        PublicKey adminAta = key(attempt.sourceAta().orElseThrow(), "adminAta");
        boolean identityMatches = Base58.encode(
                software.sava.core.tx.Transaction.getId(transaction.data()))
                .equals(attempt.transactionSignature());
        boolean instructionsMatch = identityMatches
                && codec.matchesExpectedBurnInstructions(
                        transaction.data(), feePayer, adminOwner, mint,
                        attempt.amount(), attempt.decimals());
        Optional<BurnTokenBalances> transactionBalances = burnTokenBalances(
                transaction, adminOwner, mint, adminAta, attempt.decimals());
        if (transactionBalances.isEmpty()) {
            return ObservationEvidence.mismatchedBurn(
                    transaction.slot(), transaction.blockTime(), instructionsMatch,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    "finalized-transaction-token-balances-missing");
        }
        AccountInfo<byte[]> mintAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        AccountInfo<byte[]> adminAccount = observationClient.getAccountInfo(
                Commitment.FINALIZED, adminAta).join();
        if (mintAccount == null || mintAccount.data() == null
                || adminAccount == null || adminAccount.data() == null) {
            return ObservationEvidence.mismatchedBurn(
                    transaction.slot(), transaction.blockTime(), instructionsMatch,
                    Optional.empty(), Optional.empty(), transactionBalances,
                    "finalized-account-missing");
        }
        boolean accountsMatch;
        try {
            validateMintAccountForBurn(mintAccount, mint);
            validateTokenAccount(adminAccount, adminAta, adminOwner, mint);
            accountsMatch = true;
        } catch (RuntimeException mismatch) {
            accountsMatch = false;
        }
        BigInteger supply = unsigned(Mint.read(mint, mintAccount.data()).supply());
        BigInteger adminBalance = unsigned(TokenAccount.read(
                adminAta, adminAccount.data()).amount());
        BigInteger supplyDecrease = attempt.preMintSupply().subtract(supply);
        BurnTokenBalances nativeBalances = transactionBalances.orElseThrow();
        BigInteger adminDecrease = nativeBalances.pre().subtract(nativeBalances.post());
        boolean exact = accountsMatch && instructionsMatch
                && nativeBalances.pre().equals(attempt.preSourceBalance().orElseThrow())
                && nativeBalances.post().equals(adminBalance)
                && adminDecrease.equals(attempt.amount())
                && supplyDecrease.equals(attempt.amount());
        return exact
                ? ObservationEvidence.confirmedBurn(
                        transaction.slot(), transaction.blockTime(), supply,
                        adminBalance, supplyDecrease, adminDecrease, nativeBalances)
                : ObservationEvidence.mismatchedBurn(
                        transaction.slot(), transaction.blockTime(), instructionsMatch,
                        Optional.of(supply), Optional.of(adminBalance),
                        transactionBalances, "finalized-burn-effect-mismatch");
    }

    private static Optional<BurnTokenBalances> burnTokenBalances(
            Tx transaction, PublicKey owner, PublicKey mint,
            PublicKey ata, int decimals) {
        try {
            int index = accountIndex(transaction.skeleton().parseAccounts(), ata);
            if (index < 0) return Optional.empty();
            Optional<BigInteger> pre = tokenBalance(
                    transaction.meta().preTokenBalances(), index, mint, owner, decimals);
            Optional<BigInteger> post = tokenBalance(
                    transaction.meta().postTokenBalances(), index, mint, owner, decimals);
            return pre.isPresent() && post.isPresent()
                    ? Optional.of(new BurnTokenBalances(
                            pre.orElseThrow(), post.orElseThrow()))
                    : Optional.empty();
        } catch (RuntimeException invalidMetadata) {
            return Optional.empty();
        }
    }

    private static Optional<TransactionTokenBalances> transferTokenBalances(
            Tx transaction, PublicKey sourceOwner, PublicKey destinationOwner,
            PublicKey mint, PublicKey sourceAta, PublicKey destinationAta,
            boolean destinationExisted, int decimals) {
        try {
            var accounts = transaction.skeleton().parseAccounts();
            int sourceIndex = accountIndex(accounts, sourceAta);
            int destinationIndex = accountIndex(accounts, destinationAta);
            if (sourceIndex < 0 || destinationIndex < 0) {
                return Optional.empty();
            }
            Optional<BigInteger> preSource = tokenBalance(
                    transaction.meta().preTokenBalances(), sourceIndex,
                    mint, sourceOwner, decimals);
            Optional<BigInteger> postSource = tokenBalance(
                    transaction.meta().postTokenBalances(), sourceIndex,
                    mint, sourceOwner, decimals);
            Optional<BigInteger> postDestination = tokenBalance(
                    transaction.meta().postTokenBalances(), destinationIndex,
                    mint, destinationOwner, decimals);
            Optional<BigInteger> preDestination;
            if (destinationExisted) {
                preDestination = tokenBalance(
                        transaction.meta().preTokenBalances(), destinationIndex,
                        mint, destinationOwner, decimals);
            } else if (transaction.meta().preTokenBalances().stream().anyMatch(
                    balance -> balance.accountIndex() == destinationIndex)) {
                return Optional.empty();
            } else {
                preDestination = Optional.of(BigInteger.ZERO);
            }
            if (preSource.isEmpty() || postSource.isEmpty()
                    || preDestination.isEmpty() || postDestination.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new TransactionTokenBalances(
                    preSource.orElseThrow(), postSource.orElseThrow(),
                    preDestination.orElseThrow(), postDestination.orElseThrow()));
        } catch (RuntimeException invalidMetadata) {
            return Optional.empty();
        }
    }

    private static int accountIndex(
            software.sava.core.accounts.meta.AccountMeta[] accounts,
            PublicKey address) {
        int found = -1;
        for (int index = 0; index < accounts.length; index++) {
            if (accounts[index].publicKey().equals(address)) {
                if (found >= 0) return -1;
                found = index;
            }
        }
        return found;
    }

    private static Optional<BigInteger> tokenBalance(
            List<TokenBalance> balances, int accountIndex, PublicKey mint,
            PublicKey owner, int decimals) {
        List<TokenBalance> matching = balances.stream()
                .filter(balance -> balance.accountIndex() == accountIndex)
                .toList();
        if (matching.size() != 1) return Optional.empty();
        TokenBalance balance = matching.getFirst();
        if (!mint.equals(balance.mint()) || !owner.equals(balance.owner())
                || !SolanaMintTransactionCodec.TOKEN_PROGRAM.equals(balance.programId())
                || balance.decimals() != decimals || balance.amount() == null
                || balance.amount().signum() < 0
                || balance.amount().compareTo(MAX_U64) > 0) {
            return Optional.empty();
        }
        return Optional.of(balance.amount());
    }

    private SolanaMintTransactionCodec.SignedTransaction assemble(
            SolanaMintAttemptStore.AttemptRow attempt) {
        List<SolanaMintAttemptStore.SignatureRow> signatures = attempts.signatures(
                attempt.operationId(), attempt.attemptId());
        if (signatures.size() != 2) {
            throw new IllegalStateException("Solana signature set is incomplete");
        }
        return codec.assemble(attempt.unsignedTransaction(), signatures.stream()
                .map(signature -> new SolanaMintTransactionCodec.AuthorizedSignature(
                        key(signature.publicKey(), "signaturePublicKey"), signature.bytes()))
                .toList());
    }

    private PreparedAttempt prepared(SolanaMintAttemptStore.AttemptRow attempt) {
        byte[] message = SolanaMintTransactionCodec.serializedMessage(
                attempt.unsignedTransaction());
        if (!sha256(message).equals(attempt.messageSha256())) {
            throw new IllegalStateException("durable Solana message digest changed");
        }
        String lifetime = sha256((attempt.clusterIdentity() + "\n"
                + attempt.recentBlockhash() + "\n" + attempt.lastValidBlockHeight())
                .getBytes(StandardCharsets.UTF_8));
        return new PreparedAttempt(
                message, "solana-sha256:" + attempt.messageSha256(),
                attempt.effectKind() == SolanaMintAttemptStore.EffectKind.MINT
                        ? attempt.feePayer().publicKey()
                        : attempt.sourceOwner().orElseThrow(),
                attempt.destinationOwner(),
                "solana-" + attempt.effectKind().name().toLowerCase(Locale.ROOT)
                        + ":" + attempt.nativeAttemptId(), lifetime,
                "max-lamports=" + attempt.maximumFeeLamports(),
                sha256(attempt.unsignedTransaction()), attempt.policyVersion(),
                evidence(attempt, "prepared"));
    }

    private SignedAttempt signedAttempt(SolanaMintAttemptStore.AttemptRow attempt) {
        return new SignedAttempt(
                attempt.operationId(), attempt.attemptId(),
                new NativeIdentity(attempt.transactionSignature()),
                evidence(attempt, "signed"));
    }

    private SubmissionResult retainedSubmission(
            SolanaMintAttemptStore.AttemptRow attempt) {
        NativeIdentity identity = attempt.transactionSignature() == null
                ? null : new NativeIdentity(attempt.transactionSignature());
        if (attempt.status() == SolanaMintAttemptStore.AttemptStatus.EXPIRED
                && attempt.effectKind() == SolanaMintAttemptStore.EffectKind.BURN) {
            return new SubmissionResult(
                    SubmissionClassification.RETRYABLE_NO_EFFECT, identity,
                    evidence(attempt, "submission-expired-replacement-safe"));
        }
        return switch (attempt.status()) {
            case ACCEPTED, CONFIRMED -> new SubmissionResult(
                    SubmissionClassification.ACCEPTED, identity,
                    evidence(attempt, "submission-accepted"));
            case REJECTED, REVERTED, MISMATCHED, EXPIRED -> new SubmissionResult(
                    SubmissionClassification.DEFINITIVELY_REJECTED, null,
                    evidence(attempt, "submission-rejected"));
            case AMBIGUOUS, SUBMISSION_STARTED -> new SubmissionResult(
                    SubmissionClassification.AMBIGUOUS, identity,
                    evidence(attempt, "submission-ambiguous"));
            case PREPARED, PARTIALLY_SIGNED, SIGNED -> throw new IllegalStateException(
                    "Solana attempt did not reach a submission outcome");
        };
    }

    private InquiryResult inquiry(
            SolanaMintAttemptStore.AttemptRow attempt, NativeIdentity identity,
            RetrySafety safety, String kind) {
        return new InquiryResult(
                attempt.operationId(), attempt.attemptId(), Optional.of(identity), safety,
                evidence(attempt, kind));
    }

    private SolanaMintAttemptStore.AttemptRow find(AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        SolanaMintAttemptStore.AttemptRow attempt = attempts.find(
                identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Solana mint attempt was not found"));
        identity.nativeIdentity().ifPresent(nativeIdentity -> {
            if (!nativeIdentity.value().equals(attempt.transactionSignature())) {
                throw new IllegalArgumentException("Solana native identity changed");
            }
        });
        return attempt;
    }

    private static SigningRequirement requirement(
            int order, SolanaMintAttemptStore.SignerContext signer) {
        return new SigningRequirement(
                order, signer.keyAlias(), signer.keyRole(), SettlementNetwork.SOLANA,
                SigningRequest.Mode.SOLANA_MESSAGE, SigningRequest.Algorithm.ED25519,
                signer.publicKey(), signer.keyVersion());
    }

    private static SolanaMintAttemptStore.SignerContext signer(
            String alias, SigningRequest.KeyRole role, String version, String publicKey) {
        return new SolanaMintAttemptStore.SignerContext(
                new KeyAlias(alias), role, version, publicKey);
    }

    private SolanaMintAttemptStore.SignerContext transferSigner(
            WalletTransferOperation operation) {
        boolean primary = configuration.destinationOwner().equals(
                operation.source().normalizedAddress());
        return signer(
                primary ? configuration.transferAuthorityKeyAlias()
                        : configuration.transferDestinationAuthorityKeyAlias(),
                SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                primary ? configuration.transferAuthorityKeyVersion()
                        : configuration.transferDestinationAuthorityKeyVersion(),
                operation.source().normalizedAddress());
    }

    private void validateTransfer(WalletTransferOperation operation) {
        var unit = operation.quantity().unit();
        String expectedDestination = operation.purpose()
                == WalletTransferOperation.Purpose.REDEMPTION_CUSTODY
                ? configuration.redemptionOwner()
                : configuration.transferDestinationOwner();
        boolean primarySource = configuration.destinationOwner().equals(
                operation.source().normalizedAddress());
        boolean registeredSource = primarySource
                || (operation.purpose()
                        == WalletTransferOperation.Purpose.REDEMPTION_CUSTODY
                    && configuration.transferDestinationOwner().equals(
                            operation.source().normalizedAddress()));
        String expectedAlias = primarySource
                ? configuration.transferAuthorityKeyAlias()
                : configuration.transferDestinationAuthorityKeyAlias();
        String expectedVersion = primarySource
                ? configuration.transferAuthorityKeyVersion()
                : configuration.transferDestinationAuthorityKeyVersion();
        if (operation.network() != SettlementNetwork.SOLANA
                || !configuration.assetId().equals(unit.assetId())
                || !configuration.unitId().equals(unit.unitId())
                || configuration.unitVersion() != unit.version()
                || configuration.decimals() != unit.scale()
                || !operation.quantity().atomicUnits().equals(BigInteger.valueOf(10_000))
                || !configuration.mintAddress().equals(operation.contractAddress())
                || !configuration.policyVersion().equals(
                        operation.finalityPolicyVersion())
                || !registeredSource
                || !expectedDestination.equals(
                        operation.destination().normalizedAddress())
                || !new KeyAlias(expectedAlias).equals(
                        operation.source().keyReference())
                || !expectedVersion.equals(
                        operation.source().keyVersion())) {
            throw new IllegalArgumentException(
                    "wallet transfer does not match the retained local Solana policy");
        }
    }

    private void requireFundedFeePayer(PublicKey feePayer) {
        BigInteger feeBalance = unsigned(
                submissionClient.getBalance(Commitment.FINALIZED, feePayer)
                        .join().lamports());
        if (feeBalance.compareTo(configuration.minimumFeePayerLamports()) < 0) {
            throw new IllegalStateException("local Solana fee payer has insufficient funds");
        }
    }

    private LatestBlockHash latestUsableBlockhash() {
        LatestBlockHash latest = submissionClient.getLatestBlockHash(
                configuration.preparationCommitment().nativeCommitment()).join();
        if (latest.lastValidBlockHeight() < 0
                || submissionClient.getBlockHeight(
                        configuration.preparationCommitment().nativeCommitment())
                        .join().height() > latest.lastValidBlockHeight()) {
            throw new IllegalStateException("local Solana blockhash validity is incoherent");
        }
        return latest;
    }

    private void validateMint(TokenOperation operation, OperationAttempt attempt) {
        if (operation.kind() != OperationKind.MINT
                || !operation.attemptIds().contains(attempt.attemptId())) {
            throw new IllegalArgumentException(
                    "only an authorized retained Solana mint attempt is supported");
        }
        var unit = operation.quantity().unit();
        if (!configuration.assetId().equals(unit.assetId())
                || !configuration.unitId().equals(unit.unitId())
                || configuration.unitVersion() != unit.version()
                || configuration.decimals() != unit.scale()
                || operation.quantity().atomicUnits().compareTo(MAX_U64) > 0) {
            throw new IllegalArgumentException(
                    "mint asset/unit does not match local Solana token policy");
        }
    }

    private void validateBurn(TokenOperation operation, OperationAttempt attempt) {
        if (operation.kind() != OperationKind.BURN
                || !operation.attemptIds().contains(attempt.attemptId())) {
            throw new IllegalArgumentException(
                    "only an authorized retained Solana burn attempt is supported");
        }
        var unit = operation.quantity().unit();
        if (!configuration.assetId().equals(unit.assetId())
                || !configuration.unitId().equals(unit.unitId())
                || configuration.unitVersion() != unit.version()
                || configuration.decimals() != unit.scale()
                || !operation.quantity().atomicUnits().equals(BigInteger.valueOf(10_000))) {
            throw new IllegalArgumentException(
                    "burn asset/unit does not match local Solana redemption policy");
        }
    }

    private void validateMintAccount(
            AccountInfo<byte[]> account, PublicKey mint, PublicKey authority) {
        Mint data = Mint.read(mint, account.data());
        if (!account.pubKey().equals(mint)
                || !account.owner().equals(SolanaMintTransactionCodec.TOKEN_PROGRAM)
                || data == null || !data.address().equals(mint)
                || !authority.equals(data.mintAuthority())
                || data.decimals() != configuration.decimals() || !data.initialized()
                || (data.freezeAuthority() != null
                    && !PublicKey.NONE.equals(data.freezeAuthority()))) {
            throw new IllegalStateException(
                    "configured Solana mint account does not match policy");
        }
    }

    private void validateMintAccountForBurn(
            AccountInfo<byte[]> account, PublicKey mint) {
        Mint data = Mint.read(mint, account.data());
        if (!account.pubKey().equals(mint)
                || !account.owner().equals(SolanaMintTransactionCodec.TOKEN_PROGRAM)
                || data == null || !data.address().equals(mint)
                || data.decimals() != configuration.decimals() || !data.initialized()
                || (data.freezeAuthority() != null
                    && !PublicKey.NONE.equals(data.freezeAuthority()))) {
            throw new IllegalStateException(
                    "configured Solana burn mint account does not match policy");
        }
    }

    private static void validateTokenAccount(
            AccountInfo<byte[]> account, PublicKey ata,
            PublicKey owner, PublicKey mint) {
        TokenAccount data = TokenAccount.read(ata, account.data());
        if (!account.pubKey().equals(ata)
                || !account.owner().equals(SolanaMintTransactionCodec.TOKEN_PROGRAM)
                || data == null || !data.address().equals(ata)
                || !data.owner().equals(owner) || !data.mint().equals(mint)
                || data.state() != AccountState.Initialized) {
            throw new IllegalStateException(
                    "existing associated token account does not match policy");
        }
    }

    private void verifyCluster(SolanaRpcClient client, String role) {
        try {
            if (!configuration.clusterIdentity().equals(client.getGenesisHash().join())) {
                throw new IllegalStateException(
                        role + " RPC is not the configured local Solana cluster");
            }
        } catch (CompletionException failure) {
            throw new IllegalStateException(role + " RPC cluster check failed", failure);
        }
    }

    private static boolean deterministicRpcRejection(RuntimeException failure) {
        Throwable cause = rootCause(failure);
        if (!(cause instanceof JsonRpcException rpc) || rpc.customError() == null) {
            return false;
        }
        String kind = rpc.customError().getClass().getSimpleName();
        return kind.contains("Preflight") || kind.contains("SignatureVerification")
                || kind.contains("BlockhashNotFound")
                || kind.contains("InsufficientFunds");
    }

    private static String structuredSafeCode(RuntimeException failure) {
        Throwable cause = rootCause(failure);
        if (cause instanceof JsonRpcException rpc && rpc.customError() != null) {
            String kind = rpc.customError().getClass().getSimpleName()
                    .replaceAll("[^A-Za-z0-9]", "")
                    .toLowerCase(Locale.ROOT);
            return "rpc-" + (kind.isBlank() ? "rejected" : kind).substring(
                    0, Math.min(kind.isBlank() ? 8 : kind.length(), 48));
        }
        return "rpc-rejected";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> AccountInfo<T> requiredAccount(
            AccountInfo<T> account, String label) {
        if (account == null || account.data() == null) {
            throw new IllegalStateException(label + " is unavailable");
        }
        return account;
    }

    private static PublicKey key(String value, String field) {
        try {
            PublicKey key = PublicKey.fromBase58Encoded(value);
            if (key.toByteArray().length != 32) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(field + " must be a 32-byte public key");
        }
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String routeSnapshot(TokenOperation operation) {
        return "route:local-solana-v1:"
                + operation.acceptanceContext().commandDigest().substring(0, 32);
    }

    private static String routeSnapshot(WalletTransferOperation operation) {
        return "route:local-solana-transfer-v1:"
                + operation.commandDigest().substring(0, 32);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static EvidenceRef evidence(
            SolanaMintAttemptStore.AttemptRow attempt, String kind) {
        return new EvidenceRef("internal:solana:" + kind + ":" + attempt.attemptId());
    }

    private static SolanaRpcClient client(Configuration configuration) {
        return SolanaRpcClient.build().endpoint(configuration.rpcUri())
                .requestTimeout(configuration.requestTimeout())
                .defaultCommitment(
                        configuration.preparationCommitment().nativeCommitment())
                .createClient();
    }

    @FunctionalInterface
    interface SubmissionTransport {
        String send(String base64Transaction);
    }

    public record Configuration(
            URI rpcUri,
            String clusterIdentity,
            String mintAddress,
            String destinationOwner,
            String feePayerPublicKey,
            String feePayerKeyAlias,
            String feePayerKeyVersion,
            String mintAuthorityPublicKey,
            String mintAuthorityKeyAlias,
            String mintAuthorityKeyVersion,
            String transferDestinationOwner,
            String transferAuthorityKeyAlias,
            String transferAuthorityKeyVersion,
            String transferDestinationAuthorityKeyAlias,
            String transferDestinationAuthorityKeyVersion,
            String redemptionOwner,
            String burnAuthorityKeyAlias,
            String burnAuthorityKeyVersion,
            String walletRegistryVersion,
            String assetId,
            String unitId,
            int unitVersion,
            int decimals,
            String policyVersion,
            CommitmentLevel preparationCommitment,
            CommitmentLevel observationCommitment,
            BigInteger minimumFeePayerLamports,
            BigInteger maximumFeeLamports,
            Duration requestTimeout) {

        public Configuration(
                URI rpcUri,
                String clusterIdentity,
                String mintAddress,
                String destinationOwner,
                String feePayerPublicKey,
                String feePayerKeyAlias,
                String feePayerKeyVersion,
                String mintAuthorityPublicKey,
                String mintAuthorityKeyAlias,
                String mintAuthorityKeyVersion,
                String transferDestinationOwner,
                String transferAuthorityKeyAlias,
                String transferAuthorityKeyVersion,
                String redemptionOwner,
                String burnAuthorityKeyAlias,
                String burnAuthorityKeyVersion,
                String walletRegistryVersion,
                String assetId,
                String unitId,
                int unitVersion,
                int decimals,
                String policyVersion,
                CommitmentLevel preparationCommitment,
                CommitmentLevel observationCommitment,
                BigInteger minimumFeePayerLamports,
                BigInteger maximumFeeLamports,
                Duration requestTimeout) {
            this(rpcUri, clusterIdentity, mintAddress, destinationOwner,
                    feePayerPublicKey, feePayerKeyAlias, feePayerKeyVersion,
                    mintAuthorityPublicKey, mintAuthorityKeyAlias,
                    mintAuthorityKeyVersion, transferDestinationOwner,
                    transferAuthorityKeyAlias, transferAuthorityKeyVersion,
                    transferAuthorityKeyAlias, transferAuthorityKeyVersion,
                    redemptionOwner, burnAuthorityKeyAlias, burnAuthorityKeyVersion,
                    walletRegistryVersion, assetId, unitId, unitVersion, decimals,
                    policyVersion, preparationCommitment, observationCommitment,
                    minimumFeePayerLamports, maximumFeeLamports, requestTimeout);
        }

        public Configuration(
                URI rpcUri,
                String clusterIdentity,
                String mintAddress,
                String destinationOwner,
                String feePayerPublicKey,
                String feePayerKeyAlias,
                String feePayerKeyVersion,
                String mintAuthorityPublicKey,
                String mintAuthorityKeyAlias,
                String mintAuthorityKeyVersion,
                String assetId,
                String unitId,
                int unitVersion,
                int decimals,
                String policyVersion,
                CommitmentLevel preparationCommitment,
                CommitmentLevel observationCommitment,
                BigInteger minimumFeePayerLamports,
                BigInteger maximumFeeLamports,
                Duration requestTimeout) {
            this(rpcUri, clusterIdentity, mintAddress, destinationOwner,
                    feePayerPublicKey, feePayerKeyAlias, feePayerKeyVersion,
                    mintAuthorityPublicKey, mintAuthorityKeyAlias,
                    mintAuthorityKeyVersion,
                    "86Cud6zB3MZRYcCBgYftqoZRZw1jVqQfDkobchgk9vir",
                    "local-solana:transfer-authority",
                    "local-solana-transfer-authority-v1",
                    "local-solana:transfer-authority",
                    "local-solana-transfer-authority-v1",
                    "9xQeWvG816bUx9EPfEZvT9YcDT4VQ5cMfbX6LEK2q4H",
                    "local-solana:burn-authority",
                    "local-solana-burn-authority-v1",
                    "local-solana-wallet-registry-v1",
                    assetId, unitId, unitVersion, decimals, policyVersion,
                    preparationCommitment, observationCommitment,
                    minimumFeePayerLamports, maximumFeeLamports, requestTimeout);
        }

        public Configuration {
            Objects.requireNonNull(rpcUri, "rpcUri");
            if (!"http".equalsIgnoreCase(rpcUri.getScheme())
                    || rpcUri.getUserInfo() != null
                    || rpcUri.getQuery() != null || rpcUri.getFragment() != null
                    || rpcUri.getPort() < 1
                    || !("127.0.0.1".equals(rpcUri.getHost())
                        || "localhost".equalsIgnoreCase(rpcUri.getHost())
                        || "::1".equals(rpcUri.getHost()))) {
                throw new IllegalArgumentException(
                        "local Solana RPC must be an uncredentialed loopback HTTP URI");
            }
            key(clusterIdentity, "clusterIdentity");
            key(mintAddress, "mintAddress");
            key(destinationOwner, "destinationOwner");
            key(transferDestinationOwner, "transferDestinationOwner");
            key(redemptionOwner, "redemptionOwner");
            key(feePayerPublicKey, "feePayerPublicKey");
            key(mintAuthorityPublicKey, "mintAuthorityPublicKey");
            requireText(feePayerKeyAlias, "feePayerKeyAlias", 128);
            requireText(feePayerKeyVersion, "feePayerKeyVersion", 256);
            requireText(mintAuthorityKeyAlias, "mintAuthorityKeyAlias", 128);
            requireText(mintAuthorityKeyVersion, "mintAuthorityKeyVersion", 256);
            requireText(transferAuthorityKeyAlias, "transferAuthorityKeyAlias", 128);
            requireText(transferAuthorityKeyVersion, "transferAuthorityKeyVersion", 256);
            requireText(transferDestinationAuthorityKeyAlias,
                    "transferDestinationAuthorityKeyAlias", 128);
            requireText(transferDestinationAuthorityKeyVersion,
                    "transferDestinationAuthorityKeyVersion", 256);
            requireText(burnAuthorityKeyAlias, "burnAuthorityKeyAlias", 128);
            requireText(burnAuthorityKeyVersion, "burnAuthorityKeyVersion", 256);
            requireText(walletRegistryVersion, "walletRegistryVersion", 128);
            if (destinationOwner.equals(transferDestinationOwner)) {
                throw new IllegalArgumentException(
                        "local Solana transfer owners must differ");
            }
            if (redemptionOwner.equals(destinationOwner)
                    || redemptionOwner.equals(transferDestinationOwner)) {
                throw new IllegalArgumentException(
                        "local Solana ADMIN redemption owner must be distinct");
            }
            requireText(assetId, "assetId", 64);
            requireText(unitId, "unitId", 64);
            requireText(policyVersion, "policyVersion", 256);
            if (!SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58().equals(
                        "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
                    || !SolanaMintTransactionCodec.ATA_PROGRAM.toBase58().equals(
                        "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
                    || unitVersion < 1 || decimals != 2) {
                throw new IllegalArgumentException(
                        "local Solana token route must use classic SPL with two decimals");
            }
            Objects.requireNonNull(preparationCommitment, "preparationCommitment");
            Objects.requireNonNull(observationCommitment, "observationCommitment");
            if (observationCommitment != CommitmentLevel.FINALIZED) {
                throw new IllegalArgumentException(
                        "local Solana observation commitment must be finalized");
            }
            if (minimumFeePayerLamports == null
                    || minimumFeePayerLamports.signum() <= 0
                    || maximumFeeLamports == null
                    || maximumFeeLamports.signum() <= 0
                    || maximumFeeLamports.compareTo(minimumFeePayerLamports) > 0) {
                throw new IllegalArgumentException(
                        "local Solana fee bounds are invalid");
            }
            if (requestTimeout == null || requestTimeout.isZero()
                    || requestTimeout.isNegative()
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "local Solana RPC timeout must be positive and at most 30 seconds");
            }
        }

        private static void requireText(String value, String field, int max) {
            if (value == null || value.isBlank() || value.length() > max) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }
    }

    public enum CommitmentLevel {
        CONFIRMED,
        FINALIZED;

        Commitment nativeCommitment() {
            return switch (this) {
                case CONFIRMED -> Commitment.CONFIRMED;
                case FINALIZED -> Commitment.FINALIZED;
            };
        }
    }

    private record TransactionTokenBalances(
            BigInteger preSource,
            BigInteger postSource,
            BigInteger preDestination,
            BigInteger postDestination) {
    }

    private record BurnTokenBalances(BigInteger pre, BigInteger post) {
    }

    private record ObservationEvidence(
            ObservationClassification classification,
            SolanaMintAttemptStore.ObservationStatus status,
            Optional<Long> slot,
            Optional<Long> blockTime,
            Optional<String> errorCode,
            boolean expectedInstructions,
            Optional<BigInteger> supply,
            Optional<BigInteger> balance,
            Optional<BigInteger> sourceBalance,
            Optional<BigInteger> transactionPreSourceBalance,
            Optional<BigInteger> transactionPostSourceBalance,
            Optional<BigInteger> transactionPreDestinationBalance,
            Optional<BigInteger> transactionPostDestinationBalance,
            Optional<BigInteger> supplyDelta,
            Optional<BigInteger> balanceDelta,
            Optional<BigInteger> sourceDelta,
            String evidenceKind) {

        static ObservationEvidence pending(String kind) {
            return new ObservationEvidence(
                    ObservationClassification.ABSENT_OR_PENDING,
                    SolanaMintAttemptStore.ObservationStatus.ABSENT_OR_PENDING,
                    Optional.empty(), Optional.empty(), Optional.empty(), false,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        static ObservationEvidence reverted(long slot, String kind, String error) {
            return new ObservationEvidence(
                    ObservationClassification.REVERTED,
                    SolanaMintAttemptStore.ObservationStatus.REVERTED,
                    Optional.of(slot), Optional.empty(), Optional.of(bounded(error)), false,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        static ObservationEvidence confirmed(
                long slot, java.util.OptionalLong blockTime,
                BigInteger supply, BigInteger balance,
                BigInteger supplyDelta, BigInteger balanceDelta) {
            return new ObservationEvidence(
                    ObservationClassification.CONFIRMED,
                    SolanaMintAttemptStore.ObservationStatus.CONFIRMED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), true,
                    Optional.of(supply), Optional.of(balance), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(supplyDelta), Optional.of(balanceDelta), Optional.empty(),
                    "mint-confirmed");
        }

        static ObservationEvidence mismatched(
                long slot, java.util.OptionalLong blockTime, boolean instructions,
                Optional<BigInteger> supply, Optional<BigInteger> balance, String kind) {
            return new ObservationEvidence(
                    ObservationClassification.MISMATCHED,
                    SolanaMintAttemptStore.ObservationStatus.MISMATCHED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), instructions,
                    supply, balance, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), kind);
        }

        static ObservationEvidence confirmedTransfer(
                long slot, java.util.OptionalLong blockTime,
                BigInteger supply, BigInteger sourceBalance,
                BigInteger destinationBalance, BigInteger sourceDelta,
                BigInteger destinationDelta,
                TransactionTokenBalances transactionBalances) {
            return new ObservationEvidence(
                    ObservationClassification.CONFIRMED,
                    SolanaMintAttemptStore.ObservationStatus.CONFIRMED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), true,
                    Optional.of(supply), Optional.of(destinationBalance),
                    Optional.of(sourceBalance),
                    Optional.of(transactionBalances.preSource()),
                    Optional.of(transactionBalances.postSource()),
                    Optional.of(transactionBalances.preDestination()),
                    Optional.of(transactionBalances.postDestination()),
                    Optional.of(BigInteger.ZERO),
                    Optional.of(destinationDelta), Optional.of(sourceDelta),
                    "transfer-confirmed");
        }

        static ObservationEvidence mismatchedTransfer(
                long slot, java.util.OptionalLong blockTime, boolean instructions,
                Optional<BigInteger> supply, Optional<BigInteger> sourceBalance,
                Optional<BigInteger> destinationBalance,
                Optional<TransactionTokenBalances> transactionBalances,
                String kind) {
            return new ObservationEvidence(
                    ObservationClassification.MISMATCHED,
                    SolanaMintAttemptStore.ObservationStatus.MISMATCHED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), instructions,
                    supply, destinationBalance, sourceBalance,
                    transactionBalances.map(TransactionTokenBalances::preSource),
                    transactionBalances.map(TransactionTokenBalances::postSource),
                    transactionBalances.map(TransactionTokenBalances::preDestination),
                    transactionBalances.map(TransactionTokenBalances::postDestination),
                    Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        static ObservationEvidence confirmedBurn(
                long slot, java.util.OptionalLong blockTime,
                BigInteger supply, BigInteger adminBalance,
                BigInteger supplyDecrease, BigInteger adminDecrease,
                BurnTokenBalances transactionBalances) {
            return new ObservationEvidence(
                    ObservationClassification.CONFIRMED,
                    SolanaMintAttemptStore.ObservationStatus.CONFIRMED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), true,
                    Optional.of(supply), Optional.empty(), Optional.of(adminBalance),
                    Optional.of(transactionBalances.pre()),
                    Optional.of(transactionBalances.post()),
                    Optional.empty(), Optional.empty(),
                    Optional.of(supplyDecrease), Optional.empty(),
                    Optional.of(adminDecrease), "burn-confirmed");
        }

        static ObservationEvidence mismatchedBurn(
                long slot, java.util.OptionalLong blockTime, boolean instructions,
                Optional<BigInteger> supply, Optional<BigInteger> adminBalance,
                Optional<BurnTokenBalances> transactionBalances, String kind) {
            return new ObservationEvidence(
                    ObservationClassification.MISMATCHED,
                    SolanaMintAttemptStore.ObservationStatus.MISMATCHED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), instructions,
                    supply, Optional.empty(), adminBalance,
                    transactionBalances.map(BurnTokenBalances::pre),
                    transactionBalances.map(BurnTokenBalances::post),
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        SolanaMintAttemptStore.ObservationDraft toDraft(String evidenceReference) {
            return new SolanaMintAttemptStore.ObservationDraft(
                    status, "finalized", slot, blockTime, errorCode,
                    expectedInstructions, supply, balance, sourceBalance,
                    transactionPreSourceBalance, transactionPostSourceBalance,
                    transactionPreDestinationBalance,
                    transactionPostDestinationBalance,
                    supplyDelta, balanceDelta, sourceDelta,
                    evidenceReference);
        }

        private static Optional<Long> boxed(java.util.OptionalLong value) {
            return value.isPresent() ? Optional.of(value.getAsLong()) : Optional.empty();
        }

        private static String bounded(String value) {
            String safe = value == null ? "unknown" : value.replaceAll(
                    "[^A-Za-z0-9_-]", "");
            return safe.substring(0, Math.min(safe.length(), 64));
        }
    }
}
