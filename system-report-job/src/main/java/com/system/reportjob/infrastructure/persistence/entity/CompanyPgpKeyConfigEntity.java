package com.system.reportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_pgp_key_config")
@SQLDelete(sql = "UPDATE company_pgp_key_config SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class CompanyPgpKeyConfigEntity extends BaseEntity {
    // Unique chỉ trên các row còn sống - enforce bằng partial unique index
    // uq_company_pgp_key_config_company_active (V12), không dùng @Column(unique = true) vì đó sẽ là
    // constraint áp dụng cho MỌI row kể cả row đã soft-delete.
    @Column(name = "company_code", nullable = false)
    private String companyCode;

    @Column(name = "bank_private_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bankPrivateKeyEncrypted;

    @Column(name = "bank_key_passphrase_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bankKeyPassphraseEncrypted;

    @Column(name = "company_public_key_armored", nullable = false, columnDefinition = "TEXT")
    private String companyPublicKeyArmored;

    @Column(name = "key_fingerprint")
    private String keyFingerprint;

    @Column(name = "active", nullable = false)
    private boolean active;
}
