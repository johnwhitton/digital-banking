package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/** Ethereum-native EIP-1559 and bounded ERC-20 encoding kept inside the adapter. */
public final class EthereumTransactionCodec {

    private static final BigInteger CURVE_ORDER = Sign.CURVE_PARAMS.getN();
    private static final BigInteger HALF_CURVE_ORDER = CURVE_ORDER.shiftRight(1);

    public String mintCalldata(String recipient, BigInteger atomicAmount) {
        requireAddress(recipient, "recipient");
        requirePositive(atomicAmount, "atomicAmount");
        Function mint = new Function(
                "mint",
                List.of(new Address(recipient), new Uint256(atomicAmount)),
                List.of());
        return FunctionEncoder.encode(mint);
    }

    public String transferCalldata(String destination, BigInteger atomicAmount) {
        requireAddress(destination, "destination");
        requirePositive(atomicAmount, "atomicAmount");
        Function transfer = new Function(
                "transfer",
                List.of(new Address(destination), new Uint256(atomicAmount)),
                List.of());
        return FunctionEncoder.encode(transfer);
    }

    public String addressFromPublicKey(byte[] uncompressedPublicKey) {
        byte[] publicKey = Objects.requireNonNull(
                uncompressedPublicKey, "uncompressedPublicKey").clone();
        if (publicKey.length != 65 || publicKey[0] != 4) {
            throw new IllegalArgumentException(
                    "secp256k1 public key must use 65-byte uncompressed encoding");
        }
        return "0x" + Keys.getAddress(new BigInteger(1, Arrays.copyOfRange(publicKey, 1, 65)))
                .toLowerCase(Locale.ROOT);
    }

    public byte[] signingPayload(Transaction transaction) {
        return TransactionEncoder.encode(raw(transaction));
    }

    public byte[] signingDigest(Transaction transaction) {
        return Hash.sha3(signingPayload(transaction));
    }

    public String recoverAddress(byte[] digest, byte[] compactSignature) {
        Objects.requireNonNull(digest, "digest");
        if (digest.length != 32) {
            throw new IllegalArgumentException("digest must contain exactly 32 bytes");
        }
        ParsedSignature signature = parseSignature(compactSignature);
        BigInteger publicKey = Sign.recoverFromSignature(
                signature.recoveryId(),
                new ECDSASignature(signature.r(), signature.s()),
                digest);
        if (publicKey == null) {
            throw new IllegalArgumentException("signature cannot be recovered");
        }
        return "0x" + Keys.getAddress(publicKey).toLowerCase(Locale.ROOT);
    }

    public SignedTransaction encodeSigned(
            Transaction transaction, byte[] compactSignature, String expectedSigner) {
        String normalizedSigner = normalizeAddress(expectedSigner, "expectedSigner");
        byte[] digest = signingDigest(transaction);
        if (!recoverAddress(digest, compactSignature).equals(normalizedSigner)) {
            throw new IllegalArgumentException("signature does not match the expected signer");
        }
        ParsedSignature signature = parseSignature(compactSignature);
        byte[] bytes = TransactionEncoder.encode(
                raw(transaction),
                new Sign.SignatureData(
                        (byte) (signature.recoveryId() + 27),
                        unsigned32(signature.r()),
                        unsigned32(signature.s())));
        return new SignedTransaction(bytes, Hash.sha3(bytes));
    }

    private static RawTransaction raw(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return RawTransaction.createTransaction(
                transaction.chainId(),
                transaction.nonce(),
                transaction.gasLimit(),
                transaction.contractAddress(),
                transaction.value(),
                transaction.calldata(),
                transaction.maxPriorityFeePerGas(),
                transaction.maxFeePerGas());
    }

    private static ParsedSignature parseSignature(byte[] compactSignature) {
        Objects.requireNonNull(compactSignature, "compactSignature");
        if (compactSignature.length != 65) {
            throw new IllegalArgumentException("compact signature must contain exactly 65 bytes");
        }
        int recoveryId = Byte.toUnsignedInt(compactSignature[64]);
        if (recoveryId > 1) {
            throw new IllegalArgumentException("EIP-1559 y parity must be 0 or 1");
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(compactSignature, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(compactSignature, 32, 64));
        if (r.signum() <= 0 || r.compareTo(CURVE_ORDER) >= 0
                || s.signum() <= 0 || s.compareTo(HALF_CURVE_ORDER) > 0) {
            throw new IllegalArgumentException("signature must use valid r and canonical low-s values");
        }
        return new ParsedSignature(r, s, recoveryId);
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int length = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - length, result, result.length - length, length);
        return result;
    }

    private static void requireAddress(String address, String field) {
        normalizeAddress(address, field);
    }

    private static String normalizeAddress(String address, String field) {
        Objects.requireNonNull(address, field);
        String clean = Numeric.cleanHexPrefix(address);
        if (clean.length() != 40 || !clean.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException(field + " must be a 20-byte hexadecimal address");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static BigInteger requireNonNegative(BigInteger value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static BigInteger requirePositive(BigInteger value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public record Transaction(
            long chainId,
            BigInteger nonce,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            String contractAddress,
            BigInteger value,
            String calldata) {

        public Transaction {
            if (chainId <= 0) {
                throw new IllegalArgumentException("chainId must be positive");
            }
            nonce = requireNonNegative(nonce, "nonce");
            maxPriorityFeePerGas = requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
            maxFeePerGas = requireNonNegative(maxFeePerGas, "maxFeePerGas");
            if (maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0) {
                throw new IllegalArgumentException("maxFeePerGas must cover maxPriorityFeePerGas");
            }
            gasLimit = requirePositive(gasLimit, "gasLimit");
            contractAddress = normalizeAddress(contractAddress, "contractAddress");
            value = requireNonNegative(value, "value");
            Objects.requireNonNull(calldata, "calldata");
            if (!calldata.startsWith("0x") || (calldata.length() & 1) != 0
                    || !Numeric.cleanHexPrefix(calldata).matches("[0-9a-fA-F]+")) {
                throw new IllegalArgumentException("calldata must be non-empty hexadecimal bytes");
            }
        }
    }

    public record SignedTransaction(byte[] bytes, byte[] hash) {
        public SignedTransaction {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            hash = Objects.requireNonNull(hash, "hash").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }

    private record ParsedSignature(BigInteger r, BigInteger s, int recoveryId) {
    }
}
