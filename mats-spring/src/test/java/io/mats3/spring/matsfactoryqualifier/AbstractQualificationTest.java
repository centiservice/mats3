package io.mats3.spring.matsfactoryqualifier;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.EnableMats;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.activemq.MatsLocalVmActiveMq;

/**
 * Base class for all the qualification tests - we do not use SpringRunner or other frameworks, but instead do all
 * Spring config ourselves. This so that the testing is as application-like as possible.
 *
 * @author Endre Stølsvik 2019-05-25 00:35 - http://stolsvik.com/, endre@stolsvik.com
 */
@Configuration
@EnableMats
public class AbstractQualificationTest {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected AnnotationConfigApplicationContext _ctx;

    @Bean
    protected MatsTestLatch matsTestLatch() {
        return new MatsTestLatch();
    }

    @Bean
    protected MatsLocalVmActiveMq activeMq1() {
        return MatsLocalVmActiveMq.createInVmActiveMq("activeMq1");
    }

    @Bean
    protected MatsLocalVmActiveMq activeMq2() {
        return MatsLocalVmActiveMq.createInVmActiveMq("activeMq2");
    }

    @Bean
    protected ConnectionFactory connectionFactory1(@Qualifier("activeMq1") MatsLocalVmActiveMq activeMq1) {
        return activeMq1.getConnectionFactory();
    }

    @Bean
    protected ConnectionFactory connectionFactory2(@Qualifier("activeMq2") MatsLocalVmActiveMq activeMq2) {
        return activeMq2.getConnectionFactory();
    }

    protected MatsFactory getMatsFactory(
            @Qualifier("connectionFactory1") ConnectionFactory connectionFactory) {
        JmsMatsFactory<String> mf = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                this.getClass().getSimpleName(), "#testing#",
                JmsMatsJmsSessionHandler_Pooling.create(connectionFactory),
                MatsSerializerJson.create());
        mf.getFactoryConfig().setConcurrency(1);
        return mf;
    }

    protected void startSpring() {
        long nanosStart = System.nanoTime();
        log.info("Starting " + this.getClass().getSimpleName() + "!");
        log.info(" \\- new'ing up AnnotationConfigApplicationContext, giving class [" + this.getClass()
                .getSimpleName() + "] as base.");
        _ctx = new AnnotationConfigApplicationContext(this.getClass());
        double startTimeMs = (System.nanoTime() - nanosStart) / 1_000_000d;
        log.info(" \\- done, AnnotationConfigApplicationContext: [" + _ctx + "], took: [" + startTimeMs + " ms].");

        // Since this test is NOT run by the SpringRunner, the instance which this code is running in is not
        // managed by Spring. That is: JUnit have instantiated one (this), and Spring has instantiated another.
        // Therefore, manually autowire this which JUnit has instantiated.
        _ctx.getAutowireCapableBeanFactory().autowireBean(this);
    }

    @Inject
    private MatsTestLatch _latch;

    protected void doStandardTest(MatsFactory _matsFactory, String endPointIdBase) {
        SpringTestDataTO dto = new SpringTestDataTO(Math.PI, "Data");
        SpringTestStateTO sto = new SpringTestStateTO(256, "State");
        _matsFactory.getDefaultInitiator().initiateUnchecked(
                msg -> msg.traceId("TraceId")
                        .from("FromId")
                        .to(endPointIdBase + ".single")
                        .replyTo(endPointIdBase + ".terminator", sto)
                        .request(dto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ":single"), result.getData());
        Assert.assertEquals(sto, result.getState());
    }

    protected void stopSpring() {
        // :: Close Spring.
        long nanosStart = System.nanoTime();
        log.info("Stop - closing Spring ApplicationContext.");
        _ctx.close();
        log.info("done. took: [" + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms].");
    }
}
