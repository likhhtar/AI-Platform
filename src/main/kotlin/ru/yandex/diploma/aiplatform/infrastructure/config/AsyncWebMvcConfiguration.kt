package ru.yandex.diploma.aiplatform.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class AsyncWebMvcConfiguration : WebMvcConfigurer {

    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.setDefaultTimeout(0L)
    }
}
