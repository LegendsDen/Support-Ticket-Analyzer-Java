package com.support.analyzer.spring_server.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.support.analyzer.spring_server.dto.EmbeddingDocument;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarTicket;
import com.support.analyzer.spring_server.dto.TripletWithEmbedding;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ElasticsearchService {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchService.class);

    private final ElasticsearchClient client;
    private final List<BulkOperation> bulkBuffer = Collections.synchronizedList(new ArrayList<>());
    private final int BULK_THRESHOLD = 100; // flush every 100 documents

    @Value("${elasticsearch.deduplication.index}")
    private String indexName;

    @Value("${elasticsearch.search.index}")
    private String tripletIndexName;

    @Autowired
    public ElasticsearchService(ElasticsearchClient client) {
        this.client = client;
    }

    public void indexEmbedding(String ticketId, List<Double> embedding) {
        BulkOperation op = BulkOperation.of(b -> b
                .index(i -> i
                        .index(indexName)
                        .id(ticketId)
                        .document(Map.of("ticketId", ticketId, "embedding", embedding))
                )
        );
        addToBulk(op);
    }

    public void indexTicketTripletWithEmbedding(TicketTriplet triplet, List<Double> issueEmbedding) {
        BulkOperation op = BulkOperation.of(b -> b
                .index(i -> i
                        .index(tripletIndexName)
                        .id(triplet.getTicketId())
                        .document(Map.of(
                                "ticketId", triplet.getTicketId(),
                                "issue", triplet.getIssue(),
                                "rca", triplet.getRca(),
                                "solution", triplet.getSolution(),
                                "issueEmbedding", issueEmbedding
                        ))
                )
        );
        addToBulk(op);
    }

    private void addToBulk(BulkOperation op) {
        bulkBuffer.add(op);
        log.debug("Added operation to bulk buffer. Buffer size: {}", bulkBuffer.size());

        if (bulkBuffer.size() >= BULK_THRESHOLD) {
            flushBulk();
        }
    }

    public synchronized void flushBulk() {
        if (bulkBuffer.isEmpty()) return;

        try {
            List<BulkOperation> opsToFlush = new ArrayList<>(bulkBuffer);
            bulkBuffer.clear();

            log.info("Flushing {} operations via bulk request", opsToFlush.size());

            BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(opsToFlush));
            BulkResponse response = client.bulk(bulkRequest);

            if (response.errors()) {
                log.warn("Bulk indexing had errors. First error: {}",
                        response.items().get(0).error());

                // Log error details
                int errorCount = 0;
                for (var item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        log.error("Bulk operation failed for document {}: {}",
                                item.id(), item.error().reason());
                    }
                }
                log.warn("Total errors in bulk operation: {}/{}", errorCount, opsToFlush.size());

            } else {
                log.info("Successfully indexed {} documents in bulk.", opsToFlush.size());
            }
        } catch (Exception e) {
            log.error("Bulk indexing failed: {}", e, e);

            // Clear buffer to prevent infinite retry
            bulkBuffer.clear();
        }
    }

    @PreDestroy
    public void onDestroy() {
        log.info("Application shutdown: performing final bulk flush for Elasticsearch...");
        finalFlush();
    }

    // Manual flush for end of processing
    public void finalFlush() {
        log.info("Performing final Elasticsearch bulk flush...");
        flushBulk();
        log.info("Final Elasticsearch flush completed");
    }

    // Get current buffer size for monitoring
    public int getBulkBufferSize() {
        return bulkBuffer.size();
    }

    // Existing search methods remain unchanged
    public List<ElasticsearchSimilarTicket> findKNearestNeighbors(String ticketId, int k) {
        try {
            List<Double> targetEmbedding = getEmbeddingByTicketId(ticketId);
            if (targetEmbedding == null) {
                log.warn("No embedding found for ticket: " + ticketId);
                return Collections.emptyList();
            }

            List<Float> floatEmbedding = targetEmbedding.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            SearchResponse<EmbeddingDocument> response = client.search(s -> s
                            .index(indexName)
                            .knn(knn -> knn
                                    .field("embedding")
                                    .queryVector(floatEmbedding)
                                    .k((long) k + 1L)
                                    .numCandidates(Math.max(100L, k * 10L))
                            )
                            .size(k + 1)
                            .source(source -> source.filter(f -> f.includes("ticketId")))
                    , EmbeddingDocument.class);

            return response.hits().hits().stream()
                    .filter(hit -> !hit.id().equals(ticketId))
                    .limit(k)
                    .map(hit -> {
                        String hitTicketId = hit.source() != null ? hit.source().getTicketId() : hit.id();
                        return new ElasticsearchSimilarTicket(hitTicketId, hit.score());
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error finding k-nearest neighbors for ticket " + ticketId + ": " + e, e);
            return Collections.emptyList();
        }
    }

    public List<ElasticsearchSimilarInference> findSimilarTriplets(List<Double> queryEmbedding, int k) {
        try {
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                log.warn("Query embedding is null or empty");
                return Collections.emptyList();
            }

            List<Float> floatEmbedding = queryEmbedding.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            SearchResponse<TripletWithEmbedding> response = client.search(s -> s
                            .index(tripletIndexName)
                            .knn(knn -> knn
                                    .field("issueEmbedding")
                                    .queryVector(floatEmbedding)
                                    .k((long) k)
                                    .numCandidates(Math.max(100L, k * 10L))
                            )
                            .size(k)
                    , TripletWithEmbedding.class);

            return response.hits().hits().stream()
                    .limit(k)
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(triplet -> new ElasticsearchSimilarInference(
                            triplet.getRca(),
                            triplet.getIssue(),
                            triplet.getSolution()
                    ))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error finding similar triplets: {}", e, e);
            return Collections.emptyList();
        }
    }

    public TripletWithEmbedding getTripletByTicketId(String ticketId) {
        try {
            SearchResponse<TripletWithEmbedding> response = client.search(s -> s
                            .index(tripletIndexName)
                            .query(q -> q
                                    .term(t -> t
                                            .field("ticketId")
                                            .value(ticketId)
                                    )
                            )
                            .size(1)
                    , TripletWithEmbedding.class);

            if (response.hits().hits().isEmpty()) {
                log.warn("No triplet found for ticketId: " + ticketId);
                return null;
            }

            return response.hits().hits().get(0).source();

        } catch (Exception e) {
            log.error("Error getting triplet for ticket " + ticketId + ": " + e, e);
            return null;
        }
    }

    private List<Double> getEmbeddingByTicketId(String ticketId) {
        try {
            SearchResponse<EmbeddingDocument> response = client.search(s -> s
                            .index(indexName)
                            .query(q -> q
                                    .term(t -> t
                                            .field("ticketId")
                                            .value(ticketId)
                                    )
                            )
                            .size(1)
                    , EmbeddingDocument.class);

            if (response.hits().hits().isEmpty()) {
                log.warn("No embedding found for ticketId: " + ticketId);
                return null;
            }

            EmbeddingDocument doc = response.hits().hits().get(0).source();
            return doc != null ? doc.getEmbedding() : null;

        } catch (Exception e) {
            log.error("Error getting embedding for ticket " + ticketId + ": " + e, e);
            return null;
        }
    }

    // Buffer statistics for monitoring
    public BufferStats getBufferStats() {
        return new BufferStats(
                bulkBuffer.size(),
                BULK_THRESHOLD
        );
    }

    // Inner class for buffer statistics
    public static class BufferStats {
        private final int currentBufferSize;
        private final int bulkThreshold;

        public BufferStats(int currentBufferSize, int bulkThreshold) {
            this.currentBufferSize = currentBufferSize;
            this.bulkThreshold = bulkThreshold;
        }

        public int getCurrentBufferSize() { return currentBufferSize; }
        public int getBulkThreshold() { return bulkThreshold; }

        @Override
        public String toString() {
            return String.format(
                    "ElasticsearchBufferStats{buffer=%d/%d}",
                    currentBufferSize, bulkThreshold
            );
        }
    }
}