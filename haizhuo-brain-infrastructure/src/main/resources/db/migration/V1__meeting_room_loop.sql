CREATE TABLE agent_session (
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    active_run_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_agent_session_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_run (
    run_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    message TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    business_outcome VARCHAR(24) NOT NULL,
    answer TEXT NULL,
    booking_id VARCHAR(48) NULL,
    operation_key VARCHAR(128) NOT NULL,
    event_sequence INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_agent_run_user_request (user_id, client_request_id),
    KEY idx_agent_run_session_state (session_id, state),
    CONSTRAINT fk_agent_run_session FOREIGN KEY (session_id) REFERENCES agent_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_run_event (
    run_id VARCHAR(36) NOT NULL,
    event_sequence INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id, event_sequence),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_meeting_room (
    room_id VARCHAR(32) NOT NULL,
    capacity INT NOT NULL,
    projector_available BOOLEAN NOT NULL,
    monitor_summary VARCHAR(500) NOT NULL,
    monitor_observed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_meeting_booking (
    booking_id VARCHAR(48) NOT NULL,
    room_id VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    attendees INT NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (booking_id),
    UNIQUE KEY uk_mock_booking_operation (operation_key),
    KEY idx_mock_booking_room_time (room_id, start_at, end_at),
    CONSTRAINT fk_mock_booking_room FOREIGN KEY (room_id) REFERENCES mock_meeting_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mock_meeting_room (room_id, capacity, projector_available, monitor_summary, monitor_observed_at)
VALUES ('A-201', 8, TRUE, '当前空闲，设备正常（模拟监控）', CURRENT_TIMESTAMP(3));
