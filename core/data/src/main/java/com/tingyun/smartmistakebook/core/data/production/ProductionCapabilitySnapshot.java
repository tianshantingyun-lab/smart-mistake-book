package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository;
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository;
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository;
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource;
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort;
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository;

/** The only application-visible, atomic capability snapshot. */
public abstract class ProductionCapabilitySnapshot implements AutoCloseable {
    private ProductionCapabilitySnapshot() {}

    public abstract CaptureWorkflowRepository getCaptureWorkflow();

    public abstract BatchImportRepository getBatchImport();

    public abstract MistakeDetailRepository getMistakeDetail();

    public abstract MistakeOrganizationRepository getMistakeOrganization();

    public abstract StudentMistakeLibraryCatalogRepository getStudentMistakeCatalog();

    public abstract TutorSessionProductionCapability getTutorSession();

    public abstract TutorConversationLobbyPort getTutorConversationLobby();

    public abstract TutorMasteryAndProfileProductionCapability getTutorMasteryAndProfile();

    public abstract TutorTeachingReferenceRepository getTutorTeachingReference();

    public abstract ModelTaskRepository getModelTaskQueue();

    public abstract RestrictedModelAssetSource getModelAssetDocuments();

    public abstract ReviewPlanningProductionCapability getReviewPlanning();

    public abstract ProductionWorkManagerCoordination getWorkManagerCoordination();

    public abstract ProductionTerminalCutoverCapability getTerminalCutoverGate();

    @Override
    public abstract void close();

    static ProductionCapabilitySnapshot newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends ProductionCapabilitySnapshot {
        private Issued() {}

        @Override
        public CaptureWorkflowRepository getCaptureWorkflow() {
            return leases().getCaptureWorkflow().getCapability();
        }

        @Override
        public BatchImportRepository getBatchImport() {
            return leases().getBatchImport().getCapability();
        }

        @Override
        public MistakeDetailRepository getMistakeDetail() {
            return leases().getMistakeDetail().getCapability();
        }

        @Override
        public MistakeOrganizationRepository getMistakeOrganization() {
            return leases().getMistakeOrganization().getCapability();
        }

        @Override
        public StudentMistakeLibraryCatalogRepository getStudentMistakeCatalog() {
            return leases().getStudentMistakeCatalog().getCapability();
        }

        @Override
        public TutorSessionProductionCapability getTutorSession() {
            return leases().getTutorSession().getCapability();
        }

        @Override
        public TutorConversationLobbyPort getTutorConversationLobby() {
            return leases().getTutorConversationLobby().getCapability();
        }

        @Override
        public TutorMasteryAndProfileProductionCapability getTutorMasteryAndProfile() {
            return leases().getTutorMasteryAndProfile().getCapability();
        }

        @Override
        public TutorTeachingReferenceRepository getTutorTeachingReference() {
            return leases().getTutorTeachingReference().getCapability();
        }

        @Override
        public ModelTaskRepository getModelTaskQueue() {
            return leases().getModelTaskQueue().getCapability();
        }

        @Override
        public RestrictedModelAssetSource getModelAssetDocuments() {
            return leases().getModelAssetDocuments().getCapability();
        }

        @Override
        public ReviewPlanningProductionCapability getReviewPlanning() {
            return leases().getReviewPlanning().getCapability();
        }

        @Override
        public ProductionWorkManagerCoordination getWorkManagerCoordination() {
            return leases().getWorkManagerCoordination().getCapability();
        }

        @Override
        public ProductionTerminalCutoverCapability getTerminalCutoverGate() {
            return leases().getTerminalCutoverGate().getCapability();
        }

        @Override
        public void close() {
            ProductionCapabilityPublicationRegistry.closeSnapshot(this);
        }

        private IssuedProductionCapabilityLeases leases() {
            return ProductionCapabilityPublicationRegistry.activeSnapshot(this);
        }
    }
}
