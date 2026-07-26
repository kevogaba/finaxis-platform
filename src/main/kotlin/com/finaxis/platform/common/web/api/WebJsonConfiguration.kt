package com.finaxis.platform.common.web.api

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverters
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Configures the dedicated Jackson 3 MVC converter without changing internal mappers. */
@Configuration(proxyBeanMethods = false)
class WebJsonConfiguration {
    /** Replaces the default MVC JSON converter through Spring Framework 7's builder API. */
    @Bean
    fun apiJsonWebMvcConfigurer(apiJsonCodec: ApiJsonCodec): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun configureMessageConverters(builder: HttpMessageConverters.ServerBuilder) {
                builder.withJsonConverter(JacksonJsonHttpMessageConverter(apiJsonCodec.mapper))
            }
        }
}
