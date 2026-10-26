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
): String = "$organisationId:$userId:$KEYCLOAK_PROVISIONING_KEY_TAG${generationSuffix(generation)}"

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
internal fun generationSuffix(generation: Int): String =
    if (generation > 0) "$GENERATION_SUFFIX_PREFIX$generation" else ""

/** The key segment naming a Keycloak provisioning dispatch, between the ids and any suffix. */
internal const val KEYCLOAK_PROVISIONING_KEY_TAG = "KEYCLOAK_PROVISIONING"

/**
 * What precedes the generation number in a key's suffix. The invitation status read model
 * rebuilds [keycloakDispatchKey] in SQL from this and [KEYCLOAK_PROVISIONING_KEY_TAG], and
 * `MemberReinviteIntegrationTests` fails if the two ever differ.
 */
internal const val GENERATION_SUFFIX_PREFIX = ":G"
