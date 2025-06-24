package com.support.analyzer.spring_server.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.support.analyzer.spring_server.dto.EmbeddingDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ElasticsearchService {

    private final ElasticsearchClient elasticsearchClient;

    @Value("${elasticsearch.index}")
    private String indexName;

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
            System.out.println("Indexed embedding for ticket: " + ticketId);
        } catch (Exception e) {
            System.err.println("Error indexing embedding for " + ticketId + ": " + e.getMessage());
        }
    }
}
