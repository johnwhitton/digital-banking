package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

/** Package-local secp256k1 key mechanics shared by the two local signer modes. */
final class Secp256k1LocalKey {

    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    private static final HexFormat HEX = HexFormat.of();

    private final ECPrivateKeyParameters privateKey;
    private final ECPoint publicPoint;
    private final byte[] publicKey;

    private Secp256k1LocalKey(BigInteger scalar) {
        this.privateKey = new ECPrivateKeyParameters(scalar, DOMAIN);
        this.publicPoint = CURVE.getG().multiply(scalar).normalize();
        this.publicKey = publicPoint.getEncoded(false);
    }

    static Secp256k1LocalKey generate(SecureRandom random) {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(
                DOMAIN, Objects.requireNonNull(random, "random")));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        ECPrivateKeyParameters generated = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters publicPart = (ECPublicKeyParameters) pair.getPublic();
        Secp256k1LocalKey result = new Secp256k1LocalKey(generated.getD());
        if (!result.publicPoint.equals(publicPart.getQ().normalize())) {
            throw new IllegalStateException("generated secp256k1 public identity is inconsistent");
        }
        return result;
    }

    static Secp256k1LocalKey configured(char[] configured) {
        char[] value = Objects.requireNonNull(configured, "configured").clone();
        try {
            int offset;
            if (value.length == 64) {
                offset = 0;
            } else if (value.length == 66 && value[0] == '0' && value[1] == 'x') {
                offset = 2;
            } else {
                throw invalidKey();
            }
            byte[] bytes = new byte[32];
            for (int index = 0; index < bytes.length; index++) {
                int high = Character.digit(value[offset + index * 2], 16);
                int low = Character.digit(value[offset + index * 2 + 1], 16);
                if (high < 0 || low < 0) {
                    throw invalidKey();
                }
                bytes[index] = (byte) ((high << 4) | low);
            }
            try {
                BigInteger scalar = new BigInteger(1, bytes);
                if (scalar.signum() == 0 || scalar.compareTo(CURVE.getN()) >= 0) {
                    throw invalidKey();
                }
                return new Secp256k1LocalKey(scalar);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    byte[] publicKey() {
        return publicKey.clone();
    }

    String address() {
        byte[] digest = new Keccak.Digest256().digest(
                Arrays.copyOfRange(publicKey, 1, publicKey.length));
        return "0x" + HEX.formatHex(
                Arrays.copyOfRange(digest, digest.length - 20, digest.length))
                .toLowerCase(Locale.ROOT);
    }

    byte[] sign(byte[] digest, SecureRandom random) {
        byte[] material = Objects.requireNonNull(digest, "digest").clone();
        if (material.length != 32) {
            throw new IllegalArgumentException("EVM digest must contain exactly 32 bytes");
        }
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ParametersWithRandom(
                privateKey, Objects.requireNonNull(random, "random")));
        BigInteger[] components = signer.generateSignature(material);
        BigInteger r = components[0];
        BigInteger s = components[1];
        BigInteger halfOrder = CURVE.getN().shiftRight(1);
        if (s.compareTo(halfOrder) > 0) {
            s = CURVE.getN().subtract(s);
        }
        int recoveryId = recoveryId(publicPoint, material, r, s);
        byte[] signature = new byte[65];
        System.arraycopy(fixed32(r), 0, signature, 0, 32);
        System.arraycopy(fixed32(s), 0, signature, 32, 32);
        signature[64] = (byte) recoveryId;
        return signature;
    }

    private static int recoveryId(
            ECPoint expected, byte[] digest, BigInteger r, BigInteger s) {
        for (int candidate = 0; candidate < 4; candidate++) {
            ECPoint recovered = recover(digest, r, s, candidate);
            if (recovered != null && recovered.normalize().equals(expected.normalize())) {
                return candidate;
            }
        }
        throw new IllegalStateException("secp256k1 recovery identity is unavailable");
    }

    private static ECPoint recover(
            byte[] digest, BigInteger r, BigInteger s, int recoveryId) {
        BigInteger n = CURVE.getN();
        BigInteger x = r.add(n.multiply(BigInteger.valueOf(recoveryId / 2L)));
        if (x.compareTo(CURVE.getCurve().getField().getCharacteristic()) >= 0) {
            return null;
        }
        byte[] compressed = new byte[33];
        compressed[0] = (byte) ((recoveryId & 1) == 0 ? 0x02 : 0x03);
        System.arraycopy(fixed32(x), 0, compressed, 1, 32);
        ECPoint point;
        try {
            point = CURVE.getCurve().decodePoint(compressed);
        } catch (IllegalArgumentException invalidPoint) {
            return null;
        }
        if (!point.multiply(n).isInfinity()) {
            return null;
        }
        BigInteger inverseR = r.modInverse(n);
        BigInteger eInverse = new BigInteger(1, digest)
                .negate().mod(n).multiply(inverseR).mod(n);
        BigInteger sOverR = s.multiply(inverseR).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(
                CURVE.getG(), eInverse, point, sOverR).normalize();
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - copy, result, result.length - copy, copy);
        return result;
    }

    private static IllegalArgumentException invalidKey() {
        return new IllegalArgumentException(
                "configured secp256k1 key must be one canonical 32-byte scalar");
    }
}
