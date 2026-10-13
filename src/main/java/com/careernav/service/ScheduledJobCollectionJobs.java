package com.careernav.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobCollectionJobs {
    private final JobCollectionService service;
    public ScheduledJobCollectionJobs(JobCollectionService service) { this.service = service; }
    @Scheduled(cron = "${app.collection.expiry-cron}") public void expiryCheck() { service.runExpiryCheck(); }
    @Scheduled(cron = "${app.collection.incremental-cron}") public void incrementalCollection() { service.runIncremental(); }
    @Scheduled(cron = "${app.collection.full-cron}") public void fullCollection() { service.runFull(); }
    @Scheduled(cron = "${app.collection.stats-cron}") public void marketStats() { service.runMarketSnapshot(); }
}
