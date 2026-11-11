package com.example.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/items").setViewName("forward:/index.html");
        registry.addViewController("/orders").setViewName("forward:/index.html");
        registry.addViewController("/orders/{id}").setViewName("forward:/index.html");
        registry.addViewController("/sell").setViewName("forward:/index.html");
        registry.addViewController("/stock-history").setViewName("forward:/index.html");
        registry.addViewController("/account").setViewName("forward:/index.html");
        registry.addViewController("/users").setViewName("forward:/index.html");
        registry.addViewController("/dashboard").setViewName("forward:/index.html");
    }
}
