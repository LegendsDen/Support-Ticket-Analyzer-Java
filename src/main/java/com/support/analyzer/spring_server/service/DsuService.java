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
        private int numComponents; // Track number of separate components

        public DSU(List<String> tickets) {
            parent = new HashMap<>();
            size = new HashMap<>();
            numComponents = tickets.size();

            for (String ticket : tickets) {
                parent.put(ticket, ticket); // Each node is its own parent initially
                size.put(ticket, 1);        // Each component has size 1 initially
            }
        }


        public String find(String x) {
            // Check if x exists in our DSU
            if (!parent.containsKey(x)) {
                throw new IllegalArgumentException("Ticket " + x + " not found in DSU");
            }

            // If x is not its own parent, recursively find the root and compress path
            if (!parent.get(x).equals(x)) {
                // Path compression: make x point directly to the root
                // This line ONLY overwrites the value for key 'x', not other entries
                parent.put(x, find(parent.get(x)));
            }
            return parent.get(x);
        }

        public boolean union(String x, String y) {
            // Validate inputs
            if (!parent.containsKey(x) || !parent.containsKey(y)) {
                throw new IllegalArgumentException("One or both tickets not found in DSU");
            }

            String rootX = find(x);
            String rootY = find(y);

            // Already in same component
            if (rootX.equals(rootY)) {
                return false; // No union performed
            }

            // Union by size - attach smaller tree under root of larger tree
            int sizeX = size.get(rootX);
            int sizeY = size.get(rootY);

            if (sizeX < sizeY) {
                // Make rootY the parent of rootX
                parent.put(rootX, rootY);
                size.put(rootY, sizeX + sizeY);
                size.remove(rootX); // Clean up - rootX is no longer a root
            } else {
                // Make rootX the parent of rootY
                parent.put(rootY, rootX);
                size.put(rootX, sizeX + sizeY);
                size.remove(rootY); // Clean up - rootY is no longer a root
            }

            numComponents--; // We merged two components into one
            return true; // Union was performed
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


    public List<String> buildClustersAndGetRepresentatives(List<String>allTicketIds,int k) {
        try {
            log.info("Starting clustering process with k={} and similarity threshold={}", k, SIMILARITY_THRESHOLD);

            // Get all ticket IDs
            if (allTicketIds.isEmpty()) {
                log.warn("No tickets found in Elasticsearch");
                return Collections.emptyList();
            }



            // Initialize DSU
            DSU dsu = new DSU(allTicketIds);


            // For each ticket, find k-nearest neighbors using KNN and union if similarity > threshold
            int processedCount = 0;
            int unionsPerformed = 0;

            for (String ticketId : allTicketIds) {
                try {
                    List<ElasticsearchSimilarTicket> neighbors = elasticsearchService.findKNearestNeighbors(ticketId, k);
//                    log.info("Processing ticket {} with {},{} neighbors", ticketId, neighbors, neighbors.size());
                    String root1 = dsu.find(ticketId);
                    for (ElasticsearchSimilarTicket neighbor : neighbors) {
//                        log.info("Ticket {} is similar to {} with similarity {}", ticketId, neighbor.getTicketId(), neighbor.getSimilarity());
                        if (neighbor.getSimilarity() >= SIMILARITY_THRESHOLD) {
                            String root2 = dsu.find(neighbor.getTicketId());

                            if (root1 != null && root2 != null && !root1.equals(root2)) {
                                dsu.union(ticketId, neighbor.getTicketId());
                                unionsPerformed++;
                                log.info("United {} and {} with cosine similarity {}",
                                        ticketId, neighbor.getTicketId(), neighbor.getSimilarity());
                            }
                        }
                    }

                    processedCount++;
                    if (processedCount % 100 == 0) {
                        log.info("Processed {}/{} tickets, performed {} unions", processedCount, allTicketIds.size(), unionsPerformed);
                    }

                } catch (Exception e) {
                    log.error("Error processing ticket {}"+e, ticketId, e);
                }
            }

            // Get clusters
            Map<String, List<String>> clusters = dsu.getClusters();
            log.info("Created {} unique clusters from {} unions", clusters.size(), unionsPerformed);

            List<String> representatives = selectClusterRepresentatives(clusters, dsu);
            log.info("Selected {} cluster representatives", representatives.size());

            return representatives;

        } catch (Exception e) {
            log.error("Error in clustering process: " + e, e);
            return Collections.emptyList();
        }
    }





    private List<String> selectClusterRepresentatives(Map<String, List<String>> clusters, DSU dsu) {
        List<String> representatives = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : clusters.entrySet()) {
            String clusterRoot = entry.getKey();
//            List<String> clusterMembers = entry.getValue();
            representatives.add(clusterRoot);
        }

        return representatives;
    }




}