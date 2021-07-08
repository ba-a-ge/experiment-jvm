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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.jojoti.grpcstartersb.GRpcScope;
import io.github.jojoti.grpcstartersb.ScopeServerInterceptor;
import io.grpc.*;

import java.util.List;

/**
 * 访问控制和用户会话拦截器
 * 拦截器需要 使用者 自行添加到 grpc server 里面去，多server无法知道用户注入哪里
 * https://stackoverflow.com/questions/68164315/access-message-request-in-first-grpc-interceptor-before-headers-in-second-grpc-i
 *
 * @author JoJo Wang
 * @link github.com/jojoti
 */
class RAMInterceptor implements ScopeServerInterceptor {

    private final RAMAccessInterceptor ramAccessInterceptor;
    private final GRpcRAMProperties gRpcRAMProperties;

    private ImmutableList<MethodDescriptor<?, ?>> allowAnonymous;
    private ImmutableMap<MethodDescriptor<?, ?>, RAM> rams;
    private GRpcScope currentGRpcScope;

    RAMInterceptor(RAMAccessInterceptor ramAccessInterceptor, GRpcRAMProperties gRpcRAMProperties) {
        this.ramAccessInterceptor = ramAccessInterceptor;
        this.gRpcRAMProperties = gRpcRAMProperties;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        // 允许匿名访问的接口不校验 权限
        if (this.allowAnonymous.contains(call.getMethodDescriptor())) {
            return next.startCall(call, headers);
        }

        final var foundRam = rams.get(call.getMethodDescriptor());
        if (foundRam != null) {
            try {
                // 判断用户是否登陆
                var isLogin = this.ramAccessInterceptor.checkSession(this.currentGRpcScope, foundRam, call, headers, next);
                if (isLogin) {
                    // 权限不足
                    final var error = Status.fromCode(Status.UNAUTHENTICATED.getCode()).withDescription("Auth failed, please check session");
                    call.close(error, new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                // 可以从 metadata 获取 ip 啥的
                var rs = this.ramAccessInterceptor.checkAccess(this.currentGRpcScope, foundRam, call, headers, next);
                if (rs != null) {
                    return rs;
                }
            } catch (Exception e) {
                final var error = Status.fromCode(Status.INTERNAL.getCode()).withDescription("info:" + e.getMessage());
                call.close(error, new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
        }

        return RAMAccessInterceptor.newDefaultPermissionDenied(call);
    }

    @Override
    public List<String> getScopes() {
        return this.gRpcRAMProperties.enableScopeNames();
    }

    @Override
    public void aware(GRpcScope currentGRpcScope, ImmutableList<BindableService> servicesEvent) {
        this.currentGRpcScope = currentGRpcScope;

        this.addAllowAnonymous(servicesEvent);

        var found = ServiceDescriptorAnnotations.getAnnotationMaps(servicesEvent, RAM.class, true);
        var builder = ImmutableMap.<MethodDescriptor<?, ?>, RAM>builder();
        for (var entry : found.entrySet()) {
            builder.put(entry.getKey(), entry.getValue());
        }
        this.rams = builder.build();
        this.ramAccessInterceptor.onRegister(currentGRpcScope, servicesEvent);
    }

    private void addAllowAnonymous(List<BindableService> servicesEvent) {
        var foundAllowAnonymous = ServiceDescriptorAnnotations.getAnnotationMaps(servicesEvent, RAMAllowAnonymous.class, false);
        var builder = ImmutableList.<MethodDescriptor<?, ?>>builder();
        for (var entry : foundAllowAnonymous.entrySet()) {
            builder.add(entry.getKey());
        }
        this.allowAnonymous = builder.build();
    }

    @Override
    public ScopeServerInterceptor cloneThis() {
        try {
            return (ScopeServerInterceptor) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

}