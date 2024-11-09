package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatBytes;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatMillis;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatTimestamp;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
            boolean[] serverInstancesHasBeenOk = new boolean[1];

            // :: Check: RUNNING
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

            // :: Check: Only one application serving this Cache, and that it is us.
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY, Axis.EXTERNAL, Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        Map<String, Set<String>> map = info.getServerAppNamesToNodenames();
                        CheckResult ret;
                        if (map.isEmpty()) {
                            ret = checkContext.fault("Not yet seeing any applications serving the DataName '"
                                    + info.getDataName() + "!")
                                    .turnOffAxes(Axis.EXTERNAL, Axis.DEGRADED_PARTIAL,
                                            Axis.MANUAL_INTERVENTION_REQUIRED);
                        }
                        else if (map.size() == 1) {
                            String who = map.keySet().iterator().next();
                            if (info.getAppName().equals(who)) {
                                ret = checkContext.ok("We're the single application serving DataName '"
                                        + info.getDataName() + "'");
                                serverInstancesHasBeenOk[0] = true;
                            }
                            else {
                                ret = checkContext.fault("There is a single application serving DataName '"
                                        + info.getDataName() + "', but it is not us!");
                                ret.text(" -> This is REALLY BAD! The app is '" + who + "'.");
                                ret.text(" -> This means that there is a name-clash between multiple cache servers")
                                        .text("    using the same DataName, living on different applications.");
                                if (serverInstancesHasBeenOk[0]) {
                                    ret.turnOffAxes(Axis.NOT_READY);
                                }
                            }
                        }
                        else {
                            ret = checkContext.fault("There are " + map.size() + " applications serving"
                                    + " DataName '" + info.getDataName() + "'!");
                            ret.text(" -> This is REALLY BAD! The apps are " + map.keySet() + ".");
                            ret.text(" -> This means that there is a name-clash between multiple cache servers")
                                    .text("    using the same DataName, living on different applications.");
                            if (serverInstancesHasBeenOk[0]) {
                                ret.turnOffAxes(Axis.NOT_READY);
                            }
                        }

                        return ret;
                    });

            // :: Check: Clients present
            checkSpec.check(responsibleF,
                    Axis.MANUAL_INTERVENTION_REQUIRED,
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        Map<String, Set<String>> clientAppNamesToNodenames = info.getClientAppNamesToNodenames();
                        // ?: Are there any clients?
                        if (clientAppNamesToNodenames.isEmpty()) {
                            long timeStarted = info.getCacheStartedTimestamp();
                            long timeRunning = System.currentTimeMillis() - timeStarted;
                            int allowedHours = 8;
                            // Longer than allowed hours since?
                            boolean longTime = timeRunning > allowedHours * 60 * 60_000;
                            if (longTime) {
                                var ret = checkContext.fault("No clients are listening to the Cache Server!");
                                ret.text(" -> It is more than " + allowedHours
                                        + " hours since the Cache Server started.");
                                ret.text(" -> This is most probably not intentional, indicating dead functionality.");
                                ret.text(" -> The Cache Server must be removed from the application code, or do not");
                                ret.text("    start it. A temporary solution is to restart the application.");
                                return ret;
                            }
                            else {
                                return checkContext.ok("No clients are listening to the Cache Server, but it"
                                        + " is less than " + allowedHours + " hours since it started.");
                            }
                        }
                        else {
                            int totalNodes = clientAppNamesToNodenames.values().stream().mapToInt(Set::size).sum();
                            var ret = checkContext.ok("We have clients listening: "
                                    + clientAppNamesToNodenames.size() + " apps, " + totalNodes + " nodes");

                            clientAppNamesToNodenames.forEach((app, nodes) -> {
                                ret.text(" -> " + app + ": " + nodes);
                            });

                            return ret;
                        }
                    });

            // :: Check: Unacknowledged Exceptions
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

                            // Add up to 3 of the exceptions to the context
                            exceptionEntries.stream()
                                    .filter(e -> !e.isAcknowledged())
                                    .limit(3)
                                    .forEach(e -> checkContext.exception(e.getCategory() + ": " + e.getMessage(),
                                            e.getThrowable()));
                        }

                        return ret;
                    });

            // --------------------------------------------------------
            // :: Add some information about the Cache Server
            // Last update sent:
            checkSpec.dynamicText(checkContext -> {
                CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                if (info.getLastUpdateSentTimestamp() > 0) {
                    return "# Last update sent: " + _formatTimestamp(info
                            .getLastUpdateSentTimestamp())
                            + " (" + _formatMillis(System.currentTimeMillis()
                                    - info.getLastUpdateSentTimestamp()) + " ago)";
                }
                else {
                    return "# Last update: -I've yet to send any data-";
                }
            });
            // Information about last update sent:
            checkSpec.dynamicText(checkContext -> {
                CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                if (info.getLastUpdateDataCount() > 0) {
                    return "# Data: Count: " + info.getLastUpdateDataCount()
                            + ", Uncompressed: " + _formatBytes(info.getLastUpdateUncompressedSize())
                            + ", Compressed: " + _formatBytes(info.getLastUpdateCompressedSize());
                }
                else {
                    return "# Data: -I've yet to send any data-";
                }
            });
            // Update Received:
            checkSpec.dynamicText(checkContext -> {
                CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                if (info.getLastAnyUpdateReceivedTimestamp() == 0) {
                    return "# Last update received: -I've not yet received any data-";
                }
                else {
                    return "# Last update received: " + _formatTimestamp(info
                            .getLastAnyUpdateReceivedTimestamp())
                            + " (" + _formatMillis(System.currentTimeMillis()
                                    - info.getLastAnyUpdateReceivedTimestamp()) + " ago)";
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
            // :: Check: RUNNING
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        CacheClientInformation info = client.getCacheClientInformation();
                        checkContext.put("info", info);

                        if ((info.getCacheClientLifeCycle() == CacheClientLifecycle.RUNNING)
                                && info.isInitialPopulationDone()) {
                            return checkContext.ok("Client is RUNNING, and initial population is done");
                        }
                        else {
                            var ret = checkContext.fault("Client is NOT running - it is '"
                                    + info.getCacheClientLifeCycle() + "'");
                            if (info.isInitialPopulationDone()) {
                                ret.text(" -> Initial population is (somehow!) done.");
                            }
                            else {
                                ret.text(" -> Initial population is NOT done yet.");
                            }
                            return ret;
                        }
                    });

            // :: Check: Server Seen
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                        long lastServerSeenTimestamp = info.getLastServerSeenTimestamp();
                        // 45 min + 1/3 more, as per JavaDoc ..
                        long maxAdvertisementMinutes = (MatsEagerCacheServer.ADVERTISEMENT_INTERVAL_MINUTES * 4 / 3);
                        // .. then + 10 minutes for leniency
                        long maxMinutesAllow = maxAdvertisementMinutes + 10;

                        String lastAdvertise = "[" + _formatTimestamp(lastServerSeenTimestamp) + ", "
                                + _formatMillis(System.currentTimeMillis() - lastServerSeenTimestamp) + " ago]";

                        long shouldBeWithin = lastServerSeenTimestamp + (maxMinutesAllow * 60_000);
                        CheckResult ret;
                        if (System.currentTimeMillis() < shouldBeWithin) {
                            ret = checkContext.ok("Server last seen " + lastAdvertise + " (max "
                                    + maxMinutesAllow + " minutes)");
                        }
                        else {
                            ret = checkContext.fault("Servers LOST! Last seen " + lastAdvertise);
                            checkContext.text(" -> We expect advertise at least every " + maxAdvertisementMinutes
                                    + " minutes.");
                            checkContext.text(" -> Next advertise should have been within "
                                    + _formatTimestamp(shouldBeWithin));
                            return ret;
                        }
                        // Add info about the servers
                        info.getServerAppNamesToNodenames().forEach((app, nodes) -> {
                            ret.text(" -> " + app + ": " + nodes);
                        });
                        return ret;
                    });

            // :: Check: Unacknowledged Exceptions
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

                            // Add up to 3 of the exceptions to the context
                            exceptionEntries.stream()
                                    .filter(e -> !e.isAcknowledged())
                                    .limit(3)
                                    .forEach(e -> checkContext.exception(e.getCategory() + ": " + e.getMessage(),
                                            e.getThrowable()));
                        }

                        return ret;
                    });

            // --------------------------------------------------------
            // :: Add some information about the Cache Client
            // Update received:
            checkSpec.dynamicText(checkContext -> {
                CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                // ?: Is the client running?
                if (info.isInitialPopulationDone()) {
                    return "# Last update: " + _formatTimestamp(info
                            .getLastAnyUpdateReceivedTimestamp())
                            + " (" + _formatMillis(System.currentTimeMillis()
                                    - info.getLastAnyUpdateReceivedTimestamp()) + " ago)";
                }
                return "# Last update: -Initial population not yet done-";
            });
            // Information about last update received:
            checkSpec.dynamicText(checkContext -> {
                CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                // ?: Have we gotten any data?
                if (info.getLastUpdateDataCount() > 0) {
                    return "# Data: Count: " + info.getLastUpdateDataCount()
                            + ", Compressed: " + _formatBytes(info.getLastUpdateCompressedSize())
                            + ", Decompressed: " + _formatBytes(info.getLastUpdateDecompressedSize());
                }
                return "# Data: -No data received yet-";
            });
            // Number of accesses:
            checkSpec.dynamicText(checkContext -> {
                CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                return "# Number of accesses: " + info.getNumberOfAccesses();
            });
        });
    }
}
