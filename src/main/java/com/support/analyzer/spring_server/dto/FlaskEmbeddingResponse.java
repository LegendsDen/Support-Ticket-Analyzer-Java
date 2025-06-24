package com.support.analyzer.spring_server.dto;

import lombok.Data;
import java.util.List;

@Data
public class FlaskEmbeddingResponse {
    private List<Double> embedding;
}