package com.support.analyzer.spring_server.service;
import com.support.analyzer.spring_server.entity.SupportTicket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupportTicketIngestService {

    private final MongoService mongoService;
    private final MaskingService maskingService;
    private final OpenAIService openAIService;
    private final EmbeddingService embeddingService;

    @Autowired
    public SupportTicketIngestService(MongoService mongoService,
                                       MaskingService maskingService,
                                       OpenAIService openAIService,
                                       EmbeddingService embeddingService) {
        this.mongoService = mongoService;
        this.maskingService = maskingService;
        this.openAIService = openAIService;
        this.embeddingService = embeddingService;
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
                    System.err.println("Skipping ticket " + ticketId + ": masking failed or empty");
                    continue;
                }
                String joinedMasked = String.join("\n", maskedMessages);

                String summary = openAIService.summarizeMessages(joinedMasked);
                if (summary == null || summary.isBlank()) {
                    System.err.println("Skipping ticket " + ticketId + ": summary is blank");
                    continue;
                }

                List<Double> embedding = embeddingService.getEmbedding(ticketId, summary);
                if (embedding == null || embedding.isEmpty()) {
                    System.err.println("Skipping ticket " + ticketId + ": embedding failed");
                    continue;
                }




            } catch (Exception e) {
                System.err.println("❌ Error processing ticket " + ticket.getTicketId() + ": " + e.getMessage());
            }
        }
    }
}
