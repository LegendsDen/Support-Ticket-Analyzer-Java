package com.support.analyzer.spring_server.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.support.analyzer.spring_server.dto.EmbeddingDocument;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarTicket;
import com.support.analyzer.spring_server.dto.TripletWithEmbedding;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ElasticsearchService {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchService.class);

    private final ElasticsearchClient elasticsearchClient;

    @Value("${elasticsearch.deduplication.index}")
    private String indexName;

    @Value("${elasticsearch.search.index}")
    private String tripletIndexName;

    public ElasticsearchService(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    public void indexEmbedding(String ticketId, List<Double> embedding) {
        try {
            EmbeddingDocument doc = new EmbeddingDocument(ticketId, embedding);

            IndexRequest<EmbeddingDocument> request = IndexRequest.of(i -> i
                    .index(indexName)
                    .id(ticketId)
                    .document(doc)
            );

            elasticsearchClient.index(request);
            log.info("Indexed embedding for ticket " + ticketId);
        } catch (Exception e) {
            log.error("Error indexing embedding for " + ticketId + ": " + e.getMessage());
        }
    }

    public void indexTicketTripletWithEmbedding(TicketTriplet triplet, List<Double> issueEmbedding) {
        try {
            TripletWithEmbedding tripletWithEmbedding = new TripletWithEmbedding(
                    triplet.getTicketId(),
                    triplet.getRca(),
                    triplet.getIssue(),
                    triplet.getSolution(),
                    issueEmbedding
            );

            IndexRequest<TripletWithEmbedding> request = IndexRequest.of(i -> i
                    .index(tripletIndexName)
                    .id(triplet.getTicketId())
                    .document(tripletWithEmbedding)
            );

            elasticsearchClient.index(request);
            log.info("Indexed ticket triplet with issue embedding for ticket " + triplet.getTicketId());
        } catch (Exception e) {
            log.error("Error indexing ticket triplet with embedding for " + triplet.getTicketId() + ": " + e.getMessage());
        }
    }

    public List<ElasticsearchSimilarTicket> findKNearestNeighbors(String ticketId, int k) {
        try {
            List<Double> targetEmbedding = getEmbeddingByTicketId(ticketId);
            if (targetEmbedding == null) {
                log.warn("No embedding found for ticket: " + ticketId);
                return Collections.emptyList();
            }

            // Convert to float array as Elasticsearch KNN typically expects float vectors
            List<Float> floatEmbedding = targetEmbedding.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            SearchResponse<EmbeddingDocument> response = elasticsearchClient.search(s -> s
                            .index(indexName)
                            .knn(knn -> knn
                                    .field("embedding")
                                    .queryVector(floatEmbedding)
                                    .k((long) k + 1L) // +1 to account for self-match
                                    .numCandidates(Math.max(100L, k * 10L))
                            )
                            .size(k + 1) // Ensure we get enough results
                            .source(source -> source.filter(f -> f.includes("ticketId")))
                    , EmbeddingDocument.class);

            return response.hits().hits().stream()
                    .filter(hit -> !hit.id().equals(ticketId)) // Remove self-match
                    .limit(k)
                    .map(hit -> {
                        String hitTicketId = hit.source() != null ? hit.source().getTicketId() : hit.id();
                        return new ElasticsearchSimilarTicket(hitTicketId, hit.score());
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error finding k-nearest neighbors for ticket " + ticketId + ": " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public List<ElasticsearchSimilarInference> findSimilarTriplets(List<Double> queryEmbedding, int k) {
        try {
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                log.warn("Query embedding is null or empty");
                return Collections.emptyList();
            }

            // Convert to float array
            List<Float> floatEmbedding = queryEmbedding.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList());

            SearchResponse<TripletWithEmbedding> response = elasticsearchClient.search(s -> s
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
            log.error("Error finding similar triplets: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public TripletWithEmbedding getTripletByTicketId(String ticketId) {
        try {
            SearchResponse<TripletWithEmbedding> response = elasticsearchClient.search(s -> s
                            .index(tripletIndexName)
                            .query(q -> q
                                    .term(t -> t
                                            .field("ticketId.keyword")
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
            log.error("Error getting triplet for ticket " + ticketId + ": " + e.getMessage(), e);
            return null;
        }
    }

    private List<Double> getEmbeddingByTicketId(String ticketId) {
        try {
            SearchResponse<EmbeddingDocument> response = elasticsearchClient.search(s -> s
                            .index(indexName)
                            .query(q -> q
                                    .term(t -> t
                                            .field("ticketId.keyword") // Use .keyword for exact match
                                            .value(ticketId)
                                    )
                            )
                            .size(1) // Only need one result
                    , EmbeddingDocument.class);

            if (response.hits().hits().isEmpty()) {
                log.warn("No embedding found for ticketId: " + ticketId);
                return null;
            }

            EmbeddingDocument doc = response.hits().hits().get(0).source();
            return doc != null ? doc.getEmbedding() : null;

        } catch (Exception e) {
            log.error("Error getting embedding for ticket " + ticketId + ": " + e.getMessage(), e);
            return null;
        }
    }
}