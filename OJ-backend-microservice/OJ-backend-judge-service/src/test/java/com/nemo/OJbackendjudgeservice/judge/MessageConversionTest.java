package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendjudgeservice.config.RabbitMqConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import static org.junit.jupiter.api.Assertions.*;

class MessageConversionTest {
    @Test void jsonPublishedSubmissionIdIsNotConvertedFromItsFirstByte() {
        String id = "2101125366876663809";
        var message = new Jackson2JsonMessageConverter().toMessage(id, new MessageProperties());
        message.getMessageProperties().setInferredArgumentType(Long.class);
        assertEquals(Long.valueOf(id), new RabbitMqConfig().judgeMessageConverter().fromMessage(message));
    }
}
