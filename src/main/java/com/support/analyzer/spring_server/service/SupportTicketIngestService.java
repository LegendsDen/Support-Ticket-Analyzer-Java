package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.*;
import com.support.analyzer.spring_server.entity.SummarizedTicket;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import com.support.analyzer.spring_server.util.PerfStats;
import com.support.analyzer.spring_server.util.PerfTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class SupportTicketIngestService {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketIngestService.class);
    private static final Logger perfLog = LoggerFactory.getLogger("PERF_SUMMARY");

    private final MongoService mongoService;
    private final MaskingService maskingService;
    private final OpenAIService openAIService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchService elasticsearchService;
    private final DsuService dsuService;

    @Value("${batchSize}")
    private int batchSize;

    @Value("${numThreads}")
    private int numThreads;

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
        PerfStats mainPerfStats = PerfTracker.start();

        long startTime = System.currentTimeMillis();

        PerfTracker.in("getAllSupportTickets");
        List<SupportTicket> tickets = mongoService.getAllSupportTicket();
        PerfTracker.out("getAllSupportTickets");

        log.info("Processing {} tickets", tickets.size());
        List<String> allTicketIds = Collections.synchronizedList(new ArrayList<>());

        PerfTracker.in("phase1_prepareBatches");

        List<List<SupportTicket>> batches = new ArrayList<>();
        for (int i = 0; i < tickets.size(); i += batchSize) {
            batches.add(tickets.subList(i, Math.min(i + batchSize, tickets.size())));
        }
        PerfTracker.out("phase1_prepareBatches");

        ExecutorService ticketExecutor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> ticketFutures = new ArrayList<>();

        PerfTracker.in("phase1_batchProcessing");

        for (List<SupportTicket> batch : batches) {
            Future<?> future = ticketExecutor.submit(() -> {
                // Each thread gets its own performance tracker
                PerfStats threadPerfStats = new PerfStats();
                try {
                    threadPerfStats.markOperationStart("prepareMaskItems");
                    List<MaskBatchRequest.MaskItem> maskItems = batch.stream()
                            .map(ticket -> new MaskBatchRequest.MaskItem(
                                    ticket.getTicketId(),
                                    ticket.getMessages().stream()
                                            .map(msg -> msg.get_source().getMessage())
                                            .collect(Collectors.toList())))
                            .collect(Collectors.toList());
                    threadPerfStats.markOperationEnd("prepareMaskItems");

                    log.info("Masking {} tickets", maskItems.size());

                    threadPerfStats.markOperationStart("maskingService_batch");
                    List<FlaskMaskedResponseItem> maskedResults = maskingService.getMaskedMessagesBatch(maskItems);
                    threadPerfStats.markOperationEnd("maskingService_batch");

                    // VALIDATION: Check if masking results are available
                    if (maskedResults == null || maskedResults.isEmpty()) {
                        log.error("Masking service returned null or empty results for batch. Skipping batch processing.");
                        return;
                    }

                    log.info("Masked {} tickets", maskedResults.size());

                    threadPerfStats.markOperationStart("prepareEmbedItems");

                    threadPerfStats.markOperationStart("prepareSummarizationBatch");
                    List<SummarizationRequest> summarizationRequests = new ArrayList<>();

                    for (FlaskMaskedResponseItem item : maskedResults) {
                        String ticketId = item.getTicketId();
                        List<String> maskedMessages = item.getMaskedMessages();
                        log.info("Ticket {} masked messages: {}", ticketId, maskedMessages);
                        if (maskedMessages == null || maskedMessages.isEmpty()) {
                            log.warn("Skipping ticket {} - no masked messages available", ticketId);
                            continue;
                        }

                        String joinedMasked = String.join("\n", maskedMessages);
                        summarizationRequests.add(new SummarizationRequest(ticketId, joinedMasked));
                    }
                    threadPerfStats.markOperationEnd("prepareSummarizationBatch");

                    // VALIDATION: Check if we have any valid summarization requests
                    if (summarizationRequests.isEmpty()) {
                        log.error("No valid summarization requests after masking. Skipping batch processing.");
                        return;
                    }

                    log.info("Sending {} tickets for batch summarization", summarizationRequests.size());

                    threadPerfStats.markOperationStart("openAIService_batchSummarize");
                    List<SummarizationResponse> summaryResponses = openAIService.summarizeMessagesBatch(summarizationRequests);
                    threadPerfStats.markOperationEnd("openAIService_batchSummarize");

                    log.info("Received {} summarization responses", summaryResponses.size());

                    threadPerfStats.markOperationStart("processResponsesAndSave");
                    List<EmbedBatchRequest.EmbedItem> embedItems = new ArrayList<>();

                    for (SummarizationResponse response : summaryResponses) {
                        if (response.isSuccess() && response.getSummary() != null && !response.getSummary().isBlank()) {

                            threadPerfStats.markOperationStart("mongoService_addSummarize_" + response.getTicketId());
                            mongoService.addSummarizeTicket(new SummarizedTicket(response.getTicketId(), response.getSummary()));
                            threadPerfStats.markOperationEnd("mongoService_addSummarize_" + response.getTicketId());

                            // Prepare for embedding
                            embedItems.add(new EmbedBatchRequest.EmbedItem(response.getTicketId(), response.getSummary()));

                            log.info("Processed and saved ticket {}: {}", response.getTicketId(), response.getSummary());
                        } else {
                            log.warn("Failed to summarize ticket {}: {}", response.getTicketId(), response.getErrorMessage());
                        }
                    }
                    threadPerfStats.markOperationEnd("processResponsesAndSave");

                    // VALIDATION: Check if we have items to embed
                    if (embedItems.isEmpty()) {
                        log.error("No valid items for embedding after summarization. Skipping embedding and indexing.");
                        return;
                    }

                    log.info("Successfully processed {} out of {} summaries", embedItems.size(), summarizationRequests.size());
                    threadPerfStats.markOperationEnd("prepareEmbedItems");

                    threadPerfStats.markOperationStart("embeddingService_batch");
                    List<FlaskEmbeddingResponseItem> embeddingResults = embeddingService.getEmbeddingsBatch(embedItems);
                    threadPerfStats.markOperationEnd("embeddingService_batch");

                    // VALIDATION: Check if embedding results are available
                    if (embeddingResults == null || embeddingResults.isEmpty()) {
                        log.error("Embedding service returned null or empty results for batch. Skipping Elasticsearch indexing.");
                        return;
                    }

                    threadPerfStats.markOperationStart("elasticsearchService_indexBatch");
                    for (FlaskEmbeddingResponseItem item : embeddingResults) {
                        // Additional validation for individual embedding items
                        if (item.getTicketId() == null || item.getEmbedding() == null || item.getEmbedding().isEmpty()) {
                            log.warn("Skipping indexing for ticket {} - invalid embedding data", item.getTicketId());
                            continue;
                        }

                        elasticsearchService.indexEmbedding(item.getTicketId(), item.getEmbedding());
                        allTicketIds.add(item.getTicketId());
                    }
                    threadPerfStats.markOperationEnd("elasticsearchService_indexBatch");

                    threadPerfStats.stopAndGetStat();
                    perfLog.info("Batch thread performance for {} tickets: {}",
                            batch.size(), threadPerfStats.toFormattedString());

                } catch (Exception e) {
                    log.error("Batch processing failed: {}", e.getMessage(), e);
                }
            });
            ticketFutures.add(future);
        }

        for (Future<?> f : ticketFutures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("Ticket processing task failed: {}", e.getMessage());
            }
        }

        ticketExecutor.shutdown();
        PerfTracker.out("phase1_batchProcessing");

        PerfTracker.in("phase1_finalFlush");
        mongoService.finalFlush();
        elasticsearchService.finalFlush();
        PerfTracker.out("phase1_finalFlush");

        // PHASE 2: Clustering
        log.info("Phase 1 complete. Starting clustering...");
        PerfTracker.in("phase2_clustering");
        List<String> representatives = dsuService.buildClustersAndGetRepresentatives(allTicketIds, 5);
        PerfTracker.out("phase2_clustering");

        // VALIDATION: Check if clustering produced representatives
        if (representatives == null || representatives.isEmpty()) {
            log.error("Clustering did not produce any representatives. Skipping Phase 3.");
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("Processing stopped after Phase 2. Processed {} tickets in {} ms", allTicketIds.size(), totalDuration);
            return;
        }

        // PHASE 3: Triplet Generation + Batch Embedding
        PerfTracker.in("phase3_prepareTripletBatches");
        ExecutorService tripletExecutor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> tripletFutures = new ArrayList<>();
        AtomicInteger processedReps = new AtomicInteger(0);

        List<List<String>> repBatches = new ArrayList<>();
        for (int i = 0; i < representatives.size(); i += batchSize) {
            repBatches.add(representatives.subList(i, Math.min(i + batchSize, representatives.size())));
        }
        PerfTracker.out("phase3_prepareTripletBatches");

        PerfTracker.in("phase3_tripletProcessing");

        for (List<String> repBatch : repBatches) {
            Future<?> future = tripletExecutor.submit(() -> {
                try {
                    // Prepare batch request from summarized tickets
                    List<OpenAIService.TripletBatchRequestItem> tripletRequestItems = new ArrayList<>();
                    for (String repId : repBatch) {
                        SummarizedTicket summarized = mongoService.getSummarizedTicketById(repId);
                        if (summarized != null && summarized.getSummary() != null && !summarized.getSummary().isBlank()) {
                            tripletRequestItems.add(new OpenAIService.TripletBatchRequestItem(repId, summarized.getSummary()));
                        }
                    }

                    // VALIDATION: Check if we have valid triplet request items
                    if (tripletRequestItems.isEmpty()) {
                        log.error("No valid triplet request items for batch: {}. Skipping triplet generation.", repBatch);
                        return;
                    }

                    List<TicketTriplet> triplets = openAIService.generateTicketTripletsBatch(tripletRequestItems);

                    // VALIDATION: Check if triplet generation was successful
                    if (triplets == null || triplets.isEmpty()) {
                        log.error("Triplet generation returned null or empty results for batch: {}. Skipping embedding.", repBatch);
                        return;
                    }

                    log.info("Generated {} triplets for representatives: {}", triplets.size(), repBatch);

                    List<EmbedBatchRequest.EmbedItem> embedItems = triplets.stream()
                            .filter(t -> t.getTicketId() != null && t.getIssue() != null)
                            .map(t -> new EmbedBatchRequest.EmbedItem(t.getTicketId(), t.getIssue()))
                            .collect(Collectors.toList());

                    // VALIDATION: Check if we have items to embed
                    if (embedItems.isEmpty()) {
                        log.error("No valid items for embedding after triplet generation for batch: {}. Skipping embedding.", repBatch);
                        return;
                    }

                    List<FlaskEmbeddingResponseItem> embeddingResults = embeddingService.getEmbeddingsBatch(embedItems);

                    // VALIDATION: Check if embedding results are available
                    if (embeddingResults == null || embeddingResults.isEmpty()) {
                        log.error("Embedding service returned null or empty results for triplet batch: {}. Skipping indexing.", repBatch);
                        return;
                    }

                    for (TicketTriplet triplet : triplets) {
                        mongoService.addTicketTriplet(triplet);
                        List<Double> embedding = embeddingResults.stream()
                                .filter(item -> item.getTicketId().equals(triplet.getTicketId()))
                                .findFirst()
                                .map(FlaskEmbeddingResponseItem::getEmbedding)
                                .orElse(null);

                        if (embedding != null && !embedding.isEmpty()) {
                            elasticsearchService.indexTicketTripletWithEmbedding(triplet, embedding);
                        } else {
                            log.warn("No embedding found for triplet ticket ID: {}. Skipping Elasticsearch indexing.", triplet.getTicketId());
                        }
                    }

                    processedReps.addAndGet(repBatch.size());
                } catch (Exception e) {
                    log.error("Triplet batch processing failed for batch {}: {}", repBatch, e.getMessage(), e);
                }
            });
            tripletFutures.add(future);
        }

        for (Future<?> f : tripletFutures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("Triplet processing task failed: {}", e.getMessage());
            }
        }

        tripletExecutor.shutdown();
        PerfTracker.out("phase3_tripletProcessing");

        PerfTracker.in("phase3_finalFlush");
        mongoService.finalFlush();
        elasticsearchService.finalFlush();
        PerfTracker.out("phase3_finalFlush");

        long totalDuration = System.currentTimeMillis() - startTime;
        log.info("Completed processing {} tickets and {} representatives in {} ms",
                allTicketIds.size(), processedReps.get(), totalDuration);

        // Stop and log main performance stats
        PerfStats finalStats = PerfTracker.stopAndClean();
        if (finalStats != null) {
            perfLog.info("=== MAIN THREAD PERFORMANCE STATS ===");
            perfLog.info(finalStats.toFormattedString());

            // Log aggregated service timing summary
            perfLog.info("=== SERVICE TIMING SUMMARY ===");
            logServiceSummary(finalStats);
            perfLog.info("=== END PERFORMANCE STATS ===");
        }
    }

    private void logServiceSummary(PerfStats stats) {
        Map<String, Long> operationTimes = stats.getOperationTimes();

        // Main phases
        perfLog.info("Phase 1 (Batch Processing): {}ms",
                operationTimes.getOrDefault("phase1_batchProcessing", 0L));
        perfLog.info("Phase 2 (Clustering): {}ms",
                operationTimes.getOrDefault("phase2_clustering", 0L));
        perfLog.info("Phase 3 (Triplet Processing): {}ms",
                operationTimes.getOrDefault("phase3_tripletProcessing", 0L));

        // Service breakdown
        perfLog.info("MongoDB getAllSupportTickets: {}ms",
                operationTimes.getOrDefault("getAllSupportTickets", 0L));
        perfLog.info("Final flush operations: {}ms",
                operationTimes.getOrDefault("phase1_finalFlush", 0L) +
                        operationTimes.getOrDefault("phase3_finalFlush", 0L));
    }
}