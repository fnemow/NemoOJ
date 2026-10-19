package com.nemo.OJbackendquestionservice.outbox;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/** Retryable outbox delivery. AI escalation remains visible in feedback when SMTP is unavailable. */
@Slf4j
@Component
public class AiNotificationDispatcher {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> senders;
    @Value("${spring.mail.username:}") private String from;
    @Value("${ai.notification.max-retries:8}") private int maxRetries;
    @Value("${ai.notification.batch-size:10}") private int batchSize;

    public AiNotificationDispatcher(JdbcTemplate jdbc,ObjectProvider<JavaMailSender> senders) { this.jdbc=jdbc;this.senders=senders; }

    @Scheduled(fixedDelayString="${ai.notification.poll-interval-ms:10000}")
    public void dispatch() {
        JavaMailSender sender=senders.getIfAvailable();
        if(sender==null) return;
        List<Row> due;
        try { due=jdbc.query("SELECT id,dedupeKey,recipient,subject,bodyText,retryCount FROM ai_notification_outbox WHERE "
                        + "(status='PENDING' AND nextAttemptAt<=NOW()) OR (status='SENDING' AND leaseExpireAt<NOW()) ORDER BY id LIMIT ?",
                (rs,row)->new Row(rs.getLong("id"),rs.getString("dedupeKey"),rs.getString("recipient"),rs.getString("subject"),rs.getString("bodyText"),rs.getInt("retryCount")),batchSize); }
        catch(Exception e){log.warn("AI notification outbox scan failed",e);return;}
        for(Row row:due) send(row,sender);
    }

    private void send(Row row,JavaMailSender sender) {
        int claimed=jdbc.update("UPDATE ai_notification_outbox SET status='SENDING',leaseExpireAt=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=? AND ((status='PENDING' AND nextAttemptAt<=NOW()) OR (status='SENDING' AND leaseExpireAt<NOW()))",row.id());
        if(claimed==0)return;
        try {
            SimpleMailMessage mail=new SimpleMailMessage();
            if(from!=null&&!from.isBlank())mail.setFrom(from);
            mail.setTo(row.recipient());mail.setSubject(row.subject());mail.setText(row.body());
            sender.send(mail);
            jdbc.update("UPDATE ai_notification_outbox SET status='SENT',sentAt=NOW(),leaseExpireAt=NULL,lastError=NULL WHERE id=? AND status='SENDING'",row.id());
        } catch(Exception e) {
            int retry=row.retries()+1;String status=retry>=maxRetries?"FAILED":"PENDING";
            String message=e.getMessage()==null?"SMTP delivery failed":e.getMessage();message=message.substring(0,Math.min(500,message.length()));
            jdbc.update("UPDATE ai_notification_outbox SET status=?,retryCount=?,leaseExpireAt=NULL,nextAttemptAt=DATE_ADD(NOW(),INTERVAL LEAST(3600,30*POW(2,?)) SECOND),lastError=? WHERE id=? AND status='SENDING'",
                    status,retry,Math.min(retry-1,7),message,row.id());
            log.warn("AI escalation email delivery failed; dedupeKey={}, attempt={}",row.dedupeKey(),retry);
        }
    }
    private record Row(long id,String dedupeKey,String recipient,String subject,String body,int retries) { }
}
