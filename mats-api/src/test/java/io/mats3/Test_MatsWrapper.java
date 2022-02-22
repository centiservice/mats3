package io.mats3;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.MatsStage.StageConfig;

/**
 * Tests the MatsFactoryWrapper, and that the different unwrap methods work as expected.
 *
 * @author Endre Stølsvik 2022-02-22 23:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsWrapper {

    @Test
    public void testWrapping() {
        // :: ARRANGE/ACT: Make a MF
        DummyMatsFactory root = new DummyMatsFactory();

        // :: ACT/ASSERT:
        Assert.assertSame(root, root.unwrapFully());
        Assert.assertSame(root, root.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, root.unwrapTo(DummyMatsFactory.class));

        // :: ARRANGE/ACT: Wrap up, into LAYER 1!
        DummyMatsFactoryWrapper layer1 = new DummyMatsFactoryWrapper(root);

        // :: ACT/ASSERT:
        Assert.assertSame(root, layer1.unwrap());
        Assert.assertSame(root, layer1.unwrapFully());
        Assert.assertSame(layer1, layer1.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer1.unwrapTo(DummyMatsFactory.class));

        // :: ARRANGE/ACT: Wrap up more, into LAYER 2!
        DummyMatsFactoryWrapper layer2 = new DummyMatsFactoryWrapper(layer1);

        // :: ACT/ASSERT:
        Assert.assertSame(root, ((MatsFactoryWrapper) layer2.unwrap()).unwrap());
        Assert.assertSame(root, layer2.unwrapFully());
        Assert.assertSame(layer2, layer2.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer2.unwrapTo(DummyMatsFactory.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInterfaceNotPresent() {
        // :: ARRANGE/ACT: Make a MF
        DummyMatsFactory root = new DummyMatsFactory();

        // :: ACT/ASSERT:
        // try to unwrap to DummyInterface, which this does NOT implement
        root.unwrapTo(SpecialInterface.class);
    }

    @Test
    public void testInterfacePresent() {
        // :: ARRANGE/ACT: Make a MF with interface
        DummyMatsFactoryWithSpecialInterface root = new DummyMatsFactoryWithSpecialInterface();

        // :: ACT/ASSERT
        Assert.assertSame(root, root.unwrapFully());
        Assert.assertSame(root, root.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, root.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, root.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, root.unwrapTo(SpecialInterface.class));

        // :: ARRANGE/ACT: Wrap up, into LAYER 1!
        DummyMatsFactoryWrapper layer1 = new DummyMatsFactoryWrapper(root);

        // :: ACT/ASSERT:
        Assert.assertSame(root, layer1.unwrap());
        Assert.assertSame(root, layer1.unwrapFully());
        Assert.assertSame(layer1, layer1.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer1.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, layer1.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, layer1.unwrapTo(SpecialInterface.class));

        // :: ARRANGE/ACT: Wrap up more, into LAYER 2!
        DummyMatsFactoryWrapper layer2 = new DummyMatsFactoryWrapper(layer1);

        // :: ACT/ASSERT:
        Assert.assertSame(root, ((MatsFactoryWrapper) layer2.unwrap()).unwrap());
        Assert.assertSame(root, layer2.unwrapFully());
        Assert.assertSame(layer2, layer2.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer2.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, layer2.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, layer2.unwrapTo(SpecialInterface.class));
    }

    @Test
    public void testWithInterfaceInStack() {
        // :: ARRANGE/ACT: Make a MF with interface
        DummyMatsFactoryWithSpecialInterface root = new DummyMatsFactoryWithSpecialInterface();

        // :: ACT/ASSERT
        Assert.assertSame(root, root.unwrapFully());
        Assert.assertSame(root, root.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, root.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, root.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, root.unwrapTo(SpecialInterface.class));

        // :: ARRANGE/ACT: Wrap up, into LAYER *with interface* 1!
        DummyMatsFactoryWrapperWithRandomInterface layer1 = new DummyMatsFactoryWrapperWithRandomInterface(root);

        // :: ACT/ASSERT:
        Assert.assertSame(root, layer1.unwrap());
        Assert.assertSame(root, layer1.unwrapFully());
        Assert.assertSame(layer1, layer1.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer1.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, layer1.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, layer1.unwrapTo(SpecialInterface.class));

        Assert.assertSame(layer1, layer1.unwrapTo(RandomInterface.class));

        // :: ARRANGE/ACT: Wrap up more, into LAYER 2!
        DummyMatsFactoryWrapper layer2 = new DummyMatsFactoryWrapper(layer1);

        // :: ACT/ASSERT:
        Assert.assertSame(root, ((MatsFactoryWrapper) layer2.unwrap()).unwrap());
        Assert.assertSame(root, layer2.unwrapFully());
        Assert.assertSame(layer2, layer2.unwrapTo(MatsFactory.class));
        Assert.assertSame(root, layer2.unwrapTo(DummyMatsFactory.class));
        Assert.assertSame(root, layer2.unwrapTo(DummyMatsFactoryWithSpecialInterface.class));

        Assert.assertSame(root, layer2.unwrapTo(SpecialInterface.class));

        Assert.assertSame(layer1, layer1.unwrapTo(RandomInterface.class));
    }

    private static class DummyMatsFactoryWrapperWithRandomInterface extends MatsFactoryWrapper implements
            RandomInterface {
        public DummyMatsFactoryWrapperWithRandomInterface(MatsFactory targetMatsFactory) {
            super(targetMatsFactory);
        }
    }

    private interface RandomInterface {
    }

    private static class DummyMatsFactoryWrapper extends MatsFactoryWrapper {
        public DummyMatsFactoryWrapper(MatsFactory targetMatsFactory) {
            super(targetMatsFactory);
        }
    }

    private interface SpecialInterface {
    }

    private static class DummyMatsFactoryWithSpecialInterface extends DummyMatsFactory implements SpecialInterface {
    }

    /**
     * Just to have an instance which inherits the default "base unwrapping" methods.
     */
    private static class DummyMatsFactory implements MatsFactory {

        @Override
        public FactoryConfig getFactoryConfig() {
            return null;
        }

        @Override
        public <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass) {
            return null;
        }

        @Override
        public <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass,
                Consumer<? super EndpointConfig<R, S>> endpointConfigLambda) {
            return null;
        }

        @Override
        public <R, I> MatsEndpoint<R, Void> single(String endpointId, Class<R> replyClass, Class<I> incomingClass,
                ProcessSingleLambda<R, I> processor) {
            return null;
        }

        @Override
        public <R, I> MatsEndpoint<R, Void> single(String endpointId, Class<R> replyClass, Class<I> incomingClass,
                Consumer<? super EndpointConfig<R, Void>> endpointConfigLambda,
                Consumer<? super StageConfig<R, Void, I>> stageConfigLambda, ProcessSingleLambda<R, I> processor) {
            return null;
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> terminator(String endpointId, Class<S> stateClass, Class<I> incomingClass,
                ProcessTerminatorLambda<S, I> processor) {
            return null;
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> terminator(String endpointId, Class<S> stateClass, Class<I> incomingClass,
                Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
                Consumer<? super StageConfig<Void, S, I>> stageConfigLambda, ProcessTerminatorLambda<S, I> processor) {
            return null;
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
                Class<I> incomingClass, ProcessTerminatorLambda<S, I> processor) {
            return null;
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
                Class<I> incomingClass, Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
                Consumer<? super StageConfig<Void, S, I>> stageConfigLambda, ProcessTerminatorLambda<S, I> processor) {
            return null;
        }

        @Override
        public List<MatsEndpoint<?, ?>> getEndpoints() {
            return null;
        }

        @Override
        public Optional<MatsEndpoint<?, ?>> getEndpoint(String endpointId) {
            return Optional.empty();
        }

        @Override
        public MatsInitiator getDefaultInitiator() {
            return null;
        }

        @Override
        public MatsInitiator getOrCreateInitiator(String name) {
            return null;
        }

        @Override
        public List<MatsInitiator> getInitiators() {
            return null;
        }

        @Override
        public void start() {
        }

        @Override
        public void holdEndpointsUntilFactoryIsStarted() {
        }

        @Override
        public boolean waitForReceiving(int timeoutMillis) {
            return false;
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            return false;
        }
    }

}
