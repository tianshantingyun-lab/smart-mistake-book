package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository;
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository;
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository;
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository;
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource;
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository;
import com.tingyun.smartmistakebook.core.data.production.ProductionWorkManagerCoordination;
import com.tingyun.smartmistakebook.core.data.review.ProductionDailyReviewPorts;
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository;
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort;
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort;
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Current-generation narrow ports. The storage/runtime aggregate is erased to AutoCloseable and
 * never appears in a constructor or getter visible outside this package.
 */
public final class CurrentGenerationProductionOwnerPorts implements AutoCloseable {
    private static final Object PUBLICATION_MONITOR = new Object();
    private static final Map<CurrentGenerationProductionOwnerPorts, Object>
            PUBLICATION_BINDINGS = new IdentityHashMap<>();
    private final AutoCloseable ownerResources;
    private final BatchImportRepository batchImport;
    private final CaptureWorkflowRepository captureWorkflow;
    private final MistakeDetailRepository mistakeDetail;
    private final MistakeOrganizationRepository mistakeOrganization;
    private final StudentMistakeLibraryCatalogRepository studentMistakeCatalog;
    private final TutorMasteryContextRepository tutorMasteryContext;
    private final TutorCurrentSessionHostPort tutorCurrentSessionHost;
    private final TutorConversationLobbyPort tutorConversationLobby;
    private final LearningMasteryDisplayRepository learningMasteryDisplay;
    private final LearningMasteryPrivacyRepository learningMasteryPrivacy;
    private final TutorTeachingReferenceRepository tutorTeachingReference;
    private final ModelTaskRepository modelTaskQueue;
    private final RestrictedModelAssetSource modelAssetDocuments;
    private final ProductionDailyReviewPorts dailyReview;
    private final ProductionWorkManagerCoordination workManagerCoordination;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CurrentGenerationProductionOwnerPorts(
            AutoCloseable ownerResources,
            BatchImportRepository batchImport,
            CaptureWorkflowRepository captureWorkflow,
            MistakeDetailRepository mistakeDetail,
            MistakeOrganizationRepository mistakeOrganization,
            StudentMistakeLibraryCatalogRepository studentMistakeCatalog,
            TutorCurrentSessionHostPort tutorCurrentSessionHost,
            TutorConversationLobbyPort tutorConversationLobby,
            TutorMasteryContextRepository tutorMasteryContext,
            LearningMasteryDisplayRepository learningMasteryDisplay,
            LearningMasteryPrivacyRepository learningMasteryPrivacy,
            TutorTeachingReferenceRepository tutorTeachingReference,
            ModelTaskRepository modelTaskQueue,
            RestrictedModelAssetSource modelAssetDocuments,
            ProductionDailyReviewPorts dailyReview,
            ProductionWorkManagerCoordination workManagerCoordination) {
        this.ownerResources = ownerResources;
        this.batchImport = batchImport;
        this.captureWorkflow = captureWorkflow;
        this.mistakeDetail = mistakeDetail;
        this.mistakeOrganization = mistakeOrganization;
        this.studentMistakeCatalog = studentMistakeCatalog;
        this.tutorCurrentSessionHost = tutorCurrentSessionHost;
        this.tutorConversationLobby = tutorConversationLobby;
        this.tutorMasteryContext = tutorMasteryContext;
        this.learningMasteryDisplay = learningMasteryDisplay;
        this.learningMasteryPrivacy = learningMasteryPrivacy;
        this.tutorTeachingReference = tutorTeachingReference;
        this.modelTaskQueue = modelTaskQueue;
        this.modelAssetDocuments = modelAssetDocuments;
        this.dailyReview = dailyReview;
        this.workManagerCoordination = workManagerCoordination;
    }

