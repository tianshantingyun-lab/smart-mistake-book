package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository;
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository;
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository;

/**
 * The published mastery slot exposes only read contracts. The Java shell keeps its constructor
 * genuinely private in bytecode; Kotlin's private abstract constructor emits a public synthetic
 * bridge that another module can reflectively invoke.
 */
public abstract class TutorMasteryAndProfileProductionCapability {
    private TutorMasteryAndProfileProductionCapability() {}

    public abstract TutorMasteryContextRepository getTutorMasteryContext();

    public abstract LearningMasteryDisplayRepository getLearningMasteryDisplay();

    public abstract LearningMasteryPrivacyRepository getLearningMasteryPrivacy();

    static TutorMasteryAndProfileProductionCapability issue(
            TutorMasteryContextRepository tutorMasteryContext,
            LearningMasteryDisplayRepository learningMasteryDisplay,
            LearningMasteryPrivacyRepository learningMasteryPrivacy) {
        return new Issued(tutorMasteryContext, learningMasteryDisplay, learningMasteryPrivacy);
    }

    private static final class Issued extends TutorMasteryAndProfileProductionCapability {
        private final TutorMasteryContextRepository tutorMasteryContext;
        private final LearningMasteryDisplayRepository learningMasteryDisplay;
        private final LearningMasteryPrivacyRepository learningMasteryPrivacy;

        private Issued(
                TutorMasteryContextRepository tutorMasteryContext,
                LearningMasteryDisplayRepository learningMasteryDisplay,
                LearningMasteryPrivacyRepository learningMasteryPrivacy) {
            this.tutorMasteryContext = tutorMasteryContext;
            this.learningMasteryDisplay = learningMasteryDisplay;
            this.learningMasteryPrivacy = learningMasteryPrivacy;
        }

        @Override
        public TutorMasteryContextRepository getTutorMasteryContext() {
            return tutorMasteryContext;
        }

        @Override
        public LearningMasteryDisplayRepository getLearningMasteryDisplay() {
            return learningMasteryDisplay;
        }

        @Override
        public LearningMasteryPrivacyRepository getLearningMasteryPrivacy() {
            return learningMasteryPrivacy;
        }
    }
}
