package com.tingyun.smartmistakebook.core.model

fun ModelFailureCode.requiresModelSettings(): Boolean = this in MODEL_SETTINGS_FAILURE_CODES

fun ModelFailureCode.requiresEgressAuthorizationRenewal(): Boolean =
    this == ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED ||
        this == ModelFailureCode.EGRESS_AUTHORIZATION_INVALID

private val MODEL_SETTINGS_FAILURE_CODES = setOf(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
    ModelFailureCode.AUTHENTICATION_FAILED,
)
