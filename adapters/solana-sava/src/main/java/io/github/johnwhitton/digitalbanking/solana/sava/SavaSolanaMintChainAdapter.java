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
import software.sava.rpc.json.http.response.Tx;
import software.sava.rpc.json.http.response.TxStatus;

/** Durable local-only classic-SPL mint adapter. Sava types remain inside this module. */
public final class SavaSolanaMintChainAdapter implements ChainPort {

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
        return new ChainCapabilities(true, false, true);
    }

    @Override
    public PreparedAttempt prepare(
            UUID deliveryId, TokenOperation operation, OperationAttempt attempt) {
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
                replacementParent, replacementSequence, configuration.clusterIdentity(),
                routeSnapshot(operation), SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58(),
                SolanaMintTransactionCodec.ATA_PROGRAM.toBase58(),
                configuration.mintAddress(), configuration.destinationOwner(),
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
                attempt.feePayer().publicKey(), attempt.destinationOwner(),
                "solana-mint:" + attempt.nativeAttemptId(), lifetime,
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
            key(feePayerPublicKey, "feePayerPublicKey");
            key(mintAuthorityPublicKey, "mintAuthorityPublicKey");
            requireText(feePayerKeyAlias, "feePayerKeyAlias", 128);
            requireText(feePayerKeyVersion, "feePayerKeyVersion", 256);
            requireText(mintAuthorityKeyAlias, "mintAuthorityKeyAlias", 128);
            requireText(mintAuthorityKeyVersion, "mintAuthorityKeyVersion", 256);
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

    private record ObservationEvidence(
            ObservationClassification classification,
            SolanaMintAttemptStore.ObservationStatus status,
            Optional<Long> slot,
            Optional<Long> blockTime,
            Optional<String> errorCode,
            boolean expectedInstructions,
            Optional<BigInteger> supply,
            Optional<BigInteger> balance,
            Optional<BigInteger> supplyDelta,
            Optional<BigInteger> balanceDelta,
            String evidenceKind) {

        static ObservationEvidence pending(String kind) {
            return new ObservationEvidence(
                    ObservationClassification.ABSENT_OR_PENDING,
                    SolanaMintAttemptStore.ObservationStatus.ABSENT_OR_PENDING,
                    Optional.empty(), Optional.empty(), Optional.empty(), false,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        static ObservationEvidence reverted(long slot, String kind, String error) {
            return new ObservationEvidence(
                    ObservationClassification.REVERTED,
                    SolanaMintAttemptStore.ObservationStatus.REVERTED,
                    Optional.of(slot), Optional.empty(), Optional.of(bounded(error)), false,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), kind);
        }

        static ObservationEvidence confirmed(
                long slot, java.util.OptionalLong blockTime,
                BigInteger supply, BigInteger balance,
                BigInteger supplyDelta, BigInteger balanceDelta) {
            return new ObservationEvidence(
                    ObservationClassification.CONFIRMED,
                    SolanaMintAttemptStore.ObservationStatus.CONFIRMED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), true,
                    Optional.of(supply), Optional.of(balance),
                    Optional.of(supplyDelta), Optional.of(balanceDelta), "mint-confirmed");
        }

        static ObservationEvidence mismatched(
                long slot, java.util.OptionalLong blockTime, boolean instructions,
                Optional<BigInteger> supply, Optional<BigInteger> balance, String kind) {
            return new ObservationEvidence(
                    ObservationClassification.MISMATCHED,
                    SolanaMintAttemptStore.ObservationStatus.MISMATCHED,
                    Optional.of(slot), boxed(blockTime), Optional.empty(), instructions,
                    supply, balance, Optional.empty(), Optional.empty(), kind);
        }

        SolanaMintAttemptStore.ObservationDraft toDraft(String evidenceReference) {
            return new SolanaMintAttemptStore.ObservationDraft(
                    status, "finalized", slot, blockTime, errorCode,
                    expectedInstructions, supply, balance, supplyDelta, balanceDelta,
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
