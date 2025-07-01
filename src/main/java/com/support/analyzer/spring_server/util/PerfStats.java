// PerfStats.java
package com.support.analyzer.spring_server.util;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PerfStats {
    private final Instant startTime;
    private final Map<String, Long> operationTimes;
    private final Map<String, Integer> operationCounts;
    private final Stack<String> operationStack;
    private final Map<String, Instant> currentOperations;
    private Instant endTime;
    private long totalDuration;

    public PerfStats() {
        this.startTime = Instant.now();
        this.operationTimes = new ConcurrentHashMap<>();
        this.operationCounts = new ConcurrentHashMap<>();
        this.operationStack = new Stack<>();
        this.currentOperations = new ConcurrentHashMap<>();
    }

    /**
     * Mark the start of an operation
     */
    public synchronized void markOperationStart(String operationName) {
        currentOperations.put(operationName, Instant.now());
        operationStack.push(operationName);
    }

    /**
     * Mark the end of an operation
     */
    public synchronized void markOperationEnd(String operationName) {
        Instant endInstant = Instant.now();
        Instant startInstant = currentOperations.remove(operationName);

        if (startInstant != null) {
            long duration = Duration.between(startInstant, endInstant).toMillis();
            operationTimes.merge(operationName, duration, Long::sum);
            operationCounts.merge(operationName, 1, Integer::sum);
        }

        // Remove from stack if it's the current operation
        if (!operationStack.isEmpty() && operationStack.peek().equals(operationName)) {
            operationStack.pop();
        }
    }

    /**
     * Stop tracking and finalize stats
     */
    public synchronized PerfStats stopAndGetStat() {
        if (endTime == null) {
            endTime = Instant.now();
            totalDuration = Duration.between(startTime, endTime).toMillis();
        }
        return this;
    }

    /**
     * Get performance statistics as a map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> statsMap = new LinkedHashMap<>();
        statsMap.put("totalDurationMs", totalDuration);
        statsMap.put("startTime", startTime.toString());
        statsMap.put("endTime", endTime != null ? endTime.toString() : "Not finished");

        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        for (String operation : operationTimes.keySet()) {
            Map<String, Object> opStats = new HashMap<>();
            opStats.put("totalTimeMs", operationTimes.get(operation));
            opStats.put("count", operationCounts.get(operation));
            opStats.put("averageTimeMs", operationTimes.get(operation) / operationCounts.get(operation));
            operations.put(operation, opStats);
        }
        statsMap.put("operations", operations);

        return statsMap;
    }

    /**
     * Get formatted string representation
     */
    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Stats:\n");
        sb.append("  Total Duration: ").append(totalDuration).append("ms\n");
        sb.append("  Operations:\n");

        for (String operation : operationTimes.keySet()) {
            long totalTime = operationTimes.get(operation);
            int count = operationCounts.get(operation);
            long avgTime = totalTime / count;

            sb.append("    ").append(operation).append(":\n");
            sb.append("      Total: ").append(totalTime).append("ms\n");
            sb.append("      Count: ").append(count).append("\n");
            sb.append("      Average: ").append(avgTime).append("ms\n");
        }

        return sb.toString();
    }

    // Getters
    public long getTotalDuration() { return totalDuration; }
    public Map<String, Long> getOperationTimes() { return new HashMap<>(operationTimes); }
    public Map<String, Integer> getOperationCounts() { return new HashMap<>(operationCounts); }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
}