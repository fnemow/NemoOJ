package com.nemo.OJbackendquestionservice.config;

import com.nemo.OJbackendcommon.security.CsrfTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CsrfConfig {
    @Bean
    CsrfTokenFilter csrfTokenFilter() { return new CsrfTokenFilter(); }
}
