CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS jobs (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    company VARCHAR(255) NOT NULL,
    city VARCHAR(100),
    salary VARCHAR(100),
    education VARCHAR(100),
    experience VARCHAR(100),
    skills_json TEXT NOT NULL DEFAULT '[]',
    responsibilities_json TEXT NOT NULL DEFAULT '[]',
    description TEXT,
    job_description TEXT,
    job_requirements TEXT,
    original_job_description TEXT,
    original_job_requirements TEXT,
    content_origin VARCHAR(40) DEFAULT 'ORIGINAL',
    ai_model VARCHAR(120),
    ai_prompt_version VARCHAR(80),
    ai_generated_at TIMESTAMP,
    ai_confidence NUMERIC(5,2),
    source VARCHAR(255),
    source_url TEXT,
    collected_at TIMESTAMP,
    published_at TIMESTAMP,
    valid_through TIMESTAMP,
    last_seen_at TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    content_hash VARCHAR(80),
    dataset_name VARCHAR(120),
    dataset_year INTEGER,
    source_record_id VARCHAR(120),
    source_table VARCHAR(120),
    category VARCHAR(120),
    area VARCHAR(120),
    location VARCHAR(255),
    annual_salary VARCHAR(100),
    avg_salary VARCHAR(100),
    industry VARCHAR(255),
    company_size VARCHAR(100),
    welfare TEXT,
    search_text TEXT,
    license VARCHAR(120)
);

-- 学历材料最近一次真实性/一致性核验结果。
CREATE TABLE IF NOT EXISTS education_verifications (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL UNIQUE REFERENCES resumes(id) ON DELETE CASCADE,
    status VARCHAR(40),
    status_label VARCHAR(80),
    score INTEGER,
    confidence INTEGER,
    passed BOOLEAN,
    official_report_detected BOOLEAN,
    report_type VARCHAR(160),
    proof_file_type VARCHAR(32),
    proof_hash VARCHAR(80),
    extraction_mode VARCHAR(40),
    summary TEXT,
    checks_json TEXT,
    warnings_json TEXT,
    checked_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_education_verifications_resume
    ON education_verifications(resume_id);

ALTER TABLE education_verifications
    ADD COLUMN IF NOT EXISTS qr_detected BOOLEAN,
    ADD COLUMN IF NOT EXISTS qr_status VARCHAR(40),
    ADD COLUMN IF NOT EXISTS qr_status_label VARCHAR(120),
    ADD COLUMN IF NOT EXISTS qr_content TEXT,
    ADD COLUMN IF NOT EXISTS qr_verification_code VARCHAR(120),
    ADD COLUMN IF NOT EXISTS official_verify_url TEXT,
    ADD COLUMN IF NOT EXISTS official_confirmed BOOLEAN,
    ADD COLUMN IF NOT EXISTS official_confirmed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS proof_owner_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS proof_school VARCHAR(200),
    ADD COLUMN IF NOT EXISTS proof_education VARCHAR(80),
    ADD COLUMN IF NOT EXISTS proof_major VARCHAR(160),
    ADD COLUMN IF NOT EXISTS proof_graduation_year INTEGER,
    ADD COLUMN IF NOT EXISTS proof_education_category VARCHAR(120),
    ADD COLUMN IF NOT EXISTS proof_duration VARCHAR(80),
    ADD COLUMN IF NOT EXISTS proof_study_mode VARCHAR(120),
    ADD COLUMN IF NOT EXISTS proof_college VARCHAR(200),
    ADD COLUMN IF NOT EXISTS proof_department VARCHAR(200),
    ADD COLUMN IF NOT EXISTS proof_enrollment_date VARCHAR(80),
    ADD COLUMN IF NOT EXISTS proof_student_status VARCHAR(120),
    ADD COLUMN IF NOT EXISTS proof_expected_graduation_date VARCHAR(80);


-- 面试技能证据：由已完成的面试记录生成，供简历诊断和岗位匹配复用。
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS skill_evidence_json TEXT;
