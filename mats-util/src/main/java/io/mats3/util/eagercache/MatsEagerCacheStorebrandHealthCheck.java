package io.mats3.util.eagercache;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckMetadata.HealthCheckMetadataBuilder;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.HealthCheckRegistry.RegisteredHealthCheck;
import com.storebrand.healthcheck.Responsible;

import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientLifecycle;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerLifeCycle;

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
        CacheServerInformation info = server.getCacheServerInformation();
        String id = "'" + info.getDataName() + "' @ '" + info.getNodename() + "'";
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
                        if (info.getCacheServerLifeCycle() == CacheServerLifeCycle.RUNNING) {
                            return checkContext.ok("Server is running");
                        }
                        else {
                            return checkContext.fault("Server is NOT running - it is '"
                                    + info.getCacheServerLifeCycle() + "'");
                        }
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
        CacheClientInformation info = client.getCacheClientInformation();
        String id = "'" + info.getDataName() + "' @ '" + info.getNodename() + "'";
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
            // Check whether running
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        if (info.getCacheClientLifeCycle() == CacheClientLifecycle.RUNNING) {
                            return checkContext.ok("Client is running");
                        }
                        else {
                            return checkContext.fault("Client is NOT running - it is '"
                                    + info.getCacheClientLifeCycle() + "'");
                        }
                    });
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        if (info.isInitialPopulationDone()) {
                            return checkContext.ok("Initial population is done");
                        }
                        else {
                            return checkContext.fault("Initial population is NOT yet done");
                        }
                    });
        });
    }
}
