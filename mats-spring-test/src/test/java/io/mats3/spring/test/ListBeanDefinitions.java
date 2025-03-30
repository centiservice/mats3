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

package io.mats3.spring.test;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardMethodMetadata;

/**
 * @author Endre Stølsvik 2019-05-20 00:01 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ListBeanDefinitions {
    private static final Logger log = LoggerFactory.getLogger(ListBeanDefinitions.class);
    public static void listAllBeansDefinitions(ConfigurableListableBeanFactory configurableListableBeanFactory) {
        log.info("BeanDefinitionCount: " + configurableListableBeanFactory.getBeanDefinitionCount());
        String[] beanDefinitionNames = configurableListableBeanFactory.getBeanDefinitionNames();
        for (String beanDefinitionName : beanDefinitionNames) {
            BeanDefinition beanDefinition = configurableListableBeanFactory.getBeanDefinition(beanDefinitionName);
            log.info("BeanDefinitionName: " + beanDefinitionName + ": BeanDefinition TYPE: " + beanDefinition.getClass()
                    .getSimpleName() + ": [" + beanDefinition + "]");
            String[] attributeNames = beanDefinition.attributeNames();
            log.info("  \\- AttributeNames: " + Arrays.asList(attributeNames));
            String[] dependsOn = beanDefinition.getDependsOn();
            if (dependsOn != null) {
                log.info("  \\ - dependsOn: " + Arrays.asList(dependsOn));
            }
            log.info("  \\ - factoryMethod: " + beanDefinition.getFactoryMethodName());
            log.info("  \\ - factoryBean: " + beanDefinition.getFactoryBeanName());

            // ?: Is this an AnnotatedBeanDefinition
            if (beanDefinition instanceof AnnotatedBeanDefinition) {
                MethodMetadata factoryMethodMetadata = ((AnnotatedBeanDefinition) beanDefinition)
                        .getFactoryMethodMetadata();
                if (factoryMethodMetadata != null) {
                    if (!(factoryMethodMetadata instanceof StandardMethodMetadata)) {
                        throw new IllegalStateException("The FactoryMethodMetadata found is not of type"
                                + " StandardMethodMetadata - thus cannot run getIntrospectedMethod() on it,"
                                + " BeanDefinition: [" + beanDefinition + "].");
                    }
                    StandardMethodMetadata factoryMethodMetadata_Standard = (StandardMethodMetadata) factoryMethodMetadata;

                    log.info("  |- Classname: " + factoryMethodMetadata.getReturnTypeName() + ".");
                    Method introspectedMethod = factoryMethodMetadata_Standard.getIntrospectedMethod();
                    log.info("  \\- Method@: " + introspectedMethod);
                    log.info("       - annotations:["+Arrays.asList(introspectedMethod.getAnnotations())+"]");
                }
                AnnotationMetadata metadata = ((AnnotatedBeanDefinition) beanDefinition)
                        .getMetadata();
                if (metadata != null) {
                    log.info("  \\- AnnotationTypes: [" + metadata.getAnnotationTypes() + "]");
                }
            }
        }
    }
}
