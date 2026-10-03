package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiJsonCodec
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference

private val ADDRESS_TYPE = object : TypeReference<Map<String, String>>() {}

/**
 * Reads a branch's stored `address_jsonb` back into the response's `address` map.
 *
 * The column is written from a `Map<String, String>`, so a well-formed value always parses.
 * Anything else (blank, `null`, a non-object, nested values) is a damaged row, and a read must not
 * fail over a descriptive field, so it answers the empty address rather than a 500.
 */
internal fun ApiJsonCodec.branchAddress(addressJson: String): Map<String, String> =
    try {
        mapper.readValue(addressJson, ADDRESS_TYPE) ?: emptyMap()
    } catch (_: JacksonException) {
        emptyMap()
    }
