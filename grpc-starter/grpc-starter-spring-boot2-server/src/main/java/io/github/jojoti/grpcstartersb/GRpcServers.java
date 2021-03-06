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

package io.github.jojoti.grpcstartersb;

import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import io.github.jojoti.grpcstartersb.autoconfigure.GRpcServerProperties;
import io.github.jojoti.utildaemonthreads.DaemonThreads;
import io.github.jojoti.utilguavaext.GetAddress;
import io.grpc.*;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * rfs:
 * https://github.com/spring-projects/spring-boot/blob/v2.5.1/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/embedded/EmbeddedWebServerFactoryCustomizerAutoConfiguration.java
 * https://github.com/spring-projects/spring-boot/blob/v2.5.1/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/reactive/context/WebServerStartStopLifecycle.java
 * https://github.com/spring-projects/spring-boot/blob/v2.5.1/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/reactive/context/WebServerManager.java
 * <p>
 * ?????? spring boot ??? ???????????? ??????????????? server
 * ??? spring boot ????????????????????? spring context ????????? grpc
 *
 * @author JoJo Wang
 * @link github.com/jojoti
 */
public class GRpcServers implements SmartLifecycle, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(GRpcServers.class);

    private final GRpcServerProperties gRpcServerProperties;

    private ImmutableList<MultiServer> servers;
    private ApplicationContext applicationContext;

    private volatile DaemonThreads daemonThreads;

    public GRpcServers(GRpcServerProperties gRpcServerProperties) {
        this.gRpcServerProperties = gRpcServerProperties;
    }

    @Override
    public void start() {
        Preconditions.checkArgument(this.gRpcServerProperties.getServers() != null && this.gRpcServerProperties.getServers().size() > 0, "Servers is not allow empty");

        log.info("Starting gRPC Server ...");
        // ??????????????? grpc server ????????????
        // ????????????????????????????????? ???????????? spring @Order ????????????
        final var allGlobalInterceptors = applicationContext.getBeansWithAnnotation(GRpcGlobalInterceptor.class)
                .values()
                .stream()
                .map(c -> (ServerInterceptor) c)
                .collect(Collectors.toUnmodifiableList());

        // ?????? handler
        final var allScopeHandlers = applicationContext.getBeansWithAnnotation(GRpcScopeService.class);

        // ?????????????????????
        final var scopeHandlers = Multimaps.<GRpcScope, BindableService>newMultimap(Maps.newHashMap(), Lists::newArrayList);

        for (Object value : allScopeHandlers.values()) {
            // check impl BindableService
            Preconditions.checkArgument(value instanceof BindableService, "Annotation @GRpcScopeService class must be instance of io.grpc.BindableService");

            // ???????????????
            final var foundScope = AnnotationUtils.findAnnotation(value.getClass(), GRpcScopeService.class).scope();

            scopeHandlers.put(foundScope, (BindableService) value);
        }

        // ?????????????????????
        final var scopeInterceptors = Multimaps.<GRpcScope, ServerInterceptor>newMultimap(Maps.newHashMap(), Lists::newArrayList);

        final var allScopeInterceptors = applicationContext.getBeansWithAnnotation(GRpcScopeGlobalInterceptor.class);
        for (Object value : allScopeInterceptors.values()) {
            Preconditions.checkArgument(value instanceof ServerInterceptor, "Annotation @GRpcScopeService class must be instance of io.grpc.ServerInterceptor");
            // ???????????????
            final var foundScope = AnnotationUtils.findAnnotation(value.getClass(), GRpcScopeGlobalInterceptor.class).scope();
            scopeInterceptors.put(foundScope, (ServerInterceptor) value);
        }

        final var serverBuilders = Lists.<ServerBuilders>newArrayList();

        final var services = Multimaps.<GRpcScope, BindableService>newListMultimap(Maps.newHashMap(), Lists::newArrayList);
        // ????????????????????? ?????? ?????? ?????????

        final var scopeInterceptorUtils = new DynamicScopeFilterUtils();

        for (Map.Entry<GRpcScope, Collection<BindableService>> entry : scopeHandlers.asMap().entrySet()) {
            // ?????? scopeName ????????????
            final var config = getServerConfigByScopeName(entry.getKey().value());
            final var newServerBuilder = getServerBuilder(config);

            // ???????????? ??? api ????????? ?????? grpc ??????
            // ????????????: io/github/jojoti/grpcstartersbram/SessionInterceptor.java:72
            this.applicationContext.publishEvent(new GrpcServerBuilderCreateEvent(new GrpcServerBuilderCreate(entry.getKey(), newServerBuilder)));
            // newServerBuilder

            final HealthStatusManager health = config.getHealthStatus().isEnabled() ? new HealthStatusManager() : null;

            if (health != null) {
                log.info("GRPC scopeName {} add health service", entry.getKey().value());
                // ???????????? ?????? service
                newServerBuilder.addService(health.getHealthService());
            }

            // ?????????????????? service
            for (BindableService bindableService : entry.getValue()) {
                final var foundGRpcServiceInterceptors = bindableService.getClass().getAnnotation(GRpcServiceInterceptors.class);

                // ?????????????????????????????????

                // ???????????? service ??????????????????
                if (foundGRpcServiceInterceptors != null && foundGRpcServiceInterceptors.interceptors().length > 0) {
                    final var foundInterceptors = Lists.<ServerInterceptor>newArrayList();
                    for (var interceptor : foundGRpcServiceInterceptors.interceptors()) {
                        final var findDefinedInterceptorBean = applicationContext.getBean(interceptor);
                        // ?????????????????? bean ????????????
                        Preconditions.checkNotNull(findDefinedInterceptorBean, "Class " + interceptor + " ioc bean not found");
                        foundInterceptors.add(scopeInterceptorUtils.addCheck(entry.getKey(), findDefinedInterceptorBean));
                    }
                    ServerInterceptors.intercept(bindableService, foundInterceptors);
                }

                if (foundGRpcServiceInterceptors == null || foundGRpcServiceInterceptors.applyScopeGlobalInterceptors()) {
                    // ?????????????????????
                    final var foundScopeInterceptors = scopeInterceptors.get(entry.getKey());
                    if (foundScopeInterceptors != null && foundScopeInterceptors.size() > 0) {
                        for (ServerInterceptor foundScopeInterceptor : foundScopeInterceptors) {
                            // grpc ?????????????????????????????????
                            newServerBuilder.intercept(scopeInterceptorUtils.addCheck(entry.getKey(), foundScopeInterceptor));
                        }
                    }
                }

                if (foundGRpcServiceInterceptors == null || foundGRpcServiceInterceptors.applyGlobalInterceptors()) {
                    for (ServerInterceptor allGlobalInterceptor : allGlobalInterceptors) {
                        if (allGlobalInterceptor instanceof ScopeServerInterceptor) {
                            // ????????????????????? scope ????????????????????????
                            // ???????????????????????????????????? ?????? tag
                            Preconditions.checkNotNull(((ScopeServerInterceptor) allGlobalInterceptor).getScopes());
                            for (String scope : ((ScopeServerInterceptor) allGlobalInterceptor).getScopes()) {
                                if (entry.getKey().value().equals(scope)) {
                                    newServerBuilder.intercept(scopeInterceptorUtils.addCheck(entry.getKey(), allGlobalInterceptor));
                                    break;
                                }
                            }
                        } else {
                            // ????????????????????????
                            // grpc ?????????????????????????????????
                            newServerBuilder.intercept(scopeInterceptorUtils.addCheck(entry.getKey(), allGlobalInterceptor));
                        }
                    }
                }
                newServerBuilder.addService(bindableService);
                if (health != null) {
                    health.setStatus(bindableService.bindService().getServiceDescriptor().getName(), HealthCheckResponse.ServingStatus.SERVING);
                }
                // ??????????????? apis
                services.put(entry.getKey(), bindableService);
            }

            serverBuilders.add(new ServerBuilders(newServerBuilder, health, entry.getKey().value(), config));
            log.info("GRPC scopeName {} add new builder", entry.getKey().value());
        }

        for (var server : this.gRpcServerProperties.getServers().entrySet()) {
            var exists = false;
            for (ServerBuilders serverBuilder : serverBuilders) {
                if (server.getKey().equals(serverBuilder.scopeName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw new IllegalArgumentException("Scope " + server.getKey() + " exists, but no handler is configured");
            }
        }

        if (serverBuilders.size() != scopeHandlers.asMap().size() || serverBuilders.size() != this.gRpcServerProperties.getServers().size()) {
            // ?????????server???????????? ?????? bean????????????????????????
            throw new IllegalArgumentException("Config error, please check config or annotation");
        }

        // ?????? message ??? ????????? ????????? ????????? bean ?????????????????????
        for (var entry : scopeInterceptorUtils.getRef().asMap().entrySet()) {
            var found = services.get(entry.getKey());
            Preconditions.checkNotNull(found);
            for (ScopeServerInterceptor scopeServerInterceptor : entry.getValue()) {
                scopeServerInterceptor.aware(entry.getKey(), ImmutableList.copyOf(found));
            }
        }

        final var daemon = DaemonThreads.newDaemonThreads(this.gRpcServerProperties.getServers().size(),
                "Multi grpc server awaiter", (handler, e) -> {
                    log.error("E: {}", handler, e);
                });

        final var serversBuilder = ImmutableList.<MultiServer>builder();

        for (var serverBuilder : serverBuilders) {
            try {
                daemon.startThreads(serverBuilder.scopeName, () -> {
                    final var server = serverBuilder.serverBuilder.build().start();
                    // ?????????????????? consul ??? ??????????????? event
                    log.info("GRPC Server {} started, listening on port {}", serverBuilder.scopeName, server.getPort());
                    serversBuilder.add(new MultiServer(server, serverBuilder.healthStatusManager, serverBuilder.scopeName, serverBuilder.config));
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        this.daemonThreads = daemon;
        this.servers = serversBuilder.build();

        // fixme ???????????????????????????
//        this.applicationContext.publishEvent(new ScopeServicesEvent(new ScopeServicesEventEntities(services)));
    }

    @Override
    public void stop() {
        log.info("grpc server stopping...");
        if (this.servers != null) {
            for (var server : this.servers) {
                this.daemonThreads.downThreads(server.scopeName, () -> {
                    if (server.healthStatusManager != null) {
                        for (ServerServiceDefinition service : server.server.getServices()) {
                            server.healthStatusManager.clearStatus(service.getServiceDescriptor().getName());
                        }
                    }
                    server.server.shutdown();
                    server.server.awaitTermination(server.config.getShutdownGracefullyMills(), TimeUnit.MILLISECONDS);
                    log.info("gRPC server {} stopped", server.scopeName);
                });
            }
            this.servers = null;
            log.info("gRPC server all stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return this.daemonThreads != null && this.daemonThreads.isHealth();
    }

    private GRpcServerProperties.ServerItem getServerConfigByScopeName(String scopeName) {
        for (var gRpcServerPropertiesServer : this.gRpcServerProperties.getServers().entrySet()) {
            if (gRpcServerPropertiesServer.getKey().equals(scopeName)) {
                return gRpcServerPropertiesServer.getValue();
            }
        }
        throw new IllegalArgumentException("ScopeName " + scopeName + " not found, please you check config or annotation");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private ServerBuilder<?> getServerBuilder(GRpcServerProperties.ServerItem serverItem) {
        // fixme ?????? netty netty shared
        return NettyServerBuilder.forAddress(GetAddress.getSocketAddress(serverItem.getAddress()));
    }

    private static final class MultiServer {
        final Server server;
        final HealthStatusManager healthStatusManager;
        final String scopeName;
        final GRpcServerProperties.ServerItem config;

        MultiServer(Server server, HealthStatusManager healthStatusManager, String scopeName, GRpcServerProperties.ServerItem config) {
            this.server = server;
            this.healthStatusManager = healthStatusManager;
            this.scopeName = scopeName;
            this.config = config;
        }
    }

    private static final class ServerBuilders {
        final ServerBuilder<?> serverBuilder;
        final HealthStatusManager healthStatusManager;
        final String scopeName;
        final GRpcServerProperties.ServerItem config;

        ServerBuilders(ServerBuilder<?> serverBuilder, HealthStatusManager healthStatusManager, String scopeName, GRpcServerProperties.ServerItem config) {
            this.serverBuilder = serverBuilder;
            this.healthStatusManager = healthStatusManager;
            this.scopeName = scopeName;
            this.config = config;
        }
    }

    /**
     * ?????? scope ??? clone ??????
     */
    private static final class DynamicScopeFilterUtils {

        // ?????????????????? ?????? scope
        private final Multimap<ScopeServerInterceptor, GRpcScope> ref = Multimaps.newSetMultimap(Maps.newHashMap(), Sets::newHashSet);
        private final Multimap<GRpcScope, ScopeServerInterceptor> newRef = Multimaps.newSetMultimap(Maps.newHashMap(), Sets::newHashSet);

        // a -> B inter
        // b -> B inter
        // c -> B inter
        // d -> C inter
        // f -> C inter

        // =>
        //
        public ServerInterceptor addCheck(GRpcScope scope, ServerInterceptor object) {
            if (object instanceof ScopeServerInterceptor) {
                var serverInterceptor = (ScopeServerInterceptor) object;
                // ??? scope ?????????
                if (serverInterceptor.getScopes().contains(scope.value())) {
                    // ?????????????????? ????????? ???????????????
                    var founds = ref.get(serverInterceptor);
                    if (founds == null || founds.size() == 0) {
                        newRef.put(scope, serverInterceptor);
                        ref.put(serverInterceptor, scope);
                    } else if (!founds.contains(scope)) {
                        var newObject = serverInterceptor.cloneThis();
                        newRef.put(scope, newObject);
                        ref.put(serverInterceptor, scope);
                        return newObject;
                    }
                }
            }
            return object;
        }

        public Multimap<GRpcScope, ScopeServerInterceptor> getRef() {
            return newRef;
        }

    }

}
