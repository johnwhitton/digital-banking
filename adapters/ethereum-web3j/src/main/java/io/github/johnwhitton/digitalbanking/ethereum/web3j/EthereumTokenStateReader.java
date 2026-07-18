package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

/** Reads exact ERC-20 balances and supply at one explicit canonical block. */
final class EthereumTokenStateReader {

    private final Web3j client;

    EthereumTokenStateReader(Web3j client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    EthereumRedemptionBalanceStore.Snapshot latest(
            EthereumRedemptionBalanceStore.Context context, Instant observedAt)
            throws IOException {
        BigInteger blockNumber = client.ethBlockNumber().send().getBlockNumber();
        return at(context, blockNumber, observedAt);
    }

    EthereumRedemptionBalanceStore.Snapshot at(
            EthereumRedemptionBalanceStore.Context context,
            BigInteger blockNumber, Instant observedAt) throws IOException {
        DefaultBlockParameter block = DefaultBlockParameter.valueOf(blockNumber);
        EthBlock.Block header = client.ethGetBlockByNumber(block, false).send().getBlock();
        if (header == null || header.getHash() == null) {
            throw new IOException("canonical balance-observation block is unavailable");
        }
        return new EthereumRedemptionBalanceStore.Snapshot(
                blockNumber, normalizeHash(header.getHash()),
                call(context.contractAddress(), balanceOf(context.sourceAddress()), block),
                call(context.contractAddress(), balanceOf(context.adminAddress()), block),
                call(context.contractAddress(), totalSupply(), block), observedAt);
    }

    private BigInteger call(
            String contractAddress, String calldata,
            DefaultBlockParameter block) throws IOException {
        EthCall response = client.ethCall(
                Transaction.createEthCallTransaction(null, contractAddress, calldata), block)
                .send();
        if (response.hasError() || response.getValue() == null) {
            throw new IOException("ERC-20 state inquiry failed");
        }
        BigInteger value = Numeric.toBigInt(response.getValue());
        if (value.signum() < 0) {
            throw new IOException("ERC-20 state inquiry returned a negative value");
        }
        return value;
    }

    private static String balanceOf(String address) {
        return FunctionEncoder.encode(new Function(
                "balanceOf", List.of(new Address(address)),
                List.of(new TypeReference<Uint256>() { })));
    }

    private static String totalSupply() {
        return FunctionEncoder.encode(new Function(
                "totalSupply", List.of(), List.of(new TypeReference<Uint256>() { })));
    }

    private static String normalizeHash(String value) throws IOException {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "hash"));
        if (clean.length() != 64 || !clean.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("balance-observation block hash is malformed");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }
}
