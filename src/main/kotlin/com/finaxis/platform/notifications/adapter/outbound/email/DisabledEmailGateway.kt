package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException

/** No-op gateway active when `finaxis.email.enabled=false`; always fails permanently. */
class DisabledEmailGateway : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt =
        throw PermanentEmailDeliveryException("Email delivery is disabled.")
}
