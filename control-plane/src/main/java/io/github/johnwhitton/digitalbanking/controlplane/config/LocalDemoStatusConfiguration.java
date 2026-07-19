package io.github.johnwhitton.digitalbanking.controlplane.config;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.controlplane.api.LocalDemoStatusService;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jLocalDemoStatusReader;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresLocalDemoStatusReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo-environment & local-demo & local-ethereum & !local-signer")
class LocalDemoStatusConfiguration {

    @Bean
    PostgresLocalDemoStatusReader postgresLocalDemoStatusReader(DataSource dataSource) {
        return new PostgresLocalDemoStatusReader(dataSource);
    }

    @Bean(destroyMethod = "close")
    Web3jLocalDemoStatusReader web3jLocalDemoStatusReader(
            LocalEthereumProperties ethereum,
            WalletIdentityRegistry wallets) {
        return new Web3jLocalDemoStatusReader(
                ethereum.rpcUrl(), ethereum.contractAddress(),
                wallet(wallets, "USER_WALLET_1"), wallet(wallets, "USER_WALLET_2"),
                wallet(wallets, "ADMIN_REDEMPTION"));
    }

    @Bean
    LocalDemoStatusService localDemoStatusService(
            PostgresLocalDemoStatusReader database,
            Web3jLocalDemoStatusReader chain) {
        return new LocalDemoStatusService(database, chain);
    }

    private static String wallet(WalletIdentityRegistry registry, String reference) {
        return registry.resolve(new WalletReference("synthetic-wallet:" + reference))
                .normalizedAddress();
    }
}
