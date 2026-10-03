package com.example.seats.config;

import com.example.seats.web.AdminAuthInterceptor;
import com.example.seats.web.UserAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuth;
    private final UserAuthInterceptor userAuth;

    public WebConfig(AdminAuthInterceptor adminAuth, UserAuthInterceptor userAuth) {
        this.adminAuth = adminAuth;
        this.userAuth = userAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuth).addPathPatterns("/shows");
        registry.addInterceptor(userAuth).addPathPatterns("/shows/*/reserve", "/reservations/*/cancel");
    }
}
