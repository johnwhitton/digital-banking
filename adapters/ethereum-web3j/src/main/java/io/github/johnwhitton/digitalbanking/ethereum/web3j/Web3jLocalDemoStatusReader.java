package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

/** Bounded ERC-20 inquiry for the fixed local demonstration wallets. */
public final class Web3jLocalDemoStatusReader implements AutoCloseable {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    private final Web3j client;
    private final String contractAddress;
    private final String userOneAddress;
    private final String userTwoAddress;
    private final String adminAddress;

    public Web3jLocalDemoStatusReader(
            String rpcUrl,
            String contractAddress,
            String userOneAddress,
            String userTwoAddress,
            String adminAddress) {
        this.client = Web3j.build(new HttpService(Objects.requireNonNull(rpcUrl, "rpcUrl")));
        this.contractAddress = address(contractAddress, "contract");
        this.userOneAddress = address(userOneAddress, "user one");
        this.userTwoAddress = address(userTwoAddress, "user two");
        this.adminAddress = address(adminAddress, "admin");
    }

    public Snapshot snapshot() {
        try {
            BigInteger chainId = client.ethChainId().send().getChainId();
            BigInteger blockNumber = client.ethBlockNumber().send().getBlockNumber();
            return new Snapshot(
                    chainId, blockNumber, contractAddress,
                    call(balanceOf(userOneAddress)), call(balanceOf(userTwoAddress)),
                    call(balanceOf(adminAddress)), call(totalSupply()));
        } catch (IOException failure) {
            throw new IllegalStateException("local Ethereum status inquiry failed", failure);
        }
    }

    private BigInteger call(String calldata) throws IOException {
        EthCall response = client.ethCall(
                Transaction.createEthCallTransaction(null, contractAddress, calldata),
                DefaultBlockParameterName.LATEST).send();
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

    private static String address(String value, String name) {
        String normalized = Objects.requireNonNull(value, name + " address")
                .toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " address is invalid");
        }
        return normalized;
    }

    @Override
    public void close() {
        client.shutdown();
    }

    public record Snapshot(
            BigInteger chainId,
            BigInteger blockNumber,
            String contractAddress,
            BigInteger userOneBalanceAtomic,
            BigInteger userTwoBalanceAtomic,
            BigInteger adminBalanceAtomic,
            BigInteger totalSupplyAtomic) {
        public Snapshot {
            Objects.requireNonNull(chainId, "chainId");
            Objects.requireNonNull(blockNumber, "blockNumber");
            Objects.requireNonNull(contractAddress, "contractAddress");
            Objects.requireNonNull(userOneBalanceAtomic, "userOneBalanceAtomic");
            Objects.requireNonNull(userTwoBalanceAtomic, "userTwoBalanceAtomic");
            Objects.requireNonNull(adminBalanceAtomic, "adminBalanceAtomic");
            Objects.requireNonNull(totalSupplyAtomic, "totalSupplyAtomic");
        }
    }
}
