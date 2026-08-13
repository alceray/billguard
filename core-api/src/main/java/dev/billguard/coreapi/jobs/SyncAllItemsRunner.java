package dev.billguard.coreapi.jobs;

import dev.billguard.coreapi.plaid.PlaidItemRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("job")
public class SyncAllItemsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SyncAllItemsRunner.class);

    private final PlaidItemRepository itemRepository;
    private final JobQueueService jobQueueService;
    private final ConfigurableApplicationContext applicationContext;

    public SyncAllItemsRunner(
        PlaidItemRepository itemRepository,
        JobQueueService jobQueueService,
        ConfigurableApplicationContext applicationContext
    ) {
        this.itemRepository = itemRepository;
        this.jobQueueService = jobQueueService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int enqueued = 0;
        for (String itemId : itemRepository.findAllPlaidItemIds()) {
            if (jobQueueService.enqueueSync(itemId)) {
                enqueued++;
            }
        }
        log.info("Daily Plaid sync enqueue complete enqueued={}", enqueued);
        int exitCode = SpringApplication.exit(applicationContext);
        System.exit(exitCode);
    }
}
