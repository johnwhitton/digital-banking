package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

class EthereumWalletTransferObservationTest {

    private static final String CONTRACT = "0x" + "a".repeat(40);
    private static final String SOURCE = "0x" + "1".repeat(40);
    private static final String DESTINATION = "0x" + "2".repeat(40);
    private static final BigInteger AMOUNT = BigInteger.valueOf(10_000);

    @Test
    void requiresOneExactNonRemovedTransferEvent() {
        TransactionReceipt receipt = receipt(log(
                CONTRACT, SOURCE, DESTINATION, AMOUNT, false));
        assertTrue(Web3jEthereumWalletTransferChainAdapter.hasExactTransferEvent(
                receipt, CONTRACT, SOURCE, DESTINATION, AMOUNT));

        assertFalse(matches(receipt(log("0x" + "b".repeat(40),
                SOURCE, DESTINATION, AMOUNT, false))));
        assertFalse(matches(receipt(log(CONTRACT,
                "0x" + "3".repeat(40), DESTINATION, AMOUNT, false))));
        assertFalse(matches(receipt(log(CONTRACT,
                SOURCE, "0x" + "3".repeat(40), AMOUNT, false))));
        assertFalse(matches(receipt(log(CONTRACT,
                SOURCE, DESTINATION, BigInteger.ONE, false))));
        assertFalse(matches(receipt(log(CONTRACT,
                SOURCE, DESTINATION, AMOUNT, true))));
        assertFalse(matches(receipt(
                log(CONTRACT, SOURCE, DESTINATION, AMOUNT, false),
                log(CONTRACT, SOURCE, DESTINATION, AMOUNT, false))));
        assertFalse(matches(receipt(
                log(CONTRACT, SOURCE, DESTINATION, AMOUNT, true),
                log(CONTRACT, SOURCE, DESTINATION, AMOUNT, false))));

        Log malformed = log(CONTRACT, SOURCE, DESTINATION, AMOUNT, false);
        malformed.setTopics(List.of(Hash.sha3String("Transfer(address,address,uint256)")));
        assertFalse(matches(receipt(malformed)));
    }

    private static boolean matches(TransactionReceipt receipt) {
        return Web3jEthereumWalletTransferChainAdapter.hasExactTransferEvent(
                receipt, CONTRACT, SOURCE, DESTINATION, AMOUNT);
    }

    private static TransactionReceipt receipt(Log... logs) {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setLogs(List.of(logs));
        return receipt;
    }

    private static Log log(
            String contract, String source, String destination,
            BigInteger amount, boolean removed) {
        Log log = new Log();
        log.setAddress(contract);
        log.setRemoved(removed);
        log.setTopics(List.of(
                Hash.sha3String("Transfer(address,address,uint256)"),
                topic(source), topic(destination)));
        log.setData("0x" + Numeric.toHexStringNoPrefixZeroPadded(amount, 64));
        return log;
    }

    private static String topic(String address) {
        return "0x" + "0".repeat(24) + address.substring(2);
    }
}
