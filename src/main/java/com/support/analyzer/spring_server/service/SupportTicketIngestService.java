package com.support.analyzer.spring_server.service;
import com.support.analyzer.spring_server.entity.SummarizedTicket;
import com.support.analyzer.spring_server.entity.SupportTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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


    @Autowired
    public SupportTicketIngestService(MongoService mongoService,
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

    public void processAllTickets() {
        List<SupportTicket> tickets = mongoService.getAllSupportTicket();

        for (SupportTicket ticket : tickets) {
            try {
                String ticketId = ticket.getTicketId();
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


    }
}
