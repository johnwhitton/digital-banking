package io.github.johnwhitton.digitalbanking.solana.sava;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import software.sava.core.accounts.ProgramDerivedAddress;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;

/** Classic-SPL legacy transaction encoding with externally authorized signatures only. */
final class SolanaMintTransactionCodec {

    static final PublicKey SYSTEM_PROGRAM = key("11111111111111111111111111111111");
    static final PublicKey TOKEN_PROGRAM = key(
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    static final PublicKey ATA_PROGRAM = key(
            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
    private static final BigInteger MAX_U64 = new BigInteger("18446744073709551615");

    PreparedMessage prepare(
            PublicKey feePayer,
            PublicKey mintAuthority,
            PublicKey destinationOwner,
            PublicKey mint,
            String recentBlockhash,
            BigInteger amount,
            int decimals,
            boolean createAta) {
        Objects.requireNonNull(feePayer, "feePayer");
        Objects.requireNonNull(mintAuthority, "mintAuthority");
        Objects.requireNonNull(destinationOwner, "destinationOwner");
        Objects.requireNonNull(mint, "mint");
        requireAmount(amount);
        if (decimals != 2) {
            throw new IllegalArgumentException("local USDZELLE requires two decimals");
        }
        PublicKey destinationAta = associatedTokenAddress(destinationOwner, mint);
        Instruction checkedMint = mintToChecked(
                mint, destinationAta, mintAuthority, amount, decimals);
        List<Instruction> instructions = createAta
                ? List.of(createAta(feePayer, destinationOwner, mint, destinationAta), checkedMint)
                : List.of(checkedMint);
        Transaction transaction = Transaction.createTx(feePayer, instructions);
        transaction.setRecentBlockHash(recentBlockhash);
        if (transaction.exceedsSizeLimit()) {
            throw new IllegalArgumentException("Solana mint transaction exceeds native limit");
        }
        byte[] unsigned = transaction.serialized();
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(unsigned);
        if (!skeleton.isLegacy()) {
            throw new IllegalStateException("bounded Solana mint must use a legacy message");
        }
        PublicKey[] signers = skeleton.parseSignerPublicKeys();
        if (!Arrays.equals(signers, new PublicKey[] {feePayer, mintAuthority})) {
            throw new IllegalStateException("legacy message signer ordering is not canonical");
        }
        byte[] message = serializedMessage(unsigned);
        byte[] instructionBytes = instructions.stream()
                .flatMap(instruction -> java.util.stream.Stream.of(
                        instruction.programId().publicKey().toByteArray(),
                        instruction.copyData()))
                .reduce(new byte[0], SolanaMintTransactionCodec::concat);
        return new PreparedMessage(
                unsigned, message, destinationAta, List.copyOf(instructions),
                List.of(signers), sha256(message), sha256(instructionBytes));
    }

    PreparedTransferMessage prepareTransfer(
            PublicKey feePayer,
            PublicKey sourceOwner,
            PublicKey destinationOwner,
            PublicKey mint,
            String recentBlockhash,
            BigInteger amount,
            int decimals,
            boolean createDestinationAta) {
        Objects.requireNonNull(feePayer, "feePayer");
        Objects.requireNonNull(sourceOwner, "sourceOwner");
        Objects.requireNonNull(destinationOwner, "destinationOwner");
        Objects.requireNonNull(mint, "mint");
        requireAmount(amount);
        if (decimals != 2) {
            throw new IllegalArgumentException("local USDZELLE requires two decimals");
        }
        PublicKey sourceAta = associatedTokenAddress(sourceOwner, mint);
        PublicKey destinationAta = associatedTokenAddress(destinationOwner, mint);
        Instruction checkedTransfer = transferChecked(
                sourceAta, mint, destinationAta, sourceOwner, amount, decimals);
        List<Instruction> instructions = createDestinationAta
                ? List.of(createAta(
                        feePayer, destinationOwner, mint, destinationAta), checkedTransfer)
                : List.of(checkedTransfer);
        Transaction transaction = Transaction.createTx(feePayer, instructions);
        transaction.setRecentBlockHash(recentBlockhash);
        if (transaction.exceedsSizeLimit()) {
            throw new IllegalArgumentException(
                    "Solana transfer transaction exceeds native limit");
        }
        byte[] unsigned = transaction.serialized();
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(unsigned);
        if (!skeleton.isLegacy()) {
            throw new IllegalStateException("bounded Solana transfer must use a legacy message");
        }
        PublicKey[] signers = skeleton.parseSignerPublicKeys();
        if (!Arrays.equals(signers, new PublicKey[] {feePayer, sourceOwner})) {
            throw new IllegalStateException("legacy message signer ordering is not canonical");
        }
        byte[] message = serializedMessage(unsigned);
        byte[] instructionBytes = instructions.stream()
                .flatMap(instruction -> java.util.stream.Stream.of(
                        instruction.programId().publicKey().toByteArray(),
                        instruction.copyData()))
                .reduce(new byte[0], SolanaMintTransactionCodec::concat);
        return new PreparedTransferMessage(
                unsigned, message, sourceAta, destinationAta,
                List.copyOf(instructions), List.of(signers),
                sha256(message), sha256(instructionBytes));
    }

    SignedTransaction assemble(
            byte[] unsignedTransaction,
            List<AuthorizedSignature> signatures) {
        byte[] unsigned = Objects.requireNonNull(
                unsignedTransaction, "unsignedTransaction").clone();
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(unsigned);
        PublicKey[] required = skeleton.parseSignerPublicKeys();
        if (!skeleton.isLegacy() || required.length != signatures.size()) {
            throw new IllegalArgumentException("authorized signature set is incomplete");
        }
        byte[] message = serializedMessage(unsigned);
        Transaction transaction = skeleton.createTransaction();
        for (int order = 0; order < signatures.size(); order++) {
            AuthorizedSignature signature = signatures.get(order);
            if (!required[order].equals(signature.publicKey())) {
                throw new IllegalArgumentException(
                        "signature order does not match required signer order");
            }
            transaction.sign(new AuthorizedSignatureSigner(
                    signature.publicKey(), message, signature.bytes()));
        }
        byte[] signed = transaction.serialized();
        if (!Arrays.equals(message, serializedMessage(signed))) {
            throw new IllegalStateException("signature assembly changed the authorized message");
        }
        for (int order = 0; order < signatures.size(); order++) {
            int offset = compactSignatureCountLength(signed) + order * Transaction.SIGNATURE_LENGTH;
            if (!Arrays.equals(signatures.get(order).bytes(),
                    Arrays.copyOfRange(signed, offset, offset + Transaction.SIGNATURE_LENGTH))) {
                throw new IllegalStateException("assembled signature ordering changed");
            }
        }
        return new SignedTransaction(
                Base64.getEncoder().encodeToString(signed),
                Transaction.getBase58Id(signed), sha256(signed));
    }

    boolean matchesExpectedInstructions(
            byte[] serializedTransaction,
            PublicKey feePayer,
            PublicKey mintAuthority,
            PublicKey destinationOwner,
            PublicKey mint,
            BigInteger amount,
            int decimals,
            boolean includesAta) {
        try {
            TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(
                    serializedTransaction);
            Instruction[] observed = skeleton.parseLegacyInstructions();
            PublicKey ata = associatedTokenAddress(destinationOwner, mint);
            List<Instruction> expected = includesAta
                    ? List.of(createAta(feePayer, destinationOwner, mint, ata),
                            mintToChecked(mint, ata, mintAuthority, amount, decimals))
                    : List.of(mintToChecked(mint, ata, mintAuthority, amount, decimals));
            if (observed.length != expected.size()) {
                return false;
            }
            for (int index = 0; index < observed.length; index++) {
                Instruction left = observed[index];
                Instruction right = expected.get(index);
                if (!left.programId().publicKey().equals(right.programId().publicKey())
                        || !Arrays.equals(left.copyData(), right.copyData())
                        || !left.accounts().stream().map(AccountMeta::publicKey).toList()
                                .equals(right.accounts().stream()
                                        .map(AccountMeta::publicKey).toList())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    boolean matchesExpectedTransferInstructions(
            byte[] serializedTransaction,
            PublicKey feePayer,
            PublicKey sourceOwner,
            PublicKey destinationOwner,
            PublicKey mint,
            BigInteger amount,
            int decimals,
            boolean includesDestinationAta) {
        try {
            PublicKey sourceAta = associatedTokenAddress(sourceOwner, mint);
            PublicKey destinationAta = associatedTokenAddress(destinationOwner, mint);
            List<Instruction> expected = includesDestinationAta
                    ? List.of(createAta(
                                    feePayer, destinationOwner, mint, destinationAta),
                            transferChecked(sourceAta, mint, destinationAta,
                                    sourceOwner, amount, decimals))
                    : List.of(transferChecked(sourceAta, mint, destinationAta,
                            sourceOwner, amount, decimals));
            Instruction[] observed = TransactionSkeleton.deserializeSkeleton(
                    serializedTransaction).parseLegacyInstructions();
            if (observed.length != expected.size()) {
                return false;
            }
            for (int index = 0; index < observed.length; index++) {
                Instruction left = observed[index];
                Instruction right = expected.get(index);
                if (!left.programId().publicKey().equals(right.programId().publicKey())
                        || !Arrays.equals(left.copyData(), right.copyData())
                        || !left.accounts().stream().map(AccountMeta::publicKey).toList()
                                .equals(right.accounts().stream()
                                        .map(AccountMeta::publicKey).toList())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    static PublicKey associatedTokenAddress(PublicKey owner, PublicKey mint) {
        ProgramDerivedAddress derived = PublicKey.findProgramAddress(
                List.of(owner.toByteArray(), TOKEN_PROGRAM.toByteArray(), mint.toByteArray()),
                ATA_PROGRAM);
        return derived.publicKey();
    }

    private static Instruction createAta(
            PublicKey feePayer, PublicKey owner, PublicKey mint, PublicKey ata) {
        return Instruction.createInstruction(ATA_PROGRAM, List.of(
                AccountMeta.createWritableSigner(feePayer),
                AccountMeta.createWrite(ata),
                AccountMeta.createRead(owner),
                AccountMeta.createRead(mint),
                AccountMeta.createRead(SYSTEM_PROGRAM),
                AccountMeta.createRead(TOKEN_PROGRAM)), new byte[] {1});
    }

    private static Instruction mintToChecked(
            PublicKey mint,
            PublicKey destination,
            PublicKey authority,
            BigInteger amount,
            int decimals) {
        requireAmount(amount);
        byte[] data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 14).putLong(amount.longValue()).put((byte) decimals).array();
        return Instruction.createInstruction(TOKEN_PROGRAM, List.of(
                AccountMeta.createWrite(mint),
                AccountMeta.createWrite(destination),
                AccountMeta.createReadOnlySigner(authority)), data);
    }

    private static Instruction transferChecked(
            PublicKey source,
            PublicKey mint,
            PublicKey destination,
            PublicKey authority,
            BigInteger amount,
            int decimals) {
        requireAmount(amount);
        byte[] data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 12).putLong(amount.longValue()).put((byte) decimals).array();
        return Instruction.createInstruction(TOKEN_PROGRAM, List.of(
                AccountMeta.createWrite(source),
                AccountMeta.createRead(mint),
                AccountMeta.createWrite(destination),
                AccountMeta.createReadOnlySigner(authority)), data);
    }

    private static void requireAmount(BigInteger amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_U64) > 0) {
            throw new IllegalArgumentException(
                    "Solana amount must fit an unsigned 64-bit integer");
        }
    }

    static byte[] serializedMessage(byte[] transaction) {
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(transaction);
        int accountCountLength = compactU16Length(skeleton.numIncludedAccounts());
        int messageOffset = skeleton.recentBlockHashIndex()
                - 3 - accountCountLength - PublicKey.PUBLIC_KEY_LENGTH
                        * skeleton.numIncludedAccounts();
        if (messageOffset <= 0 || messageOffset >= transaction.length) {
            throw new IllegalArgumentException("serialized transaction framing is invalid");
        }
        return Arrays.copyOfRange(transaction, messageOffset, transaction.length);
    }

    private static int compactSignatureCountLength(byte[] transaction) {
        int first = Byte.toUnsignedInt(transaction[0]);
        if ((first & 0x80) == 0) {
            return 1;
        }
        if (transaction.length > 1 && (Byte.toUnsignedInt(transaction[1]) & 0x80) == 0) {
            return 2;
        }
        throw new IllegalArgumentException("signature count framing is unsupported");
    }

    private static int compactU16Length(int value) {
        return value < 128 ? 1 : value < 16_384 ? 2 : 3;
    }

    private static PublicKey key(String value) {
        return PublicKey.fromBase58Encoded(value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    record PreparedMessage(
            byte[] unsignedTransaction,
            byte[] message,
            PublicKey destinationAta,
            List<Instruction> instructions,
            List<PublicKey> requiredSigners,
            String messageSha256,
            String instructionSha256) {

        PreparedMessage {
            unsignedTransaction = unsignedTransaction.clone();
            message = message.clone();
            instructions = List.copyOf(instructions);
            requiredSigners = List.copyOf(requiredSigners);
        }

        @Override public byte[] unsignedTransaction() { return unsignedTransaction.clone(); }
        @Override public byte[] message() { return message.clone(); }
    }

    record PreparedTransferMessage(
            byte[] unsignedTransaction,
            byte[] message,
            PublicKey sourceAta,
            PublicKey destinationAta,
            List<Instruction> instructions,
            List<PublicKey> requiredSigners,
            String messageSha256,
            String instructionSha256) {

        PreparedTransferMessage {
            unsignedTransaction = unsignedTransaction.clone();
            message = message.clone();
            Objects.requireNonNull(sourceAta, "sourceAta");
            Objects.requireNonNull(destinationAta, "destinationAta");
            instructions = List.copyOf(instructions);
            requiredSigners = List.copyOf(requiredSigners);
        }

        @Override public byte[] unsignedTransaction() { return unsignedTransaction.clone(); }
        @Override public byte[] message() { return message.clone(); }
    }

    record AuthorizedSignature(PublicKey publicKey, byte[] bytes) {
        AuthorizedSignature {
            Objects.requireNonNull(publicKey, "publicKey");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length != Transaction.SIGNATURE_LENGTH) {
                throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    record SignedTransaction(String base64, String primarySignature, String sha256) {
    }

    /** Sava receives only a verified public signature and cannot reach raw key material. */
    private record AuthorizedSignatureSigner(
            PublicKey publicKey, byte[] expectedMessage, byte[] signature) implements Signer {

        private AuthorizedSignatureSigner {
            expectedMessage = expectedMessage.clone();
            signature = signature.clone();
            if (signature.length != Transaction.SIGNATURE_LENGTH
                    || !publicKey.verifySignature(expectedMessage, signature)) {
                throw new IllegalArgumentException(
                        "authorized signature does not match the expected signer and message");
            }
        }

        @Override public PrivateKey privateKey() {
            throw new UnsupportedOperationException("signature-only bridge has no private key");
        }

        @Override public Signer createDedicatedSigner() { return this; }

        @Override
        public int sign(byte[] data, int messageOffset, int messageLength, int signatureOffset) {
            byte[] approved = sign(data, messageOffset, messageLength);
            System.arraycopy(approved, 0, data, signatureOffset, approved.length);
            return approved.length;
        }

        @Override
        public byte[] sign(byte[] data, int offset, int length) {
            return sign(Arrays.copyOfRange(data, offset, offset + length));
        }

        @Override
        public byte[] sign(byte[] data) {
            if (!Arrays.equals(expectedMessage, data)) {
                throw new IllegalArgumentException("message differs from authorized bytes");
            }
            return signature.clone();
        }
    }
}
