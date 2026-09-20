package com.lucky.agent.workflow.exception;

/** 工作流模块统一异常（定义非法、编译失败、执行失败等）。 */
public class WorkflowException extends RuntimeException {

    private final String code;

    public WorkflowException(String message) {
        super(message);
        this.code = "WORKFLOW_ERROR";
    }

    public WorkflowException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.code = "WORKFLOW_ERROR";
    }

    public String getCode() {
        return code;
    }
}
