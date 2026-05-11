package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the convention parsing in {@link EntityArchiveSink}.
 * Pure logic — no database, runs under surefire.
 */
class EntityArchiveSinkConventionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deriveEntityType_stripsEventSuffixAndPackage() {
        assertThat(EntityArchiveSink.deriveEntityType(
            "myfluxo.domain.users.events.UserEvent$Deleted"))
            .isEqualTo("User");

        assertThat(EntityArchiveSink.deriveEntityType(
            "com.example.orders.OrderEvent$Deleted"))
            .isEqualTo("Order");

        assertThat(EntityArchiveSink.deriveEntityType(
            "OrderItemEvent$Deleted"))
            .isEqualTo("OrderItem");
    }

    @Test
    void deriveEntityType_returnsNullForFlatClassName() {
        assertThat(EntityArchiveSink.deriveEntityType("a.b.JustDeleted"))
            .isNull();
    }

    @Test
    void deriveEntityType_keepsSimpleNameWhenItIsNotXxxEvent() {
        // Useful escape hatch for non-conventional aggregate names.
        assertThat(EntityArchiveSink.deriveEntityType("a.b.Membership$Deleted"))
            .isEqualTo("Membership");
    }

    @Test
    void extractEntityId_pullsValueFromBrandedIdRecord() throws Exception {
        var uuid = UUID.randomUUID();
        var payload = JSON.readTree("""
            {
              "userId": {"value": "%s"},
              "email": {"value": "a@b.c"}
            }
            """.formatted(uuid));

        assertThat(EntityArchiveSink.extractEntityId(payload, "User"))
            .isEqualTo(uuid);
    }

    @Test
    void extractEntityId_acceptsPlainUuidString() throws Exception {
        var uuid = UUID.randomUUID();
        var payload = JSON.readTree("""
            { "userId": "%s" }
            """.formatted(uuid));

        assertThat(EntityArchiveSink.extractEntityId(payload, "User"))
            .isEqualTo(uuid);
    }

    @Test
    void extractEntityId_returnsNullWhenFieldMissing() throws Exception {
        var payload = JSON.readTree("""
            { "somethingElse": "x" }
            """);
        assertThat(EntityArchiveSink.extractEntityId(payload, "User")).isNull();
    }

    @Test
    void extractEntityId_returnsNullForMalformedUuid() throws Exception {
        var payload = JSON.readTree("""
            { "userId": {"value": "not-a-uuid"} }
            """);
        assertThat(EntityArchiveSink.extractEntityId(payload, "User")).isNull();
    }
}
