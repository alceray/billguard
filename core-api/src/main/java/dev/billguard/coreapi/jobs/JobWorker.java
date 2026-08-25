package dev.billguard.coreapi.jobs;

import java.time.Duration;

import dev.billguard.coreapi.config.WorkerProperties;
import dev.billguard.coreapi.detection.RecurringChargeDetectionService;
import dev.billguard.coreapi.plaid.PlaidSyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "billguard.worker", name = "enabled", havingValue = "true")
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int MAX_ATTEMPTS = 5;

    private final JobQueueRepository repository;
    private final PlaidSyncService plaidSyncService;
    private final RecurringChargeDetectionService detectionService;
    private final WorkerProperties properties;

    public JobWorker(
        JobQueueRepository repository,
        PlaidSyncService plaidSyncService,
        RecurringChargeDetectionService detectionService,
        WorkerProperties properties
    ) {
        this.repository = repository;
        this.plaidSyncService = plaidSyncService;
        this.detectionService = detectionService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${billguard.worker.poll-delay:2000}")
    public void poll() {
        int reclaimed = repository.reclaimStale(properties.staleAfter());
        if (reclaimed > 0) {
            log.warn("Reclaimed stale jobs count={}", reclaimed);
        }
        for (int claimed = 0; claimed < properties.batchSize(); claimed++) {
            var jobs = repository.claim(1);
            if (jobs.isEmpty()) {
                return;
            }
            process(jobs.getFirst());
        }
    }

    private void process(JobQueueEntry job) {
        try {
            switch (job.type()) {
                case JobQueueService.SYNC_ITEM -> plaidSyncService.syncItem(job.dedupeKey());
                case JobQueueService.DETECT_STREAMS -> detectionService.detectUser(
                    java.util.UUID.fromString(job.dedupeKey()));
                default -> throw new IllegalArgumentException("Unsupported job type: " + job.type());
            }
            repository.markDone(job.id());
            log.info("Job completed jobId={} type={} attempt={}", job.id(), job.type(), job.attempts());
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
            if (job.attempts() >= MAX_ATTEMPTS) {
                repository.markDead(job.id(), message);
                log.error("Job moved to dead state jobId={} type={} attempts={}",
                    job.id(), job.type(), job.attempts(), exception);
                return;
            }
            Duration delay = Duration.ofSeconds(30L * (1L << Math.max(0, job.attempts() - 1)));
            repository.retry(job.id(), delay, message);
            log.warn("Job scheduled for retry jobId={} type={} attempt={} delaySeconds={}",
                job.id(), job.type(), job.attempts(), delay.toSeconds());
        }
    }
}
