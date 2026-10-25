package com.finaxis.platform.lifecycle.application

import java.util.UUID

/**
 * The identity dispatch key of a user's Keycloak provisioning in one organisation, for one life
 * of the membership ([generation], see [generationSuffix]). Every writer and reader of that
 * dispatch uses it, so the approval and the bootstrap retry name one job, and the JobRunr job id
 * derived from it (`deterministicJobId`) is one per life.
 */
internal fun keycloakDispatchKey(
    organisationId: UUID,
    userId: UUID,
    generation: Int = 0,
): String = "$organisationId:$userId:KEYCLOAK_PROVISIONING${generationSuffix(generation)}"

/**
 * The identity dispatch key of a user's application-invite email in one organisation, for one
 * life of the membership ([generation], see [generationSuffix]).
 */
internal fun applicationInviteDispatchKey(
    organisationId: UUID,
    userId: UUID,
    generation: Int = 0,
): String = "$userId:$organisationId:APPLICATION_INVITE${generationSuffix(generation)}"

/**
 * What a membership's generation adds to a dispatch key: nothing for generation 0, so the keys
 * (and the job ids derived from them) of a membership never re-invited are byte-for-byte what
 * earlier releases wrote and what jobs already stored are filed under, and `:G<n>` from the first
 * re-invitation on (ADR 0037). Each re-invitation is a new dispatch row and a new job.
 */
internal fun generationSuffix(generation: Int): String = if (generation > 0) ":G$generation" else ""
