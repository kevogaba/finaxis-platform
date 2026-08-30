/**
 * Public outbound email-delivery port for Finaxis application emails, owned by the notifications
 * module. Consumed cross-module by lifecycle's application-invite job handler.
 */
@org.springframework.modulith.NamedInterface("email")
package com.finaxis.platform.notifications.application.port.outbound.email;
