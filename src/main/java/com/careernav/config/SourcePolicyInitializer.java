package com.careernav.config;

import com.careernav.service.SourcePolicyService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class SourcePolicyInitializer implements ApplicationRunner {
    private final SourcePolicyService service;
    public SourcePolicyInitializer(SourcePolicyService service) { this.service = service; }
    @Override public void run(ApplicationArguments args) { service.initializeDefaults(); }
}
