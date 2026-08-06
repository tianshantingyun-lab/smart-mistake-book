package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort;
import java.util.Objects;

/**
 * Public current-Tutor capability. The learning writer remains private inside the Host assembly.
 */
public abstract class TutorSessionProductionCapability {
    private TutorSessionProductionCapability() {}

    public abstract TutorCurrentSessionHostPort getCurrentSessionHost();

    static TutorSessionProductionCapability issue(TutorCurrentSessionHostPort currentSessionHost) {
        return new Issued(Objects.requireNonNull(currentSessionHost, "currentSessionHost"));
    }

    private static final class Issued extends TutorSessionProductionCapability {
        private final TutorCurrentSessionHostPort currentSessionHost;
        private Issued(TutorCurrentSessionHostPort currentSessionHost) {
            this.currentSessionHost = currentSessionHost;
        }

        @Override
        public TutorCurrentSessionHostPort getCurrentSessionHost() {
            return currentSessionHost;
        }
    }
}
