package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.entity.*;
import com.support.analyzer.spring_server.repository.NewSupportTicketRepository;
import com.support.analyzer.spring_server.repository.SummarizedTicketRepository;
import com.support.analyzer.spring_server.repository.SupportTicketRepository;
import com.support.analyzer.spring_server.repository.TicketTripletRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MongoService {
    private static final Logger log = LoggerFactory.getLogger(MongoService.class);

    private final SupportTicketRepository messageRepository;
    private final SummarizedTicketRepository summarizedTicketRepository;
    private final TicketTripletRepository ticketTripletRepository;
    private final NewSupportTicketRepository newSupportTicketRepository;
    private final MongoTemplate mongoTemplate;

    // Bulk buffers - SHARED across all threads
    private final List<SummarizedTicket> summarizedTicketBuffer = Collections.synchronizedList(new ArrayList<>());
    private final List<TicketTriplet> tripletBuffer = Collections.synchronizedList(new ArrayList<>());
    private final int BULK_THRESHOLD = 50; // flush every 50 documents

    @Autowired
    public MongoService(SupportTicketRepository messageRepository,
                        SummarizedTicketRepository summarizedTicketRepository,
                        TicketTripletRepository ticketTripletRepository,
                        NewSupportTicketRepository newSupportTicketRepository,
                        MongoTemplate mongoTemplate) {
        this.messageRepository = messageRepository;
        this.summarizedTicketRepository = summarizedTicketRepository;
        this.ticketTripletRepository = ticketTripletRepository;
        this.newSupportTicketRepository = newSupportTicketRepository;
        this.mongoTemplate = mongoTemplate;
    }


    public List<SupportTicket> getAllSupportTicket() {
        return messageRepository.findAll();
    }

    public SupportTicket getSupportTicketById(String id) {
        return messageRepository.findById(id).orElseThrow(() -> new RuntimeException("Ticket not found"));
    }

    public NewSupportTicket getNewSupportTicketById(String id) {
        return newSupportTicketRepository.findById(id).orElseThrow(() -> new RuntimeException("Ticket not found"));
    }

    public NewSupportTicket addNewSupportTicket(NewSupportTicket supportTicket) {
        try {
            supportTicket.setCreatedAt(LocalDateTime.now());
            supportTicket.setUpdatedAt(LocalDateTime.now());
            return newSupportTicketRepository.save(supportTicket);
        } catch (Exception e) {
            throw new RuntimeException("Error saving support ticket: " + e.getMessage());
        }
    }

    public SupportTicket addSupportTicket(SupportTicket supportTicket) {
        try {
            supportTicket.setCreatedAt(LocalDateTime.now());
            supportTicket.setUpdatedAt(LocalDateTime.now());
            return messageRepository.save(supportTicket);
        } catch (Exception e) {
            throw new RuntimeException("Error saving support ticket: " + e.getMessage());
        }
    }

    public void addMessageToTicket(String ticketId, Message message) {
        try {
            SupportTicket ticket = messageRepository.findById(ticketId)
                    .orElseThrow(() -> new RuntimeException("Ticket not found"));
            ticket.setUpdatedAt(LocalDateTime.now());
            ticket.getMessages().add(message);
            messageRepository.save(ticket);
        } catch (Exception e) {
            throw new RuntimeException("Error adding message to ticket: " + e.getMessage());
        }
    }

    public SummarizedTicket getSummarizedTicketById(String ticketId) {
        try {
            return summarizedTicketRepository.findById(ticketId).orElse(null);
        } catch (Exception e) {
            log.error("Error getting summarized ticket {}: {}", ticketId, e.getMessage());
            return null;
        }
    }


    public void addSummarizeTicketDirect(SummarizedTicket summarizedTicket) {
        summarizedTicket.setCreatedAt(LocalDateTime.now());
        summarizedTicket.setUpdatedAt(LocalDateTime.now());
        try {
            summarizedTicketRepository.save(summarizedTicket); // save() automatically does upsert in Spring Data
        } catch (Exception e) {
            throw new RuntimeException("Error saving summarized ticket: " + e.getMessage());
        }
    }

    public void addTicketTripletDirect(TicketTriplet ticketTriplet) {
        try {
            ticketTripletRepository.save(ticketTriplet); // save() automatically does upsert in Spring Data
        } catch (Exception e) {
            throw new RuntimeException("Error saving ticket triplet: " + e.getMessage());
        }
    }

    // ==================== NEW BUFFERED BULK UPSERT METHODS ====================
    public void addSummarizeTicket(SummarizedTicket summarizedTicket) {
        summarizedTicket.setCreatedAt(LocalDateTime.now());
        summarizedTicket.setUpdatedAt(LocalDateTime.now());

        summarizedTicketBuffer.add(summarizedTicket);
        log.debug("Added summarized ticket to buffer. Buffer size: {}", summarizedTicketBuffer.size());

        if (summarizedTicketBuffer.size() >= BULK_THRESHOLD) {
            flushSummarizedTickets();
        }
    }

    public void addTicketTriplet(TicketTriplet ticketTriplet) {
        tripletBuffer.add(ticketTriplet);
        log.debug("Added triplet to buffer. Buffer size: {}", tripletBuffer.size());

        if (tripletBuffer.size() >= BULK_THRESHOLD) {
            flushTicketTriplets();
        }
    }

    public synchronized void flushSummarizedTickets() {
        if (summarizedTicketBuffer.isEmpty()) return;

        try {
            List<SummarizedTicket> toFlush = new ArrayList<>(summarizedTicketBuffer);
            summarizedTicketBuffer.clear();

            log.info("Flushing {} summarized tickets via bulk upsert operation", toFlush.size());

            BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SummarizedTicket.class);

            for (SummarizedTicket ticket : toFlush) {
                Query query = new Query(Criteria.where("_id").is(ticket.getTicketId()));

                Update update = new Update()
                        .set("summary", ticket.getSummary())
                        .set("updatedAt", ticket.getUpdatedAt())
                        .setOnInsert("createdAt", ticket.getCreatedAt());

                bulkOps.upsert(query, update);
            }

            var result = bulkOps.execute();
            log.info("Successfully bulk upserted {} summarized tickets. Matched: {}, Modified: {}, Upserted: {}",
                    toFlush.size(), result.getMatchedCount(), result.getModifiedCount(), result.getUpserts().size());

        } catch (Exception e) {
            log.error("Error bulk upserting summarized tickets: {}", e.getMessage());

            // Fallback to individual upserts
            log.info("Attempting individual upserts as fallback");
            List<SummarizedTicket> failedItems = new ArrayList<>(summarizedTicketBuffer);
            summarizedTicketBuffer.clear();

            int successCount = 0;
            for (SummarizedTicket ticket : failedItems) {
                try {
                    summarizedTicketRepository.save(ticket); // save() does upsert
                    successCount++;
                } catch (Exception ex) {
                    log.error("Failed to upsert summarized ticket {}: {}", ticket.getTicketId(), ex.getMessage());
                }
            }
            log.info("Fallback completed: {}/{} summarized tickets upserted successfully",
                    successCount, failedItems.size());
        }
    }

    public synchronized void flushTicketTriplets() {
        if (tripletBuffer.isEmpty()) return;

        try {
            List<TicketTriplet> toFlush = new ArrayList<>(tripletBuffer);
            tripletBuffer.clear();

            log.info("Flushing {} ticket triplets via bulk upsert operation", toFlush.size());

            BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, TicketTriplet.class);

            for (TicketTriplet triplet : toFlush) {
                Query query = new Query(Criteria.where("_id").is(triplet.getTicketId()));

                Update update = new Update()
                        .set("rca", triplet.getRca())
                        .set("issue", triplet.getIssue())
                        .set("solution", triplet.getSolution());

                bulkOps.upsert(query, update);
            }

            var result = bulkOps.execute();
            log.info("Successfully bulk upserted {} ticket triplets. Matched: {}, Modified: {}, Upserted: {}",
                    toFlush.size(), result.getMatchedCount(), result.getModifiedCount(), result.getUpserts().size());

        } catch (Exception e) {
            log.error("Error bulk upserting ticket triplets: {}", e.getMessage());

            // Fallback to individual upserts
            log.info("Attempting individual upserts as fallback");
            List<TicketTriplet> failedItems = new ArrayList<>(tripletBuffer);
            tripletBuffer.clear();

            int successCount = 0;
            for (TicketTriplet triplet : failedItems) {
                try {
                    ticketTripletRepository.save(triplet); // save() does upsert
                    successCount++;
                } catch (Exception ex) {
                    log.error("Failed to upsert ticket triplet {}: {}", triplet.getTicketId(), ex.getMessage());
                }
            }
            log.info("Fallback completed: {}/{} ticket triplets upserted successfully",
                    successCount, failedItems.size());
        }
    }

    @PreDestroy
    public void onDestroy() {
        log.info("Application shutdown: performing final bulk flush for MongoDB...");
        finalFlush();
    }

    // Manual flush methods for end of processing
    public void finalFlush() {
        log.info("Performing final MongoDB bulk flush...");
        flushSummarizedTickets();
        flushTicketTriplets();
        log.info("Final MongoDB flush completed");
    }

    // Get current buffer sizes for monitoring
    public int getSummarizedTicketBufferSize() {
        return summarizedTicketBuffer.size();
    }

    public int getTripletBufferSize() {
        return tripletBuffer.size();
    }

    // Get buffer statistics
    public BufferStats getBufferStats() {
        return new BufferStats(
                summarizedTicketBuffer.size(),
                tripletBuffer.size(),
                BULK_THRESHOLD
        );
    }

    // Inner class for buffer statistics
    public static class BufferStats {
        private final int summarizedTicketBufferSize;
        private final int tripletBufferSize;
        private final int bulkThreshold;

        public BufferStats(int summarizedTicketBufferSize, int tripletBufferSize, int bulkThreshold) {
            this.summarizedTicketBufferSize = summarizedTicketBufferSize;
            this.tripletBufferSize = tripletBufferSize;
            this.bulkThreshold = bulkThreshold;
        }

        public int getSummarizedTicketBufferSize() { return summarizedTicketBufferSize; }
        public int getTripletBufferSize() { return tripletBufferSize; }
        public int getBulkThreshold() { return bulkThreshold; }

        @Override
        public String toString() {
            return String.format(
                    "MongoBufferStats{summarizedBuffer=%d/%d, tripletBuffer=%d/%d}",
                    summarizedTicketBufferSize, bulkThreshold,
                    tripletBufferSize, bulkThreshold
            );
        }
    }
}