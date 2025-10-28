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

package io.mats3.spring;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Role;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.MethodIntrospector.MetadataLookup;
import org.springframework.core.SpringVersion;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessContextWrapper;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.MatsStage;
import io.mats3.spring.MatsClassMapping.Stage;

/**
 * The {@link BeanPostProcessor}-class specified by the {@link EnableMats @EnableMats} annotation.
 * <p>
 * It checks all Spring beans registered to the Spring {@link ApplicationContext} for whether their classes are
 * annotated with {@link MatsClassMapping @MatsClassMapping}, or if they have methods annotated with
 * {@link MatsMapping @MatsMapping} or {@link MatsEndpointSetup @MatsEndpointSetup}, and if so configures Mats endpoints
 * for them on the (possibly specified) {@link MatsFactory}. It will also control any registered {@link MatsFactory}
 * beans, invoking {@link MatsFactory#holdEndpointsUntilFactoryIsStarted()} early in the startup procedure before adding
 * the endpoints, and then {@link MatsFactory#start() MatsFactory.start()} as late as possible in the startup procedure.
 * Upon Spring context shutdown, it invokes {@link MatsFactory#stop(int) MatsFactory.stop(..)} as early as possible in
 * the shutdown procedure.
 * <p>
 * <h3>Startup procedure:</h3>
 * <ol>
 * <li>The {@link MatsSpringAnnotationRegistration} (which is a <code>BeanPostProcessor</code>) will have each bean in
 * the Spring ApplicationContext presented:
 * <ol>
 * <li>Each {@link MatsFactory} bean will have their {@link MatsFactory#holdEndpointsUntilFactoryIsStarted()} method
 * invoked.</li>
 * <li>Each bean, and each method of each bean, will be searched for the relevant annotations. Such annotated methods
 * will be put in a list.</li>
 * </ol>
 * </li>
 * <li>Upon {@link ContextRefreshedEvent}:
 * <ol>
 * <li>All definitions that was put in the list will be processed, and Mats Endpoints will be registered into the
 * MatsFactory - using the specified MatsFactory if this was qualified in the definition (Read more at
 * {@link MatsMapping @MatsMapping}).</li>
 * <li>Then, all MatsFactories present in the ApplicationContext will have their {@link MatsFactory#start()} method
 * invoked, which will "release" the configured Mats endpoints, so that they will start (which in turn fires up their
 * "stage processor" threads, which will each go and get a connection to the underlying MQ (which probably is JMS-based)
 * and start to listen for messages).</li>
 * </ol>
 * </li>
 * </ol>
 * <p>
 * Notice: {@code ContextRefreshedEvent} is invoked late in the Spring ApplicationContext startup procedure. This means
 * that all beans have been fully injected, and any {@code @PostConstruct} and lifecycle events have been run. Thus, if
 * the Endpoint depends on any bean/service, it is now running. This is important as the Endpoint's Stage Processors
 * will start picking messages of the queue pretty much immediately. And this is important again if you have any cache
 * that takes time to be populated - it might be of interest to instead use programmatic registration and implement
 * delayed start for Endpoints which depends on caches that take a substantial time to load.
 * </p>
 * <p>
 * Notice: <i>All</i> MatsFactories found in the Spring ApplicationContext are started, regardless of whether they had
 * any Mats endpoints registered using the Mats SpringConfig. This implies that if you register any Mats endpoints
 * programmatically using e.g. <code>@PostConstruct</code> or similar functionality, these will also be started - unless
 * they have not had their {@link MatsEndpoint#finishSetup()} method invoked, ref. "delayed start" mentioned above.
 * </p>
 * <p>
 * <h3>Shutdown procedure:</h3>
 * <ol>
 * <li>Upon {@link ContextClosedEvent}, all MatsFactories in the ApplicationContext will have their
 * {@link MatsFactory#stop(int) stop()} method invoked.
 * <li>This causes all registered Mats Endpoints to be {@link MatsEndpoint#stop(int) stopped}, which releases the
 * connection to the underlying MQ and stops the stage processor threads.
 * </ol>
 * <p>
 * Notice: {@code ContextClosedEvent} is fired rather early in the Spring ApplicationContext shutdown procedure. When
 * running in integration testing mode, which typically will involve starting an in-vm ActiveMQ in the same JVM as the
 * endpoints, this early stopping and releasing of connections is beneficial so that when the in-vm ActiveMQ is stopped
 * somewhat later, one won't get a load of connection failures from the Mats endpoints which otherwise would have their
 * connections shut down under their feet.
 * <p>
 *
 * @author Endre Stølsvik - 2016-05-21 - http://endre.stolsvik.com
 */
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@Component
public class MatsSpringAnnotationRegistration implements
        BeanPostProcessor, ApplicationContextAware { // Notice that this class also registers @EventListeners
    // Use clogging, since that's what Spring does.
    private static final Log log = LogFactory.getLog(MatsSpringAnnotationRegistration.class);
    private static final String LOG_PREFIX = "#SPRINGMATS# ";

    private final boolean _ignoreQualifiersAndDoubleRegistrations;

    public MatsSpringAnnotationRegistration() {
        this(false);
    }

    public static MatsSpringAnnotationRegistration createForTesting_IgnoreQualifiersAndDoubleRegistration() {
        return new MatsSpringAnnotationRegistration(true);
    }

    private MatsSpringAnnotationRegistration(boolean ignoreQualifiersAndDoubleRegistrations) {
        _ignoreQualifiersAndDoubleRegistrations = ignoreQualifiersAndDoubleRegistrations;
        if (ignoreQualifiersAndDoubleRegistrations) {
            log.info(LOG_PREFIX + "@MatsSpringAnnotationRegistration Created, TESTING mode: Ignoring qualifiers and"
                    + " double registrations.");
        }
        else {
            log.info(LOG_PREFIX + "@MatsSpringAnnotationRegistration Created, standard mode.");
        }
    }

    private ConfigurableApplicationContext _configurableApplicationContext;
    private ConfigurableListableBeanFactory _configurableListableBeanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        log.info(LOG_PREFIX + "ApplicationContextAware.setApplicationContext('" + applicationContext.getClass()
                .getSimpleName() + "'). Spring Version: [" + SpringVersion.getVersion()
                + "]. ApplicationContext: " + applicationContext);
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            throw new IllegalStateException("The ApplicationContext when using Mats' SpringConfig"
                    + " must implement " + ConfigurableApplicationContext.class.getSimpleName()
                    + ", while the provided ApplicationContext is of type [" + applicationContext.getClass().getName()
                    + "], and evidently don't.");
        }

        _configurableApplicationContext = (ConfigurableApplicationContext) applicationContext;

        // NOTICE!! We CAN NOT touch the _beans_ at this point, since we then will create them, and we will therefore
        // be hit by the "<bean> is not eligible for getting processed by all BeanPostProcessors" - the
        // BeanPostProcessor in question being ourselves!
        // (.. However, the BeanDefinitions is okay to handle.)
        _configurableListableBeanFactory = _configurableApplicationContext.getBeanFactory();
    }

    private final Map<String, MatsFactory> _matsFactories = new HashMap<>();
    private final IdentityHashMap<MatsFactory, String> _matsFactoriesToName = new IdentityHashMap<>();
    private final Set<Class<?>> _classesWithNoMatsMappingAnnotations = ConcurrentHashMap.newKeySet();

    private static Class<?> getClassOfBean(Object bean) {
        /*
         * There are just too many of these "get the underlying class" stuff in Spring. Since none of these made any
         * difference in the unit tests, I'll go for the one giving the actual underlying "user class", thus having the
         * least amount of added crazy proxy methods to search through. Not sure if this disables any cool Spring magic
         * that could be of interest, e.g. that Spring resolves some meta-annotation stuff by putting the actual
         * annotation on the proxied/generated class or some such.
         */
        // return AopUtils.getTargetClass(bean);
        // return AopProxyUtils.ultimateTargetClass(bean);
        // return bean.getClass(); // Standard Java
        return ClassUtils.getUserClass(bean);
        // Of interest, notice also "AopTestUtils" that does the same thing for Spring beans - the objects themselves.
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        /* no-op */
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // :: Get the BeanDefinition, to check for type.
        BeanDefinition beanDefinition;
        try {
            beanDefinition = _configurableListableBeanFactory.getBeanDefinition(beanName);
        }
        catch (NoSuchBeanDefinitionException e) {
            // -> This is a non-registered bean, which evidently is used when doing unit tests with JUnit SpringRunner.
            // (possibly also for scopes other than singleton, like request-scoped beans?? - not sure)
            if (log.isTraceEnabled()) log.trace(LOG_PREFIX + getClass().getSimpleName()
                    + ".postProcessAfterInitialization(bean, \"" + beanName
                    + "\"): Found no bean definition for the given bean name! Test class?! Ignoring.");
            return bean;
        }

        if (log.isTraceEnabled()) log.trace(LOG_PREFIX + getClass().getSimpleName()
                + ".postProcessAfterInitialization(bean, \"" + beanName + "\") - bean class:["
                + beanDefinition.getBeanClassName() + "], scope:[" + beanDefinition.getScope() + "]");

        /*
         * We check if this is a MatsFactory, in which case we set it to ".holdEndpointsUntilFactoryStarted()", and
         * perform ".start()" on it when we get the ContextRefreshedEvent.
         *
         * We then check if the bean has any Mats SpringConfig @Annotations on it, and if so store it for later
         * processing when we get the ContextRefreshedEvent.
         *
         * Note that both of these "handle later" logics are shortcut if we have already gotten the
         * ContextRefreshedEvent - this is to handle lazy initialization, which typically happens if using e.g. Remock
         * or MockBean in testing scenarios.
         */

        // ?: Is this bean a MatsFactory?
        if (bean instanceof MatsFactory) {
            // -> Yes, MatsFactory, so ask it to hold any subsequently registered endpoints until the MatsFactory is
            // explicitly started.
            MatsFactory matsFactory = (MatsFactory) bean;
            // ?: However, have we already had the ContextRefreshedEvent presented?
            // This may happen if we're in some kind of lazy instantiation mode, like when using Remock or MockBean.
            if (_contextHasBeenRefreshed) {
                // -> Yes, so then we should not hold anyway.
                log.info(LOG_PREFIX + "Found a MatsFactory [" + bean + "]."
                        + " HOWEVER, the context is already refreshed, so we won't invoke"
                        + " matsFactory.holdEndpointsUntilFactoryIsStarted() - but instead invoke matsFactory.start()."
                        + " This means that any not yet started endpoints will start, and any subsequently registered"
                        + " endpoints will start immediately. The reason why this has happened is most probably due"
                        + " to lazy initialization, where beans are being instantiated \"on demand\" after the "
                        + " life cycle processing has happened (i.e. we got ContextRefreshedEvent already).");
                matsFactory.start();
            }
            else {
                // -> No, have not had ContextRefreshedEvent presented yet - so hold endpoints.
                log.info(LOG_PREFIX + "Found a MatsFactory [" + bean + "]."
                        + " We invoke matsFactory.holdEndpointsUntilFactoryIsStarted(), ensuring that any subsequently"
                        + " registered endpoints is held until we explicitly invoke matsFactory.start() later at"
                        + " ContextRefreshedEvent, so that they do not start processing messages until the entire"
                        + " application is ready for service. We also sets the name to the beanName if not already"
                        + " set.");
                // Ensure that any subsequently registered endpoints won't start until we hit matsFactory.start()
                matsFactory.holdEndpointsUntilFactoryIsStarted();
            }
            // Add to map for later use
            _matsFactories.put(beanName, matsFactory);
            _matsFactoriesToName.put(matsFactory, beanName);
            // Set the name of the MatsFactory to the Spring bean name if it is not already set.
            if ("".equals(matsFactory.getFactoryConfig().getName())) {
                matsFactory.getFactoryConfig().setName(beanName);
            }
        }

        // Find actual class of bean (in case of AOPed bean)
        Class<?> targetClass = getClassOfBean(bean);
        // ?: Have we checked this bean before and found no @Mats..-annotations? (might happen with prototype beans)
        if (_classesWithNoMatsMappingAnnotations.contains(targetClass)) {
            // -> Yes, we've checked it before, and it has no @Mats..-annotations.
            if (log.isTraceEnabled()) log.trace(LOG_PREFIX + "Already checked bean [" + beanName
                    + "], bean class: [" + bean.getClass().getSimpleName() + "]: No Mats SpringConfig annotations.");
            return bean;
        }

        // E-> Must check this bean.
        Map<Method, Set<MatsMapping>> methodsWithMatsMappingAnnotations = findRepeatableAnnotatedMethods(targetClass,
                MatsMapping.class);
        Map<Method, Set<MatsEndpointSetup>> methodsWithMatsStagedAnnotations = findRepeatableAnnotatedMethods(
                targetClass, MatsEndpointSetup.class);
        @SuppressWarnings("deprecation") // Supporting Spring 4 still
        Set<MatsClassMapping> matsEndpointSetupAnnotationsOnClasses = AnnotationUtils.getRepeatableAnnotations(
                targetClass, MatsClassMapping.class);

        // ?: Are there any Mats annotated methods on this class?
        if (methodsWithMatsMappingAnnotations.isEmpty()
                && methodsWithMatsStagedAnnotations.isEmpty()
                && matsEndpointSetupAnnotationsOnClasses.isEmpty()) {
            // -> No, so cache that fact, to short-circuit discovery next time for this class (e.g. prototype beans)
            _classesWithNoMatsMappingAnnotations.add(targetClass);
            if (log.isTraceEnabled()) log.trace(LOG_PREFIX + "No @MatsMapping, @MatsClassMapping or @MatsEndpointSetup"
                    + " annotations found on bean [" + beanName + "], bean class: [" + bean.getClass()
                    + "], bean instance: [" + bean + "].");
            return bean;
        }

        // Assert that it is a singleton. NOTICE: It may be prototype, but also other scopes like request-scoped.
        if (!beanDefinition.isSingleton()) {
            throw new BeanCreationException("The bean [" + beanName + "] is not a singleton (scope: ["
                    + beanDefinition.getScope() + "]), which does not make sense when it comes to beans that have"
                    + " methods annotated with @Mats..-annotations.");
        }

        // :: Process any @MatsMapping methods
        for (Entry<Method, Set<MatsMapping>> entry : methodsWithMatsMappingAnnotations.entrySet()) {
            Method method = entry.getKey();
            for (MatsMapping matsMapping : entry.getValue()) {
                log.info(LOG_PREFIX + "Found @MatsMapping on method '" + simpleMethodDescription(method)
                        + "' :#: Annotation:[" + matsMapping + "] :#: method:[" + method + "].");
                _matsMappingMethods.add(new MatsMappingHolder(matsMapping, method, bean));
                // ?: Has context already been refreshed? This might happen if using lazy init, e.g. Remock.
                if (_contextHasBeenRefreshed) {
                    // -> Yes, already refreshed, so process right away, as the process-at-refresh won't happen.
                    log.info(LOG_PREFIX + " \\- ContextRefreshedEvent already run! Process right away!");
                    processMatsMapping(matsMapping, method, bean);
                }
            }
        }
        // :: Process any @MatsEndpointSetup methods
        for (Entry<Method, Set<MatsEndpointSetup>> entry : methodsWithMatsStagedAnnotations.entrySet()) {
            Method method = entry.getKey();
            for (MatsEndpointSetup matsEndpointSetup : entry.getValue()) {
                log.info(LOG_PREFIX + "Found @MatsMapping on method '" + simpleMethodDescription(method)
                        + "' :#: Annotation:[" + matsEndpointSetup + "] :#: method:[" + method + "].");
                _matsStagedMethods.add(new MatsEndpointSetupHolder(matsEndpointSetup, method, bean));
                // ?: Has context already been refreshed? This might happen if using lazy init, e.g. Remock.
                if (_contextHasBeenRefreshed) {
                    // -> Yes, already refreshed, so process right away, as the process-at-refresh won't happen.
                    log.info(LOG_PREFIX + " \\- ContextRefreshedEvent already run! Process right away!");
                    processMatsEndpointSetup(matsEndpointSetup, method, bean);
                }
            }
        }
        // :: Process any @MatsClassMapping beans
        for (MatsClassMapping matsClassMapping : matsEndpointSetupAnnotationsOnClasses) {
            log.info(LOG_PREFIX + "Found @MatsClassMapping on bean '"
                    + classNameWithoutPackage(ClassUtils.getUserClass(targetClass))
                    + "' :#: Annotation:[" + matsClassMapping + "] :#: class:[" + targetClass + "].");
            _matsStagedClasses.add(new MatsClassMappingHolder(matsClassMapping, bean));
            // ?: Has context already been refreshed? This might happen if using lazy init, e.g. Remock.
            if (_contextHasBeenRefreshed) {
                // -> Yes, already refreshed, so process right away, as the process-at-refresh won't happen.
                log.info(LOG_PREFIX + " \\- ContextRefreshedEvent already run! Process right away!");
                processMatsClassMapping(matsClassMapping, bean);
            }
        }
        return bean;
    }

    /**
     * @return a Map [Method, Set-of-Annotations] handling repeatable annotations, e.g. @MatsMapping from the supplied
     *         class, returning an empty map if none.
     */
    private <A extends Annotation> Map<Method, Set<A>> findRepeatableAnnotatedMethods(Class<?> targetClass,
            Class<A> repeatableAnnotationClass) {
        return MethodIntrospector.selectMethods(targetClass, (MetadataLookup<Set<A>>) method -> {
            @SuppressWarnings("deprecation") // Supporting Spring 4 still
            Set<A> matsMappingAnnotations = AnnotationUtils.getRepeatableAnnotations(method, repeatableAnnotationClass);
            return (!matsMappingAnnotations.isEmpty() ? matsMappingAnnotations : null);
        });
    }

    private final List<MatsMappingHolder> _matsMappingMethods = new ArrayList<>();
    private final List<MatsEndpointSetupHolder> _matsStagedMethods = new ArrayList<>();
    private final List<MatsClassMappingHolder> _matsStagedClasses = new ArrayList<>();

    private static class MatsMappingHolder {
        private final MatsMapping matsMapping;
        private final Method method;
        private final Object bean;

        public MatsMappingHolder(MatsMapping matsMapping, Method method, Object bean) {
            this.matsMapping = matsMapping;
            this.method = method;
            this.bean = bean;
        }
    }

    private static class MatsEndpointSetupHolder {
        private final MatsEndpointSetup matsEndpointSetup;
        private final Method method;
        private final Object bean;

        public MatsEndpointSetupHolder(MatsEndpointSetup matsEndpointSetup, Method method, Object bean) {
            this.matsEndpointSetup = matsEndpointSetup;
            this.method = method;
            this.bean = bean;
        }
    }

    private static class MatsClassMappingHolder {
        private final MatsClassMapping matsClassMapping;
        private final Object bean;

        public MatsClassMappingHolder(MatsClassMapping matsClassMapping, Object bean) {
            this.matsClassMapping = matsClassMapping;
            this.bean = bean;
        }
    }

    private boolean _contextHasBeenRefreshed;

    /**
     * {@link ContextRefreshedEvent} runs pretty much as the latest step in the Spring life cycle starting process:
     * Processes all {@link MatsMapping} and {@link MatsEndpointSetup} annotations, then starts the MatsFactory, which
     * will start any "hanging" MATS Endpoints, which will then start consuming messages.
     */
    @EventListener
    public void onContextRefreshedEvent(ContextRefreshedEvent e) {
        log.info(LOG_PREFIX + "ContextRefreshedEvent: Registering all SpringConfig-defined Mats Endpoints, then"
                + " running MatsFactory.start() on all MatsFactories we've seen to start all registered"
                + " Mats Endpoints.");

        /*
         * This is a mega-hack to handle the situation where the entire Spring context's bean definitions has been put
         * in lazy-init mode, like "Remock" does. It forces all bean defined MatsFactories to be instantiated if they
         * have not yet been depended on by the beans that so far has been pulled in. This serves two purposes a) They
         * will be registered in the postProcessBeforeInitialization() above, and b) any endpoint whose containing bean
         * is instantiated later (after ContextRefreshedEvent) and which depends on a not-yet instantiated MatsFactory
         * will not crash (as can happen otherwise, as our @MatsFactory Qualification-trickery evidently uses methods of
         * Spring that does not force instantiation of beans, specifically this method:
         * BeanFactoryAnnotationUtils.qualifiedBeanOfType(BeanFactory beanFactory, Class<T> beanType, String qualifier)
         */
        _configurableListableBeanFactory.getBeansOfType(MatsFactory.class);

        // :: Register Mats endpoints for all @MatsMapping and @MatsEndpointSetup annotated methods.
        _matsMappingMethods.forEach(h -> processMatsMapping(h.matsMapping, h.method, h.bean));
        _matsStagedMethods.forEach(h -> processMatsEndpointSetup(h.matsEndpointSetup, h.method, h.bean));
        _matsStagedClasses.forEach(h -> processMatsClassMapping(h.matsClassMapping, h.bean));

        /*
         * Now set the flag that denotes that ContextRefreshedEvent has been run, so that any subsequent beans with any
         * of Mats' Spring annotations will be insta-registered - as there won't be a second ContextRefreshedEvent which
         * otherwise has responsibility of registering these (this method). Again, this can happen if the beans in the
         * Spring context are in lazy-init mode.
         */
        _contextHasBeenRefreshed = true;

        // :: Start the MatsFactories
        if (_matsFactoriesToName.isEmpty()) {
            log.info(LOG_PREFIX + "We have not seen any MatsFactories, so nothing to start.");
        }
        else {
            log.info(LOG_PREFIX + "Invoking matsFactory.start() on all " + _matsFactories.size() + " MatsFactories"
                    + " we've seen to start registered endpoints.");
            _matsFactories.forEach((name, factory) -> {
                log.info(LOG_PREFIX + "  \\- MatsFactory '" + name + "'.start()");
                factory.start();
            });
        }
    }

    /**
     * {@link ContextClosedEvent} runs pretty much as the first step in the Spring life cycle <b>stopping</b> process:
     * Stop the MatsFactory, which will stop all MATS Endpoints, which will have them release their JMS resources - and
     * then the MatsFactory will clean out the JmsMatsJmsSessionHandler.
     */
    @EventListener
    public void onContextClosedEvent(ContextClosedEvent e) {
        if (_matsFactories.isEmpty()) {
            log.info(LOG_PREFIX + "ContextClosedEvent: We have not seen any MatsFactories, so nothing to stop.");
        }
        else {
            log.info(LOG_PREFIX + "ContextClosedEvent: Running MatsFactory.stop() on all MatsFactories we've seen"
                    + " to stop all registered MATS Endpoints and clean out the JmsMatsJmsSessionHandler.");
            _matsFactories.forEach((name, factory) -> {
                log.info(LOG_PREFIX + "  \\- MatsFactory '" + name + "'.stop()");
                factory.stop(30_000);
            });
        }

        // Reset the state. Not that I ever believe this will ever be refreshed again afterwards.
        _contextHasBeenRefreshed = false;
        _matsMappingMethods.clear();
        _matsStagedMethods.clear();
        _matsStagedClasses.clear();
    }

    /**
     * Processes a method annotated with {@link MatsMapping @MatsMapping} - note that one method can have multiple such
     * annotations, and this method will be invoked for each of them.
     */
    private void processMatsMapping(MatsMapping matsMapping, Method method, Object bean) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Processing @MatsMapping method '"
                + simpleMethodDescription(method) + "':#: Annotation:[" + matsMapping + "]");

        // ?: Is the endpointId == ""?
        if (matsMapping.endpointId().equals("")) {
            // -> Yes, and it is not allowed to have empty endpointId.
            throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping, method)
                    + " is missing endpointId (or 'value')");
        }

        // Override accessibility
        method.setAccessible(true);

        // :: Assert that the endpoint is not annotated with @Transactional
        // (Should probably also check whether it has gotten transactional AOP by XML, but who is stupid enough
        // to use XML config in 2016?! Let alone 2018?! Let alone late 2020?!?)
        Transactional transactionalAnnotation = AnnotationUtils.findAnnotation(method, Transactional.class);
        if (transactionalAnnotation != null) {
            throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping, method)
                    + " shall not be annotated with @Transactional,"
                    + " as Mats does its own transaction management, method:" + method + ", @Transactional:"
                    + transactionalAnnotation);
        }
        // E-> Good to go, set up the endpoint.

        Parameter[] params = method.getParameters();
        final int paramsLength = params.length;
        int dtoParam = -1;
        int stoParam = -1;
        int processContextParam = -1;
        // ?: Check params
        if (paramsLength == 0) {
            // -> No params, not allowed
            throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping, method)
                    + " must have at least one parameter: The DTO class.");
        }
        else if (paramsLength == 1) {
            // -> One param, must be the DTO
            // (No need for @Dto-annotation)
            if (params[0].getType().equals(ProcessContext.class)) {
                throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping, method)
                        + " must have one parameter that is not the ProcessContext: The DTO class");
            }
            dtoParam = 0;
        }
        else {
            // -> More than one param - find each parameter
            // Find the ProcessContext, if present.
            for (int i = 0; i < paramsLength; i++) {
                if (params[i].getType().equals(ProcessContext.class)) {
                    processContextParam = i;
                    break;
                }
            }
            // ?: Was ProcessContext present, and there is only one other param?
            if ((processContextParam != -1) && (paramsLength == 2)) {
                // -> Yes, ProcessContext and one other param: The other shall be the DTO.
                // (No need for @Dto-annotation)
                dtoParam = processContextParam ^ 1;
            }
            else {
                // -> Either ProcessContext + more-than-one extra, or no ProcessContext and more-than-one param.
                // Find DTO and STO, must be annotated with @Dto and @Sto annotations.
                for (int i = 0; i < paramsLength; i++) {
                    if (params[i].getAnnotation(Dto.class) != null) {
                        dtoParam = i;
                    }
                    if (params[i].getAnnotation(Sto.class) != null) {
                        stoParam = i;
                    }
                }
                if (dtoParam == -1) {
                    throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping,
                            method) + " consists of several parameters, one of which needs to be annotated with @Dto");
                }
            }
        }

        // ----- We've found the method parameters (DTO, STO, ProcessContext) - now deduce attributes.

        String descriptionOfAnnotation = simpleAnnotationAndMethodDescription(matsMapping, method);

        // :: Find which MatsFactory to use

        MatsFactory matsFactoryToUse = getMatsFactoryToUse(
                descriptionOfAnnotation,
                method,
                matsMapping.matsFactoryCustomQualifierType(),
                matsMapping.matsFactoryQualifierValue(),
                matsMapping.matsFactoryBeanName());

        // :: If we're in testing mode, we'll check whether this is a double registration, and just ignore it.
        if (shouldWeIgnoreThis(matsFactoryToUse, matsMapping.endpointId())) {
            return;
        }

        // :: Find the concurrency to use, if set (-1 if not)
        int concurrency = getConcurrencyToUse(descriptionOfAnnotation, matsMapping.concurrency());

        // :: Set up the Endpoint

        final Class<?> replyType = method.getReturnType();

        // :: Check whether a Subscription is wanted
        boolean subscription = matsMapping.subscription();
        // Currently this is only allowed for terminators, hence it shall have void return type
        if (subscription && (!replyType.getName().equals("void"))) {
            throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsMapping,
                    method) + " have specified subscription=true, but have a non-void return type. Only"
                    + " Terminators can be subscription based, i.e. \"SubscriptionTerminator\".");
        }

        Class<?> dtoType = params[dtoParam].getType();
        Class<?> stoType = (stoParam == -1 ? Void.TYPE : params[stoParam].getType());

        // Make the parameters final, since we'll be using them in lambdas
        final int dtoParamF = dtoParam;
        final int processContextParamF = processContextParam;
        final int stoParamF = stoParam;

        String typeEndpoint;

        // Get an argument array for the method populated with default values for all types: Primitives cannot be null.
        Object[] templateArgsArray = defaultArgsArray(method);

        String origin = originForMethod(matsMapping, method);
        MatsEndpoint<?, ?> matsEndpoint;
        // ?: Do we have a void return value?
        if (replyType.getName().equals("void")) {
            // -> Yes, void return: Setup Terminator.
            if (subscription) {
                matsEndpoint = matsFactoryToUse.subscriptionTerminator(matsMapping.endpointId(), stoType, dtoType,
                        (processContext, state, incomingDto) -> invokeMatsLambdaMethod(
                                matsMapping, method, bean, templateArgsArray,
                                processContextParamF, processContext,
                                dtoParamF, incomingDto,
                                stoParamF, state));
                typeEndpoint = "Subscription Terminator";
            }
            else {
                matsEndpoint = matsFactoryToUse.terminator(matsMapping.endpointId(), stoType, dtoType,
                        (processContext, state, incomingDto) -> invokeMatsLambdaMethod(
                                matsMapping, method, bean, templateArgsArray,
                                processContextParamF, processContext,
                                dtoParamF, incomingDto,
                                stoParamF, state));
                typeEndpoint = "Terminator";
            }
        }
        else {
            // -> A ReplyType is provided, setup Single endpoint.
            // ?: Is a State parameter specified?
            if (stoParamF != -1) {
                // -> Yes, so then we need to hack together a "single" endpoint out of a staged with single lastStage.
                typeEndpoint = "SingleStage w/State";
                matsEndpoint = matsFactoryToUse.staged(matsMapping.endpointId(), replyType, stoType);
                // NOTE: .lastStage() invokes .finishSetup()
                MatsStage<?, ?, ?> matsStage = matsEndpoint.lastStage(dtoType, (processContext, state, incomingDto) -> {
                    Object reply = invokeMatsLambdaMethod(matsMapping, method, bean, templateArgsArray,
                            processContextParamF, processContext,
                            dtoParamF, incomingDto,
                            stoParamF, state);
                    return helperCast(reply);
                });
            }
            else {
                // -> No state parameter, so use the proper Single endpoint.
                typeEndpoint = "SingleStage";
                matsEndpoint = matsFactoryToUse.single(matsMapping.endpointId(), replyType, dtoType,
                        (processContext, incomingDto) -> {
                            Object reply = invokeMatsLambdaMethod(matsMapping, method, bean, templateArgsArray,
                                    processContextParamF, processContext,
                                    dtoParamF, incomingDto,
                                    -1, null);
                            return helperCast(reply);
                        });
            }
        }
        // Set creation-info on the MatsEndpoint
        matsEndpoint.getEndpointConfig().setOrigin(origin);
        // .. and on the single stage
        matsEndpoint.getStages().get(0).getStageConfig().setOrigin(origin);

        // Set concurrency, if set
        if (concurrency > 0) {
            matsEndpoint.getEndpointConfig().setConcurrency(concurrency);
        }

        if (log.isInfoEnabled()) {
            String procCtxParamDesc = processContextParam != -1 ? "param#" + processContextParam : "<not present>";
            String stoParamDesc = stoParam != -1 ? "param#" + stoParam + ":" + stoType.getSimpleName()
                    : "<not present>";
            String dtoParamDesc = "param#" + dtoParam + ":" + dtoType.getSimpleName();
            log.info(LOG_PREFIX + "Processed " + typeEndpoint + " Mats Spring endpoint by "
                    + simpleAnnotationAndMethodDescription(matsMapping, method)
                    + " :: ReplyType:[" + replyType.getSimpleName()
                    + "], ProcessContext:[" + procCtxParamDesc
                    + "], STO:[" + stoParamDesc
                    + "], DTO:[" + dtoParamDesc
                    + "], Concurrency:[" + (concurrency != -1 ? Integer.toString(concurrency) : "~default~") + "]");
        }
    }

    /**
     * Process a method annotated with {@link MatsEndpointSetup @MatsEndpointSetup} - note that one method can have
     * multiple such annotations, and this method will be invoked for each of them.
     */
    private void processMatsEndpointSetup(MatsEndpointSetup matsEndpointSetup, Method method, Object bean) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Processing @MatsEndpointSetup method '"
                + simpleMethodDescription(method) + "':#: Annotation:[" + matsEndpointSetup + "]");

        // ?: Is the endpointId == ""?
        if (matsEndpointSetup.endpointId().equals("")) {
            // -> Yes, and it is not allowed to have empty endpointId.
            throw new MatsSpringConfigException("The " + simpleAnnotationAndMethodDescription(matsEndpointSetup, method)
                    + " is missing endpointId (or 'value')");
        }

        // Override accessibility
        method.setAccessible(true);

        // Find parameters: MatsEndpoint and potentially EndpointConfig
        Parameter[] params = method.getParameters();
        final int paramsLength = params.length;
        int endpointParam = -1;
        int configParam = -1;
        // ?: Check params
        if (paramsLength == 0) {
            // -> No params, not allowed
            throw new IllegalStateException("The " + simpleAnnotationAndMethodDescription(matsEndpointSetup, method)
                    + " must have at least one parameter: A MatsEndpoint.");
        }
        else if (paramsLength == 1) {
            // -> One param, must be the MatsEndpoint instances
            if (!params[0].getType().equals(MatsEndpoint.class)) {
                throw new IllegalStateException("The " + simpleAnnotationAndMethodDescription(matsEndpointSetup, method)
                        + " must have one parameter of type MatsEndpoint.");
            }
            endpointParam = 0;
        }
        else {
            // -> More than one param - find each parameter
            // Find MatsEndpoint and EndpointConfig parameters:
            for (int i = 0; i < paramsLength; i++) {
                if (params[i].getType().equals(MatsEndpoint.class)) {
                    endpointParam = i;
                }
                if (params[i].getType().equals(EndpointConfig.class)) {
                    configParam = i;
                }
            }
            if (endpointParam == -1) {
                throw new IllegalStateException("The " + simpleAnnotationAndMethodDescription(matsEndpointSetup, method)
                        + " consists of several parameters, one of which needs to be the MatsEndpoint.");
            }
        }

        // ----- We've found the method parameters - now deduce attributes.

        // :: Invoke the @MatsEndpointSetup-annotated staged endpoint setup method

        MatsFactory matsFactoryToUse = getMatsFactoryToUse(
                simpleAnnotationAndMethodDescription(matsEndpointSetup, method),
                method,
                matsEndpointSetup.matsFactoryCustomQualifierType(),
                matsEndpointSetup.matsFactoryQualifierValue(),
                matsEndpointSetup.matsFactoryBeanName());
        MatsEndpoint<?, ?> endpoint = matsFactoryToUse
                .staged(matsEndpointSetup.endpointId(), matsEndpointSetup.reply(), matsEndpointSetup.state());
        endpoint.getEndpointConfig().setOrigin(originForMethod(matsEndpointSetup, method));

        // Invoke the @MatsEndpointSetup-annotated setup method
        Object[] args = new Object[paramsLength];
        args[endpointParam] = endpoint;
        if (configParam != -1) {
            args[configParam] = endpoint.getEndpointConfig();
        }
        try {
            method.invoke(bean, args);
        }
        catch (IllegalAccessException | IllegalArgumentException e) {
            throw new MatsSpringConfigException("Problem with invoking "
                    + simpleAnnotationAndMethodDescription(matsEndpointSetup, method) + ".", e);
        }
        catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            }
            throw new MatsSpringConfigException("Got InvocationTargetException when invoking "
                    + simpleAnnotationAndMethodDescription(matsEndpointSetup, method) + ".", e);
        }

        // This endpoint is finished set up.
        endpoint.finishSetup();
        log.info(LOG_PREFIX + "Processed Mats Endpoint Configuration by "
                + simpleAnnotationAndMethodDescription(matsEndpointSetup, method)
                + " :: MatsEndpoint:[param#" + endpointParam + "], EndpointConfig:[param#" + configParam + "]");
    }

    /**
     * Process a class annotated with {@link MatsClassMapping @MatsClassMapping} - note that one class can have multiple
     * such annotations, and this method will be invoked for each of them.
     */
    private void processMatsClassMapping(MatsClassMapping matsClassMapping, Object bean) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Processing @MatsClassMapping bean '"
                + classNameWithoutPackage(bean) + "':#: Annotation:[" + matsClassMapping + "]");

        // Find actual class of bean (in case of AOPed bean)
        Class<?> matsClass = getClassOfBean(bean);
        // Find all methods annotated with Stage
        Map<Method, Stage> stages = MethodIntrospector.selectMethods(matsClass,
                (MetadataLookup<Stage>) method -> AnnotationUtils.findAnnotation(method, Stage.class));

        // :: Get Stages sorted in order by ordinal, and assert that all are <0 and that no ordinal is duplicated.
        SortedMap<Integer, Method> stagesByOrdinal = new TreeMap<>();
        stages.forEach((method, stageAnnotation) -> {
            int ordinal = stageAnnotation.ordinal();
            // ?: ASSERT: Negative ordinal?
            if (ordinal < 0) {
                // -> Yes, negative -> error.
                throw new MatsSpringConfigException("On @MatsClassMapping endpoint at class '"
                        + classNameWithoutPackage(bean) + "', the Stage ordinal is negative (" + ordinal
                        + ") on @Stage annotation of method '" + simpleMethodDescription(method)
                        + "' - all ordinals must be >=0 and unique within this endpoint"
                        + " (The ordinal defines the order of stages of this endpoint)."
                        + " @Stage annotation:[" + stageAnnotation + "]");
            }
            // ?: ASSERT: Duplicate ordinal?
            if (stagesByOrdinal.containsKey(ordinal)) {
                // -> Yes, duplicate -> error.
                Method prevMethod = stagesByOrdinal.get(ordinal);
                throw new MatsSpringConfigException("The Stage with ordinal [" + ordinal
                        + "] of @MatsClassMapping endpoint at class '" + classNameWithoutPackage(bean)
                        + "' is duplicated on another Stage of the same endpoint."
                        + " All Stages of an endpoint must have ordinal set, and must be unique within the endpoint"
                        + " (The ordinal defines the order of stages of this endpoint)."
                        + "\n  - This method:     '" + simpleMethodDescription(method)
                        + "' with @Stage annotation: [" + stageAnnotation + "]"
                        + "\n  - Previous method: '" + simpleMethodDescription(prevMethod)
                        + "' with @Stage annotation:[" + stages.get(prevMethod) + "]");
            }
            stagesByOrdinal.put(ordinal, method);
        });
        // ?: ASSERT: Do we have an INITIAL Stage?
        if (!stagesByOrdinal.containsKey(0)) {
            // -> No, INITIAL Stage not present -> error.
            throw new MatsSpringConfigException("The @MatsClassMapping endpoint at class '"
                    + classNameWithoutPackage(bean) + "' is missing initial stage:"
                    + " No method is annotated with '@Stage(Stage.INITIAL)' (i.e. ordinal=0)");
        }

        // :: ASSERT: Check that only the last stage has a return type
        int lastOrdinal = stagesByOrdinal.lastKey();
        for (Entry<Integer, Method> entry : stagesByOrdinal.entrySet()) {
            int ordinal = entry.getKey();
            Method method = entry.getValue();
            // ?: Is this the last ordinal?
            if (ordinal == lastOrdinal) {
                // -> Last, so ignore.
                continue;
            }
            // ?: ASSERT: Does this non-last Stage return something else than void?
            if (method.getReturnType() != Void.TYPE) {
                // -> Yes, returns non-void -> error.
                throw new MatsSpringConfigException("The Stage with ordinal [" + ordinal
                        + "] of @MatsClassMapping endpoint at class '" + classNameWithoutPackage(bean)
                        + "' has a return type '" + classNameWithoutPackage(method.getReturnType())
                        + "' (not void), but it is not the last stage. Only the last stage shall have a return type,"
                        + " which is the return type for the endpoint. Method: '"
                        + simpleMethodDescription(method) + "'");
            }
        }

        // :: Find reply type: Get return type of last Stage

        Method lastStageMethod = stagesByOrdinal.get(stagesByOrdinal.lastKey());
        // .. modify replyClass if it is Void.class to void.class.. Nuances.. They matter..
        // NOTE: void.class == Void.TYPE, while Void.class != Void.TYPE
        Class<?> replyClass = lastStageMethod.getReturnType() == Void.class
                ? void.class
                : lastStageMethod.getReturnType();

        // :: Find request type: Get DTO-type of first Stage

        Method initialStageMethod = stagesByOrdinal.get(stagesByOrdinal.firstKey());
        int dtoParamIdxOfInitial = findDtoParamIndexForMatsClassMappingLambdaMethod(initialStageMethod);
        if (dtoParamIdxOfInitial == -1) {
            throw new MatsSpringConfigException("The Initial Stage of @MatsClassMapping endpoint at class '"
                    + classNameWithoutPackage(bean) + "' does not have a incoming DTO (message) parameter."
                    + " Either it must be the sole parameter, or it must be marked by annotation @Dto."
                    + " Method: [" + initialStageMethod + "]");
        }
        Class<?> requestClass = initialStageMethod.getParameters()[dtoParamIdxOfInitial].getType();

        // ----- We've found the method parameters (DTO, STO, ProcessContext) - now deduce attributes.

        String descriptionOfAnnotation = "@MatsClassMapping-annotated bean '" + classNameWithoutPackage(bean) + "'";

        // :: Find which MatsFactory to use

        MatsFactory matsFactoryToUse = getMatsFactoryToUse(descriptionOfAnnotation,
                matsClass,
                matsClassMapping.matsFactoryCustomQualifierType(),
                matsClassMapping.matsFactoryQualifierValue(),
                matsClassMapping.matsFactoryBeanName());

        // :: If we're in testing mode, we'll check whether this is a double registration, and just ignore it.
        if (shouldWeIgnoreThis(matsFactoryToUse, matsClassMapping.endpointId())) {
            return;
        }

        // :: Find the concurrency to use, if set (-1 if not)

        int concurrencyForEndpoint = getConcurrencyToUse(descriptionOfAnnotation, matsClassMapping.concurrency());

        // :: Create the Staged Endpoint

        log.info(LOG_PREFIX + "The " + descriptionOfAnnotation + "' has " + stages.size()
                + " Stage" + (stages.size() > 1 ? "s" : "") + ", request DTO [" + classNameWithoutPackage(requestClass)
                + "], and reply DTO [" + classNameWithoutPackage(replyClass) + "].");

        // We'll first check that the class has a no-args constructor. This is not strictly necessary as long as the
        // serialization mechanism can construct an instance, but problems have turned up when the serialization
        // mechanism is GSON, and constructor injection is employed. The problem is that if a no-args constructor is not
        // present, GSON will use 'Objenesis' to instantiate the object, which will not invoke /any/ constructor. This
        // is not a problem in itself, but if the class has a field that uses declaration initialization (i.e. private
        // List<String> _list = new ArrayList<>()), then this field will not be initialized when no constructor is
        // invoked, and thus be null. The mechanism that we use here to find the fields that Spring has injected will
        // thus assume that this field is an injected field (i.e. it is present in the bean instance, but _not_ in the
        // newly instantiated instance), and thus it will be stored as a template field, and thus it will NOT be assumed
        // to be state: It will be assumed to be a shared service of sorts, and thus shared between all stage processing
        // threads. Which is close to opposite of what you expected, and will lead to very strange and hard-to-debug
        // problems. Thus, we check that the class has a no-args constructor, and if not, we throw.
        try {
            matsClass.getDeclaredConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new MatsSpringConfigException("The class [" + matsClass.getSimpleName() + "] does not"
                    + " have a no-args constructor, which is required for @MatsClassMapping. (The reason is that"
                    + " some serialization mechanisms (GSON) employ 'Objenesis' for instantiation if a no-args"
                    + " constructor is missing, and any declaration initialized fields will then not be initialized,"
                    + " as Java rely on constructor invocation to do that).");
        }

        // Make an "empty state object" to see if it can be done: Checks that deserialization works for an "empty
        // object", which can fail if the combination of the class's fields and serialization mechanism is not
        // compatible, e.g. if the class has a state field of type java.lang.Thread, which would be insane anyway.
        //
        // We'll later also use this instance to check if some fields are set by the class itself, i.e. with no-args
        // constructor or initial value / declaration initialization.
        Object freshInstantiatedStateObject = matsFactoryToUse.getFactoryConfig().instantiateNewObject(matsClass);

        // :: Hold on to all non-null fields of the bean - these are what Spring has injected. Make "template".

        Field[] processContextField_hack = new Field[1];
        LinkedHashMap<Field, Object> templateFields = new LinkedHashMap<>();
        ReflectionUtils.doWithFields(matsClass, field -> {
            String name = field.getName();
            Class<?> clazz = field.getType();

            // ?: Is this field static?
            if (Modifier.isStatic(field.getModifiers())) {
                // -> Yes, static, and thus we should not care about it.
                log.info(LOG_PREFIX + " - Field [" + name + "] is static: Should not be injected, ProcessContext nor"
                        + " state field, so ignore it.");
                return;
            }

            // ?: Is this a field of type ProcessContext?
            if (clazz.isAssignableFrom(ProcessContext.class)) {
                // -> Yes, ProcessContext - we store the reference for later, so that we can set it upon processing.
                // Get ReplyType of ProcessContext. NOTE: It shall return a ParameterizedType, as it has type parameter.
                ParameterizedType genericType = (ParameterizedType) field.getGenericType();
                Type processContextReplyType = genericType.getActualTypeArguments()[0];

                log.info(LOG_PREFIX + " - Field [" + name + "] is Mats' ProcessContext<" + processContextReplyType
                        .getClass().getSimpleName() + "> - reply type parameter is [" + processContextReplyType + "]");
                // ?: Is this Void.class? In that case, change it to void.class - see similar logic for replyClass.
                if (processContextReplyType == Void.class) {
                    // -> Yes, Void.class, i.e. ProcessContext<Void>. Change it to 'void'.
                    processContextReplyType = void.class;
                }
                if (processContextField_hack[0] != null) {
                    throw new MatsSpringConfigException("The @MatsClassMapping endpoint at class '"
                            + classNameWithoutPackage(bean) + "' evidently has more than one ProcessContext field."
                            + " Only one is allowed."
                            + "\n  - This field:     [" + field + "]"
                            + "\n  - Previous field: [" + processContextField_hack[0] + "]");
                }
                if (processContextReplyType != replyClass) {
                    throw new MatsSpringConfigException("The @MatsClassMapping endpoint at class '"
                            + classNameWithoutPackage(bean) + "' has a ProcessContext field where the reply type"
                            + " does not match the resolved reply type from the last Stage."
                            + " ProcessContext Field: [" + field + "]"
                            + "\n  - Type from field:   ProcessContext<" + processContextReplyType + ">"
                            + "\n  - Reply type resolved from Stages: [" + replyClass + "]");
                }
                processContextField_hack[0] = field;
                return;
            }

            // ?: Is this field a primitive?
            if (clazz.isPrimitive()) {
                // -> Yes, primitive, and per contract for @MatsClassMapping, you cannot use dependency injection for
                // primitives, as they are assumed to be state fields.
                log.info(LOG_PREFIX + " - Field [" + name + "] is primitive: Assuming state field, ignoring. (Type: "
                        + clazz + ").");
                return;
            }

            // :: Get the field value
            field.setAccessible(true);
            Object value = field.get(bean);

            // ?: Does this field have the value null?
            if (value == null) {
                // -> Yes, null value, and we conclude that dependency injection didn't set them, thus state fields.
                log.info(LOG_PREFIX + " - Field [" + name + "] of Spring bean is null: Assuming state field, ignoring."
                        + " (Type: [" + field.getGenericType() + "])");
                return;
            }
            // ->?: Not null, but is it also set in a newly instantiated variant of the matsClass?
            // (That is, is it a 'declaration-initialized' field, i.e. 'List<String> _list = new ArrayList<>();')
            else if (field.get(freshInstantiatedStateObject) != null) {
                // -> Yes, both non-null in the Spring bean, AND in a newly instantiated instance of the matsClass
                log.info(LOG_PREFIX + " - Field [" + name + "] is non-null both in Spring bean AND in newly"
                        + " instantiated instance: Assuming declaration-initialized state field, ignoring. (Type: ["
                        + field.getGenericType() + "])");
                return;
            }

            // ----- This is not ProcessContext nor a State field, thus a Spring Dependency Injected field. We assume.

            log.info(LOG_PREFIX + " - Field [" + name + "] of Spring bean is non-null: Assuming Spring Dependency"
                    + " Injection has set it - storing as template. (Type:[" + field.getGenericType() + "], Value:["
                    + value + "])");

            // Check that 'transient' is set for injected fields.
            if (!Modifier.isTransient(field.getModifiers())) {
                throw new MatsSpringConfigException("Missing 'transient' modifier on injected field [" + name
                        + "] of class [" + classNameWithoutPackage(bean) + "].");
            }

            templateFields.put(field, value);
        });

        // :: Keep ProcessContext field, and make it accessible.

        Field processContextField = processContextField_hack[0];
        if (processContextField != null) {
            processContextField.setAccessible(true);
        }

        // :: Actually make the endpoint
        MatsEndpoint<?, ?> ep;
        try {
            ep = matsFactoryToUse.staged(matsClassMapping.endpointId(), replyClass, matsClass);
        }
        catch (RuntimeException e) {
            throw new MatsSpringConfigException("Could not create endpoint for @MatsClassMapping endpoint at class '"
                    + classNameWithoutPackage(bean) + "' - you'll have to diligently read the cause message and"
                    + " stacktrace!", e);
        }
        ep.getEndpointConfig()
                .setOrigin("@MatsClassMapping " + matsClass.getSimpleName() + ";" + matsClass.getName());

        // Set concurrency, if set
        if (concurrencyForEndpoint > 0) {
            ep.getEndpointConfig().setConcurrency(concurrencyForEndpoint);
        }

        // :: Make the stages of the Endpoint by running through the @Stage-annotated methods.

        stagesByOrdinal.forEach((ordinal, method) -> {
            Stage stageAnnotation = stages.get(method);

            // :: Find concurrency to use, if set (-1 if not)
            int concurrencyForStage = getConcurrencyToUse(
                    descriptionOfAnnotation + " @Stage(" + ordinal + "):" + method.getName() + "(..)",
                    stageAnnotation.concurrency());

            // :: Find the DTO parameter, if any.
            int dtoParamIdx = findDtoParamIndexForMatsClassMappingLambdaMethod(method);
            Parameter[] parameters = method.getParameters();
            Class<?> incomingClass = dtoParamIdx == -1
                    ? Void.class
                    : parameters[dtoParamIdx].getType();
            // :: Find the ProcessContext parameter, if any.
            int tempPcParamIdx = -1;
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getType() == ProcessContext.class) {
                    tempPcParamIdx = i;
                    break;
                }
            }
            int processContextParamIdx = tempPcParamIdx;

            log.info(LOG_PREFIX + "  -> Stage '" + ordinal + "': '" + simpleMethodDescription(method)
                    + ", DTO paramIdx:" + dtoParamIdx + ", DTO class:"
                    + classNameWithoutPackage(incomingClass) + " - ProcessContext paramIdx:" + processContextParamIdx
                    + ", Concurrency:[" + (concurrencyForEndpoint != -1 ? Integer.toString(concurrencyForEndpoint)
                            : "~default~") + "]");

            Object[] defaultArgsArray = defaultArgsArray(method);

            // Force accessible, i.e. ignore visibility modifiers.
            method.setAccessible(true);

            MatsStage<?, ?, ?> stage = ep.stage(incomingClass, (originalProcessContext, state, incomingDto) -> {

                // :: Make lambdas for setting and clearing the state and ProcessContext fields.

                Consumer<ProcessContext<?>> setFields = (processContext) -> {
                    // :: Set the "template fields" from original Dependency Injection of @Service.
                    for (Entry<Field, Object> entry : templateFields.entrySet()) {
                        Field templateField = entry.getKey();
                        try {
                            templateField.set(state, entry.getValue());
                        }
                        catch (IllegalAccessException e) {
                            throw new MatsSpringInvocationTargetException("Didn't manage to set \"template field\" '"
                                    + templateField.getName() + "' assumed coming from Spring Dependency Injection into"
                                    + " the @MatsClassMapping combined state/@Service class '"
                                    + classNameWithoutPackage(matsClass) + "' upon invocation of Mats Stage.", e);
                        }
                    }

                    // :: Set the ProcessContext for this processing.
                    if (processContextField != null) {
                        try {
                            processContextField.set(state, processContext);
                        }
                        catch (IllegalAccessException e) {
                            throw new MatsSpringInvocationTargetException("Didn't manage to set the ProcessContext '"
                                    + processContextField.getName() + "' into "
                                    + " the @MatsClassMapping combined state/@Service class '"
                                    + classNameWithoutPackage(matsClass) + "' upon invocation of Mats Stage.", e);
                        }
                    }
                };

                Runnable clearFields = () -> {
                    // :: Null out the template fields
                    for (Entry<Field, Object> entry : templateFields.entrySet()) {
                        Field templateField = entry.getKey();
                        try {
                            templateField.set(state, null);
                        }
                        catch (IllegalAccessException e) {
                            throw new MatsSpringInvocationTargetException("Didn't manage to null \"template field\" '"
                                    + templateField.getName() + "'.", e);
                        }
                    }

                    // :: Null out the ProcessContext
                    if (processContextField != null) {
                        try {
                            processContextField.set(state, null);
                        }
                        catch (IllegalAccessException e) {
                            throw new MatsSpringInvocationTargetException("Didn't manage to null the ProcessContext '"
                                    + processContextField.getName() + "'.", e);
                        }
                    }
                };

                // :: Proxy the ProcessContext to handle nulling and re-setting the fields upon API-traversals
                ProcessContextWrapper<Object> wrappedProcessContext = new ProcessContextWrapper<Object>(helperCast(
                        originalProcessContext)) {

                    @Override
                    public MessageReference request(String endpointId, Object requestDto) {
                        clearFields.run();
                        MessageReference ref = super.request(endpointId, requestDto);
                        setFields.accept(this);
                        return ref;
                    }

                    @Override
                    public MessageReference reply(Object replyDto) {
                        clearFields.run();
                        MessageReference ref = super.reply(replyDto);
                        setFields.accept(this);
                        return ref;
                    }

                    @Override
                    public MessageReference next(Object nextDto) {
                        clearFields.run();
                        MessageReference ref = super.next(nextDto);
                        setFields.accept(this);
                        return ref;
                    }
                };

                // :: Make the invocation
                // .. first set the fields
                setFields.accept(wrappedProcessContext);
                // .. do the invocation
                Object o = invokeMatsLambdaMethod(matsClassMapping, method, state, defaultArgsArray,
                        processContextParamIdx, wrappedProcessContext,
                        dtoParamIdx, incomingDto,
                        -1, null);
                // .. then clear fields before going out of lambda.
                clearFields.run();

                // ?: Is this the last stage, and we're not in a "Terminator", void-returning endpoint?
                if ((method == lastStageMethod) && (replyClass != void.class)) {
                    // -> Yes, this is the last stage - so return whatever the invocation came up with.
                    originalProcessContext.reply(helperCast(o));
                }
            });

            // Set origin "debug info":
            stage.getStageConfig().setOrigin("@Stage(" + stageAnnotation.ordinal() + ") "
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(..);"
                    + method.getDeclaringClass().getName());

            // Set concurrency, if set
            if (concurrencyForStage > 0) {
                stage.getStageConfig().setConcurrency(concurrencyForStage);
            }
        });
        // This endpoint is finished set up.
        ep.finishSetup();
        log.info(LOG_PREFIX + "Processed Mats Class Mapped Endpoint by @MatsClassMapping-annotated bean '"
                + classNameWithoutPackage(bean) + "'.");
    }

    private boolean shouldWeIgnoreThis(MatsFactory matsFactoryToUse, String matsClassMapping) {
        if (_ignoreQualifiersAndDoubleRegistrations) {
            // ?: Have we already seen this endpointId on this MatsFactory?
            if (matsFactoryToUse.getEndpoint(matsClassMapping).isPresent()) {
                // -> Yes, we've already seen this endpointId on this MatsFactory, so we'll just ignore this.
                log.info(LOG_PREFIX + "In TESTING mode: Ignoring double registration of endpointId '"
                        + matsClassMapping + "' on MatsFactory [" + matsFactoryToUse.getFactoryConfig()
                        .getName() + "], as we've already seen this endpointId on this MatsFactory.");
                return true;
            }
        }
        return false;
    }

    /**
     * Find which parameter is the DTO on a method in a @MatsClassMapping service.
     */
    private int findDtoParamIndexForMatsClassMappingLambdaMethod(Method method) {
        Parameter[] parameters = method.getParameters();
        int dtoParamPos = -1;

        // ?: Special case for 1 param: If not ProcessContext, then it can be non-annotated DTO parameter.
        if (parameters.length == 1) {
            // If the param is ProcessContext, then it is not the DTO.
            return parameters[0].getType() == ProcessContext.class ? -1 : 0;
        }

        // ?: Special case for 2 params: Can be one ProcessContext and one non-annotated DTO parameter.
        if (parameters.length == 2) {
            // ?: Is the first param ProcessContext?
            if (parameters[0].getType() == ProcessContext.class) {
                // -> Yes, first param is processContext, check the other (second)
                // If the param is ProcessContext, then it is not the DTO.
                return parameters[1].getType() == ProcessContext.class ? -1 : 1;
            }
            // -> No: ?: Is the second param ProcessContext?
            else if (parameters[1].getType() == ProcessContext.class) {
                // -> Yes, second param is processContext, check the other (first)
                // If the param is ProcessContext, then it is not the DTO.
                return parameters[0].getType() == ProcessContext.class ? -1 : 0;
            }
            // E-> Neither of the params is ProcessContext, go for default search
        }

        // :: Find a parameter annotated with @Dto
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getAnnotation(Dto.class) != null) {
                if (dtoParamPos != -1) {
                    throw new MatsSpringConfigException("More than one parameter of method '"
                            + simpleMethodDescription(method) + "' is annotated with @Dto");
                }
                dtoParamPos = i;
            }
        }
        return dtoParamPos;
    }

    /**
     * Creates an array with arguments for the supplied method which will invoke without {@link NullPointerException} -
     * the problem being that primitive types cannot be null: Null are not coerced into the default value for the
     * primitive as one might could think.
     */
    Object[] defaultArgsArray(Method method) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Object defaultArg = null;
            if (parameters[i].getType() == boolean.class) {
                defaultArg = false;
            }
            else if (parameters[i].getType() == byte.class) {
                defaultArg = (byte) 0;
            }
            else if (parameters[i].getType() == short.class) {
                defaultArg = (short) 0;
            }
            else if (parameters[i].getType() == int.class) {
                defaultArg = 0;
            }
            else if (parameters[i].getType() == long.class) {
                defaultArg = 0L;
            }
            else if (parameters[i].getType() == float.class) {
                defaultArg = 0f;
            }
            else if (parameters[i].getType() == double.class) {
                defaultArg = 0d;
            }
            args[i] = defaultArg;
        }
        return args;
    }

    /**
     * Helper for invoking the Method that constitute the Mats process-lambdas for @MatsMapping and @MatsClassMapping.
     */
    private static Object invokeMatsLambdaMethod(Annotation matsAnnotation, Method method, Object bean,
            Object[] templateDefaultArgsArray,
            int processContextParamIdx, ProcessContext<?> processContext,
            int dtoParamIdx, Object dto,
            int stoParamIdx, Object sto)
            throws MatsRefuseMessageException {
        Object[] args = templateDefaultArgsArray.clone();
        if (processContextParamIdx != -1) {
            args[processContextParamIdx] = processContext;
        }
        if (dtoParamIdx != -1) {
            args[dtoParamIdx] = dto;
        }
        if (stoParamIdx != -1) {
            args[stoParamIdx] = sto;
        }
        try {
            return method.invoke(bean, args);
        }
        catch (IllegalAccessException | IllegalArgumentException e) {
            throw new MatsRefuseMessageException("Problem with invoking "
                    + simpleAnnotationAndMethodDescription(matsAnnotation, method) + ".", e);
        }
        catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof MatsRefuseMessageException) {
                throw (MatsRefuseMessageException) e.getTargetException();
            }
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            }
            throw new MatsSpringInvocationTargetException("Got InvocationTargetException when invoking "
                    + simpleAnnotationAndMethodDescription(matsAnnotation, method) + ".", e);
        }
    }

    /**
     * Insane cast helper. <i>Please... help. me.</i>.
     */
    @SuppressWarnings("unchecked")
    private static <R> R helperCast(Object objectToCast) {
        return (R) objectToCast;
    }

    /**
     * @param concurrencySpecifier
     *            the 'concurrency()' value from the Mats annotation.
     * @return the value to use for concurrency, or -1 if the concurrency should be left unspecified.
     */
    private int getConcurrencyToUse(String forWhat, String concurrencySpecifier) {
        if ("".equals(concurrencySpecifier)) {
            return -1;
        }
        int concurrency;
        try {
            concurrency = Integer.parseInt(concurrencySpecifier);
        }
        catch (NumberFormatException e) {
            throw new MatsSpringConfigException("Not a valid integer: The concurrency specifier ["
                    + concurrencySpecifier + "] for [" + forWhat + "] is not an integer.");
        }
        if (concurrency < 0) {
            throw new MatsSpringConfigException("Negative concurrency: The concurrency specifier ["
                    + concurrencySpecifier + "] for [" + forWhat + "] is negative - not allowed.");
        }
        return concurrency;
    }

    private MatsFactory getMatsFactoryToUse(String forWhat, AnnotatedElement annotatedElement,
            Class<? extends Annotation> aeCustomQualifierType,
            String aeQualifierValue, String aeBeanName) {

        // :: First check whether we're in the testing mode.
        // ?: Are we in the testing mode?
        if (_ignoreQualifiersAndDoubleRegistrations) {
            // -> Yes, testing mode: Just get the MatsFactory straight from the Spring context.
            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "TESTING MODE: Getting MatsFactory straight form Spring"
                    + " context, ignoring any qualifiers if present.");
            return getMatsFactoryUnspecified(forWhat);
        }
        // E-> No, not testing mode - resolve the MatsFactory based on the qualifiers, if present.

        List<MatsFactory> specifiedMatsFactories = new ArrayList<>();

        int numberOfQualifications = 0;

        @SuppressWarnings("deprecation") // Supporting Spring 4 still
        Annotation[] annotations = AnnotationUtils.getAnnotations(annotatedElement);
        for (Annotation annotation : annotations) {
            // ?: Is this a @Qualifier
            if (annotation.annotationType() == Qualifier.class) {
                // -> Yes, @Qualifier - so get the correct MatsFactory by Qualifier.value()
                Qualifier qualifier = (Qualifier) annotation;
                specifiedMatsFactories.add(getMatsFactoryByQualifierValue(forWhat, qualifier.value()));
                numberOfQualifications++;
            }
            // ?: Is this a custom qualifier annotation, meta-annotated with @Qualifier?
            else {
                @SuppressWarnings("deprecation") // Supporting Spring 4 still
                boolean annotationMetaPresent = AnnotationUtils
                        .isAnnotationMetaPresent(annotation.annotationType(), Qualifier.class);
                if (annotationMetaPresent) {
                    // -> Yes, @Qualifier-meta-annotated annotation - get the correct MatsFactory also annotated with
                    // this
                    specifiedMatsFactories.add(getMatsFactoryByCustomQualifier(forWhat, annotation.annotationType(),
                            annotation));
                    numberOfQualifications++;
                }
            }
        }

        // Was the annotation element 'matsFactoryCustomQualifierType' specified?
        if (aeCustomQualifierType != Annotation.class) {
            specifiedMatsFactories.add(getMatsFactoryByCustomQualifier(forWhat, aeCustomQualifierType, null));
            numberOfQualifications++;
        }

        // ?: Was the annotation element 'matsFactoryQualifierValue' specified?
        if (!"".equals(aeQualifierValue)) {
            specifiedMatsFactories.add(getMatsFactoryByQualifierValue(forWhat, aeQualifierValue));
            numberOfQualifications++;
        }

        // ?: Was the annotation element 'matsFactoryBeanName' specified?
        if (!"".equals(aeBeanName)) {
            specifiedMatsFactories.add(getMatsFactoryByBeanName(forWhat, aeBeanName));
            numberOfQualifications++;
        }

        // ?: Was more than one qualification style in use?
        if (numberOfQualifications > 1) {
            // -> Yes, and that's an ambiguous qualification, which we don't like.
            throw new BeanCreationException("When trying to get specific MatsFactory for " + forWhat + " based on"
                    + " @Mats..-annotation properties; and @Qualifier-annotations and custom qualifier annotations on"
                    + " the @Mats..-annotated method, we found that there was more than one qualification style"
                    + " present. Check your specifications on the element [" + annotatedElement + "].");
        }

        // ?: Did the specific MatsFactory logic end up with more than one MatsFactory?
        if (specifiedMatsFactories.size() > 1) {
            // -> Yes, and that's an ambiguous qualification, which we don't like.
            throw new BeanCreationException("When trying to get specific MatsFactory for " + forWhat + " based on"
                    + " @Mats..-annotation properties; and @Qualifier-annotations and custom qualifier annotations on"
                    + " the @Mats..-annotated method, we ended up with more than one MatsFactory."
                    + " Check your specifications on the element [" + annotatedElement + "].");
        }
        // ?: If there was one specified MatsFactory, use that, otherwise find any MatsFactory (fails if more than one).
        MatsFactory matsFactory;
        String matsFactoryName;
        if (specifiedMatsFactories.size() == 1) {
            matsFactory = specifiedMatsFactories.get(0);
            matsFactoryName = _matsFactoriesToName.get(matsFactory);
        }
        else {
            matsFactory = getMatsFactoryUnspecified(forWhat);
            matsFactoryName = "<unspecified/default>";
        }

        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + ".. using MatsFactory [" + matsFactoryName
                + "]: [" + matsFactory + "].");

        return matsFactory;
    }

    /**
     * Using lazy getting of MatsFactory, as else we can get a whole heap of <i>"Bean of type is not eligible for
     * getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying)"</i> situations.
     */
    private MatsFactory getMatsFactoryUnspecified(String forWhat) {
        try {
            return _configurableApplicationContext.getBean(MatsFactory.class);
        }
        catch (NoUniqueBeanDefinitionException e) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there was MULTIPLE MatsFactories available"
                    + " in the Spring ApplicationContext - You must specify which one to use: Using props on the"
                    + " @Mats..-annotation itself (read its JavaDoc); or further annotate the @Mats..-annotated method"
                    + " with a @Qualifier(value), which either matches the same @Qualifier(value)-annotation on a"
                    + " MatsFactory bean, or where the 'value' matches the bean name of a MatsFactory; or annotate both"
                    + " the @Mats..-annotated method and the MatsFactory @Bean factory method with the same custom"
                    + " annotation which is meta-annotated with @Qualifier; or mark one (and only one) of the"
                    + " MatsFactories as @Primary", e);
        }
        catch (NoSuchBeanDefinitionException e) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there is NO MatsFactory available in the"
                    + " Spring ApplicationContext", e);
        }
    }

    private final Map<String, MatsFactory> _cache_MatsFactoryByBeanName = new HashMap<>();

    private MatsFactory getMatsFactoryByBeanName(String forWhat, String beanName) {
        // :: Cache lookup
        MatsFactory matsFactory = _cache_MatsFactoryByBeanName.get(beanName);
        if (matsFactory != null) {
            return matsFactory;
        }
        // E-> Didn't find in cache, see if we can find it now.
        try {
            Object bean = _configurableApplicationContext.getBean(beanName);
            if (!(bean instanceof MatsFactory)) {
                throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for "
                        + forWhat + ", " + this.getClass().getSimpleName() + " found that the @Mats..-annotation"
                        + " specified Spring bean '" + beanName + "' is not of type MatsFactory");
            }
            // Cache, and return
            _cache_MatsFactoryByBeanName.put(beanName, (MatsFactory) bean);
            return (MatsFactory) bean;
        }
        catch (NoSuchBeanDefinitionException e) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there is no MatsFactory with the name '"
                    + beanName + "' available in the Spring ApplicationContext", e);
        }
    }

    private final Map<String, MatsFactory> _cache_MatsFactoryByQualifierValue = new HashMap<>();

    private MatsFactory getMatsFactoryByQualifierValue(String forWhat, String qualifierValue) {
        // :: Cache lookup
        MatsFactory matsFactory = _cache_MatsFactoryByQualifierValue.get(qualifierValue);
        if (matsFactory != null) {
            return matsFactory;
        }
        // E-> Didn't find in cache, see if we can find it now.
        try {
            matsFactory = BeanFactoryAnnotationUtils.qualifiedBeanOfType(_configurableListableBeanFactory,
                    MatsFactory.class, qualifierValue);
            // Cache, and return
            _cache_MatsFactoryByQualifierValue.put(qualifierValue, matsFactory);
            return matsFactory;
        }
        catch (NoUniqueBeanDefinitionException e) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there was MULTIPLE MatsFactories available"
                    + " in the Spring ApplicationContext with the qualifier value '" + qualifierValue + "', this is"
                    + " probably not what you want.", e);
        }
        catch (NoSuchBeanDefinitionException e) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there is NO MatsFactory with the qualifier"
                    + " value '" + qualifierValue + "' available in the Spring ApplicationContext", e);
        }
    }

    private final Map<Class<? extends Annotation>, Map<Annotation, MatsFactory>> _cache_MatsFactoryByCustomQualifier = new HashMap<>();

    /**
     * @param customQualifierType
     *            must be non-null.
     * @param customQualifier
     *            If != null, then it must be .equals() with the one on the bean, otherwise it is enough that the type
     *            matches.
     * @return the matched bean - if more than one bean matches, {@link BeanCreationException} is raised.
     */
    private MatsFactory getMatsFactoryByCustomQualifier(String forWhat, Class<? extends Annotation> customQualifierType,
            Annotation customQualifier) {
        // :: Cache lookup - notice how this handles the situation where customQualifier (instance, not type) is null.
        Map<Annotation, MatsFactory> subCacheMap = _cache_MatsFactoryByCustomQualifier.get(customQualifierType);
        if (subCacheMap != null) {
            MatsFactory matsFactory = subCacheMap.get(customQualifier);
            if (matsFactory != null) {
                log.debug("Found cached MatsFactory with CustomAnnotationType [" + customQualifierType
                        + "], custom annotation instance [" + customQualifier + "].");
                return matsFactory;
            }
        }
        // E-> Didn't find in cache, see if we can find it now.

        // :: First find all beans that are annotated with the custom qualifier and are of type MatsFactory.
        // (This should really not exist, as it would mean that you've extended e.g. JmsMatsFactory and annotated that,
        // which is really not something I am advocating.)
        // (A strange effect observed when running "all tests in all modules found by regexp" in IntelliJ is that his
        // method of finding beans with specific annotation actually /do/ return @Bean factory methods. This is not
        // observed when running each module's tests by itself. I do not understand the difference.. But we thus need
        // to de-duplicate the resulting beans between this method of finding beans, and the next one up. Doing this by
        // using Set<String[BeanName]> instead of List.)
        String[] beanNamesWithCustomQualifierClass = _configurableListableBeanFactory.getBeanNamesForAnnotation(
                customQualifierType);
        Set<String> annotatedBeanNames = Arrays.stream(beanNamesWithCustomQualifierClass)
                .filter(beanName -> {
                    // These beans HAVE the custom qualifier type present, so this method will not return null.
                    Annotation annotationOnBean = _configurableListableBeanFactory.findAnnotationOnBean(beanName,
                            customQualifierType);
                    // If the custom qualifier instance is not specified, then it is enough that the type matches.
                    // If specified, the annotation instance must be equal to the one on the bean.
                    return customQualifier == null || customQualifier.equals(annotationOnBean);
                })
                .collect(Collectors.toSet());

        // :: Then find all MatsFactories created by @Bean factory methods, which are annotated with custom qualifier
        // (This is pretty annoyingly not a feature that is easily available in Spring proper, AFAIK)
        String[] beanDefinitionNames = _configurableListableBeanFactory.getBeanDefinitionNames();
        for (String beanDefinitionName : beanDefinitionNames) {
            BeanDefinition beanDefinition = _configurableListableBeanFactory.getBeanDefinition(beanDefinitionName);
            // ?: Is this an AnnotatedBeanDefinition, thus might be a bean created by a factory method (e.g. @Bean)
            if (beanDefinition instanceof AnnotatedBeanDefinition) {
                // -> Yes, AnnotatedBeanDefinition.
                // ?: Is it a factory method?
                MethodMetadata factoryMethodMetadata = ((AnnotatedBeanDefinition) beanDefinition)
                        .getFactoryMethodMetadata();
                if (factoryMethodMetadata != null) {
                    // -> Yes, factory method
                    // ?: Can we introspect that method?
                    if (!(factoryMethodMetadata instanceof StandardMethodMetadata)) {
                        // -> No, so just skip it after logging.
                        log.warn(LOG_PREFIX + "AnnotatedBeanDefinition.getFactoryMethodMetadata() returned a"
                                + " MethodMetadata which is not of type StandardMethodMetadata - therefore cannot run"
                                + " getIntrospectedMethod() on it to find annotations on the factory method."
                                + " AnnotatedBeanDefinition: [" + beanDefinition + "],"
                                + " MethodMetadata: [" + factoryMethodMetadata + "]");
                        continue;
                    }
                    StandardMethodMetadata factoryMethodMetadata_Standard = (StandardMethodMetadata) factoryMethodMetadata;

                    Method introspectedMethod = factoryMethodMetadata_Standard.getIntrospectedMethod();
                    Annotation[] annotations = introspectedMethod.getAnnotations();
                    for (Annotation annotation : annotations) {
                        // If the annotation instance is not specified, then it is enough that the type matches.
                        // If specified, the annotation instance must be equal to the one on the bean.
                        if (((customQualifier == null) && (annotation.annotationType() == customQualifierType))
                                || annotation.equals(customQualifier)) {
                            annotatedBeanNames.add(beanDefinitionName);
                        }
                    }
                }
            }
        }

        // :: Map over from BeanNames to actual Beans, filter away any beans not MatsFactory (e.g. @MatsClassMapping)
        List<MatsFactory> matsFactories = annotatedBeanNames.stream()
                .map(beanName -> _configurableListableBeanFactory.getBean(beanName))
                .filter(bean -> bean instanceof MatsFactory)
                .map(matsFactory -> (MatsFactory) matsFactory)
                .collect(Collectors.toList());

        // :: Assert that we only got /one/ matching MatsFactory - not zero, not several
        String qualifierString = (customQualifier != null ? customQualifier.toString()
                : customQualifierType.getSimpleName());
        if (matsFactories.size() > 1) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there was MULTIPLE (" + matsFactories.size()
                    + ") MatsFactories available in the Spring ApplicationContext with the custom qualifier annotation"
                    + " '" + qualifierString + "', this is probably not what you want.");
        }
        if (matsFactories.isEmpty()) {
            throw new BeanCreationException("When trying to perform Spring-based MATS Endpoint creation for " + forWhat
                    + ", " + this.getClass().getSimpleName() + " found that there is NO MatsFactory with the custom"
                    + " qualifier annotation '" + qualifierString + "' available in the Spring ApplicationContext");
        }

        // :: Cache, and return
        _cache_MatsFactoryByCustomQualifier.computeIfAbsent(customQualifierType, $ -> new HashMap<>())
                .put(customQualifier, matsFactories.get(0));
        return matsFactories.get(0);
    }

    /**
     * Thrown if the setup of a Mats Spring endpoint fails.
     */
    public static class MatsSpringConfigException extends RuntimeException {
        public MatsSpringConfigException(String message, Throwable cause) {
            super(message, cause);
        }

        public MatsSpringConfigException(String message) {
            super(message);
        }
    }

    /**
     * Thrown if the invocation of a {@link MatsMapping @MatsMapping} or {@link MatsEndpointSetup @MatsEndpointSetup}
     * annotated method raises {@link InvocationTargetException} and the underlying exception is not a
     * {@link RuntimeException}.
     */
    public static class MatsSpringInvocationTargetException extends RuntimeException {
        public MatsSpringInvocationTargetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String originForMethod(Annotation annotation, Method method) {
        return "@" + annotation.annotationType().getSimpleName() + " " + method.getDeclaringClass().getSimpleName()
                + "." + method.getName() + "(..);" + method.getDeclaringClass().getName();
    }

    private static String classNameWithoutPackage(Object object) {
        if (object == null) {
            return "<null instance>";
        }
        return classNameWithoutPackage(object.getClass()) + '@' + Integer.toHexString(System.identityHashCode(object));
    }

    private static String classNameWithoutPackage(Class<?> clazz) {
        if (clazz == null) {
            return "<null class>";
        }
        if (clazz == Void.TYPE) {
            return "void";
        }
        if (clazz.isPrimitive()) {
            return clazz.toString();
        }
        String typeName = ClassUtils.getUserClass(clazz).getTypeName();
        String packageName = clazz.getPackage().getName();
        return typeName.replace(packageName + ".", "");
    }

    private static String simpleMethodDescription(Method method) {
        return method.getReturnType().getSimpleName() + ' ' + classNameWithoutPackage(method.getDeclaringClass())
                + '.' + method.getName() + "(..)";
    }

    private static String simpleAnnotationAndMethodDescription(Annotation annotation, Method method) {
        return "@" + annotation.annotationType().getSimpleName() + "-annotated method '"
                + simpleMethodDescription(method) + '\'';
    }
}
