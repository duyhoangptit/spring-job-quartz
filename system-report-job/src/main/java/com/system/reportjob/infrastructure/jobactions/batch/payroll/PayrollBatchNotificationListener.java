package com.system.reportjob.infrastructure.jobactions.batch.payroll;


import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PayrollBatchNotificationListener implements JobExecutionListener {

	@Override
	public void beforeJob(JobExecution jobExecution) {
		// Chỉ cần khai báo nếu bạn sử dụng, nếu không có thể xóa hẳn hàm này
		log.info("!!! JOB STARTED ");
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
			log.info("!!! JOB FINISHED ");
		}
		// Viết logic xử lý sau khi Job chạy xong tại đây
		if (jobExecution.getStatus().isUnsuccessful()) {
			log.warn("!!! JOB FAILED ");
		}
	}
}
