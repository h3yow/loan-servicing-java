package com.github.h3yow.loanservicingjava.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 让所有 {@link Money} 在 JSON 中以“元”字符串输出，避免暴露内部分值结构。 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer moneyCustomizer() {
        return builder -> builder.serializerByType(Money.class, new JsonSerializer<Money>() {
            @Override
            public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
    }
}
