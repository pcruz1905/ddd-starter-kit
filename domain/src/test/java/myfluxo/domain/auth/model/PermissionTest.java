package myfluxo.domain.auth.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionTest {

    @Test
    void name_combinesResourceAndAction() {
        assertThat(new Permission("users", "read").name()).isEqualTo("users:read");
    }

    @Test
    void rejectsUppercaseResource() {
        assertThatThrownBy(() -> new Permission("Users", "read"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lowercase");
    }

    @Test
    void rejectsUppercaseAction() {
        assertThatThrownBy(() -> new Permission("users", "Read"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lowercase");
    }

    @Test
    void rejectsBlanks() {
        assertThatThrownBy(() -> new Permission("", "read"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Permission("users", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogConstants_areConsistent() {
        // Catalog constants should equal a freshly-constructed equivalent —
        // a sanity check that the catalog isn't drifting from the canonical
        // shape callers will produce.
        assertThat(Permission.USERS_READ).isEqualTo(new Permission("users", "read"));
        assertThat(Permission.USERS_WRITE).isEqualTo(new Permission("users", "write"));
        assertThat(Permission.USERS_DELETE).isEqualTo(new Permission("users", "delete"));
    }

    @Test
    void ALL_containsEveryCatalogConstant() {
        assertThat(Permission.ALL)
            .contains(Permission.USERS_READ)
            .contains(Permission.USERS_WRITE)
            .contains(Permission.USERS_DELETE);
    }

    @Test
    void toString_isCanonicalName() {
        assertThat(Permission.USERS_READ).hasToString("users:read");
    }
}
