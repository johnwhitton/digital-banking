package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

class EthereumTransactionCodecTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String CONTRACT = "0x1111111111111111111111111111111111111111";
    private static final String RECIPIENT = "0x2222222222222222222222222222222222222222";
    private static final String CAST_UNSIGNED = "02f86f827a6907843b9aca0084773594008301d4c094"
            + "111111111111111111111111111111111111111180b84440c10f19"
            + "0000000000000000000000002222222222222222222222222222222222222222"
            + "0000000000000000000000000000000000000000000000000000000000003039c0";
    private static final String CAST_DIGEST =
            "f930b8cf1f2a98036ef4a4454bad27136d672b2414095fbd7cd40f693bbfa8ce";

    private final EthereumTransactionCodec codec = new EthereumTransactionCodec();

    @Test
    void matchesCastMintAbiAndEip1559SigningVector() {
        EthereumTransactionCodec.Transaction transaction = transaction();

        assertEquals("40c10f19", codec.mintCalldata(RECIPIENT, BigInteger.valueOf(12_345))
                .substring(2, 10));
        assertArrayEquals(HEX.parseHex(CAST_UNSIGNED), codec.signingPayload(transaction));
        assertArrayEquals(HEX.parseHex(CAST_DIGEST), codec.signingDigest(transaction));
    }

    @Test
    void encodesExactDirectTransferWithoutAnArbitraryCallSurface() {
        String calldata = codec.transferCalldata(RECIPIENT, BigInteger.valueOf(10_000));

        assertEquals("a9059cbb", calldata.substring(2, 10));
        assertEquals("0".repeat(24) + RECIPIENT.substring(2),
                calldata.substring(10, 74));
        assertEquals("0".repeat(60) + "2710", calldata.substring(74));
        assertThrows(IllegalArgumentException.class,
                () -> codec.transferCalldata(RECIPIENT, BigInteger.ZERO));
    }

    @Test
    void encodesOnlyLowSCompactSignatureFromExpectedSigner() throws Exception {
        ECKeyPair key = Keys.createEcKeyPair();
        EthereumTransactionCodec.Transaction transaction = transaction();
        byte[] digest = codec.signingDigest(transaction);
        Sign.SignatureData signed = Sign.signMessage(digest, key, false);
        byte[] compact = compact(signed);
        String expectedAddress = "0x" + Keys.getAddress(key.getPublicKey());

        byte[] uncompressedPublicKey = new byte[65];
        uncompressedPublicKey[0] = 4;
        byte[] publicKey = unsigned64(key.getPublicKey());
        System.arraycopy(publicKey, 0, uncompressedPublicKey, 1, publicKey.length);
        assertEquals(expectedAddress, codec.addressFromPublicKey(uncompressedPublicKey));

        EthereumTransactionCodec.SignedTransaction encoded =
                codec.encodeSigned(transaction, compact, expectedAddress);

        assertEquals(expectedAddress, codec.recoverAddress(digest, compact));
        assertArrayEquals(encoded.hash(), org.web3j.crypto.Hash.sha3(encoded.bytes()));

        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeSigned(transaction, compact,
                        "0x3333333333333333333333333333333333333333"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeSigned(transaction, Arrays.copyOf(compact, 64), expectedAddress));

        byte[] highS = compact.clone();
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(compact, 32, 64));
        byte[] changed = unsigned32(Sign.CURVE_PARAMS.getN().subtract(s));
        System.arraycopy(changed, 0, highS, 32, 32);
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeSigned(transaction, highS, expectedAddress));
    }

    private EthereumTransactionCodec.Transaction transaction() {
        return new EthereumTransactionCodec.Transaction(
                31_337L, BigInteger.valueOf(7), BigInteger.valueOf(1_000_000_000L),
                BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(120_000),
                CONTRACT, BigInteger.ZERO,
                codec.mintCalldata(RECIPIENT, BigInteger.valueOf(12_345)));
    }

    private static byte[] compact(Sign.SignatureData signature) {
        byte[] result = new byte[65];
        System.arraycopy(unsigned32(new BigInteger(1, signature.getR())), 0, result, 0, 32);
        System.arraycopy(unsigned32(new BigInteger(1, signature.getS())), 0, result, 32, 32);
        int v = new BigInteger(1, signature.getV()).intValueExact();
        result[64] = (byte) (v >= 27 ? v - 27 : v);
        return result;
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int length = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - length, result, result.length - length, length);
        return result;
    }

    private static byte[] unsigned64(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[64];
        int length = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - length, result, result.length - length, length);
        return result;
    }
}
