package com.support.analyzer.spring_server.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
@Data
public class Message {
    private Source _source;
    @Data
    public static class Source {
        private String message;
    }
}
