package com.nemo.OJbackendcommon.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.TimeZone;

/** Browser-safe JSON contract shared by every public servlet service. */
@Configuration
public class JsonContractConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonContractCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, new BrowserSafeLongSerializer(false))
                .serializerByType(Long.TYPE, new BrowserSafeLongSerializer(false))
                .simpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
                .timeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    /** Keep 64-bit identifiers exact without converting ordinary numeric values to strings. */
    private static final class BrowserSafeLongSerializer extends StdScalarSerializer<Long>
            implements ContextualSerializer {
        private final boolean writeAsString;

        private BrowserSafeLongSerializer(boolean writeAsString) {
            super(Long.class);
            this.writeAsString = writeAsString;
        }

        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (writeAsString) {
                generator.writeString(value.toString());
            } else {
                generator.writeNumber(value);
            }
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property)
                throws JsonMappingException {
            if (property == null) {
                return new BrowserSafeLongSerializer(true);
            }
            String name = property.getName();
            boolean identifier = "id".equals(name) || "data".equals(name) || name.endsWith("Id");
            return new BrowserSafeLongSerializer(identifier);
        }
    }
}
