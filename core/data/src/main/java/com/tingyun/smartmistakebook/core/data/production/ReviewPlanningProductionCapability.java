package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort;

/** Complete review capability for one published authority generation. */
public abstract class ReviewPlanningProductionCapability {
    private ReviewPlanningProductionCapability() {}

    public abstract DailyReviewProductionCapability getPlanning();

    public abstract DailyReviewSessionActionPort getSessionActions();

    public abstract DailyReviewPacingActionPort getPacingActions();

    public abstract DailyReviewAnswerSubmissionPortFactory getAnswerSubmissionPorts();

    public abstract DailyReviewAssistanceActionPort getAssistanceActions();

    public abstract DailyReviewPacingCommandFactory getPacingCommandFactory();

    static ReviewPlanningProductionCapability issue(
            DailyReviewProductionCapability planning,
            DailyReviewSessionActionPort sessionActions,
            DailyReviewPacingActionPort pacingActions,
            DailyReviewAnswerSubmissionPortFactory answerSubmissionPorts,
            DailyReviewAssistanceActionPort assistanceActions,
            DailyReviewPacingCommandFactory pacingCommandFactory) {
        return new Issued(
                planning,
                sessionActions,
                pacingActions,
                answerSubmissionPorts,
                assistanceActions,
                pacingCommandFactory);
    }

    private static final class Issued extends ReviewPlanningProductionCapability {
        private final DailyReviewProductionCapability planning;
        private final DailyReviewSessionActionPort sessionActions;
        private final DailyReviewPacingActionPort pacingActions;
        private final DailyReviewAnswerSubmissionPortFactory answerSubmissionPorts;
        private final DailyReviewAssistanceActionPort assistanceActions;
        private final DailyReviewPacingCommandFactory pacingCommandFactory;

        private Issued(
                DailyReviewProductionCapability planning,
                DailyReviewSessionActionPort sessionActions,
                DailyReviewPacingActionPort pacingActions,
                DailyReviewAnswerSubmissionPortFactory answerSubmissionPorts,
                DailyReviewAssistanceActionPort assistanceActions,
                DailyReviewPacingCommandFactory pacingCommandFactory) {
            this.planning = planning;
            this.sessionActions = sessionActions;
            this.pacingActions = pacingActions;
            this.answerSubmissionPorts = answerSubmissionPorts;
            this.assistanceActions = assistanceActions;
            this.pacingCommandFactory = pacingCommandFactory;
        }

        @Override
        public DailyReviewProductionCapability getPlanning() {
            return planning;
        }

        @Override
        public DailyReviewSessionActionPort getSessionActions() {
            return sessionActions;
        }

        @Override
        public DailyReviewPacingActionPort getPacingActions() {
            return pacingActions;
        }

        @Override
        public DailyReviewAnswerSubmissionPortFactory getAnswerSubmissionPorts() {
            return answerSubmissionPorts;
        }

        @Override
        public DailyReviewAssistanceActionPort getAssistanceActions() {
            return assistanceActions;
        }

        @Override
        public DailyReviewPacingCommandFactory getPacingCommandFactory() {
            return pacingCommandFactory;
        }
    }
}
