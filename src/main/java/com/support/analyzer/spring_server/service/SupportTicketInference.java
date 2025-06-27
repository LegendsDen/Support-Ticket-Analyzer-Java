package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.entity.NewSupportTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;




@Service
public class SupportTicketInference {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketInference.class);
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
        this.elasticsearchService = elasticsearchService;}

    public ElasticsearchSimilarInference inferSupportTicket(String ticketId) {
        try {
            log.info("Starting inference for ticket: {}", ticketId);

            // Step 1: Get the support ticket and process messages
            NewSupportTicket ticket = mongoService.getNewSupportTicketById(ticketId);
            if (ticket == null) {
                log.warn("Ticket not found: {}", ticketId);
                return null;
            }

            List<String> rawMessages = ticket.getMessages().stream()
                    .map(msg -> msg.get_source().getMessage())
                    .collect(Collectors.toList());

            if (rawMessages.isEmpty()) {
                log.warn("No messages found for ticket: {}", ticketId);
                return null;
            }

            // Step 2: Mask sensitive information
            List<String> maskedMessages = maskingService.getMaskedMessages(ticketId, rawMessages);
            log.info("Masked messages for ticket {}: {}", ticketId, maskedMessages);
            if (maskedMessages == null || maskedMessages.isEmpty()) {
                log.warn("Masking failed or returned empty for ticket: {}", ticketId);
                return null;
            }

            String joinedMasked = String.join("\n", maskedMessages);
            log.debug("Masked messages for ticket {}: {}", ticketId, joinedMasked);

            // Step 3: Generate summary for embedding
            String summary = openAIService.summarizeMessages(joinedMasked);
            log.info("Generated summary for ticket {}: {}", ticketId, summary);
            if (summary == null || summary.isBlank()) {
                log.warn("Summary generation failed for ticket: {}", ticketId);
                return null;
            }

            // Step 4: Generate embedding for the summary
            List<Double> embedding = embeddingService.getEmbedding(ticketId, summary);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("Embedding generation failed for ticket: {}", ticketId);
                return null;
            }

            // Step 5: Find k-nearest neighbors in triplets index
            List<ElasticsearchSimilarInference> similarTriplets = elasticsearchService.findSimilarTriplets(embedding, K_NEAREST_NEIGHBORS);
            log.info("Found {} similar triplets for ticket: {}", similarTriplets, ticketId);
            if (similarTriplets.isEmpty()) {
                log.warn("No similar triplets found for ticket: {}", ticketId);
                return null;
            }

            log.info("Found {} similar triplets for ticket: {}", similarTriplets, ticketId);

            // Step 6: Generate complete inference using OpenAI with similar triplets context
            ElasticsearchSimilarInference inference = openAIService.generateCompleteInference(joinedMasked, similarTriplets);


            if (inference == null) {
                log.warn("Failed to generate complete inference for ticket: {}", ticketId);
                return null;
            }

            log.info("Successfully generated inference for ticket: {}", ticketId);
            return inference;

        } catch (Exception e) {
            log.error("Error during inference for ticket {}: {}", ticketId, e.getMessage(), e);
            throw new RuntimeException("Failed to infer support ticket: " + e.getMessage(), e);
        }
    }


}