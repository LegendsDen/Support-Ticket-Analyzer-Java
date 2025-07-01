package com.support.analyzer.spring_server.controller;



import com.support.analyzer.spring_server.dto.TicketTripletWithDetails;
import com.support.analyzer.spring_server.service.SupportTicketInference;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportTicketInferenceController {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketInferenceController.class);

    @Autowired
    private SupportTicketInference supportTicketInference;

    @PostMapping("/generate/{ticketId}")
    public ResponseEntity<InferenceResponse> generateInference(@PathVariable String ticketId) {
        try {
            log.info("Generating inference for ticket: {}", ticketId);

            TicketTripletWithDetails enhancedInference = supportTicketInference.inferSupportTicket(ticketId);

            if (enhancedInference == null) {
                log.warn("Failed to generate inference for ticket: {}", ticketId);
                return ResponseEntity.status(404).body(new InferenceResponse(
                        "error",
                        "Failed to generate inference for ticket: " + ticketId,
                        ticketId,
                        null
                ));
            }

            log.info("Successfully generated inference for ticket: {}", ticketId);
            return ResponseEntity.ok(new InferenceResponse(
                    "success",
                    "Inference generated successfully",
                    ticketId,
                    enhancedInference
            ));

        } catch (RuntimeException e) {
            log.error("Runtime error generating inference for ticket {}: {}", ticketId, e.getMessage());
            return ResponseEntity.status(500).body(new InferenceResponse(
                    "error",
                    "Internal error: " + e,
                    ticketId,
                    null
            ));
        } catch (Exception e) {
            log.error("Unexpected error generating inference for ticket {}: {}", ticketId, e, e);
            return ResponseEntity.status(500).body(new InferenceResponse(
                    "error",
                    "Unexpected error occurred: " + e,
                    ticketId,
                    null
            ));
        }
    }

    @Data
    public static class InferenceResponse {
        private String status;
        private String message;
        private String ticketId;
        private TicketTripletWithDetails inference;

        public InferenceResponse() {
        }

        public InferenceResponse(String status, String message, String ticketId, TicketTripletWithDetails inference) {
            this.status = status;
            this.message = message;
            this.ticketId = ticketId;
            this.inference = inference;
        }
    }


}