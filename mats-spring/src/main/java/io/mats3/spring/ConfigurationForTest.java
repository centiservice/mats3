package io.mats3.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Configuration;

/**
 * An "alias" for the @Configuration annotation which is meant to be used on tests' configuration classes - which then
 * is excluded from component scanning if the component scan is using {@link ComponentScanExcludingConfigurationForTest}
 * instead of the ordinary <code>ComponentScan</code>. Read more at {@link ComponentScanExcludingConfigurationForTest}.
 * 
 * @see ComponentScanExcludingConfigurationForTest
 * @author Endre Stølsvik 2019-08-12 22:38 - http://stolsvik.com/, endre@stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Configuration // meta-annotation
public @interface ConfigurationForTest {}
