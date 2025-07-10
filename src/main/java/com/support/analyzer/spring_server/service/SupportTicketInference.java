package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.entity.NewSupportTicket;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import com.support.analyzer.spring_server.dto.TicketTripletWithDetails;
import com.support.analyzer.spring_server.util.PerfStats;
import com.support.analyzer.spring_server.util.PerfTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupportTicketInference {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketInference.class);
    private static final Logger perfLog = LoggerFactory.getLogger("PERF_SUMMARY");
    private static final int K_NEAREST_NEIGHBORS = 5;

    private final MongoService mongoService;
    private final MaskingService maskingService;
    private final OpenAIService openAIService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchService elasticsearchService;

    @Autowired
    public SupportTicketInference(MongoService mongoService,
                                  MaskingService maskingService,
                                  OpenAIService openAIService,
                                  EmbeddingService embeddingService,
                                  ElasticsearchService elasticsearchService) {
        this.mongoService = mongoService;
        this.maskingService = maskingService;
        this.openAIService = openAIService;
        this.embeddingService = embeddingService;
        this.elasticsearchService = elasticsearchService;
    }

    public TicketTripletWithDetails inferSupportTicket(String ticketId) {
        PerfStats mainPerfStats = PerfTracker.start();
        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting inference for ticket: {}", ticketId);

            // Step 1: Get the support ticket and process messages
            PerfTracker.in("getTicketAndProcessMessages");
            NewSupportTicket ticket = mongoService.getNewSupportTicketById(ticketId);
            if (ticket == null) {
                log.warn("Ticket not found: {}", ticketId);
                PerfTracker.out("getTicketAndProcessMessages");
                return null;
            }

            List<String> rawMessages = ticket.getMessages().stream()
                    .map(msg -> msg.get_source().getMessage())
                    .collect(Collectors.toList());

            if (rawMessages.isEmpty()) {
                log.warn("No messages found for ticket: {}", ticketId);
                PerfTracker.out("getTicketAndProcessMessages");
                return null;
            }
            log.info("Raw messages for ticket {}: {}", ticketId, rawMessages);
            PerfTracker.out("getTicketAndProcessMessages");

            // Step 2: Mask sensitive information
            PerfTracker.in("maskingService_getSingle");
            List<String> maskedMessages = maskingService.getMaskedMessages(ticketId, rawMessages);
            if (maskedMessages == null || maskedMessages.isEmpty()) {
                log.warn("Masking failed or returned empty for ticket: {}", ticketId);
                PerfTracker.out("maskingService_getSingle");
                return null;
            }
            log.info("Masked messages for ticket {}: {}", ticketId, maskedMessages);

            String joinedMasked = String.join("\n", maskedMessages);
            log.debug("Masked messages for ticket {}: {}", ticketId, joinedMasked);
            PerfTracker.out("maskingService_getSingle");

            // Step 3: Generate summary for embedding
            PerfTracker.in("openAIService_summarizeSingle");
            String summary = openAIService.summarizeMessages(joinedMasked);
            log.info("Generated summary for ticket {}: {}", ticketId, summary);
            if (summary == null || summary.isBlank()) {
                log.warn("Summary generation failed for ticket: {}", ticketId);
                PerfTracker.out("openAIService_summarizeSingle");
                return null;
            }
            PerfTracker.out("openAIService_summarizeSingle");

            // Step 4: Generate embedding for the summary
            PerfTracker.in("embeddingService_getSingle");
            List<Double> embedding = embeddingService.getEmbedding(ticketId, summary);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("Embedding generation failed for ticket: {}", ticketId);
                PerfTracker.out("embeddingService_getSingle");
                return null;
            }
            PerfTracker.out("embeddingService_getSingle");

            // Step 5: Find k-nearest neighbors in triplets index
            PerfTracker.in("elasticsearchService_findSimilar");
            List<ElasticsearchSimilarInference> similarTriplets = elasticsearchService.findSimilarTriplets(embedding, K_NEAREST_NEIGHBORS);
            log.info("Found {} similar triplets for ticket: {}", similarTriplets.size(), ticketId);
            if (similarTriplets.isEmpty()) {
                log.warn("No similar triplets found for ticket: {}", ticketId);
                PerfTracker.out("elasticsearchService_findSimilar");
                return null;
            }
            PerfTracker.out("elasticsearchService_findSimilar");

            // Step 6: Convert ElasticsearchSimilarInference to SimilarTicketInfo for response
            PerfTracker.in("convertSimilarTriplets");
            List<TicketTripletWithDetails.SimilarTicketInfo> similarTicketInfos = similarTriplets.stream()
                    .map(triplet -> {
                        TicketTripletWithDetails.SimilarTicketInfo info = new TicketTripletWithDetails.SimilarTicketInfo();
                        info.setRca(triplet.getRca());
                        info.setIssue(triplet.getIssue());
                        info.setSolution(triplet.getSolution());
                        // If you have similarity score available, set it here
                        // info.setSimilarity(triplet.getSimilarityScore());
                        return info;
                    })
                    .collect(Collectors.toList());

            log.info("Converted {} similar triplets to SimilarTicketInfo objects", similarTicketInfos.size());
            PerfTracker.out("convertSimilarTriplets");

            // Step 7: Generate complete inference using OpenAI with similar triplets context
            PerfTracker.in("openAIService_generateCompleteInference");
            TicketTriplet inference = openAIService.generateCompleteInference(ticketId, joinedMasked, similarTriplets);

            if (inference == null) {
                log.warn("Failed to generate complete inference for ticket: {}", ticketId);
                PerfTracker.out("openAIService_generateCompleteInference");
                return null;
            }
            PerfTracker.out("openAIService_generateCompleteInference");

            // Save the inference
            PerfTracker.in("mongoService_saveInference");
            mongoService.addTicketTriplet(inference);
            mongoService.finalFlush();
            log.info("Saved inference for ticket: {},{}", ticketId, inference);
            PerfTracker.out("mongoService_saveInference");

            // Step 8: Create enhanced response with similar tickets information
            PerfTracker.in("createEnhancedResponse");
            TicketTripletWithDetails enhancedInference = new TicketTripletWithDetails(
                    inference,
                    joinedMasked,
                    summary,
                    similarTicketInfos
            );
            PerfTracker.out("createEnhancedResponse");

            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("Successfully generated inference for ticket: {} with {} similar tickets in {}ms",
                    ticketId, similarTicketInfos.size(), totalDuration);

            return enhancedInference;

        } catch (Exception e) {
            log.error("Error during inference for ticket {}: {}", ticketId, e.getMessage(), e);
            throw new RuntimeException("Failed to infer support ticket: " + e.getMessage(), e);
        } finally {
            // Stop and log performance stats
            PerfStats finalStats = PerfTracker.stopAndClean();
            if (finalStats != null) {
                perfLog.info("=== INFERENCE PERFORMANCE STATS FOR TICKET {} ===", ticketId);
                perfLog.info(finalStats.toFormattedString());

                // Log service timing summary
                perfLog.info("=== INFERENCE SERVICE TIMING SUMMARY ===");
                logInferenceServiceSummary(finalStats, ticketId);
                perfLog.info("=== END INFERENCE PERFORMANCE STATS ===");
            }
        }
    }

    private void logInferenceServiceSummary(PerfStats stats, String ticketId) {
        var operationTimes = stats.getOperationTimes();

        perfLog.info("Ticket {} - Get & Process Messages: {}ms", ticketId,
                operationTimes.getOrDefault("getTicketAndProcessMessages", 0L));
        perfLog.info("Ticket {} - Masking Service: {}ms", ticketId,
                operationTimes.getOrDefault("maskingService_getSingle", 0L));
        perfLog.info("Ticket {} - OpenAI Summarization: {}ms", ticketId,
                operationTimes.getOrDefault("openAIService_summarizeSingle", 0L));
        perfLog.info("Ticket {} - Embedding Service: {}ms", ticketId,
                operationTimes.getOrDefault("embeddingService_getSingle", 0L));
        perfLog.info("Ticket {} - Elasticsearch Similar Search: {}ms", ticketId,
                operationTimes.getOrDefault("elasticsearchService_findSimilar", 0L));
        perfLog.info("Ticket {} - Convert Similar Triplets: {}ms", ticketId,
                operationTimes.getOrDefault("convertSimilarTriplets", 0L));
        perfLog.info("Ticket {} - OpenAI Complete Inference: {}ms", ticketId,
                operationTimes.getOrDefault("openAIService_generateCompleteInference", 0L));
        perfLog.info("Ticket {} - MongoDB Save: {}ms", ticketId,
                operationTimes.getOrDefault("mongoService_saveInference", 0L));
        perfLog.info("Ticket {} - Create Enhanced Response: {}ms", ticketId,
                operationTimes.getOrDefault("createEnhancedResponse", 0L));
    }
}