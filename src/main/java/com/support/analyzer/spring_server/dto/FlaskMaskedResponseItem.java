package com.support.analyzer.spring_server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlaskMaskedResponseItem {
    private String ticketId;
    private List<String> maskedMessages;
}