package com.system.reportjob.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.persistence.entity.CompanyPgpKeyConfigEntity;
import com.system.reportjob.infrastructure.persistence.repository.CompanyPgpKeyConfigJpaRepository;
import com.system.reportjob.infrastructure.security.pgp.PgpKeyMaterialCipher;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Component
public class CompanyPgpKeyConfigRepositoryAdapter implements PgpKeyConfigRepositoryPort {

    private final CompanyPgpKeyConfigJpaRepository jpaRepository;
    private final PgpKeyMaterialCipher cipher;

    public CompanyPgpKeyConfigRepositoryAdapter(
            CompanyPgpKeyConfigJpaRepository jpaRepository, PgpKeyMaterialCipher cipher) {
        this.jpaRepository = jpaRepository;
        this.cipher = cipher;
    }

    @Override
    public CompanyPgpKeyConfig save(CompanyPgpKeyConfig config) {
        CompanyPgpKeyConfigEntity entity =
                jpaRepository.findByCompanyCode(config.companyCode()).orElseGet(CompanyPgpKeyConfigEntity::new);
        entity.setId(config.id());
        entity.setCompanyCode(config.companyCode());
        entity.setBankPrivateKeyEncrypted(cipher.seal(config.bankPrivateKeyArmored()));
        entity.setBankKeyPassphraseEncrypted(cipher.seal(config.bankKeyPassphrase()));
        entity.setCompanyPublicKeyArmored(config.companyPublicKeyArmored());
        entity.setKeyFingerprint(config.keyFingerprint());
        entity.setActive(config.active());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<CompanyPgpKeyConfig> findByCompanyCode(String companyCode) {
        return jpaRepository.findByCompanyCode(companyCode).map(this::toDomain);
    }

    @Override
    public List<CompanyPgpKeyConfig> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void delete(String companyCode) {
        jpaRepository.findByCompanyCode(companyCode).ifPresent(entity -> jpaRepository.deleteById(entity.getId()));
    }

    private CompanyPgpKeyConfig toDomain(CompanyPgpKeyConfigEntity entity) {
        return new CompanyPgpKeyConfig(
                entity.getId(),
                entity.getCompanyCode(),
                cipher.unseal(entity.getBankPrivateKeyEncrypted()),
                cipher.unseal(entity.getBankKeyPassphraseEncrypted()),
                entity.getCompanyPublicKeyArmored(),
                entity.getKeyFingerprint(),
                entity.isActive());
    }
}
