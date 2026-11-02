CREATE TABLE IF NOT EXISTS skill_questions (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(100) NOT NULL UNIQUE,
    skill VARCHAR(60) NOT NULL,
    level VARCHAR(20) NOT NULL,
    topic VARCHAR(100),
    question_text TEXT NOT NULL,
    options_json JSONB NOT NULL,
    correct_option VARCHAR(4) NOT NULL,
    explanation TEXT,
    source_repo VARCHAR(255),
    source_file VARCHAR(255),
    source_url TEXT,
    source_commit VARCHAR(64),
    license VARCHAR(40),
    verified BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_skill_questions_skill_level
    ON skill_questions(skill, level, active);

CREATE TABLE IF NOT EXISTS skill_assessment_sessions (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    skill VARCHAR(60) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    question_ids_json JSONB NOT NULL,
    started_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP,
    duration_seconds INTEGER,
    score INTEGER,
    level VARCHAR(40),
    confidence NUMERIC(5,2),
    correct_count INTEGER,
    total_count INTEGER
);

CREATE INDEX IF NOT EXISTS idx_skill_sessions_resume
    ON skill_assessment_sessions(resume_id, skill, started_at DESC);

CREATE TABLE IF NOT EXISTS skill_assessment_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES skill_assessment_sessions(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES skill_questions(id),
    selected_option VARCHAR(4),
    correct BOOLEAN NOT NULL,
    question_level VARCHAR(20) NOT NULL,
    weight NUMERIC(5,2) NOT NULL,
    answered_at TIMESTAMP NOT NULL,
    UNIQUE(session_id, question_id)
);

CREATE INDEX IF NOT EXISTS idx_skill_answers_session
    ON skill_assessment_answers(session_id);

CREATE TABLE IF NOT EXISTS skill_proficiency (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    skill VARCHAR(60) NOT NULL,
    declared_level VARCHAR(40) DEFAULT '自述',
    verified_level VARCHAR(40),
    score INTEGER,
    confidence NUMERIC(5,2),
    evidence_type VARCHAR(40) DEFAULT 'SKILL_TEST',
    session_id BIGINT REFERENCES skill_assessment_sessions(id) ON DELETE SET NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(resume_id, skill)
);
-- BKT auxiliary mastery tracking
ALTER TABLE skill_assessment_sessions
    ADD COLUMN IF NOT EXISTS bkt_mastery NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS bkt_confidence NUMERIC(6,5);

ALTER TABLE skill_proficiency
    ADD COLUMN IF NOT EXISTS bkt_mastery NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS bkt_confidence NUMERIC(6,5);

CREATE TABLE IF NOT EXISTS skill_mastery_state (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    skill VARCHAR(60) NOT NULL,
    knowledge_component VARCHAR(100) NOT NULL,
    mastery_probability NUMERIC(6,5) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    correct_count INTEGER NOT NULL DEFAULT 0,
    last_session_id BIGINT REFERENCES skill_assessment_sessions(id) ON DELETE SET NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(resume_id, skill, knowledge_component)
);

CREATE INDEX IF NOT EXISTS idx_skill_mastery_resume
    ON skill_mastery_state(resume_id, skill);

-- Multi-round skill assessment and BKT-enhanced evidence
ALTER TABLE skill_assessment_sessions
    ADD COLUMN IF NOT EXISTS current_round INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS total_rounds INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS guess_risk NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS consistency NUMERIC(6,5);

ALTER TABLE skill_assessment_answers
    ADD COLUMN IF NOT EXISTS round_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS response_ms INTEGER,
    ADD COLUMN IF NOT EXISTS self_confidence NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS bkt_before NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS bkt_after NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS guess_risk NUMERIC(6,5);

ALTER TABLE skill_proficiency
    ADD COLUMN IF NOT EXISTS guess_risk NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS consistency NUMERIC(6,5),
    ADD COLUMN IF NOT EXISTS rounds_completed INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS skill_assessment_rounds (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES skill_assessment_sessions(id) ON DELETE CASCADE,
    round_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    question_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_topics_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP,
    score INTEGER,
    correct_count INTEGER,
    total_count INTEGER,
    bkt_mastery NUMERIC(6,5),
    bkt_confidence NUMERIC(6,5),
    guess_risk NUMERIC(6,5),
    consistency NUMERIC(6,5),
    UNIQUE(session_id, round_no)
);

CREATE INDEX IF NOT EXISTS idx_skill_rounds_session
    ON skill_assessment_rounds(session_id, round_no);

