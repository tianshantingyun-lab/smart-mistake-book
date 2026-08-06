package com.tingyun.smartmistakebook.core.data.production;

import kotlin.jvm.functions.Function0;

/** WorkManager sees only execution and minimal scheduling capabilities, never a storage owner. */
public abstract class ProductionWorkManagerCoordination {
    private ProductionWorkManagerCoordination() {}

    public abstract ProductionProblemOrganizationExecutionResolver
            currentProblemOrganizationExecutionResolver();

    public abstract ProductionProblemOrganizationScheduling currentProblemOrganizationScheduling();

    static ProductionWorkManagerCoordination issue(
            Function0<? extends ProductionProblemOrganizationExecutionResolver>
                    executionResolverProvider,
            Function0<? extends ProductionProblemOrganizationScheduling> schedulingProvider) {
        return new Issued(executionResolverProvider, schedulingProvider);
    }

    private static final class Issued extends ProductionWorkManagerCoordination {
        private final Function0<? extends ProductionProblemOrganizationExecutionResolver>
                executionResolverProvider;
        private final Function0<? extends ProductionProblemOrganizationScheduling>
                schedulingProvider;

        private Issued(
                Function0<? extends ProductionProblemOrganizationExecutionResolver>
                        executionResolverProvider,
                Function0<? extends ProductionProblemOrganizationScheduling> schedulingProvider) {
            this.executionResolverProvider = executionResolverProvider;
            this.schedulingProvider = schedulingProvider;
        }

        @Override
        public ProductionProblemOrganizationExecutionResolver
                currentProblemOrganizationExecutionResolver() {
            return executionResolverProvider.invoke();
        }

        @Override
        public ProductionProblemOrganizationScheduling currentProblemOrganizationScheduling() {
            return schedulingProvider.invoke();
        }
    }
}
