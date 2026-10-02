package org.aicancode.devblog.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.aicancode.devblog.support.TestApp;
import org.junit.jupiter.api.Test;

/** Works from day one: the starter already serves /health (no business logic needed). */
class HealthTest {

    @Test
    void reportsTheDatabase() {
        TestApp app = new TestApp();
        assertThat(app.get("/health").json().toString()).isEqualTo("{\"status\":\"UP\",\"checks\":{\"database\":\"UP\"}}");
        app.store.healthy = false;
        TestApp.Res r = app.get("/health");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().toString()).isEqualTo("{\"status\":\"DOWN\",\"checks\":{\"database\":\"DOWN\"}}");
    }
}
