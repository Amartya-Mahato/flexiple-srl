package com.flexiple.sourcing;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** A mapper configured exactly like the one Spring builds from application.yml. */
public final class TestJson {

    private TestJson() {
    }

    public static ObjectMapper mapperMatchingProduction() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }
}
