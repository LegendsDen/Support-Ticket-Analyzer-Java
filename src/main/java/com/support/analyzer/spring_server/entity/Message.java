package com.support.analyzer.spring_server.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private Source _source;
    @Data
    public static class Source {
        private String message;
    }
}
