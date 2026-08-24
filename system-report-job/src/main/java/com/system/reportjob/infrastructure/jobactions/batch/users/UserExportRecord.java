package com.system.reportjob.infrastructure.jobactions.batch.users;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UserExportRecord(
        UUID id,
        UUID userId,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        String address,
        String gender,
        LocalDate dob,
        String description,
        String status,
        Instant exportedAt) {}
