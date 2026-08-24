package com.system.reportjob.infrastructure.jobactions.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.infrastructure.jobactions.JobAction;

@Component
public class EchoInProcessJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(EchoInProcessJobAction.class);

    @Override
    public boolean matches(String jobType) {
        return "ECHO".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        log.info("[ECHO] JobDefinition {} expression={}", definition.id(), definition.expression());
    }
}
