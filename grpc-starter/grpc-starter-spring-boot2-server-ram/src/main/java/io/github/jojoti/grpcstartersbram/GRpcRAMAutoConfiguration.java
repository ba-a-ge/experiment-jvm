/*
 * Copyright 2021 JoJo Wang , homepage: https://github.com/jojoti/experiment-jvm.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jojoti.grpcstartersbram;

import io.github.jojoti.grpcstartersb.GRpcGlobalInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * rfs:
 * https://stackoverflow.com/questions/48840190/spring-expression-language-java-8-foreach-or-stream-on-list
 *
 * @author JoJo Wang
 * @link github.com/jojoti
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GRpcRAMProperties.class)
// grpc server 正常启动
// https://www.cnblogs.com/zhuxudong/p/10295077.html
// https://blog.csdn.net/liujianyangbj/article/details/108810726
// https://www.jianshu.com/p/d4cb052ebfc2
// https://stackoverflow.com/questions/55330646/conditionalonexpression-if-property-is-present
// 读取 bean
//@ConditionaCoExpression("#{@GRpcRAMProperties}.enabled()")
//@ConditionalOnExpression("T(io.github.jojoti.grpcstartersbram.GRpcRAMProperties).enable('${grpcs}')")
//@ConditionalOnExpression("T(io.github.jojoti.grpcstartersbram.GRpcRAMProperties).enable('${grpcs}')")
@Conditional(GRpcRAMAutoConfiguration.EnableRam.class)
public class GRpcRAMAutoConfiguration {

    // 默认注入拒绝访问
    @Bean
    @ConditionalOnMissingBean(RAMAccessInterceptor.class)
    public RAMAccessInterceptor ramAccess() {
        return new RAMAccessInterceptorDeny();
    }

    @Bean
    @GRpcGlobalInterceptor
    @Order(1)
    public RAMInterceptor ramInterceptor(RAMAccessInterceptor ramAccessInterceptor, GRpcRAMProperties gRpcRAMProperties) {
        return new RAMInterceptor(ramAccessInterceptor, gRpcRAMProperties);
    }

    static final class EnableRam implements Condition {

        private static final Bindable<Map<String, GRpcRAMProperties.RAMItem>> STRING_LIST = Bindable.mapOf(String.class, GRpcRAMProperties.RAMItem.class);

        /**
         * rf:
         *
         * @see org.springframework.boot.autoconfigure.condition.OnPropertyListCondition
         */
        @Override
        public boolean matches(ConditionContext conditionContext, AnnotatedTypeMetadata annotatedTypeMetadata) {
            var found = Binder.get(conditionContext.getEnvironment()).bind("grpcs.servers", STRING_LIST);
            if (found.isBound()) {
//                var foundRam = false;
                for (GRpcRAMProperties.RAMItem ramItem : found.get().values()) {
                    if (ramItem.getRam().isEnabled()) {
//                        foundRam = true;
                        // 动态 注入 bean
//                        conditionContext.getRegistry().registerBeanDefinition(RAMInterceptor.class.getName() + "-" + ramItem.getScopeName(),
//                                BeanDefinitionBuilder.genericBeanDefinition(RAMInterceptor.class)
//                                        .setScope(ramItem.getScopeName())
//                                        .getBeanDefinition());
                        // fixme 动态注入 bean 后续有需求待定
                        // 动态注入 bean 的唯一 好处就是权限要 判断 a scope 的权限不用去 查询 b scope 的 配置
                        return true;
                    }
                }
//                return foundRam;
            }
            return false;
        }

    }

}
