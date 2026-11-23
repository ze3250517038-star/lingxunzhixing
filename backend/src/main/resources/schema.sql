CREATE TABLE IF NOT EXISTS portal_account (
  username VARCHAR(48) PRIMARY KEY,
  password_hash VARCHAR(72) NOT NULL,
  user_role VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS matter (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  category VARCHAR(24) NOT NULL,
  current_version_id BIGINT NULL
);

CREATE TABLE IF NOT EXISTS notice_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  matter_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  base_version_id BIGINT NULL,
  source_url VARCHAR(500) NOT NULL,
  notice_date DATE NOT NULL,
  simulated BOOLEAN NOT NULL,
  snapshot LONGTEXT NOT NULL,
  snapshot_hash CHAR(64) NOT NULL,
  fields_json LONGTEXT NOT NULL,
  version_state VARCHAR(16) NOT NULL,
  FOREIGN KEY (matter_id) REFERENCES matter(id),
  UNIQUE (matter_id, version_no),
  UNIQUE (matter_id, snapshot_hash)
);

CREATE TABLE IF NOT EXISTS version_extraction (
  version_id BIGINT PRIMARY KEY,
  extraction_mode VARCHAR(24) NOT NULL,
  llm_status VARCHAR(32) NULL,
  llm_model VARCHAR(100) NULL,
  warnings_json LONGTEXT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (version_id) REFERENCES notice_version(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS subscription (
  username VARCHAR(48) NOT NULL,
  matter_id BIGINT NOT NULL,
  PRIMARY KEY (username, matter_id),
  FOREIGN KEY (username) REFERENCES portal_account(username),
  FOREIGN KEY (matter_id) REFERENCES matter(id)
);

CREATE TABLE IF NOT EXISTS todo (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(48) NOT NULL,
  matter_id BIGINT NOT NULL,
  field_key VARCHAR(100) NOT NULL,
  acknowledged_version_id BIGINT NOT NULL,
  target_version_id BIGINT NULL,
  todo_state VARCHAR(16) NOT NULL,
  FOREIGN KEY (username, matter_id) REFERENCES subscription(username, matter_id),
  UNIQUE (username, matter_id, field_key)
);

CREATE TABLE IF NOT EXISTS todo_step (
  todo_id BIGINT NOT NULL,
  step_key VARCHAR(80) NOT NULL,
  version_id BIGINT NOT NULL,
  step_done BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (todo_id, step_key),
  FOREIGN KEY (todo_id) REFERENCES todo(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS feedback (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(48) NOT NULL,
  matter_id BIGINT NOT NULL,
  version_id BIGINT NOT NULL,
  message VARCHAR(600) NOT NULL,
  handled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (username) REFERENCES portal_account(username),
  FOREIGN KEY (matter_id) REFERENCES matter(id),
  FOREIGN KEY (version_id) REFERENCES notice_version(id)
);

CREATE TABLE IF NOT EXISTS audit_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  matter_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  actor VARCHAR(48) NOT NULL,
  action_name VARCHAR(48) NOT NULL,
  detail LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (matter_id) REFERENCES matter(id)
);

CREATE TABLE IF NOT EXISTS course (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(48) NOT NULL,
  course_name VARCHAR(120) NOT NULL,
  teacher VARCHAR(80) NOT NULL DEFAULT '',
  location VARCHAR(100) NOT NULL DEFAULT '',
  weekday INT NOT NULL,
  start_time VARCHAR(5) NOT NULL,
  end_time VARCHAR(5) NOT NULL,
  color VARCHAR(16) NOT NULL DEFAULT 'sage',
  note VARCHAR(300) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (username) REFERENCES portal_account(username)
);
