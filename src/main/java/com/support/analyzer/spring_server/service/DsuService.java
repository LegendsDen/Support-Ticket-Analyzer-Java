package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.ElasticsearchSimilarTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DsuService {
    private static final Logger log = LoggerFactory.getLogger(DsuService.class);
    private static final double SIMILARITY_THRESHOLD = 0.8;

    private final ElasticsearchService elasticsearchService;

    @Autowired
    public DsuService(ElasticsearchService elasticsearchService) {
        this.elasticsearchService = elasticsearchService;
    }

    private static class DSU {
        private Map<String, String> parent;
        private Map<String, Integer> size;
        private int numComponents;

        public DSU(List<String> tickets) {
            parent = new HashMap<>();
            size = new HashMap<>();
            numComponents = 0;
            for (String ticket : tickets) {
                makeSet(ticket);
            }
        }

        // Dynamically initialize if not present
        private void makeSet(String ticket) {
            if (!parent.containsKey(ticket)) {
                parent.put(ticket, ticket);
                size.put(ticket, 1);
                numComponents++;
            }
        }

        public String find(String x) {
            makeSet(x);  // Ensure initialization
            if (!parent.get(x).equals(x)) {
                parent.put(x, find(parent.get(x))); // Path compression
            }
            return parent.get(x);
        }

        public boolean union(String x, String y) {
            makeSet(x);
            makeSet(y);

            String rootX = find(x);
            String rootY = find(y);

            if (rootX.equals(rootY)) {
                return false;
            }

            int sizeX = size.get(rootX);
            int sizeY = size.get(rootY);

            if (sizeX < sizeY) {
                parent.put(rootX, rootY);
                size.put(rootY, sizeX + sizeY);
                size.remove(rootX);
            } else {
                parent.put(rootY, rootX);
                size.put(rootX, sizeX + sizeY);
                size.remove(rootY);
            }

            numComponents--;
            return true;
        }

        public Map<String, List<String>> getClusters() {
            Map<String, List<String>> clusters = new HashMap<>();
            for (String ticket : parent.keySet()) {
                String root = find(ticket);
                clusters.computeIfAbsent(root, k -> new ArrayList<>()).add(ticket);
            }
            return clusters;
        }

        public Map<String, Integer> getClusterSizes() {
            Map<String, Integer> clusterSizes = new HashMap<>();
            for (String ticket : parent.keySet()) {
                String root = find(ticket);
                clusterSizes.put(root, size.get(root));
            }
            return clusterSizes;
        }

        public int getClusterSize(String ticket) {
            String root = find(ticket);
            return size.get(root);
        }
    }

    public List<String> buildClustersAndGetRepresentatives(List<String> allTicketIds, int k) {
        try {
            log.info("Starting clustering process with k={} and similarity threshold={}", k, SIMILARITY_THRESHOLD);

            if (allTicketIds.isEmpty()) {
                log.warn("No tickets found in Elasticsearch");
                return Collections.emptyList();
            }

            DSU dsu = new DSU(allTicketIds);

            int processedCount = 0;
            int unionsPerformed = 0;

            for (String ticketId : allTicketIds) {
                try {
                    List<ElasticsearchSimilarTicket> neighbors = elasticsearchService.findKNearestNeighbors(ticketId, k);

                    for (ElasticsearchSimilarTicket neighbor : neighbors) {
                        if (neighbor.getSimilarity() >= SIMILARITY_THRESHOLD) {
                            if (dsu.union(ticketId, neighbor.getTicketId())) {
                                unionsPerformed++;
                                log.info("United {} and {} with cosine similarity {}", ticketId, neighbor.getTicketId(), neighbor.getSimilarity());
                            }
                        }
                    }

                    processedCount++;
                    if (processedCount % 100 == 0) {
                        log.info("Processed {}/{} tickets, performed {} unions", processedCount, allTicketIds.size(), unionsPerformed);
                    }

                } catch (Exception e) {
                    log.error("Error processing ticket {}: {}", ticketId, e.getMessage(), e);
                }
            }

            Map<String, List<String>> clusters = dsu.getClusters();
            log.info("Created {} unique clusters from {} unions", clusters.size(), unionsPerformed);

            List<String> representatives = selectClusterRepresentatives(clusters);
            log.info("Selected {} cluster representatives", representatives.size());

            return representatives;

        } catch (Exception e) {
            log.error("Error in clustering process: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<String> selectClusterRepresentatives(Map<String, List<String>> clusters) {
        return new ArrayList<>(clusters.keySet());
    }
}
