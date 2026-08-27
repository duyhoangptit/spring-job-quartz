package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.persistence.repository.CompanyPgpKeyConfigJpaRepository;
import com.system.reportjob.infrastructure.security.pgp.PgpKeyMaterialCipher;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import({CompanyPgpKeyConfigRepositoryAdapter.class, PgpKeyMaterialCipher.class})
class CompanyPgpKeyConfigRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.pgp.master-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    CompanyPgpKeyConfigRepositoryAdapter adapter;

    @Autowired
    CompanyPgpKeyConfigJpaRepository jpaRepository;

    @Test
    void savesAndReloadsWithKeyMaterialDecryptedOnRead() {
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true);

        adapter.save(config);

        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE")).contains(config);
    }

    @Test
    void keyMaterialIsEncryptedAtRestInTheDatabase() {
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true);

        adapter.save(config);

        var entity = jpaRepository.findByCompanyCode("FPT_SOFTWARE").orElseThrow();
        assertThat(entity.getBankPrivateKeyEncrypted()).doesNotContain("priv-key-armored");
        assertThat(entity.getBankKeyPassphraseEncrypted()).doesNotContain("s3cr3t");
        assertThat(entity.getCompanyPublicKeyArmored()).isEqualTo("pub-key-armored");
    }

    @Test
    void updateOverwritesTheExistingRowInsteadOfInsertingANewOne() {
        UUID id = UUID.randomUUID();
        adapter.save(new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v1", "pass-v1", "pub-v1", null, true));

        adapter.save(new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v2", "pass-v2", "pub-v2", null, true));

        assertThat(jpaRepository.findAll()).hasSize(1);
        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE"))
                .get()
                .extracting(CompanyPgpKeyConfig::bankPrivateKeyArmored)
                .isEqualTo("priv-v2");
    }

    @Test
    void deletedConfigIsNoLongerFound() {
        adapter.save(new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true));

        adapter.delete("FPT_SOFTWARE");

        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE")).isEmpty();
    }
}
