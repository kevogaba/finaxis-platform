package com.finaxis.platform.common.id

import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** Generates a time-ordered (UUIDv7) identifier, preferred over [UUID.randomUUID] for new rows. */
fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
