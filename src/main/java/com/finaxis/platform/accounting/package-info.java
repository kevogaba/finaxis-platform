/**
 * Accounting kernel: the Finaxis financial system of record. Owns the general ledger, the public
 * posting-intent API that product modules consume, and the narrow ports that foundation modules
 * implement on accounting's behalf.
 *
 * <p>BIAN: Financial Accounting (adapted) — the Service Domain "takes in financial facts and based
 * on these, creates accounting instructions that will update the general ledger and sub ledger
 * accounts". Finaxis keeps that fact-in semantic but posts synchronously inside the originating
 * business transaction rather than downstream. See docs/architecture/bian-service-landscape.md.
 *
 * <p>Types in this package are cross-module ports that accounting declares and other modules
 * implement, mirroring {@code com.finaxis.platform.lifecycle.PermissionGuard}. The public API for
 * product modules is {@code accounting::posting} plus {@code accounting::domain}; nothing outside
 * accounting may depend on its adapter, config, or outbound-port packages.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Accounting",
    allowedDependencies = {"common::application", "common::audit", "common::context"})
package com.finaxis.platform.accounting;
