package io.github.jojoti.grpcstatersb;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 全局 grpc  所有的 scope 都会添加上
 *
 * @author JoJo Wang
 * @link github.com/jojoti
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface GRpcGlobalInterceptor {

    GRpcScope scope() default @GRpcScope;

}
