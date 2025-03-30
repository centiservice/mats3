/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
 * Storebrand HealthChecks for {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient}.
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
            // !! We want the Cache Server to (eventually) be running, otherwise we're not serving the cache.
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        // Get the CacheServerInformation from the cache server
                        CacheServerInformation info = server.getCacheServerInformation();
                        // .. put the information in the context, for the other checks to use.
                        checkContext.put("info", info);

                        // ?: Is the Cache Server running?
                        if (info.getCacheServerLifeCycle() == CacheServerLifeCycle.RUNNING) {
                            // -> Yes, it is running! **This is the only correct steady-state.**
                            return checkContext.ok("Server is RUNNING");
                        }
                        // E-> No, it is not running - fault this check, thus setting NOT_READY.
                        return checkContext.fault("Server is NOT running - it is '"
                                + info.getCacheServerLifeCycle() + "'");
                    });

            // :: Check: Only one application serving this Cache, and that it is us.
            // !! We want to be the only application serving this DataName, as otherwise it is a name-clash.
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY, Axis.EXTERNAL, Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        Map<String, Set<String>> serversAppNamesToNodenames = info.getServersAppNamesToNodenames();
                        // ?: No applications serving this DataName?
                        if (serversAppNamesToNodenames.isEmpty()) {
                            // -> Yes, no applications - let's hope it is "not yet"! (We should be serving it!)
                            CheckResult ret = checkContext.fault("Not yet seeing any applications serving the"
                                    + " DataName '" + info.getDataName() + "!");
                            ret.text(" -> This should definitely be a temporary state, as *we* should be serving it!");
                            ret.turnOffAxes(Axis.EXTERNAL, Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED);
                            return ret;
                        }
                        // ?: A single application serving this DataName?
                        if (serversAppNamesToNodenames.size() == 1) {
                            // -> Yes, single application - let's hope it is us!
                            String who = serversAppNamesToNodenames.keySet().iterator().next();
                            // ?: Is it us?
                            if (info.getAppName().equals(who)) {
                                // -> Yes, it is us! **This is the only correct steady-state.**
                                serverInstancesHasBeenOk[0] = true;
                                return checkContext.ok("We're the single application serving"
                                        + " DataName '" + info.getDataName() + "'");
                            }
                            else {
                                // -> No, it is not us! Argh!
                                CheckResult ret = checkContext.fault("There is a single application serving"
                                        + " DataName '" + info.getDataName() + "', but it is not us!");
                                ret.text(" -> This is REALLY BAD! The app is '" + who + "'.");
                                ret.text(" -> This means that there is a name-clash between multiple cache servers");
                                ret.text("    using the same DataName, living on different applications.");
                                if (serverInstancesHasBeenOk[0]) {
                                    ret.turnOffAxes(Axis.NOT_READY);
                                }
                                return ret;
                            }
                        }
                        // E-> More than one application serving this DataName!
                        CheckResult ret = checkContext.fault("There are " + serversAppNamesToNodenames.size()
                                + " applications serving DataName '" + info.getDataName() + "'!");
                        ret.text(" -> This is REALLY BAD! The apps are " + serversAppNamesToNodenames.keySet() + ".");
                        ret.text(" -> This means that there is a name-clash between multiple cache servers");
                        ret.text("    using the same DataName, living on different applications.");
                        if (serverInstancesHasBeenOk[0]) {
                            ret.turnOffAxes(Axis.NOT_READY);
                        }
                        return ret;
                    });

            // :: Check: Clients present
            // !! We want there to be clients listening to the Cache Server, as otherwise it is useless.
            checkSpec.check(responsibleF, Axis.of(Axis.NOT_READY, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        Map<String, Set<String>> clientAppNamesToNodenames = info.getClientsAppNamesToNodenames();
                        // ?: Are there any clients?
                        if (clientAppNamesToNodenames.isEmpty()) {
                            // -> No, no clients - this isn't good! (No need having this CacheServer if no-one listens!)
                            long timeStarted = info.getCacheStartedTimestamp();
                            long timeRunning = System.currentTimeMillis() - timeStarted;
                            int allowedHours = 8;
                            // Longer than allowed hours since?
                            boolean longTime = timeRunning > allowedHours * 60 * 60_000;
                            // ?: Are we within the grace period?
                            if (longTime) {
                                // -> No, we're outside the grace period - whine about this problem.
                                CheckResult ret = checkContext.fault("No clients are listening to the"
                                        + " Cache Server!");
                                ret.text(" -> It is more than " + allowedHours
                                        + " hours since the Cache Server started.");
                                ret.text(" -> This is most probably not intentional, indicating dead functionality.");
                                ret.text(" -> The Cache Server must be removed from the application code, or do not");
                                ret.text("    start it. A temporary solution is to restart the application.");
                                // We're in MANUAL_INTERVENTION_REQUIRED, as this is not expected.
                                // But we should not say NOT_READY, as that could take us out of the load balancer.
                                ret.turnOffAxes(Axis.NOT_READY);
                                return ret;
                            }
                            else {
                                // Grace period - we're ok.
                                return checkContext.ok("No clients are listening to the Cache Server, but it"
                                        + " is less than " + allowedHours + " hours since it started (grace period).");
                            }
                        }
                        else {
                            // -> There are clients, great. **This is the only correct steady-state.**
                            // We have clients listening
                            int totalNodes = clientAppNamesToNodenames.values().stream().mapToInt(Set::size).sum();
                            CheckResult ret = checkContext.ok("We have clients listening: "
                                    + clientAppNamesToNodenames.size() + " apps, " + totalNodes + " nodes");

                            clientAppNamesToNodenames.forEach((app, nodes) -> {
                                ret.text(" -> " + app + ": " + nodes);
                            });

                            return ret;
                        }
                    });

            // :: Check: Unacknowledged Exceptions
            // !! There should not be ANY exceptions. If there are, they should be acknowledged (and then fixed!).
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheServerInformation info = checkContext.get("info", CacheServerInformation.class);
                        List<ExceptionEntry> exceptionEntries = info.getExceptionEntries();
                        // Count unacknowledged exceptions
                        long unacknowledged = exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .count();
                        // ?: Are there any unacknowledged exceptions?
                        if (unacknowledged == 0) {
                            // -> No, there are no unacknowledged exceptions. **This is the only correct steady-state.**
                            return checkContext.ok("No unacknowledged Exceptions present ("
                                    + exceptionEntries.size() + " total)");
                        }
                        // E-> Yes, there are unacknowledged exceptions - fault this check, thus setting
                        // DEGRADED_PARTIAL and MANUAL_INTERVENTION_REQUIRED.
                        CheckResult ret = checkContext.fault("There are unacknowledged Exceptions present: "
                                + unacknowledged + " of " + exceptionEntries.size());
                        ret.text(" -> Go to the Cache Server's GUI page to resolve and acknowledge them!");

                        // Add up to 3 of the exceptions to the context
                        exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .limit(3)
                                .forEach(e -> checkContext.exception(e.getCategory()
                                        + ": " + e.getMessage(), e.getThrowable()));
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
            // !! We want the Cache Client to (eventually) be RUNNING, otherwise we're we can't provide data.
            checkSpec.check(responsibleF,
                    Axis.of(Axis.NOT_READY),
                    checkContext -> {
                        // Get the CacheClientInformation from the cache client
                        CacheClientInformation info = client.getCacheClientInformation();
                        // .. put the information in the context, for the other checks to use.
                        checkContext.put("info", info);

                        // ?: Is the Cache Client running, and has it done its initial population?
                        if ((info.getCacheClientLifeCycle() == CacheClientLifecycle.RUNNING)
                                && info.isInitialPopulationDone()) {
                            // -> Yes, it is running and populated! **This is the only correct steady-state.**
                            return checkContext.ok("Client is RUNNING, and initial population is done");
                        }
                        else {
                            // E-> No, it is not running or populated - fault this check, thus setting NOT_READY.
                            CheckResult ret = checkContext.fault("Client is NOT running - it is '"
                                    + info.getCacheClientLifeCycle() + "'");
                            // ?: Is the initial population done?
                            if (info.isInitialPopulationDone()) {
                                // -> Yes, it is done, but the client is not running.
                                // (This should really not happen, as the client should start after initial population.)
                                ret.text(" -> Initial population is (somehow!) done.");
                            }
                            else {
                                // -> No, it is not done yet, and the client is not running.
                                ret.text(" -> Initial population is NOT done yet.");
                            }
                            return ret;
                        }
                    });

            // :: Check: Server Seen
            // !! We want the Cache Client to have seen the server recently, otherwise we're not getting updates.
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);

                        long lastServerSeenTimestamp = info.getLastServerSeenTimestamp();
                        if (lastServerSeenTimestamp == 0) {
                            return checkContext.fault("Server has not been seen yet!");
                        }

                        // 45 min + 1/3 more, as per JavaDoc ..
                        long maxAdvertisementMinutes = (MatsEagerCacheServer.ADVERTISEMENT_INTERVAL_MINUTES * 4 / 3);
                        // .. then + 10 minutes for leniency
                        long maxMinutesAllow = maxAdvertisementMinutes + 10;

                        String lastAdvertise = "[" + _formatTimestamp(lastServerSeenTimestamp) + ", "
                                + _formatMillis(System.currentTimeMillis() - lastServerSeenTimestamp) + " ago]";

                        long shouldBeWithin = lastServerSeenTimestamp + (maxMinutesAllow * 60_000);
                        CheckResult ret;
                        // ?: Is the server seen within the allowed time?
                        if (System.currentTimeMillis() < shouldBeWithin) {
                            // -> Yes, it is seen within the allowed time. **This is the only correct steady-state.**
                            ret = checkContext.ok("Server last seen " + lastAdvertise + " (max "
                                    + maxMinutesAllow + " minutes)");
                        }
                        else {
                            // -> No, it is not seen within the allowed time - fault this check, thus setting
                            // DEGRADED_PARTIAL and MANUAL_INTERVENTION_REQUIRED.
                            ret = checkContext.fault("Servers LOST! Last seen " + lastAdvertise);
                            checkContext.text(" -> We expect advertise at least every " + maxAdvertisementMinutes
                                    + " minutes.");
                            checkContext.text(" -> Next advertise should have been within "
                                    + _formatTimestamp(shouldBeWithin));
                        }
                        // Add info about the servers
                        info.getServerAppNamesToNodenames().forEach((app, nodes) -> {
                            ret.text(" -> " + app + ": " + nodes);
                        });
                        return ret;
                    });


            // :: Check: Unacknowledged Exceptions
            // !! There should not be ANY exceptions. If there are, they should be acknowledged (and then fixed!).
            checkSpec.check(responsibleF,
                    Axis.of(Axis.DEGRADED_PARTIAL, Axis.MANUAL_INTERVENTION_REQUIRED),
                    checkContext -> {
                        CacheClientInformation info = checkContext.get("info", CacheClientInformation.class);
                        List<ExceptionEntry> exceptionEntries = info.getExceptionEntries();
                        // Count unacknowledged exceptions
                        long unacknowledged = exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .count();
                        // ?: Are there any unacknowledged exceptions?
                        if (unacknowledged == 0) {
                            // -> No, there are no unacknowledged exceptions. **This is the only correct steady-state.**
                            return checkContext.ok("No unacknowledged Exceptions present ("
                                    + exceptionEntries.size() + " total)");
                        }
                        // E-> Yes, there are unacknowledged exceptions - fault this check, thus setting
                        // DEGRADED_PARTIAL and MANUAL_INTERVENTION_REQUIRED.
                        CheckResult ret = checkContext.fault("There are unacknowledged Exceptions present: "
                                + unacknowledged + " of " + exceptionEntries.size());
                        ret.text(" -> Go to the Cache Client's GUI page to resolve and acknowledge them!");

                        // Add up to 3 of the exceptions to the context
                        exceptionEntries.stream()
                                .filter(e -> !e.isAcknowledged())
                                .limit(3)
                                .forEach(e -> checkContext.exception(e.getCategory()
                                        + ": " + e.getMessage(), e.getThrowable()));
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
