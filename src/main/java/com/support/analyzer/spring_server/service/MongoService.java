package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.entity.*;
import com.support.analyzer.spring_server.repository.NewSupportTicketRepository;
import com.support.analyzer.spring_server.repository.SummarizedTicketRepository;
import com.support.analyzer.spring_server.repository.SupportTicketRepository;
import com.support.analyzer.spring_server.repository.TicketTripletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MongoService {
    private static final Logger log = LoggerFactory.getLogger(MongoService.class);

    private final SupportTicketRepository messageRepository;
    private final SummarizedTicketRepository summarizedTicketRepository;
    private final TicketTripletRepository ticketTripletRepository;
    private final NewSupportTicketRepository newSupportTicketRepository;

    @Autowired
    public MongoService(SupportTicketRepository messageRepository,
                        SummarizedTicketRepository summarizedTicketRepository,
                        TicketTripletRepository ticketTripletRepository,
                        NewSupportTicketRepository newSupportTicketRepository) {
        this.messageRepository = messageRepository;
        this.summarizedTicketRepository = summarizedTicketRepository;
        this.ticketTripletRepository = ticketTripletRepository;
        this.newSupportTicketRepository = newSupportTicketRepository;
    }


     public List<SupportTicket> getAllSupportTicket() {

         return messageRepository.findAll();
     }

     public SupportTicket getSupportTicketById(String id) {
         return messageRepository.findById(id).orElseThrow(() -> new RuntimeException("Ticket not found"));
     }
    public NewSupportTicket getNewSupportTicketById(String id) {
        return newSupportTicketRepository.findById(id).orElseThrow(() -> new RuntimeException("Ticket not found"));
    }
    public NewSupportTicket addNewSupportTicket(NewSupportTicket supportTicket) {
        try{
            supportTicket.setCreatedAt(LocalDateTime.now());
            supportTicket.setUpdatedAt(LocalDateTime.now());
            return newSupportTicketRepository.save(supportTicket);
        }
        catch(Exception e) {
            throw new RuntimeException("Error saving support ticket: " + e.getMessage());
        }

    }


     public SupportTicket addSupportTicket(SupportTicket supportTicket) {
         try{
             supportTicket.setCreatedAt(LocalDateTime.now());
             supportTicket.setUpdatedAt(LocalDateTime.now());
             return messageRepository.save(supportTicket);
         }
         catch(Exception e) {
             throw new RuntimeException("Error saving support ticket: " + e.getMessage());
         }

     }
     public void addMessageToTicket(String ticketId, Message message) {
         try{
             SupportTicket ticket = messageRepository.findById(ticketId).orElseThrow(() -> new RuntimeException("Ticket not found"));
             ticket.setUpdatedAt(LocalDateTime.now());
             ticket.getMessages().add(message);
             messageRepository.save(ticket);
         }catch(Exception e) {
             throw new RuntimeException("Error adding message to ticket: " + e.getMessage());
         }

     }

     public void addSummarizeTicket(SummarizedTicket summarizedTicket){
            summarizedTicket.setCreatedAt(LocalDateTime.now());
            summarizedTicket.setUpdatedAt(LocalDateTime.now());
            try {
                summarizedTicketRepository.save(summarizedTicket);
            } catch (Exception e) {
                throw new RuntimeException("Error saving summarized ticket: " + e.getMessage());
            }
     }

     public void addTicketTriplet(TicketTriplet ticketTriplet){
            try {
                ticketTripletRepository.save(ticketTriplet);
            } catch (Exception e) {
                throw new RuntimeException("Error saving ticket triplet: " + e.getMessage());
            }
     }
    public SummarizedTicket getSummarizedTicketById(String ticketId) {
        try {
            return summarizedTicketRepository.findById(ticketId).orElse(null);
        } catch (Exception e) {
            log.error("Error getting summarized ticket {}: {}", ticketId, e.getMessage());
            return null;
        }
    }


}
