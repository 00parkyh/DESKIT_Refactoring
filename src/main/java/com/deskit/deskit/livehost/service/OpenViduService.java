package com.deskit.deskit.livehost.service;

import io.openvidu.java.client.Connection;
import io.openvidu.java.client.ConnectionProperties;
import io.openvidu.java.client.ConnectionType;
import io.openvidu.java.client.OpenVidu;
import io.openvidu.java.client.OpenViduHttpException;
import io.openvidu.java.client.OpenViduJavaClientException;
import io.openvidu.java.client.OpenViduRole;
import io.openvidu.java.client.Recording;
import io.openvidu.java.client.RecordingLayout;
import io.openvidu.java.client.RecordingMode;
import io.openvidu.java.client.RecordingProperties;
import io.openvidu.java.client.Session;
import io.openvidu.java.client.SessionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenViduService {

    private final OpenVidu openVidu;

    private final Map<Long, String> sessionMap = new ConcurrentHashMap<>();

    private String buildSessionId(Long broadcastId) {
        return "broadcast-" + broadcastId;
    }

    private Session getActiveSessionSafely(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return openVidu.getActiveSession(sessionId);
        } catch (Exception e) {
            log.warn("OpenVidu active session lookup failed: sessionId={}, message={}", sessionId, e.getMessage());
            return null;
        }
    }

    public String createSession(Long broadcastId) throws OpenViduJavaClientException, OpenViduHttpException {
        String cachedSessionId = sessionMap.get(broadcastId);
        if (cachedSessionId != null) {
            Session cachedSession = getActiveSessionSafely(cachedSessionId);
            if (cachedSession != null) {
                return cachedSessionId;
            }
            sessionMap.remove(broadcastId);
        }

        String customSessionId = buildSessionId(broadcastId);
        Session existingSession = getActiveSessionSafely(customSessionId);
        if (existingSession != null) {
            sessionMap.put(broadcastId, existingSession.getSessionId());
            log.info("OpenVidu 기존 세션 재사용: broadcastId={}, sessionId={}", broadcastId,
                    existingSession.getSessionId());
            return existingSession.getSessionId();
        }

        RecordingProperties recordingProperties = buildRecordingProperties();

        SessionProperties properties = new SessionProperties.Builder()
                .customSessionId(customSessionId)
                .recordingMode(RecordingMode.MANUAL)
                .defaultRecordingProperties(recordingProperties)
                .build();

        Session session;
        try {
            session = openVidu.createSession(properties);
            sessionMap.put(broadcastId, session.getSessionId());
        } catch (OpenViduHttpException e) {
            if (e.getStatus() == 409) {
                Session conflictSession = getActiveSessionSafely(customSessionId);
                if (conflictSession != null) {
                    sessionMap.put(broadcastId, conflictSession.getSessionId());
                    log.info("OpenVidu 세션 충돌 후 재사용: broadcastId={}, sessionId={}", broadcastId,
                            conflictSession.getSessionId());
                    return conflictSession.getSessionId();
                }
            }
            throw e;
        }

        log.info("OpenVidu 세션 생성: broadcastId={}, sessionId={}", broadcastId, session.getSessionId());
        return session.getSessionId();
    }

    public String createToken(Long broadcastId, Map<String, Object> params)
            throws OpenViduJavaClientException, OpenViduHttpException {

        String sessionId = sessionMap.get(broadcastId);
        if (sessionId == null) {
            sessionId = createSession(broadcastId);
        }

        Session session = getActiveSessionSafely(sessionId);
        if (session == null) {
            sessionMap.remove(broadcastId);
            sessionId = createSession(broadcastId);
            session = getActiveSessionSafely(sessionId);
        }

        if (session == null) {
            throw new IllegalStateException("OpenVidu session is not active: " + buildSessionId(broadcastId));
        }

        OpenViduRole role = OpenViduRole.SUBSCRIBER;
        if (params != null && params.containsKey("role")) {
            String requestedRole = String.valueOf(params.get("role"));
            if ("HOST".equalsIgnoreCase(requestedRole)) {
                role = OpenViduRole.PUBLISHER;
            } else if ("PUBLISHER".equalsIgnoreCase(requestedRole)) {
                role = OpenViduRole.PUBLISHER;
            } else if ("MODERATOR".equalsIgnoreCase(requestedRole)) {
                role = OpenViduRole.MODERATOR;
            } else if ("SUBSCRIBER".equalsIgnoreCase(requestedRole)) {
                role = OpenViduRole.SUBSCRIBER;
            }
        }

        ConnectionProperties properties = new ConnectionProperties.Builder()
                .type(ConnectionType.WEBRTC)
                .role(role)
                .data(params != null ? params.toString() : "")
                .build();

        try {
            Connection connection = session.createConnection(properties);
            return connection.getToken();
        } catch (OpenViduHttpException e) {
            if (e.getStatus() != 404) {
                throw e;
            }

            log.warn("OpenVidu token creation got stale session. Recreating session: broadcastId={}, sessionId={}",
                    broadcastId, sessionId);
            sessionMap.remove(broadcastId);

            String recreatedSessionId = createSession(broadcastId);
            Session recreatedSession = getActiveSessionSafely(recreatedSessionId);
            if (recreatedSession == null) {
                throw e;
            }

            Connection recreatedConnection = recreatedSession.createConnection(properties);
            return recreatedConnection.getToken();
        }
    }

    public void startRecording(Long broadcastId) throws OpenViduJavaClientException, OpenViduHttpException {
        String sessionId = sessionMap.get(broadcastId);
        if (sessionId == null) {
            sessionId = createSession(broadcastId);
        }

        Optional<Recording> existing = findRecordingBySessionId(sessionId);
        if (existing.isPresent()) {
            String status = String.valueOf(existing.get().getStatus()).toLowerCase();
            if ("starting".equals(status) || "started".equals(status)) {
                log.info("OpenVidu recording already active: broadcastId={}, sessionId={}, status={}",
                        broadcastId, sessionId, status);
                return;
            }
        }

        openVidu.startRecording(sessionId, buildRecordingProperties());
    }

    private RecordingProperties buildRecordingProperties() {
        return new RecordingProperties.Builder()
                .outputMode(Recording.OutputMode.COMPOSED) // 판매자(퍼블리셔) 스트림만 보이도록 단일 파일로 녹화
                .recordingLayout(RecordingLayout.BEST_FIT)
                .hasAudio(true)
                .hasVideo(true)
                .build();
    }

    public void stopRecording(Long broadcastId) throws OpenViduJavaClientException, OpenViduHttpException {
        String sessionId = sessionMap.get(broadcastId);
        if (sessionId != null) {
            openVidu.stopRecording(sessionId);
        }
    }

    public void closeSession(Long broadcastId) {
        String sessionId = sessionMap.remove(broadcastId);
        if (sessionId != null) {
            try {
                Session session = getActiveSessionSafely(sessionId);
                if (session != null) {
                    session.close();
                    log.info("OpenVidu 세션 종료: {}", sessionId);
                }
            } catch (Exception e) {
                log.error("세션 종료 중 오류: {}", e.getMessage());
            }
        }
    }

    public Optional<Recording> findRecordingBySessionId(String sessionId)
            throws OpenViduJavaClientException, OpenViduHttpException {
        List<Recording> recordings = openVidu.listRecordings();
        if (recordings == null || recordings.isEmpty()) {
            return Optional.empty();
        }
        return recordings.stream()
                .filter(recording -> sessionId.equals(recording.getSessionId()))
                .findFirst();
    }

    public void deleteRecording(String recordingId) throws OpenViduJavaClientException, OpenViduHttpException {
        if (recordingId == null || recordingId.isBlank()) {
            return;
        }
        openVidu.deleteRecording(recordingId);
        log.info("OpenVidu recording deleted: recordingId={}", recordingId);
    }

    public void forceDisconnect(Long broadcastId, String connectionId) {
        String sessionId = sessionMap.get(broadcastId);
        if (sessionId == null || connectionId == null || connectionId.isBlank()) {
            return;
        }

        try {
            Session session = getActiveSessionSafely(sessionId);
            if (session != null) {
                session.fetch();
                boolean exists = session.getConnections().stream()
                        .anyMatch(connection -> connectionId.equals(connection.getConnectionId()));
                if (!exists) {
                    log.info("Skip force disconnect for unknown connection: {}", connectionId);
                    return;
                }
                session.forceDisconnect(connectionId);
                log.info("Force disconnected connection: {}", connectionId);
            }
        } catch (Exception e) {
            log.error("Failed to force disconnect: {}", e.getMessage());
        }
    }
}
