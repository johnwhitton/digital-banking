package io.github.johnwhitton.digitalbanking.controlplane.config;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.controlplane.api.LocalDemoStatusService;
import io.github.johnwhitton.digitalbanking.controlplane.api.LocalDemoChainStatusReader;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jLocalDemoStatusReader;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresLocalDemoStatusReader;
import io.github.johnwhitton.digitalbanking.solana.sava.SavaLocalDemoStatusReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo-environment & local-demo & (local-ethereum | local-solana) & !local-signer")
class LocalDemoStatusConfiguration {

    @Bean
    PostgresLocalDemoStatusReader postgresLocalDemoStatusReader(DataSource dataSource) {
        return new PostgresLocalDemoStatusReader(dataSource);
    }

    @Bean(destroyMethod = "close")
    @Profile("local-ethereum")
    Web3jLocalDemoStatusReader web3jLocalDemoStatusReader(
            LocalEthereumProperties ethereum,
            WalletIdentityRegistry wallets) {
        return new Web3jLocalDemoStatusReader(
                ethereum.rpcUrl(), ethereum.contractAddress(),
                wallet(wallets, "USER_WALLET_1"), wallet(wallets, "USER_WALLET_2"),
                wallet(wallets, "ADMIN_REDEMPTION"));
    }

    @Bean
    @Profile("local-solana")
    SavaLocalDemoStatusReader savaLocalDemoStatusReader(
            LocalSolanaProperties solana,
            WalletIdentityRegistry wallets) {
        return new SavaLocalDemoStatusReader(
                solana.rpcUri(), solana.requestTimeout(), solana.clusterIdentity(),
                solana.mintAddress(), solana.mintAuthorityPublicKey(),
                wallet(wallets, "USER_WALLET_1"),
                wallet(wallets, "USER_WALLET_2"),
                wallet(wallets, "ADMIN_REDEMPTION"));
    }

    @Bean
    @Profile("local-ethereum")
    LocalDemoChainStatusReader ethereumLocalDemoChainStatusReader(
            Web3jLocalDemoStatusReader reader) {
        return () -> {
            Web3jLocalDemoStatusReader.Snapshot snapshot = reader.snapshot();
            if (!java.math.BigInteger.valueOf(31_337).equals(snapshot.chainId())) {
                throw new IllegalStateException("local demo chain identity is invalid");
            }
            return new LocalDemoChainStatusReader.Snapshot(
                    "LOCAL_ANVIL", snapshot.chainId().toString(),
                    snapshot.blockNumber().toString(), snapshot.contractAddress(),
                    snapshot.userOneBalanceAtomic(), snapshot.userTwoBalanceAtomic(),
                    snapshot.adminBalanceAtomic(), snapshot.totalSupplyAtomic());
        };
    }

    @Bean
    @Profile("local-solana")
    LocalDemoChainStatusReader solanaLocalDemoChainStatusReader(
            SavaLocalDemoStatusReader reader) {
        return () -> {
            SavaLocalDemoStatusReader.Snapshot snapshot = reader.snapshot();
            return new LocalDemoChainStatusReader.Snapshot(
                    "LOCAL_SOLANA", snapshot.clusterIdentity(), snapshot.slot(),
                    snapshot.mintAddress(), snapshot.userOneBalanceAtomic(),
                    snapshot.userTwoBalanceAtomic(), snapshot.adminBalanceAtomic(),
                    snapshot.totalSupplyAtomic());
        };
    }

    @Bean
    LocalDemoStatusService localDemoStatusService(
            PostgresLocalDemoStatusReader database,
            LocalDemoChainStatusReader chain) {
        return new LocalDemoStatusService(database, chain);
    }

    private static String wallet(WalletIdentityRegistry registry, String reference) {
        return registry.resolve(new WalletReference("synthetic-wallet:" + reference))
                .normalizedAddress();
    }
}
