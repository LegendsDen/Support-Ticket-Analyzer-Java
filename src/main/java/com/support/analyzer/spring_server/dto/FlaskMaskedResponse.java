package com.support.analyzer.spring_server.dto;

import lombok.Data;

import java.util.List;

@Data
public class FlaskMaskedResponse {
    private List<String> masked_messages;
}
