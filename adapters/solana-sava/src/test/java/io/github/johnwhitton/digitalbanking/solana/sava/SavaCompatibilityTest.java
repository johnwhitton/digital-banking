package io.github.johnwhitton.digitalbanking.solana.sava;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.ProgramDerivedAddress;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.Tx;
import software.sava.rpc.json.http.response.TxStatus;

/** Executable Java 25/Sava 25.8 compatibility proof against Phase 7A layouts. */
class SavaCompatibilityTest {

    private static final PublicKey SYSTEM_PROGRAM = key(
            "11111111111111111111111111111111");
    private static final PublicKey TOKEN_PROGRAM = key(
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    private static final PublicKey ATA_PROGRAM = key(
            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
    private static final PublicKey PHASE_7A_MINT = key(
            "83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");
    private static final PublicKey PHASE_7A_USER_1 = key(
            "5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG");
    private static final PublicKey PHASE_7A_USER_1_ATA = key(
            "3DcsmpToHbAyzfn1ZSM3V9XWeAifRv8y6GbXi6VxLQZw");
    private static final PublicKey PHASE_7A_MINT_AUTHORITY = key(
            "2AFnC96vrk2EPo4Qgd3GQN6ionzpEjDQbmUjjm4vWg2k");
    private static final byte[] BLOCKHASH = new byte[32];

    @Test
    void preservesPublicKeysProgramsAndCanonicalPhase7aAta() {
        byte[] allBytes = new byte[32];
        for (int index = 0; index < allBytes.length; index++) {
            allBytes[index] = (byte) index;
        }
        PublicKey roundTrip = PublicKey.fromBase58Encoded(
                PublicKey.createPubKey(allBytes).toBase58());
        assertArrayEquals(allBytes, roundTrip.toByteArray());

        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                TOKEN_PROGRAM.toBase58());
        assertEquals("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
                ATA_PROGRAM.toBase58());
        assertEquals(PHASE_7A_USER_1_ATA, associatedTokenAddress(
                PHASE_7A_USER_1, PHASE_7A_MINT));
    }

    @Test
    void encodesIdempotentAtaAndCheckedMintInstructionsAtFieldLevel() {
        Instruction createAta = createAta(
                PHASE_7A_MINT_AUTHORITY, PHASE_7A_USER_1,
                PHASE_7A_MINT, PHASE_7A_USER_1_ATA);
        assertEquals(ATA_PROGRAM, createAta.programId().publicKey());
        assertArrayEquals(new byte[] {1}, createAta.copyData(),
                "ATA discriminator 1 is CreateIdempotent");
        assertAccounts(createAta, List.of(
                expected(PHASE_7A_MINT_AUTHORITY, true, true),
                expected(PHASE_7A_USER_1_ATA, false, true),
                expected(PHASE_7A_USER_1, false, false),
                expected(PHASE_7A_MINT, false, false),
                expected(SYSTEM_PROGRAM, false, false),
                expected(TOKEN_PROGRAM, false, false)));

        Instruction mint = mintToChecked(
                PHASE_7A_MINT, PHASE_7A_USER_1_ATA,
                PHASE_7A_MINT_AUTHORITY, 10_000L, 2);
        assertEquals(TOKEN_PROGRAM, mint.programId().publicKey());
        assertAccounts(mint, List.of(
                expected(PHASE_7A_MINT, false, true),
                expected(PHASE_7A_USER_1_ATA, false, true),
                expected(PHASE_7A_MINT_AUTHORITY, true, false)));
        assertEquals(14, Byte.toUnsignedInt(mint.copyData()[0]),
                "classic SPL Token discriminator 14 is MintToChecked");
        assertEquals(10_000L, ByteBuffer.wrap(mint.copyData(), 1, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getLong());
        assertEquals(2, Byte.toUnsignedInt(mint.copyData()[9]));
    }

    @Test
    void compilesLegacyMessageAndAssemblesExternallyAuthorizedSignaturesInSignerOrder()
            throws Exception {
        KeyPair feePair = ed25519KeyPair();
        KeyPair authorityPair = ed25519KeyPair();
        PublicKey feePayer = savaPublicKey(feePair);
        PublicKey authority = savaPublicKey(authorityPair);
        PublicKey owner = PublicKey.createPubKey(HexFormat.of().parseHex(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        PublicKey mintAddress = PublicKey.createPubKey(HexFormat.of().parseHex(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        PublicKey ata = associatedTokenAddress(owner, mintAddress);
        Instruction createAta = createAta(feePayer, owner, mintAddress, ata);
        List<Instruction> instructions = List.of(
                createAta,
                mintToChecked(mintAddress, ata, authority, 10_000L, 2));

        Transaction transaction = Transaction.createTx(feePayer, instructions);
        transaction.setRecentBlockHash(BLOCKHASH);
        byte[] unsigned = transaction.serialized();
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(unsigned);

        assertTrue(skeleton.isLegacy());
        assertEquals(2, skeleton.numSignatures());
        assertEquals(1, skeleton.numReadonlySignedAccounts());
        assertEquals(4, skeleton.numReadonlyUnsignedAccounts());
        assertArrayEquals(BLOCKHASH, skeleton.blockHash());
        assertArrayEquals(new PublicKey[] {feePayer, authority},
                skeleton.parseSignerPublicKeys());
        Instruction[] compiled = skeleton.parseLegacyInstructions();
        assertEquals(2, compiled.length);
        assertEquals(ATA_PROGRAM, compiled[0].programId().publicKey());
        assertEquals(TOKEN_PROGRAM, compiled[1].programId().publicKey());
        assertArrayEquals(createAta.copyData(), compiled[0].copyData());
        assertArrayEquals(instructions.get(1).copyData(), compiled[1].copyData());
        assertArrayEquals(
                createAta.accounts().stream().map(AccountMeta::publicKey).toArray(PublicKey[]::new),
                compiled[0].accounts().stream().map(AccountMeta::publicKey)
                        .toArray(PublicKey[]::new));
        assertArrayEquals(
                instructions.get(1).accounts().stream().map(AccountMeta::publicKey)
                        .toArray(PublicKey[]::new),
                compiled[1].accounts().stream().map(AccountMeta::publicKey)
                        .toArray(PublicKey[]::new));

        Map<PublicKey, AccountMeta> accounts = new LinkedHashMap<>();
        for (AccountMeta account : skeleton.parseAccounts()) {
            accounts.put(account.publicKey(), account);
        }
        assertTrue(accounts.get(feePayer).signer());
        assertTrue(accounts.get(feePayer).write());
        assertTrue(accounts.get(authority).signer());
        assertFalse(accounts.get(authority).write());
        assertTrue(accounts.get(ata).write());
        assertTrue(accounts.get(mintAddress).write());
        assertFalse(accounts.get(owner).write());

        byte[] message = message(unsigned);
        byte[] feeSignature = sign(feePair.getPrivate(), message);
        byte[] authoritySignature = sign(authorityPair.getPrivate(), message);
        assertTrue(feePayer.verifySignature(message, feeSignature));
        assertTrue(authority.verifySignature(message, authoritySignature));

        transaction.sign(new AuthorizedSignatureSigner(
                feePayer, message, feeSignature));
        transaction.sign(new AuthorizedSignatureSigner(
                authority, message, authoritySignature));
        byte[] signed = transaction.serialized();
        assertArrayEquals(feeSignature, Arrays.copyOfRange(signed, 1, 65));
        assertArrayEquals(authoritySignature, Arrays.copyOfRange(signed, 65, 129));
        assertArrayEquals(feeSignature, Transaction.getId(signed));
        assertEquals(feePayer.toBase58(), skeleton.feePayer().toBase58());

        Transaction rebuilt = TransactionSkeleton.deserializeSkeleton(signed)
                .createTransaction();
        assertArrayEquals(message, message(rebuilt.serialized()));

        KeyPair wrongPair = ed25519KeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> transaction.sign(new AuthorizedSignatureSigner(
                        savaPublicKey(wrongPair), message,
                        sign(wrongPair.getPrivate(), message))));
        byte[] changed = message.clone();
        changed[changed.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizedSignatureSigner(
                        feePayer, message, feeSignature).sign(changed));
    }

    @Test
    void decodesClassicMintAndTokenAccountLayoutsWithoutLoss() {
        Mint expectedMint = new Mint(
                PHASE_7A_MINT, PHASE_7A_MINT_AUTHORITY,
                10_000L, 2, true, PublicKey.NONE);
        byte[] mintBytes = new byte[Mint.BYTES];
        expectedMint.write(mintBytes, 0);
        assertEquals(expectedMint, Mint.read(PHASE_7A_MINT, mintBytes));

        TokenAccount expectedToken = new TokenAccount(
                PHASE_7A_USER_1_ATA, PHASE_7A_MINT, PHASE_7A_USER_1,
                10_000L, 0, null, AccountState.Initialized,
                0, 0L, 0L, 0, null);
        byte[] tokenBytes = new byte[TokenAccount.BYTES];
        expectedToken.write(tokenBytes, 0);
        assertEquals(expectedToken,
                TokenAccount.read(PHASE_7A_USER_1_ATA, tokenBytes));
    }

    @Test
    void rpcClientParsesAgaveBlockhashStatusTransactionAccountsSlotAndCommitment()
            throws Exception {
        Transaction transaction = Transaction.createTx(
                PHASE_7A_MINT_AUTHORITY,
                mintToChecked(PHASE_7A_MINT, PHASE_7A_USER_1_ATA,
                        PHASE_7A_MINT_AUTHORITY, 10_000L, 2));
        transaction.setRecentBlockHash(BLOCKHASH);
        byte[] transactionBytes = transaction.serialized();

        Mint expectedMint = new Mint(PHASE_7A_MINT, PHASE_7A_MINT_AUTHORITY,
                10_000L, 2, true, PublicKey.NONE);
        byte[] mintBytes = new byte[Mint.BYTES];
        expectedMint.write(mintBytes, 0);
        TokenAccount expectedToken = new TokenAccount(
                PHASE_7A_USER_1_ATA, PHASE_7A_MINT, PHASE_7A_USER_1,
                10_000L, 0, null, AccountState.Initialized,
                0, 0L, 0L, 0, null);
        byte[] tokenBytes = new byte[TokenAccount.BYTES];
        expectedToken.write(tokenBytes, 0);

        try (RpcFixture fixture = new RpcFixture(
                transactionBytes, mintBytes, tokenBytes)) {
            SolanaRpcClient client = SolanaRpcClient.build()
                    .endpoint(fixture.endpoint())
                    .requestTimeout(Duration.ofSeconds(2))
                    .defaultCommitment(Commitment.FINALIZED)
                    .createClient();
            LatestBlockHash latest = client.getLatestBlockHash().join();
            assertEquals(321L, latest.context().slot());
            assertEquals(PublicKey.createPubKey(BLOCKHASH).toBase58(), latest.blockHash());
            assertEquals(471L, latest.lastValidBlockHeight());

            String signature = Transaction.getBase58Id(transactionBytes);
            TxStatus status = client.getSignatureStatuses(List.of(signature), true)
                    .join().get(signature);
            assertEquals(322L, status.slot());
            assertEquals(Commitment.FINALIZED, status.confirmationStatus());
            assertEquals(null, status.error());

            Tx observed = client.getTransaction(Commitment.FINALIZED, signature).join();
            assertEquals(322L, observed.slot());
            assertTrue(observed.isLegacy());
            assertArrayEquals(transactionBytes, observed.data());
            assertEquals(null, observed.meta().error());

            AccountInfo<Mint> mint = client.getAccountInfo(
                    Commitment.FINALIZED, PHASE_7A_MINT, Mint.FACTORY).join();
            assertEquals(TOKEN_PROGRAM, mint.owner());
            assertEquals(expectedMint, mint.data());
            AccountInfo<TokenAccount> token = client.getAccountInfo(
                    Commitment.FINALIZED, PHASE_7A_USER_1_ATA,
                    TokenAccount.FACTORY).join();
            assertEquals(TOKEN_PROGRAM, token.owner());
            assertEquals(expectedToken, token.data());
            assertEquals(323L, client.getSlot(Commitment.FINALIZED).join());
        }
    }

    private static Instruction createAta(
            PublicKey feePayer,
            PublicKey owner,
            PublicKey mint,
            PublicKey ata) {
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
            long amount,
            int decimals) {
        byte[] data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 14).putLong(amount).put((byte) decimals).array();
        return Instruction.createInstruction(TOKEN_PROGRAM, List.of(
                AccountMeta.createWrite(mint),
                AccountMeta.createWrite(destination),
                AccountMeta.createReadOnlySigner(authority)), data);
    }

    private static PublicKey associatedTokenAddress(PublicKey owner, PublicKey mint) {
        ProgramDerivedAddress derived = PublicKey.findProgramAddress(
                List.of(owner.toByteArray(), TOKEN_PROGRAM.toByteArray(), mint.toByteArray()),
                ATA_PROGRAM);
        return derived.publicKey();
    }

    private static PublicKey key(String value) {
        return PublicKey.fromBase58Encoded(value);
    }

    private static ExpectedAccount expected(PublicKey key, boolean signer, boolean writable) {
        return new ExpectedAccount(key, signer, writable);
    }

    private static void assertAccounts(Instruction instruction, List<ExpectedAccount> expected) {
        List<ExpectedAccount> actual = instruction.accounts().stream()
                .map(account -> new ExpectedAccount(
                        account.publicKey(), account.signer(), account.write()))
                .toList();
        assertEquals(expected, actual);
    }

    private static KeyPair ed25519KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        return generator.generateKeyPair();
    }

    private static PublicKey savaPublicKey(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        return PublicKey.createPubKey(
                Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private static byte[] sign(PrivateKey key, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }

    private static byte[] message(byte[] transaction) {
        TransactionSkeleton skeleton = TransactionSkeleton.deserializeSkeleton(transaction);
        int accountCountLength = skeleton.numIncludedAccounts() < 128 ? 1 : 2;
        int messageOffset = skeleton.recentBlockHashIndex()
                - 3 - accountCountLength - 32 * skeleton.numIncludedAccounts();
        return Arrays.copyOfRange(transaction, messageOffset, transaction.length);
    }

    private record ExpectedAccount(PublicKey key, boolean signer, boolean writable) {
    }

    /** Signature-only bridge: it cannot return or create a private key. */
    private record AuthorizedSignatureSigner(
            PublicKey publicKey, byte[] expectedMessage, byte[] signature) implements Signer {

        private AuthorizedSignatureSigner {
            expectedMessage = expectedMessage.clone();
            signature = signature.clone();
            if (signature.length != Transaction.SIGNATURE_LENGTH
                    || !publicKey.verifySignature(expectedMessage, signature)) {
                throw new IllegalArgumentException(
                        "authorized signature does not match the expected public key and message");
            }
        }

        @Override
        public PrivateKey privateKey() {
            throw new UnsupportedOperationException("signature-only bridge has no private key");
        }

        @Override
        public Signer createDedicatedSigner() {
            return this;
        }

        @Override
        public int sign(byte[] data, int messageOffset, int messageLength, int signatureOffset) {
            byte[] actual = Arrays.copyOfRange(data, messageOffset, messageOffset + messageLength);
            byte[] approved = sign(actual);
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
                int mismatch = Arrays.mismatch(expectedMessage, data);
                throw new IllegalArgumentException(
                        "message differs from authorized bytes: expectedLength="
                                + expectedMessage.length + ", actualLength=" + data.length
                                + ", firstMismatch=" + mismatch);
            }
            return signature.clone();
        }
    }

    private static final class RpcFixture implements AutoCloseable {

        private final HttpServer server;
        private final byte[] transaction;
        private final byte[] mint;
        private final byte[] token;

        private RpcFixture(byte[] transaction, byte[] mint, byte[] token) throws IOException {
            this.transaction = transaction.clone();
            this.mint = mint.clone();
            this.token = token.clone();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void respond(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            String result;
            if (request.contains("getLatestBlockhash")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":321},"
                        + "\"value\":{\"blockhash\":\""
                        + PublicKey.createPubKey(BLOCKHASH).toBase58()
                        + "\",\"lastValidBlockHeight\":471}}";
            } else if (request.contains("getSignatureStatuses")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":322},"
                        + "\"value\":[{\"slot\":322,\"confirmations\":null,"
                        + "\"err\":null,\"confirmationStatus\":\"finalized\"}]}";
            } else if (request.contains("getTransaction")) {
                result = "{\"slot\":322,\"blockTime\":1784451000,"
                        + "\"meta\":{\"err\":null,\"fee\":5000,"
                        + "\"preBalances\":[1],\"postBalances\":[1],"
                        + "\"preTokenBalances\":[],\"postTokenBalances\":[],"
                        + "\"innerInstructions\":[],\"logMessages\":[],"
                        + "\"rewards\":[],\"loadedAddresses\":{\"readonly\":[],"
                        + "\"writable\":[]},\"computeUnitsConsumed\":100},"
                        + "\"transaction\":[\"" + Base64.getEncoder().encodeToString(transaction)
                        + "\",\"base64\"],\"version\":\"legacy\"}";
            } else if (request.contains("getAccountInfo")) {
                byte[] data = request.contains(PHASE_7A_MINT.toBase58()) ? mint : token;
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":323},"
                        + "\"value\":{\"data\":[\""
                        + Base64.getEncoder().encodeToString(data)
                        + "\",\"base64\"],\"executable\":false,\"lamports\":1000000,"
                        + "\"owner\":\"" + TOKEN_PROGRAM.toBase58()
                        + "\",\"rentEpoch\":0,\"space\":" + data.length + "}}";
            } else if (request.contains("getSlot")) {
                result = "323";
            } else {
                result = "null";
            }
            byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                    + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
