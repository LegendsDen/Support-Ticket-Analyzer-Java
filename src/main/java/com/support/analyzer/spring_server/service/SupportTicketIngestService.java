package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.entity.SummarizedTicket;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
        List<SupportTicket> tickets = mongoService.getAllSupportTicket();
        List<String> allTicketIds = new ArrayList<>();

        try {
            // First phase: Process all tickets for embeddings
            for (SupportTicket ticket : tickets) {
                try {
                    String ticketId = ticket.getTicketId();
                    allTicketIds.add(ticketId);

                    List<String> rawMessages = ticket.getMessages().stream()
                            .map(msg -> msg.get_source().getMessage())
                            .collect(Collectors.toList());

                    List<String> maskedMessages = maskingService.getMaskedMessages(ticketId, rawMessages);
                    if (maskedMessages == null || maskedMessages.isEmpty()) {
                        log.info("Skipping ticket " + ticketId + ": masking failed or empty");
                        continue;
                    }

                    String joinedMasked = String.join("\n", maskedMessages);

                    String summary = openAIService.summarizeMessages(joinedMasked);
                    if (summary == null || summary.isBlank()) {
                        log.info("Skipping ticket " + ticketId + ": summary is blank");
                        continue;
                    }

                    mongoService.addSummarizeTicket(new SummarizedTicket(ticketId, summary));

                    List<Double> embedding = embeddingService.getEmbedding(ticketId, summary);
                    if (embedding == null || embedding.isEmpty()) {
                        log.info("Skipping ticket " + ticketId + ": embedding failed");
                        continue;
                    }

                    elasticsearchService.indexEmbedding(ticketId, embedding);

                } catch (Exception e) {
                    log.error("Error processing ticket " + ticket.getTicketId() + ": " + e.getMessage());
                }
            }

            // Second phase: Build clusters and process representatives
            List<String> representatives = dsuService.buildClustersAndGetRepresentatives(allTicketIds, 5);
            log.info("Found {} cluster representatives: {}", representatives.size(), representatives);

            // Third phase: Generate triplets for each representative
            int processedRepresentatives = 0;
            for (String representativeTicketId : representatives) {
                try {
                    log.info("Processing representative ticket: {}", representativeTicketId);

                    // Fetch summary from MongoDB
                    SummarizedTicket summarizedTicket = mongoService.getSummarizedTicketById(representativeTicketId);
                    if (summarizedTicket == null) {
                        log.warn("No summary found for representative ticket: {}", representativeTicketId);
                        continue;
                    }

                    String summary = summarizedTicket.getSummary();
                    if (summary == null || summary.isBlank()) {
                        log.warn("Empty summary for representative ticket: {}", representativeTicketId);
                        continue;
                    }

                    // Generate RCA, issue, and solution using OpenAI
                    TicketTriplet triplet = generateTicketTriplet(representativeTicketId, summary);
                    if (triplet == null) {
                        log.warn("Failed to generate triplet for ticket: {}", representativeTicketId);
                        continue;
                    }

                    // Generate embedding for the issue
                    List<Double> issueEmbedding = embeddingService.getEmbedding(representativeTicketId, triplet.getIssue());
                    if (issueEmbedding == null || issueEmbedding.isEmpty()) {
                        log.warn("Failed to generate embedding for issue in ticket: {}", representativeTicketId);
                        // Still save the triplet even without embedding
                    }

                    // Store triplet in MongoDB
                    mongoService.addTicketTriplet(triplet);

                    // Store triplet and issue embedding in Elasticsearch
                    elasticsearchService.indexTicketTripletWithEmbedding(triplet, issueEmbedding);

                    processedRepresentatives++;
                    log.info("Successfully processed representative {}/{}: {}",
                            processedRepresentatives, representatives.size(), representativeTicketId);

                } catch (Exception e) {
                    log.error("Error processing representative ticket {}: {}", representativeTicketId, e.getMessage());
                }
            }

            log.info("Completed processing {} cluster representatives", processedRepresentatives);

        } catch (Exception e) {
            log.error("Error processing tickets: " + e.getMessage());
        }
    }

    private TicketTriplet generateTicketTriplet(String ticketId, String summary) {
        try {
            log.debug("Generating triplet for ticket: {}", ticketId);

            // Generate RCA (Root Cause Analysis)
            String rcaPrompt = "Based on the following support ticket summary, identify the root cause analysis. " +
                    "Provide only the root cause without additional explanation: " + summary;
            String rca = openAIService.generateResponse(rcaPrompt);

            if (rca == null || rca.isBlank()) {
                log.warn("Failed to generate RCA for ticket: {}", ticketId);
                return null;
            }

            // Generate Issue
            String issuePrompt = "Based on the following support ticket summary, identify the main issue. " +
                    "Provide only the issue description without additional explanation: " + summary;
            String issue = openAIService.generateResponse(issuePrompt);

            if (issue == null || issue.isBlank()) {
                log.warn("Failed to generate issue for ticket: {}", ticketId);
                return null;
            }

            // Generate Solution
            String solutionPrompt = "Based on the following support ticket summary, provide the solution. " +
                    "Provide only the solution without additional explanation: " + summary;
            String solution = openAIService.generateResponse(solutionPrompt);

            if (solution == null || solution.isBlank()) {
                log.warn("Failed to generate solution for ticket: {}", ticketId);
                return null;
            }

            // Create and return TicketTriplet
            TicketTriplet triplet = new TicketTriplet();
            triplet.setTicketId(ticketId);
            triplet.setRca(rca.trim());
            triplet.setIssue(issue.trim());
            triplet.setSolution(solution.trim());

            log.debug("Generated triplet for ticket {}: RCA={}, Issue={}, Solution={}",
                    ticketId, rca.length(), issue.length(), solution.length());

            return triplet;

        } catch (Exception e) {
            log.error("Error generating triplet for ticket {}: {}", ticketId, e.getMessage());
            return null;
        }
    }
}