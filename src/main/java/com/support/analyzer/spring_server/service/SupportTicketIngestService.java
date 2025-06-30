package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.entity.SummarizedTicket;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class SupportTicketIngestService {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketIngestService.class);

    private final MongoService mongoService;
    private final MaskingService maskingService;
    private final OpenAIService openAIService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchService elasticsearchService;
    private final DsuService dsuService;

    @Autowired
    public SupportTicketIngestService(MongoService mongoService,
                                      MaskingService maskingService,
                                      OpenAIService openAIService,
                                      EmbeddingService embeddingService,
                                      ElasticsearchService elasticsearchService,
                                      DsuService dsuService) {
        this.mongoService = mongoService;
        this.maskingService = maskingService;
        this.openAIService = openAIService;
        this.embeddingService = embeddingService;
        this.elasticsearchService = elasticsearchService;
        this.dsuService = dsuService;
    }

    public void processAllTickets() {
        long startTime = System.currentTimeMillis();
        List<SupportTicket> tickets = mongoService.getAllSupportTicket();
        List<String> allTicketIds = Collections.synchronizedList(new ArrayList<>());

        ExecutorService ticketExecutor = Executors.newFixedThreadPool(10);
        List<Future<?>> ticketFutures = new ArrayList<>();

        try {
            log.info("Starting processing of {} tickets with multithreading and bulk operations", tickets.size());

            // PHASE 1: Parallel ticket processing with bulk operations
            for (SupportTicket ticket : tickets) {
                Future<?> future = ticketExecutor.submit(() -> {
                    try {
                        String ticketId = ticket.getTicketId();


                        List<String> rawMessages = ticket.getMessages().stream()
                                .map(msg -> msg.get_source().getMessage())
                                .collect(Collectors.toList());

                        List<String> maskedMessages = maskingService.getMaskedMessages(ticketId, rawMessages);
                        if (maskedMessages == null || maskedMessages.isEmpty()) {
                            log.debug("Skipping ticket {}: masking failed or empty", ticketId);
                            return;
                        }

                        String joinedMasked = String.join("\n", maskedMessages);

                        // Rate-limited OpenAI call with built-in semaphore
                        String summary = openAIService.summarizeMessages(joinedMasked);
                        if (summary == null || summary.isBlank()) {
                            log.debug("Skipping ticket {}: summary is blank", ticketId);
                            return;
                        }
                        log.info("Processing ticket {}: {}", ticketId, summary);

                        // Add to bulk buffer (will auto-flush when threshold reached)
                        mongoService.addSummarizeTicket(new SummarizedTicket(ticketId, summary));

                        List<Double> embedding = embeddingService.getEmbedding(ticketId, summary);
                        if (embedding == null || embedding.isEmpty()) {
                            log.debug("Skipping ticket {}: embedding failed", ticketId);
                            return;
                        }
                        // Add to bulk buffer (will auto-flush when threshold reached)
                        elasticsearchService.indexEmbedding(ticketId, embedding);
                        allTicketIds.add(ticketId);

                    } catch (Exception e) {
                        log.error("Error processing ticket {}: {}", ticket.getTicketId(), e.getMessage());
                    }
                });
                ticketFutures.add(future);
            }

            // Wait for all ticket tasks to complete
            for (Future<?> f : ticketFutures) {
                try {
                    f.get(); // Wait for task
                } catch (Exception e) {
                    log.error("Ticket processing task failed: {}", e.getMessage());
                }
            }
            ticketExecutor.shutdown();

            // Flush any remaining items in buffers after phase 1
            log.info("Phase 1 complete. Flushing remaining buffers...");
            mongoService.finalFlush();
            elasticsearchService.finalFlush();

            // PHASE 2: Cluster building (single-threaded)
            log.info("Starting cluster building phase...");
            List<String> representatives = dsuService.buildClustersAndGetRepresentatives(allTicketIds, 5);
            log.info("Found {} cluster representatives: {}", representatives.size(), representatives);

            long phaseOneDuration = System.currentTimeMillis() - startTime;
            log.info("Phase 1 completed in {} ms. Processed {} tickets", phaseOneDuration, allTicketIds.size());

            // PHASE 3: Parallel triplet generation with bulk operations
            log.info("Starting triplet generation phase...");
            ExecutorService tripletExecutor = Executors.newFixedThreadPool(5);
            List<Future<?>> tripletFutures = new ArrayList<>();
            AtomicInteger processedReps = new AtomicInteger(0);

            for (String repId : representatives) {
                Future<?> future = tripletExecutor.submit(() -> {
                    try {
                        log.debug("Processing representative ticket: {}", repId);

                        SummarizedTicket summarized = mongoService.getSummarizedTicketById(repId);
                        if (summarized == null || summarized.getSummary() == null || summarized.getSummary().isBlank()) {
                            log.warn("Missing or blank summary for representative ticket: {}", repId);
                            return;
                        }

                        // Rate-limited OpenAI call with built-in semaphore
                        TicketTriplet triplet = openAIService.generateTicketTriplet(repId, summarized.getSummary());
                        if (triplet == null) {
                            log.warn("Failed to generate triplet for ticket: {}", repId);
                            return;
                        }

                        List<Double> issueEmbedding = embeddingService.getEmbedding(repId, triplet.getIssue());
                        if (issueEmbedding == null || issueEmbedding.isEmpty()) {
                            log.warn("Failed to generate issue embedding for ticket: {}", repId);
                        }

                        // Add to bulk buffers (will auto-flush when threshold reached)
                        mongoService.addTicketTriplet(triplet);
                        elasticsearchService.indexTicketTripletWithEmbedding(triplet, issueEmbedding);

                        int count = processedReps.incrementAndGet();
                        log.info("Processed representative {}/{}: {}", count, representatives.size(), repId);

                    } catch (Exception e) {
                        log.error("Error processing representative ticket {}: {}", repId, e.getMessage());
                    }
                });
                tripletFutures.add(future);
            }

            // Wait for all triplet tasks to complete
            for (Future<?> f : tripletFutures) {
                try {
                    f.get(); // Wait for task
                } catch (Exception e) {
                    log.error("Triplet processing task failed: {}", e.getMessage());
                }
            }
            tripletExecutor.shutdown();

            // Final flush of all buffers
            log.info("Phase 3 complete. Performing final flush of all buffers...");
            mongoService.finalFlush();
            elasticsearchService.finalFlush();

            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("Completed processing {} tickets and {} representatives in {} ms",
                    allTicketIds.size(), processedReps.get(), totalDuration);

            // Log buffer and rate limiting statistics
            logProcessingStatistics();

        } catch (Exception e) {
            log.error("Error processing tickets: {}", e, e);
        }
    }

    private void logProcessingStatistics() {
        log.info("=== Final Processing Statistics ===");

        // MongoDB Buffer Stats
        MongoService.BufferStats mongoStats = mongoService.getBufferStats();
        log.info("MongoDB Buffers: {}", mongoStats);

        // Elasticsearch Buffer Stats
        log.info("Elasticsearch Buffer Size: {}", elasticsearchService.getBulkBufferSize());

        // OpenAI Rate Limiting Stats
        log.info("OpenAI Rate Limiting - Available Permits: {}, Queue Length: {}, At Capacity: {}",
                openAIService.getAvailablePermits(),
                openAIService.getQueueLength(),
                openAIService.isAtCapacity());
    }

    // Method to force flush all buffers manually
    public void forceFlushAllBuffers() {
        log.info("Manually flushing all service buffers...");
        mongoService.finalFlush();
        elasticsearchService.finalFlush();
        logProcessingStatistics();
    }

    // Method to get current processing statistics
    public ProcessingStats getProcessingStats() {
        MongoService.BufferStats mongoStats = mongoService.getBufferStats();

        return new ProcessingStats(
                mongoStats.getSummarizedTicketBufferSize(),
                mongoStats.getTripletBufferSize(),
                elasticsearchService.getBulkBufferSize(),
                openAIService.getAvailablePermits(),
                openAIService.getQueueLength(),
                openAIService.isAtCapacity()
        );
    }

    // Inner class for processing statistics
    public static class ProcessingStats {
        private final int summarizedTicketBufferSize;
        private final int tripletBufferSize;
        private final int elasticsearchBufferSize;
        private final int openAIAvailablePermits;
        private final int openAIQueueLength;
        private final boolean openAIAtCapacity;

        public ProcessingStats(int summarizedTicketBufferSize, int tripletBufferSize,
                               int elasticsearchBufferSize, int openAIAvailablePermits,
                               int openAIQueueLength, boolean openAIAtCapacity) {
            this.summarizedTicketBufferSize = summarizedTicketBufferSize;
            this.tripletBufferSize = tripletBufferSize;
            this.elasticsearchBufferSize = elasticsearchBufferSize;
            this.openAIAvailablePermits = openAIAvailablePermits;
            this.openAIQueueLength = openAIQueueLength;
            this.openAIAtCapacity = openAIAtCapacity;
        }

        // Getters
        public int getSummarizedTicketBufferSize() { return summarizedTicketBufferSize; }
        public int getTripletBufferSize() { return tripletBufferSize; }
        public int getElasticsearchBufferSize() { return elasticsearchBufferSize; }
        public int getOpenAIAvailablePermits() { return openAIAvailablePermits; }
        public int getOpenAIQueueLength() { return openAIQueueLength; }
        public boolean isOpenAIAtCapacity() { return openAIAtCapacity; }

        @Override
        public String toString() {
            return String.format(
                    "ProcessingStats{summarizedBuffer=%d, tripletBuffer=%d, esBuffer=%d, " +
                            "aiPermits=%d, aiQueue=%d, aiAtCapacity=%s}",
                    summarizedTicketBufferSize, tripletBufferSize, elasticsearchBufferSize,
                    openAIAvailablePermits, openAIQueueLength, openAIAtCapacity
            );
        }
    }
}