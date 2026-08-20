package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.time.LocalDate;
import java.util.UUID;

public record UserRecord(
        UUID id,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        String address,
        String gender,
        LocalDate dob,
        String description,
        String status) {}
