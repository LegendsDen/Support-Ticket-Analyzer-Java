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
        long startTime = System.currentTimeMillis();
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
//                    log.info("Processing ticket " + ticketId + " with masked messages: " + joinedMasked);

                    String summary = openAIService.summarizeMessages(joinedMasked);
                    if (summary == null || summary.isBlank()) {
                        log.info("Skipping ticket " + ticketId + ": summary is blank");
                        continue;
                    }
                    log.info("Generated summary for ticket " + ticketId + ": " + summary);

//                    mongoService.addSummarizeTicket(new SummarizedTicket(ticketId, summary));

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
            List<String> representatives = dsuService.buildClustersAndGetRepresentatives(allTicketIds, 2);
            log.info("Found {} cluster representatives: {}", representatives.size(), representatives);
            long duration= System.currentTimeMillis() - startTime;
            log.info("Processed {} tickets in {} ms", allTicketIds.size(), duration);


//            // Third phase: Generate triplets for each representative
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
                    TicketTriplet triplet = openAIService.generateTicketTriplet(representativeTicketId, summary);
                    if (triplet == null) {
                        log.warn("Failed to generate triplet for ticket: {}", representativeTicketId);
                        continue;
                    }
                    log.info("Generated triplet for ticket {}: RCA={}, Issue={}, Solution={}",
                            representativeTicketId, triplet.getRca(), triplet.getIssue(), triplet.getSolution());
                    log.info(System.currentTimeMillis()- startTime + " ms elapsed since start of processing");

                    List<Double> issueEmbedding = embeddingService.getEmbedding(representativeTicketId, triplet.getIssue());
                    if (issueEmbedding == null || issueEmbedding.isEmpty()) {
                        log.warn("Failed to generate embedding for issue in ticket: {}", representativeTicketId);
                    }

//                    // Store triplet in MongoDB
//                    mongoService.addTicketTriplet(triplet);
//
//                    // Store triplet and issue embedding in Elasticsearch
                    elasticsearchService.indexTicketTripletWithEmbedding(triplet, issueEmbedding);
//
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


}