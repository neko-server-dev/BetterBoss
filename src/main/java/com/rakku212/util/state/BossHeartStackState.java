package com.rakku212.util.state;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public final class BossHeartStackState {

    public int heartsRemaining;
    public @Nullable UUID displayId;

    public BossHeartStackState(int heartsRemaining, @Nullable UUID displayId) {
        this.heartsRemaining = heartsRemaining;
        this.displayId = displayId;
    }
}