    static CurrentGenerationProductionOwnerPorts issue(
            AutoCloseable ownerResources,
            Object authorityBinding,
            BatchImportRepository batchImport,
            CaptureWorkflowRepository captureWorkflow,
            MistakeDetailRepository mistakeDetail,
            MistakeOrganizationRepository mistakeOrganization,
            StudentMistakeLibraryCatalogRepository studentMistakeCatalog,
            TutorCurrentSessionHostPort tutorCurrentSessionHost,
            TutorConversationLobbyPort tutorConversationLobby,
            TutorMasteryContextRepository tutorMasteryContext,
            LearningMasteryDisplayRepository learningMasteryDisplay,
            LearningMasteryPrivacyRepository learningMasteryPrivacy,
            TutorTeachingReferenceRepository tutorTeachingReference,
            ModelTaskRepository modelTaskQueue,
            RestrictedModelAssetSource modelAssetDocuments,
            ProductionDailyReviewPorts dailyReview,
            ProductionWorkManagerCoordination workManagerCoordination) {
        Objects.requireNonNull(authorityBinding, "authorityBinding");
        CurrentGenerationProductionOwnerPorts ports = new CurrentGenerationProductionOwnerPorts(
                ownerResources,
                batchImport,
                captureWorkflow,
                mistakeDetail,
                mistakeOrganization,
                studentMistakeCatalog,
                tutorCurrentSessionHost,
                tutorConversationLobby,
                tutorMasteryContext,
                learningMasteryDisplay,
                learningMasteryPrivacy,
                tutorTeachingReference,
                modelTaskQueue,
                modelAssetDocuments,
                dailyReview,
                workManagerCoordination);
        synchronized (PUBLICATION_MONITOR) {
            if (PUBLICATION_BINDINGS.put(ports, authorityBinding) != null) {
                throw new IllegalStateException(
                        "Current-generation owner ports were registered twice");
            }
        }
        return ports;
    }

    static Object claimPublicationBinding(CurrentGenerationProductionOwnerPorts ports) {
        synchronized (PUBLICATION_MONITOR) {
            Object binding = PUBLICATION_BINDINGS.remove(ports);
            if (binding == null) {
                throw new SecurityException(
                        "Current-generation owner ports are forged, revoked, or already claimed");
            }
            return binding;
        }
    }

    public BatchImportRepository getBatchImport() {
        return batchImport;
    }

    public CaptureWorkflowRepository getCaptureWorkflow() {
        return captureWorkflow;
    }

    public MistakeDetailRepository getMistakeDetail() {
        return mistakeDetail;
    }

    public MistakeOrganizationRepository getMistakeOrganization() {
        return mistakeOrganization;
    }

    public StudentMistakeLibraryCatalogRepository getStudentMistakeCatalog() {
        return studentMistakeCatalog;
    }

    public TutorMasteryContextRepository getTutorMasteryContext() {
        return tutorMasteryContext;
    }

    public TutorCurrentSessionHostPort getTutorCurrentSessionHost() {
        return tutorCurrentSessionHost;
    }

    public TutorConversationLobbyPort getTutorConversationLobby() {
        return tutorConversationLobby;
    }

    public LearningMasteryDisplayRepository getLearningMasteryDisplay() {
        return learningMasteryDisplay;
    }

    public LearningMasteryPrivacyRepository getLearningMasteryPrivacy() {
        return learningMasteryPrivacy;
    }

    public TutorTeachingReferenceRepository getTutorTeachingReference() {
        return tutorTeachingReference;
    }

    public ModelTaskRepository getModelTaskQueue() {
        return modelTaskQueue;
    }

    public RestrictedModelAssetSource getModelAssetDocuments() {
        return modelAssetDocuments;
    }

    public ProductionDailyReviewPorts getDailyReview() {
        return dailyReview;
    }

    public ProductionWorkManagerCoordination getWorkManagerCoordination() {
        return workManagerCoordination;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (PUBLICATION_MONITOR) {
            PUBLICATION_BINDINGS.remove(this);
        }
        try {
            ownerResources.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Failed to close current-generation capability owner", failure);
        }
    }
}
