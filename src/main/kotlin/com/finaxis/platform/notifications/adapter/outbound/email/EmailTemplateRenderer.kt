package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import freemarker.template.Configuration
import freemarker.template.TemplateException
import org.springframework.stereotype.Component
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils
import java.io.IOException

/** Renders Finaxis email bodies from FreeMarker templates on the application classpath. */
@Component
class EmailTemplateRenderer(
    private val freemarkerConfig: Configuration,
) {
    /**
     * Renders [templateName] (a classpath-relative path under `templates/`) with [model].
     *
     * @throws PermanentEmailDeliveryException if the template cannot be loaded or rendered
     */
    fun render(
        templateName: String,
        model: Map<String, Any?>,
    ): String =
        try {
            val template = freemarkerConfig.getTemplate(templateName)
            FreeMarkerTemplateUtils.processTemplateIntoString(template, model)
        } catch (ex: IOException) {
            throw PermanentEmailDeliveryException("Failed to load email template $templateName", ex)
        } catch (ex: TemplateException) {
            throw PermanentEmailDeliveryException(
                "Failed to render email template $templateName",
                ex,
            )
        }
}
