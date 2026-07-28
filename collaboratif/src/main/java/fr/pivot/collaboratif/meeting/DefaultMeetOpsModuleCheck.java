package fr.pivot.collaboratif.meeting;

import org.springframework.stereotype.Component;

/**
 * Bootstrap implementation of {@link MeetOpsModuleCheck}.
 *
 * <p>Always returns {@code true} (module considered enabled for all tenants) until
 * the pivot-core-starter exposes per-tenant module activation status.
 *
 * <p>TODO: wire to {@code ModuleAccessService} from pivot-core-starter (EN17)
 */
@Component
public class DefaultMeetOpsModuleCheck implements MeetOpsModuleCheck {

    /**
     * Always returns {@code true}, indicating the MeetOps module is enabled for any tenant.
     *
     * @param tenantId the tenant's {@code public.tenants.id} to check (unused in this
     *                 bootstrap implementation)
     * @return {@code true}
     */
    @Override
    public boolean isEnabled(final Long tenantId) {
        return true;
    }
}
