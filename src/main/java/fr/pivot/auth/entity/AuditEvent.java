package fr.pivot.auth.entity;

import fr.pivot.tenant.entity.Tenant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress = "";

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String meta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static AuditEvent of(User user, Tenant tenant, String eventType, String ip, String userAgent, String meta) {
        AuditEvent e = new AuditEvent();
        e.user = user;
        e.tenant = tenant;
        e.eventType = eventType;
        e.ipAddress = ip != null ? ip : "";
        e.userAgent = userAgent;
        e.meta = meta;
        return e;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Tenant getTenant() { return tenant; }
    public String getEventType() { return eventType; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public String getMeta() { return meta; }
    public Instant getCreatedAt() { return createdAt; }
}
