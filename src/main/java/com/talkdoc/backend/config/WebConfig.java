package com.talkdoc.backend.config;

import com.talkdoc.backend.auth.CurrentPrincipalArgumentResolver;
import com.talkdoc.backend.auth.SessionAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SessionAuthInterceptor sessionAuthInterceptor;
    private final CurrentPrincipalArgumentResolver currentPrincipalArgumentResolver;
    private final TalkDocProperties properties;

    public WebConfig(SessionAuthInterceptor sessionAuthInterceptor,
                     CurrentPrincipalArgumentResolver currentPrincipalArgumentResolver,
                     TalkDocProperties properties) {
        this.sessionAuthInterceptor = sessionAuthInterceptor;
        this.currentPrincipalArgumentResolver = currentPrincipalArgumentResolver;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionAuthInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentPrincipalArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(properties.websocket().allowedOriginArray())
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
