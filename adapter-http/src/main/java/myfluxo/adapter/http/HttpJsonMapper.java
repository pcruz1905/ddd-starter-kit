package myfluxo.adapter.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;

/**
 * One {@link ObjectMapper} for the HTTP adapter — used by route
 * handlers for parsing request bodies and by the idempotency middleware
 * for serializing/caching responses.
 *
 * <p>Both must use the same configuration so a value serialized by the
 * middleware (and later replayed from cache) is byte-identical to one
 * the routes would have emitted directly.
 *
 * <p>Registered as a single Avaje {@link Bean} so DI handles distribution.
 */
@Factory
public final class HttpJsonMapper {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
