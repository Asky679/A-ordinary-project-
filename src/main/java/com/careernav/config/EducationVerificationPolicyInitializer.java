package com.careernav.config;

import com.careernav.repository.EducationVerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 将历史上已识别学信网官方二维码的记录统一修正为合格学历。 */
@Component
public class EducationVerificationPolicyInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EducationVerificationPolicyInitializer.class);

    private final EducationVerificationRepository repository;

    public EducationVerificationPolicyInitializer(EducationVerificationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int updated = repository.acceptOfficialQrVerifications();
        if (updated > 0) log.info("Accepted {} existing education verification records with official CHSI QR codes", updated);
    }
}
