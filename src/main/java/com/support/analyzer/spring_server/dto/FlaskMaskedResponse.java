package com.support.analyzer.spring_server.dto;

import lombok.Data;

import java.util.List;

@Data
public class FlaskMaskedResponse {
    private String ticketId;
    private List<String> maskedMessages;
}
