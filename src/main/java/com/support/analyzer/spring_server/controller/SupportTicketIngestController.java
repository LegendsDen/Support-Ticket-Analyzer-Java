package com.support.analyzer.spring_server.controller;

import com.support.analyzer.spring_server.service.SupportTicketIngestService;
import com.support.analyzer.spring_server.service.SupportTicketIngestService2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SupportTicketIngestController {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketIngestController.class);

    @Autowired
    private SupportTicketIngestService supportTicketIngestService;
    @Autowired
    private SupportTicketIngestService2 supportTicketIngestService2;

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> processAllTickets2() {
        try {
            log.info("Starting bulk ticket processing...");

                try {
                    supportTicketIngestService.processAllTickets();
                    log.info("Tickets processing completed successfully");
                } catch (Exception e) {
                    log.error("Error during bulk ticket processing: {}", e, e);
                }
            return ResponseEntity.ok(Map.of(
                    "status", "processing_started",
                    "message", "Ticket processing has been initiated. Check logs for progress."
            ));

        } catch (Exception e) {
            log.error("Error starting ticket processing: {}", e, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "Failed to start ticket processing: " + e
            ));
        }
    }

    @PostMapping("/ingest2")
    public ResponseEntity<Map<String, String>> processAllTickets() {
        try {
            log.info("Starting bulk ticket processing...");

            try {
                supportTicketIngestService2.processAllTickets2();
                log.info("Tickets processing completed successfully");
            } catch (Exception e) {
                log.error("Error during bulk ticket processing: {}", e, e);
            }
            return ResponseEntity.ok(Map.of(
                    "status", "processing_started",
                    "message", "Ticket processing has been initiated. Check logs for progress."
            ));

        } catch (Exception e) {
            log.error("Error starting ticket processing: {}", e, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "Failed to start ticket processing: " + e
            ));
        }
    }


}