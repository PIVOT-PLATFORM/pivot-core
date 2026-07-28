package fr.pivot.collaboratif.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the {@link Clock} bean {@code MeetingAnimationService}/{@code MeetingTimerScheduler}
 * (US12.2.1) inject for every server-authoritative timer computation (AC-02/AC-04/AC-S4) — an
 * injected {@link Clock}, never {@code Instant.now()} inline, is what lets {@code
 * MeetingAnimationServiceTest} assert exact {@code elapsedSeconds}/{@code remainingSeconds}/
 * {@code overtime} values with a fixed clock (mirrors {@code
 * fr.pivot.agilite.standup.StandupTimerScheduler}'s identical injectable-{@code Clock} pattern).
 *
 * <p><strong>Bean named {@code meetOpsClock}, not the bare {@code clock}</strong> — the agilite
 * module already registers its own {@code fr.pivot.agilite.config.ClockConfig#clock()} bean.
 * Both are aggregated into the same {@code pivot-core-app} Spring context ({@code
 * PivotBackendApplicationTests} boots it in full): two {@code @Bean} methods both named {@code
 * clock()} would collide with a {@code ConflictingBeanDefinitionException} at startup. Every
 * injection site in this module therefore uses {@code @Qualifier("meetOpsClock")} rather than
 * relying on by-type autowiring, so the presence of a second, unrelated {@code Clock} bean in the
 * aggregated context is never ambiguous.
 */
@Configuration
public class MeetOpsClockConfig {

    /**
     * The system UTC clock used by every MeetOps animation timer computation.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    public Clock meetOpsClock() {
        return Clock.systemUTC();
    }
}
