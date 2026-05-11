package myfluxo.adapter.persistence.jdbc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import myfluxo.adapter.persistence.jdbc.users.UserStatusMixin;
import myfluxo.domain.users.model.UserStatus;

/**
 * Shared Jackson configuration for the JDBC adapter.
 *
 * <p>Records work natively. {@link java.time.Instant} serializes to ISO-8601
 * via {@code jackson-datatype-jsr310}. {@code WRITE_DATES_AS_TIMESTAMPS} is
 * off so payloads are human-readable, not numeric epochs.
 */
public final class JsonMapper {

    private JsonMapper() {}

    public static ObjectMapper create() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Polymorphic-type mixins for sealed domain hierarchies. Adds
            // a "type" discriminator at serialize time so deserialize can
            // round-trip cleanly. New sealed types added to the domain
            // need a mixin registered here.
            .addMixIn(UserStatus.class, UserStatusMixin.class);
    }
}
