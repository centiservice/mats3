package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatBytes;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification.CheckResult;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckMetadata.HealthCheckMetadataBuilder;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.HealthCheckRegistry.RegisteredHealthCheck;
import com.storebrand.healthcheck.Responsible;

import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientLifecycle;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerLifeCycle;
import io.mats3.util.eagercache.MatsEagerCacheServer.ExceptionEntry;

/**
 * HealthCheck for {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient}.
 *
 * @author Endre Stølsvik 2024-10-02 22:59 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsEagerCacheStorebrandHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheStorebrandHealthCheck.class);

    /**
     * Installs a HealthCheck on the provided {@link HealthCheckRegistry} for the provided {@link MatsEagerCacheServer},
     * checking the health of the cache server's.
     *
     * @param healthCheckRegistry
     *            The HealthCheckRegistry to register the health check with.
     * @param server
     *            The MatsEagerCacheServer to make a health check for.
     * @param responsible
     *            Responsible parties for the health check, if not provided, defaults to {@link Responsible#DEVELOPERS}.
     */
    public static void registerHealthCheck(HealthCheckRegistry healthCheckRegistry,
            MatsEagerCacheServer server, CharSequence... responsible) {
        CacheServerInformation inf = server.getCacheServerInformation();
        String id = "'" + inf.getDataName() + "' @ '" + inf.getNodename() + "'";
        String name = "MatsEagerCacheServer " + id;
        List<RegisteredHealthCheck> registeredHealthChecks = healthCheckRegistry.getRegisteredHealthChecks();
        for (RegisteredHealthCheck registeredHealthCheck : registeredHealthChecks) {
            if (name.equals(registeredHealthCheck.getMetadata().name)) {
                log.error("You're trying to register the same HealthCheck twice for MatsEagerCacheServer " + id
                        + ". Ignoring this second time.");
                return;
            }
        }

        if (responsible.length == 0) {
            responsible = new String[] { Responsible.DEVELOPERS.toString() };
        }

        final CharSequence[] responsibleF = responsible;
        HealthCheckMetadataBuilder meta = HealthCheckMetadata.builder();
        meta.name(name);
        meta.description("MatsEagerCacheServer: " + id);
        meta.sync(true);
        healthCheckRegistry.registerHealthCheck(meta.build(), checkSpec -> {
            // Check whether running
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        CacheServerInformation info = server.getCacheServerInformation();
                        checkContext.put("info", info);

                        CheckResult ret;
                        if (info.getCacheServerLifeCycle() == CacheServerLifeCycle.RUNNING) {
                            ret = checkContext.ok("Server is RUNNING");
                        }
                        else {
                            ret = checkContext.fault("Server is NOT running - it is '"
                                    + info.getCacheServerLifeCycle() + "'");
                        }
                        return ret;
                    });

            // :: Check that there are no unacknowledged Exceptions
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        List<ExceptionEntry> exceptionEntries = info.getExceptionEntries();
                        // Count unacknowledged exceptions
                        long unacknowledged = exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .count();
                        CheckResult ret;
                        if (unacknowledged == 0) {
                            ret = checkContext.ok("No unacknowledged Exceptions present ("
                                    + exceptionEntries.size() + " total)");
                        }
                        else {
                            ret = checkContext.fault("There are unacknowledged Exceptions present: "
                                    + unacknowledged + " of " + exceptionEntries.size());
                            checkContext.text(" -> Go to the Cache Server's GUI page to resolve and acknowledge them!");
                        }
                        if (info.getCacheServerLifeCycle() == CacheServerLifeCycle.RUNNING) {
                            checkContext.text("- Count: " + info.getLastUpdateDataCount()
                                    + ", Uncompressed: " + _formatBytes(info.getLastUpdateUncompressedSize())
                                    + ", Compressed: " + _formatBytes(info.getLastUpdateCompressedSize()));
                        }

                        return ret;
                    });

        });
    }

    /**
     * Installs a HealthCheck on the provided {@link HealthCheckRegistry} for the provided {@link MatsEagerCacheClient},
     * checking the health of the cache client.
     *
     * @param healthCheckRegistry
     *            The HealthCheckRegistry to register the health check with.
     * @param client
     *            The MatsEagerCacheClient to make a health check for.
     * @param responsible
     *            Responsible parties for the health check, if not provided, defaults to {@link Responsible#DEVELOPERS}.
     */
    public static void registerHealthCheck(HealthCheckRegistry healthCheckRegistry,
            MatsEagerCacheClient<?> client, CharSequence... responsible) {
        CacheClientInformation inf = client.getCacheClientInformation();
        String id = "'" + inf.getDataName() + "' @ '" + inf.getNodename() + "'";
        String name = client instanceof MatsEagerCacheClient.MatsEagerCacheClientMock
                ? "MatsEagerCacheClient MOCK " + id
                : "MatsEagerCacheClient " + id;
        List<RegisteredHealthCheck> registeredHealthChecks = healthCheckRegistry.getRegisteredHealthChecks();
        for (RegisteredHealthCheck registeredHealthCheck : registeredHealthChecks) {
            if (name.equals(registeredHealthCheck.getMetadata().name)) {
                log.error("You're trying to register the same HealthCheck twice for MatsEagerCacheClient " + id
                        + ". Ignoring this second time.");
                return;
            }
        }

        if (responsible.length == 0) {
            responsible = new String[] { Responsible.DEVELOPERS.toString() };
        }

        final CharSequence[] responsibleF = responsible;
        HealthCheckMetadataBuilder meta = HealthCheckMetadata.builder();
        meta.name(name);
        meta.description("MatsEagerCacheClient: " + id);
        meta.sync(true);
        healthCheckRegistry.registerHealthCheck(meta.build(), checkSpec -> {
            // :: Check whether running
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        CacheClientInformation info = client.getCacheClientInformation();
                        checkContext.put("info", info);

                        if (info.getCacheClientLifeCycle() == CacheClientLifecycle.RUNNING) {
                            return checkContext.ok("Client is RUNNING");
                        }
                        else {
                            return checkContext.fault("Client is NOT running - it is '"
                                    + info.getCacheClientLifeCycle() + "'");
                        }
                    });
            // :: Check whether initial population is done (is really already checked with above - but just to show)
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                        if (info.isInitialPopulationDone()) {
                            return checkContext.ok("Initial population is done");
                        }
                        else {
                            return checkContext.fault("Initial population is NOT yet done");
                        }
                    });
            // :: Check that there are no unacknowledged Exceptions
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                        List<ExceptionEntry> exceptionEntries = info.getExceptionEntries();
                        // Count unacknowledged exceptions
                        long unacknowledged = exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .count();
                        CheckResult ret;
                        if (unacknowledged == 0) {
                            ret = checkContext.ok("No unacknowledged Exceptions present ("
                                    + exceptionEntries.size() + " total)");
                        }
                        else {
                            ret = checkContext.fault("There are unacknowledged Exceptions present: "
                                    + unacknowledged + " of " + exceptionEntries.size());
                            checkContext.text(" -> Go to the Cache Client's GUI page to resolve and acknowledge them!");
                        }
                        if (info.isInitialPopulationDone()) {
                            checkContext.text("- Count: " + info.getLastUpdateDataCount()
                                    + ", Compressed: " + _formatBytes(info.getLastUpdateCompressedSize())
                                    + ", Decompressed: " + _formatBytes(info.getLastUpdateDecompressedSize()));
                        }

                        return ret;
                    });
        });
    }
}
