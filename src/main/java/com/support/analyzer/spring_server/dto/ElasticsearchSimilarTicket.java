package com.support.analyzer.spring_server.dto;

import lombok.Data;

@Data

public  class ElasticsearchSimilarTicket {
    private final String ticketId;
    private final double similarity;
}
