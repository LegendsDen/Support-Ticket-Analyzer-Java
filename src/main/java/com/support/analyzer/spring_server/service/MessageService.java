package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.entity.Message;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.repository.SupportTicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageService {
    @Autowired
    private SupportTicketRepository messageRepository;

     public List<SupportTicket> getAllSupportTicket() {

         return messageRepository.findAll();
     }
     public SupportTicket getSupportTicketById(String id) {
         return messageRepository.findById(id).orElseThrow(() -> new RuntimeException("Ticket not found"));
     }


     public SupportTicket saveSupportTicket(SupportTicket supportTicket) {
         supportTicket.setCreatedAt(LocalDateTime.now());
         supportTicket.setUpdatedAt(LocalDateTime.now());
         try{
             return messageRepository.insert(supportTicket);
         }
         catch(Exception e) {
             throw new RuntimeException("Error saving support ticket: " + e.getMessage());
         }

     }
     public void addMessageToTicket(String ticketId, Message message) {
         SupportTicket ticket = messageRepository.findById(ticketId).orElseThrow(() -> new RuntimeException("Ticket not found"));
         ticket.setUpdatedAt(LocalDateTime.now());
         ticket.getMessages().add(message);
         messageRepository.save(ticket);
     }
}
