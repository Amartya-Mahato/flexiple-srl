package com.flexiple.sourcing.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The single outbound HTTP client used to talk to the LLM provider. Timeouts are explicit so a
 * hanging provider surfaces as a clean {@code LLM_TIMEOUT} instead of tying up a request thread.
 */
@Configuration
class HttpClientConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient llmRestClient(LlmProperties llmProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(Duration.ofSeconds(llmProperties.timeoutSeconds()));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
