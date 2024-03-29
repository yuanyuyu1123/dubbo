/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.spring.api.DemoService;
import org.apache.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationTestConfiguration;
import org.apache.dubbo.config.spring.context.annotation.consumer.test.TestConsumerConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
/**
 * {@link EnableDubbo} Test
 *
 * @since 2.5.8
 */
class EnableDubboTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    public void setUp() {
        context = new AnnotationConfigApplicationContext();
        DubboBootstrap.reset();
    }

    @AfterEach
    public void tearDown() {
        context.close();
        DubboBootstrap.reset();
    }

    @Test
    void testConsumer() {

        context.register(TestProviderConfiguration.class, TestConsumerConfiguration.class);
        context.refresh();

        TestConsumerConfiguration consumerConfiguration = context.getBean(TestConsumerConfiguration.class);

        DemoService demoService = consumerConfiguration.getDemoService();
        String value = demoService.sayName("Mercy");
        Assertions.assertEquals("Hello,Mercy", value);

        DemoService autowiredDemoService = consumerConfiguration.getAutowiredDemoService();
        Assertions.assertEquals("Hello,Mercy", autowiredDemoService.sayName("Mercy"));

        DemoService autowiredReferDemoService = consumerConfiguration.getAutowiredReferDemoService();
        Assertions.assertEquals("Hello,Mercy", autowiredReferDemoService.sayName("Mercy"));

        TestConsumerConfiguration.Child child = context.getBean(TestConsumerConfiguration.Child.class);

        // From Child

        demoService = child.getDemoServiceFromChild();

        Assertions.assertNotNull(demoService);

        value = demoService.sayName("Mercy");

        Assertions.assertEquals("Hello,Mercy", value);

        // From Parent

        demoService = child.getDemoServiceFromParent();

        Assertions.assertNotNull(demoService);

        value = demoService.sayName("Mercy");

        Assertions.assertEquals("Hello,Mercy", value);

        // From Ancestor

        demoService = child.getDemoServiceFromAncestor();

        Assertions.assertNotNull(demoService);

        value = demoService.sayName("Mercy");

        Assertions.assertEquals("Hello,Mercy", value);

        // Test my-registry2 bean presentation
        RegistryConfig registryConfig = context.getBean("my-registry", RegistryConfig.class);

        // Test multiple binding
        Assertions.assertEquals("N/A", registryConfig.getAddress());

    }

    @EnableDubbo(scanBasePackages = "org.apache.dubbo.config.spring.context.annotation.provider")
    @ComponentScan(basePackages = "org.apache.dubbo.config.spring.context.annotation.provider")
    @PropertySource("classpath:/META-INF/dubbo-provider.properties")
    @Import(ServiceAnnotationTestConfiguration.class)
    @EnableTransactionManagement
    public static class TestProviderConfiguration {

        @Primary
        @Bean
        public PlatformTransactionManager platformTransactionManager() {
            return new PlatformTransactionManager() {

                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                    return null;
                }

                @Override
                public void commit(TransactionStatus status) throws TransactionException {

                }

                @Override
                public void rollback(TransactionStatus status) throws TransactionException {

                }
            };
        }
    }


}
