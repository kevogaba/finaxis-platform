package com.finaxis.platform.lifecycle.adapter.inbound.startup

import com.finaxis.platform.lifecycle.application.StoredTimeZoneReport
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/**
 * Logs one `WARN` at startup while an organisation or a branch stores a time zone `TimeZoneRule`
 * refuses (#331), so every environment checks itself instead of relying on someone running a
 * query. Such a value predates #276 and is one `V37` could not rewrite to an exact IANA
 * equivalent. It is read as it is, but a branch created without a zone copies its organisation's,
 * and PostgreSQL reads a raw offset with the POSIX sign.
 *
 * The line gives counts and up to ten ids per table, never the stored text (a branch create
 * before #276 accepted any text). Flyway has finished when this runs, after every singleton
 * exists. A database error is logged by its class name, without its message, and the boot goes
 * on: the check reports, it never blocks.
 */
@Component
class StoredTimeZoneStartupWarning(
    private val report: StoredTimeZoneReport,
) : SmartInitializingSingleton {
    /** Checks the stored time zones once per start and warns when any is refused. */
    override fun afterSingletonsInstantiated() {
        val findings =
            try {
                report.refused()
            } catch (failure: DataAccessException) {
                log.warn(
                    "Could not check the stored organisation and branch time zones at startup " +
                        "({}).",
                    failure.javaClass.simpleName,
                )
                return
            }
        if (findings.clean) {
            return
        }
        log.warn(
            "{} organisation(s) {} and {} branch(es) {} store a time zone that is not an IANA " +
                "region id (TimeZoneRule, #331). They are read as they are; fix them with the " +
                "steps in docs/operations/tenant-settings.md, \"Time zones stored before the " +
                "rule\".",
            findings.organisations.count,
            findings.organisations.sampleIds,
            findings.branches.count,
            findings.branches.sampleIds,
        )
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(StoredTimeZoneStartupWarning::class.java)
    }
}
