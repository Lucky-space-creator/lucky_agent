package com.lucky.agent.web.controller;

import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.model.api.dto.ModelRouterStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型路由配置查询接口。
 */
@RestController
@RequestMapping("/api/models/router")
public class ModelRouterController {

    private final ModelRouter modelRouter;

    public ModelRouterController(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    /** 路由配置快照（主/备端点、探活状态）。 */
    @GetMapping("/status")
    public ModelRouterStatus status() {
        return modelRouter.status();
    }
}
