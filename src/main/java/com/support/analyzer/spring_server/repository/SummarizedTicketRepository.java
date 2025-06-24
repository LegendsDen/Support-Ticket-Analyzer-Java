package com.support.analyzer.spring_server.repository;

import com.support.analyzer.spring_server.entity.SummarizedTicket;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SummarizedTicketRepository extends MongoRepository<SummarizedTicket,String> {
}
