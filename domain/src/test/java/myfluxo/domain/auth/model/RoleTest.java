package myfluxo.domain.auth.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleTest {

    @Test
    void admin_holdsAllPermissions() {
        assertThat(Role.Admin.INSTANCE.permissions())
            .isEqualTo(Permission.ALL);
        assertThat(Role.Admin.INSTANCE.hasPermission(Permission.USERS_DELETE)).isTrue();
    }

    @Test
    void member_canReadAndWrite_butNotDelete() {
        var member = Role.Member.INSTANCE;
        assertThat(member.hasPermission(Permission.USERS_READ)).isTrue();
        assertThat(member.hasPermission(Permission.USERS_WRITE)).isTrue();
        assertThat(member.hasPermission(Permission.USERS_DELETE)).isFalse();
    }

    @Test
    void viewer_canReadOnly() {
        var viewer = Role.Viewer.INSTANCE;
        assertThat(viewer.hasPermission(Permission.USERS_READ)).isTrue();
        assertThat(viewer.hasPermission(Permission.USERS_WRITE)).isFalse();
        assertThat(viewer.hasPermission(Permission.USERS_DELETE)).isFalse();
    }

    @Test
    void fromName_roundtripsEveryRole() {
        assertThat(Role.fromName("ADMIN")).isSameAs(Role.Admin.INSTANCE);
        assertThat(Role.fromName("MEMBER")).isSameAs(Role.Member.INSTANCE);
        assertThat(Role.fromName("VIEWER")).isSameAs(Role.Viewer.INSTANCE);
    }

    @Test
    void fromName_throwsForUnknown() {
        assertThatThrownBy(() -> Role.fromName("ROOT"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ROOT")
            .hasMessageContaining("ADMIN")
            .hasMessageContaining("MEMBER")
            .hasMessageContaining("VIEWER");
    }

    @Test
    void name_isStable() {
        // These names are persisted in the DB and may appear in JWTs —
        // changing them is a breaking migration. The test pins them.
        assertThat(Role.Admin.INSTANCE.name()).isEqualTo("ADMIN");
        assertThat(Role.Member.INSTANCE.name()).isEqualTo("MEMBER");
        assertThat(Role.Viewer.INSTANCE.name()).isEqualTo("VIEWER");
    }
}
