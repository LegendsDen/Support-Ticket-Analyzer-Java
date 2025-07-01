// PerfTracker.java
package com.support.analyzer.spring_server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerfTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerfTracker.class);
    private static final ThreadLocal<PerfStats> threadLocalPerfStats = new ThreadLocal<>();

    /**
     * Start performance tracking for current thread
     */
    public static PerfStats start() {
        PerfStats perfStats = new PerfStats();
        threadLocalPerfStats.set(perfStats);
        LOGGER.debug("Started performance tracking");
        return perfStats;
    }

    /**
     * Mark the start of an operation
     */
    public static void in(String operationName) {
        PerfStats perfStats = threadLocalPerfStats.get();
        if (perfStats != null) {
            perfStats.markOperationStart(operationName);
            LOGGER.debug("Started operation: {}", operationName);
        } else {
            LOGGER.warn("PerfTracker not initialized. Call start() first.");
        }
    }

    /**
     * Mark the end of an operation
     */
    public static void out(String operationName) {
        PerfStats perfStats = threadLocalPerfStats.get();
        if (perfStats != null) {
            perfStats.markOperationEnd(operationName);
            LOGGER.debug("Ended operation: {}", operationName);
        } else {
            LOGGER.warn("PerfTracker not initialized. Call start() first.");
        }
    }

    /**
     * Get current PerfStats instance
     */
    public static PerfStats current() {
        return threadLocalPerfStats.get();
    }

    /**
     * Stop tracking and clean up thread local
     */
    public static PerfStats stopAndClean() {
        PerfStats perfStats = threadLocalPerfStats.get();
        if (perfStats != null) {
            perfStats.stopAndGetStat();
            threadLocalPerfStats.remove();
            LOGGER.debug("Stopped performance tracking and cleaned up");
        }
        return perfStats;
    }

    /**
     * Clean up thread local without stopping (useful for error scenarios)
     */
    public static void cleanup() {
        threadLocalPerfStats.remove();
        LOGGER.debug("Cleaned up performance tracking");
    }
}