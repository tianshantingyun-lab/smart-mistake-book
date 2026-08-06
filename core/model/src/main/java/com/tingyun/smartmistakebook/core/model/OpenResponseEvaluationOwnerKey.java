package com.tingyun.smartmistakebook.core.model;

/**
 * Package-private proof that an open-response authority root belongs to the model module.
 *
 * <p>The only production holder is the module-owned core:data bridge. The private constructor and
 * package-private singleton keep Java and Kotlin callers outside the audited split package from
 * creating an issuer.
 */
final class OpenResponseEvaluationOwnerKey {
    static final OpenResponseEvaluationOwnerKey INSTANCE =
            new OpenResponseEvaluationOwnerKey();

    private OpenResponseEvaluationOwnerKey() {}
}
