package io.github.johnwhitton.digitalbanking.solana.sava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

class SolanaMintTransactionCodecTest {

    private static final String BLOCKHASH = "11111111111111111111111111111111";
    private final SolanaMintTransactionCodec codec = new SolanaMintTransactionCodec();

    @Test
    void buildsAndExternallySignsOneExactLegacyMintMessage() throws Exception {
        KeyPair feePair = keyPair();
        KeyPair authorityPair = keyPair();
        PublicKey feePayer = publicKey(feePair);
        PublicKey authority = publicKey(authorityPair);
        PublicKey owner = key("5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG");
        PublicKey mint = key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");

        SolanaMintTransactionCodec.PreparedMessage prepared = codec.prepare(
                feePayer, authority, owner, mint, BLOCKHASH,
                BigInteger.valueOf(10_000), 2, true);
        assertEquals("3DcsmpToHbAyzfn1ZSM3V9XWeAifRv8y6GbXi6VxLQZw",
                prepared.destinationAta().toBase58());
        assertEquals(List.of(feePayer, authority), prepared.requiredSigners());
        assertEquals(2, prepared.instructions().size());
        assertTrue(codec.matchesExpectedInstructions(
                prepared.unsignedTransaction(), feePayer, authority, owner, mint,
                BigInteger.valueOf(10_000), 2, true));

        SolanaMintTransactionCodec.AuthorizedSignature feeSignature =
                signature(feePair, prepared.message());
        SolanaMintTransactionCodec.SignedTransaction signed = codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        feeSignature, signature(authorityPair, prepared.message())));
        assertArrayEquals(feeSignature.bytes(), Arrays.copyOfRange(
                java.util.Base64.getDecoder().decode(signed.base64()), 1, 65));
        assertFalse(signed.primarySignature().isBlank());
        assertTrue(signed.base64().length() > prepared.unsignedTransaction().length);
        assertEquals(64, signed.sha256().length());
    }

    @Test
    void rejectsWrongQuantityMessageSignerOrderAndNativeIntent() throws Exception {
        KeyPair feePair = keyPair();
        KeyPair authorityPair = keyPair();
        KeyPair wrongPair = keyPair();
        PublicKey feePayer = publicKey(feePair);
        PublicKey authority = publicKey(authorityPair);
        PublicKey owner = key("5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG");
        PublicKey mint = key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");
        SolanaMintTransactionCodec.PreparedMessage prepared = codec.prepare(
                feePayer, authority, owner, mint, BLOCKHASH,
                BigInteger.valueOf(10_000), 2, false);

        assertThrows(IllegalArgumentException.class, () -> codec.prepare(
                feePayer, authority, owner, mint, BLOCKHASH,
                BigInteger.ZERO, 2, false));
        assertThrows(IllegalArgumentException.class, () -> codec.prepare(
                feePayer, authority, owner, mint, BLOCKHASH,
                new BigInteger("18446744073709551616"), 2, false));
        assertThrows(IllegalArgumentException.class, () -> codec.prepare(
                feePayer, authority, owner, mint, BLOCKHASH,
                BigInteger.ONE, 3, false));
        assertThrows(IllegalArgumentException.class, () -> codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        signature(authorityPair, prepared.message()),
                        signature(feePair, prepared.message()))));
        assertThrows(IllegalArgumentException.class, () -> codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        signature(feePair, prepared.message()),
                        signature(wrongPair, prepared.message()))));

        byte[] changedMessage = prepared.message();
        changedMessage[changedMessage.length - 1] ^= 1;
        SolanaMintTransactionCodec.AuthorizedSignature changed =
                new SolanaMintTransactionCodec.AuthorizedSignature(
                        feePayer, sign(feePair, changedMessage));
        assertThrows(IllegalArgumentException.class, () -> codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        changed, signature(authorityPair, prepared.message()))));

        assertFalse(codec.matchesExpectedInstructions(
                prepared.unsignedTransaction(), feePayer, authority, owner, mint,
                BigInteger.valueOf(10_001), 2, false));
        assertFalse(codec.matchesExpectedInstructions(
                prepared.unsignedTransaction(), feePayer, authority, owner,
                key("6LrPaQmHcveWvBDZAEoaRwtjtRZNGFAetBdAbNWDr7Wj"),
                BigInteger.valueOf(10_000), 2, false));
    }

    @Test
    void encodesTheFullUnsignedU64BoundaryWithoutFloatingPoint() throws Exception {
        KeyPair feePair = keyPair();
        KeyPair authorityPair = keyPair();
        SolanaMintTransactionCodec.PreparedMessage prepared = codec.prepare(
                publicKey(feePair), publicKey(authorityPair),
                key("5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG"),
                key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT"),
                BLOCKHASH, new BigInteger("18446744073709551615"), 2, false);
        byte[] data = prepared.instructions().getFirst().copyData();
        assertTrue(Arrays.equals(
                new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                Arrays.copyOfRange(data, 1, 9)));
    }

    @Test
    void buildsExactTransferCheckedWithCanonicalAtasAndOrderedAuthorities()
            throws Exception {
        KeyPair feePair = keyPair();
        KeyPair sourcePair = keyPair();
        PublicKey feePayer = publicKey(feePair);
        PublicKey sourceOwner = publicKey(sourcePair);
        PublicKey destinationOwner =
                key("86Cud6zB3MZRYcCBgYftqoZRZw1jVqQfDkobchgk9vir");
        PublicKey mint = key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");

        SolanaMintTransactionCodec.PreparedTransferMessage prepared =
                codec.prepareTransfer(
                        feePayer, sourceOwner, destinationOwner, mint, BLOCKHASH,
                        BigInteger.valueOf(10_000), 2, true);

        assertEquals(SolanaMintTransactionCodec.associatedTokenAddress(sourceOwner, mint),
                prepared.sourceAta());
        assertEquals(SolanaMintTransactionCodec.associatedTokenAddress(
                destinationOwner, mint), prepared.destinationAta());
        assertEquals(List.of(feePayer, sourceOwner), prepared.requiredSigners());
        assertEquals(2, prepared.instructions().size());
        assertArrayEquals(new byte[] {12, 16, 39, 0, 0, 0, 0, 0, 0, 2},
                prepared.instructions().getLast().copyData());
        assertTrue(codec.matchesExpectedTransferInstructions(
                prepared.unsignedTransaction(), feePayer, sourceOwner,
                destinationOwner, mint, BigInteger.valueOf(10_000), 2, true));
        assertFalse(codec.matchesExpectedTransferInstructions(
                prepared.unsignedTransaction(), feePayer, sourceOwner,
                destinationOwner, mint, BigInteger.valueOf(10_001), 2, true));

        SolanaMintTransactionCodec.SignedTransaction signed = codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        signature(feePair, prepared.message()),
                        signature(sourcePair, prepared.message())));
        assertFalse(signed.primarySignature().isBlank());
    }

    @Test
    void transferCheckedRejectsInvalidScaleUnsignedOverflowAndSignerMutation()
            throws Exception {
        KeyPair feePair = keyPair();
        KeyPair sourcePair = keyPair();
        KeyPair wrongPair = keyPair();
        PublicKey destinationOwner =
                key("86Cud6zB3MZRYcCBgYftqoZRZw1jVqQfDkobchgk9vir");
        PublicKey mint = key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");
        SolanaMintTransactionCodec.PreparedTransferMessage prepared =
                codec.prepareTransfer(
                        publicKey(feePair), publicKey(sourcePair), destinationOwner,
                        mint, BLOCKHASH, BigInteger.valueOf(10_000), 2, false);

        assertEquals(1, prepared.instructions().size());
        assertThrows(IllegalArgumentException.class, () -> codec.prepareTransfer(
                publicKey(feePair), publicKey(sourcePair), destinationOwner, mint,
                BLOCKHASH, BigInteger.ONE, 3, false));
        assertThrows(IllegalArgumentException.class, () -> codec.prepareTransfer(
                publicKey(feePair), publicKey(sourcePair), destinationOwner, mint,
                BLOCKHASH, new BigInteger("18446744073709551616"), 2, false));
        assertThrows(IllegalArgumentException.class, () -> codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        signature(feePair, prepared.message()),
                        signature(wrongPair, prepared.message()))));
    }

    @Test
    void buildsExactBurnCheckedFromTheCanonicalAdminAta() throws Exception {
        KeyPair feePair = keyPair();
        KeyPair adminPair = keyPair();
        PublicKey feePayer = publicKey(feePair);
        PublicKey adminOwner = publicKey(adminPair);
        PublicKey mint = key("83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT");

        SolanaMintTransactionCodec.PreparedBurnMessage prepared = codec.prepareBurn(
                feePayer, adminOwner, mint, BLOCKHASH,
                BigInteger.valueOf(10_000), 2);

        assertEquals(SolanaMintTransactionCodec.associatedTokenAddress(adminOwner, mint),
                prepared.sourceAta());
        assertEquals(List.of(feePayer, adminOwner), prepared.requiredSigners());
        assertEquals(1, prepared.instructions().size());
        assertArrayEquals(new byte[] {15, 16, 39, 0, 0, 0, 0, 0, 0, 2},
                prepared.instructions().getFirst().copyData());
        assertTrue(codec.matchesExpectedBurnInstructions(
                prepared.unsignedTransaction(), feePayer, adminOwner, mint,
                BigInteger.valueOf(10_000), 2));
        assertFalse(codec.matchesExpectedBurnInstructions(
                prepared.unsignedTransaction(), feePayer, adminOwner, mint,
                BigInteger.valueOf(9_999), 2));

        SolanaMintTransactionCodec.SignedTransaction signed = codec.assemble(
                prepared.unsignedTransaction(), List.of(
                        signature(feePair, prepared.message()),
                        signature(adminPair, prepared.message())));
        assertFalse(signed.primarySignature().isBlank());
    }

    private static SolanaMintTransactionCodec.AuthorizedSignature signature(
            KeyPair pair, byte[] message) throws Exception {
        return new SolanaMintTransactionCodec.AuthorizedSignature(
                publicKey(pair), sign(pair, message));
    }

    private static byte[] sign(KeyPair pair, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(message);
        return signature.sign();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        return generator.generateKeyPair();
    }

    private static PublicKey publicKey(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return PublicKey.createPubKey(
                Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private static PublicKey key(String value) {
        return PublicKey.fromBase58Encoded(value);
    }
}
