package io.github.johnwhitton.digitalbanking.solana.sava;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

/** Bounded read-only classic-SPL inquiry for the fixed local demo identities. */
public final class SavaLocalDemoStatusReader {

    private final SolanaRpcClient client;
    private final String clusterIdentity;
    private final PublicKey mint;
    private final PublicKey mintAuthority;
    private final PublicKey userOne;
    private final PublicKey userTwo;
    private final PublicKey admin;

    public SavaLocalDemoStatusReader(
            URI rpcUri,
            Duration requestTimeout,
            String clusterIdentity,
            String mint,
            String mintAuthority,
            String userOne,
            String userTwo,
            String admin) {
        Objects.requireNonNull(rpcUri, "rpcUri");
        if (!"http".equals(rpcUri.getScheme())
                || !"127.0.0.1".equals(rpcUri.getHost())) {
            throw new IllegalArgumentException("local Solana status RPC must be loopback HTTP");
        }
        this.client = SolanaRpcClient.build().endpoint(rpcUri)
                .requestTimeout(Objects.requireNonNull(requestTimeout, "requestTimeout"))
                .defaultCommitment(Commitment.FINALIZED).createClient();
        this.clusterIdentity = text(clusterIdentity, "clusterIdentity");
        this.mint = key(mint, "mint");
        this.mintAuthority = key(mintAuthority, "mintAuthority");
        this.userOne = key(userOne, "userOne");
        this.userTwo = key(userTwo, "userTwo");
        this.admin = key(admin, "admin");
    }

    public Snapshot snapshot() {
        String observedCluster = client.getGenesisHash().join();
        if (!clusterIdentity.equals(observedCluster)) {
            throw new IllegalStateException("local Solana status cluster identity changed");
        }
        long slot = client.getSlot(Commitment.FINALIZED).join();
        AccountInfo<byte[]> mintAccount = required(
                client.getAccountInfo(Commitment.FINALIZED, mint).join(), "mint");
        Mint mintData = Mint.read(mint, mintAccount.data());
        if (!mintAccount.owner().equals(SolanaMintTransactionCodec.TOKEN_PROGRAM)
                || !mint.equals(mintData.address())
                || !mintAuthority.equals(mintData.mintAuthority())
                || mintData.decimals() != 2 || !mintData.initialized()
                || (mintData.freezeAuthority() != null
                    && !PublicKey.NONE.equals(mintData.freezeAuthority()))) {
            throw new IllegalStateException("local Solana status mint policy changed");
        }
        return new Snapshot(
                observedCluster, Long.toUnsignedString(slot), mint.toBase58(),
                balance(userOne), balance(userTwo), balance(admin),
                unsigned(mintData.supply()));
    }

    private BigInteger balance(PublicKey owner) {
        PublicKey ata = SolanaMintTransactionCodec.associatedTokenAddress(owner, mint);
        AccountInfo<byte[]> account = required(
                client.getAccountInfo(Commitment.FINALIZED, ata).join(), "token account");
        TokenAccount token = TokenAccount.read(ata, account.data());
        if (!account.owner().equals(SolanaMintTransactionCodec.TOKEN_PROGRAM)
                || !ata.equals(token.address()) || !mint.equals(token.mint())
                || !owner.equals(token.owner())
                || token.state() != AccountState.Initialized) {
            throw new IllegalStateException(
                    "local Solana status token-account policy changed");
        }
        return unsigned(token.amount());
    }

    private static AccountInfo<byte[]> required(
            AccountInfo<byte[]> account, String label) {
        if (account == null || account.data() == null) {
            throw new IllegalStateException("local Solana status " + label + " is unavailable");
        }
        return account;
    }

    private static PublicKey key(String value, String label) {
        try {
            PublicKey key = PublicKey.fromBase58Encoded(value);
            if (key.toByteArray().length != 32) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(label + " must be a 32-byte public key");
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + " must be non-blank and bounded");
        }
        return value;
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    public record Snapshot(
            String clusterIdentity,
            String slot,
            String mintAddress,
            BigInteger userOneBalanceAtomic,
            BigInteger userTwoBalanceAtomic,
            BigInteger adminBalanceAtomic,
            BigInteger totalSupplyAtomic) {
    }
}
