package com.lucky.agent.common.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 微服务演进预留标记（D21 演进预案）。
 *
 * <p>标注在跨模块接口上，值为未来 RPC 服务名。本阶段为无操作标记，演进阶段才被 RPC 框架识别，
 * 保证演进时业务代码零改。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Remote {

    /** 未来 RPC 服务名。 */
    String serviceName();
}
