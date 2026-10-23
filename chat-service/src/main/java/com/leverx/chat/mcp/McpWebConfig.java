package com.leverx.chat.mcp;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class McpWebConfig implements WebMvcConfigurer {

    private final UserTokenRelay userTokenRelay;

    McpWebConfig(UserTokenRelay userTokenRelay) {
        this.userTokenRelay = userTokenRelay;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userTokenRelay).addPathPatterns("/api/**");
    }
}
