-- Frozen schema 1 compatibility fixture. Do not regenerate when evolving the schema.
CREATE TABLE package_revision (
                package_id TEXT NOT NULL, package_version TEXT NOT NULL, manifest_sha256 TEXT NOT NULL,
                content_location TEXT NOT NULL, imported_at TEXT NOT NULL,
                PRIMARY KEY(package_id, package_version)
            );
CREATE TABLE learner_session (
                session_id TEXT PRIMARY KEY, package_id TEXT NOT NULL, package_version TEXT NOT NULL,
                unit_id TEXT NOT NULL, unit_version TEXT NOT NULL, status TEXT NOT NULL,
                current_activity_id TEXT NOT NULL, checkpoint BLOB NOT NULL, updated_at TEXT NOT NULL,
                FOREIGN KEY(package_id, package_version) REFERENCES package_revision(package_id, package_version)
            );
CREATE TABLE command_result (
                command_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES learner_session(session_id),
                result_version INTEGER NOT NULL CHECK(result_version > 0), result_payload BLOB NOT NULL,
                committed_at TEXT NOT NULL, UNIQUE(command_id, session_id)
            );
CREATE TABLE draft (
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), activity_id TEXT NOT NULL,
                text TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision >= 0), modified_at TEXT NOT NULL,
                PRIMARY KEY(session_id, activity_id)
            );
CREATE TABLE attempt (
                attempt_id TEXT PRIMARY KEY, command_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), unit_revision_key TEXT NOT NULL,
                activity_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence > 0), response TEXT NOT NULL,
                outcome TEXT NOT NULL, method TEXT NOT NULL, assistance BLOB NOT NULL, submitted_at TEXT NOT NULL,
                local_study_date TEXT NOT NULL, zone_offset TEXT NOT NULL,
                FOREIGN KEY(command_id, session_id) REFERENCES command_result(command_id, session_id)
            );
CREATE TABLE learning_event (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
                command_id TEXT NOT NULL,
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), package_id TEXT NOT NULL,
                package_version TEXT NOT NULL, unit_id TEXT NOT NULL, unit_version TEXT NOT NULL,
                event_type TEXT NOT NULL, payload_version INTEGER NOT NULL CHECK(payload_version > 0),
                occurrence TEXT NOT NULL, activity_id TEXT, payload BLOB NOT NULL,
                FOREIGN KEY(command_id, session_id) REFERENCES command_result(command_id, session_id),
                FOREIGN KEY(package_id, package_version) REFERENCES package_revision(package_id, package_version)
            );
CREATE TABLE observation (
                attempt_id TEXT NOT NULL REFERENCES attempt(attempt_id), objective_id TEXT NOT NULL,
                skill TEXT NOT NULL, assessment_family TEXT, policy_version INTEGER NOT NULL,
                PRIMARY KEY(attempt_id, objective_id, policy_version)
            );
CREATE TABLE objective_summary (
                objective_id TEXT NOT NULL, unit_revision_key TEXT NOT NULL, state TEXT NOT NULL,
                supporting_attempts BLOB NOT NULL, policy_version INTEGER NOT NULL,
                PRIMARY KEY(objective_id, unit_revision_key, policy_version)
            );
CREATE TABLE review_task (
                objective_id TEXT NOT NULL, unit_revision_key TEXT NOT NULL, policy_version INTEGER NOT NULL,
                stage INTEGER NOT NULL CHECK(stage BETWEEN 0 AND 4), due_date TEXT NOT NULL,
                last_assessment_family TEXT, status TEXT NOT NULL,
                PRIMARY KEY(objective_id, unit_revision_key, policy_version)
            );
CREATE TABLE note (
                note_id TEXT PRIMARY KEY, content_id TEXT NOT NULL, revision TEXT, anchor TEXT NOT NULL,
                text TEXT NOT NULL, created_at TEXT NOT NULL, modified_at TEXT NOT NULL
            );
PRAGMA user_version = 1;

INSERT INTO package_revision VALUES ('axiora.saved', '1.0.0', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '/missing/saved.axp', '2026-11-17T00:00:00Z');
INSERT INTO learner_session VALUES ('saved-session', 'axiora.saved', '1.0.0', 'axiora.unit', '1.0.0', 'ACTIVE', 'proof', X'00000001', '2026-11-17T00:00:00Z');
INSERT INTO command_result VALUES ('saved-command', 'saved-session', 1, X'010203', '2026-11-17T00:00:00Z');
INSERT INTO draft VALUES ('saved-session', 'proof', 'saved proof draft', 9, '2026-11-17T00:00:00Z');
INSERT INTO attempt VALUES ('saved-attempt', 'saved-command', 'saved-session', 'axiora.saved@1.0.0/axiora.unit@1.0.0', 'choice', 1, 'yes', 'correct', 'exact_choice', X'7b7d', '2026-11-17T00:00:00Z', '2026-11-17', '+03:00');
INSERT INTO learning_event VALUES (8, 'saved-event', 'saved-command', 'saved-session', 'axiora.saved', '1.0.0', 'axiora.unit', '1.0.0', 'choice_submitted', 1, '2026-11-17T00:00:00Z', 'choice', X'7b7d');
INSERT INTO observation VALUES ('saved-attempt', 'recognize', 'recognition', 'family', 1);
INSERT INTO objective_summary VALUES ('recognize', 'axiora.saved@1.0.0/axiora.unit@1.0.0', 'practicing', X'5b5d', 1);
INSERT INTO review_task VALUES ('recognize', 'axiora.saved@1.0.0/axiora.unit@1.0.0', 1, 1, '2026-11-20', 'family', 'pending');
INSERT INTO note VALUES ('saved-note', 'axiora.unit', NULL, 'proof', 'saved note', '2026-11-17T00:00:00Z', '2026-11-17T00:00:00Z');
