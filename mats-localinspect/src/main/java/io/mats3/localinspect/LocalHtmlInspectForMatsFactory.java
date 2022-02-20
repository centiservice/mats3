package io.mats3.localinspect;

import java.io.IOException;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.MatsStage;

/**
 * Will produce an "embeddable" HTML interface - notice that there are CSS ({@link #getStyleSheet(Appendable)}),
 * JavaScript ({@link #getJavaScript(Appendable)}) and HTML
 * ({@link #createFactoryReport(Appendable, boolean, boolean, boolean) createFactoryReport(Appendable,..)}) to include.
 * If the {@link LocalStatsMatsInterceptor} is installed on the {@link MatsFactory} implementing
 * {@link io.mats3.api.intercept.MatsInterceptable}, it will include pretty nice "local statistics" for all initiators,
 * endpoints and stages.
 * <p />
 * Note: You are expected to {@link #create(MatsFactory) create} one instance of an implementation of this interface per
 * MatsFactory, and keep these around for the lifetime of the MatsFactories (i.e. for the JVM) - as in multiple
 * singletons. Do not create one per HTML request. The reason for this is that at a later point, this class might be
 * extended with "active" features, like stopping and starting endpoints, change the concurrency etc - at which point it
 * might itself need active state, e.g. for a feature like "stop this endpoint for 30 minutes".
 *
 * @author Endre Stølsvik 2022-02-17 23:29 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface LocalHtmlInspectForMatsFactory {

    /**
     * Creates the {@link LocalHtmlInspectForMatsFactoryImpl standard implementation} of this interface.
     */
    static LocalHtmlInspectForMatsFactory create(MatsFactory matsFactory) {
        return new LocalHtmlInspectForMatsFactoryImpl(matsFactory);
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    void getStyleSheet(Appendable out) throws IOException;

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    void getJavaScript(Appendable out) throws IOException;

    /**
     * Creates the full MatsFactory HTML report.
     *
     * @param out
     *            where to output the HTML
     * @param includeInitiators
     *            whether to include the initiators in the report
     * @param includeEndpoints
     *            whether to include the endpoints in the report
     * @param includeStages
     *            whether to include the stages of the endpoints in the report
     * @throws IOException
     *             if the Appendable throws while being output to.
     */
    void createFactoryReport(Appendable out, boolean includeInitiators, boolean includeEndpoints, boolean includeStages)
            throws IOException;

    /**
     * Creates the "Summary table" which is a part of the factory report - it may be interesting to embed on a different
     * page without the entire factory report.
     *
     * @param out
     *            where to output the HTML
     * @param includeInitiators
     *            whether to include the initiators in the report
     * @param includeEndpoints
     *            whether to include the endpoints in the report
     * @throws IOException
     *             if the Appendable throws while being output to.
     */
    void createFactorySummary(Appendable out, boolean includeInitiators, boolean includeEndpoints)
            throws IOException;

    /**
     * Creates the report of a single initiator.
     */
    void createInitiatorReport(Appendable out, MatsInitiator matsInitiator)
            throws IOException;

    /**
     * Creates the report of a single endpoint.
     */
    void createEndpointReport(Appendable out, MatsEndpoint<?, ?> matsEndpoint, boolean includeStages)
            throws IOException;

    /**
     * Creates the report of a single stage.
     */
    void createStageReport(Appendable out, MatsStage<?, ?, ?> matsStage)
            throws IOException;
}
