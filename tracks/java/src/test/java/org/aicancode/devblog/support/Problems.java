package org.aicancode.devblog.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.aicancode.devblog.domain.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** statusOf(() -> service.call()) -> [status, slug] of the ApiException it throws. */
public final class Problems {

    private Problems() {
    }

    public static List<Object> statusOf(ThrowingCallable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(e -> caught[0] = (ApiException) e);
        return List.of(caught[0].status(), caught[0].slug());
    }

    public static int codeOf(ThrowingCallable call) {
        return (int) statusOf(call).get(0);
    }
}
