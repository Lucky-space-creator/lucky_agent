package com.lucky.agent.cli;

import com.lucky.agent.cli.channel.OutputSink;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

import java.util.Map;

/**
 * CLI 启动入口。
 *
 * <p>复用同一内核、配置、记忆与执行臂（决策 D11/D20）：组件扫描覆盖 {@code com.lucky.agent}，
 * 业务装配与 Web 通道一致，只是交互外壳从浏览器换成终端。不启动 Web 容器。</p>
 *
 * <p><b>为什么先解析参数、再启动 Spring</b>：{@code --help} / {@code --version} 不该付出
 * 上下文装配的代价（本内核启动约 3 秒）。参数解析是纯 CPU 操作，放在前面能让这两个高频
 * 命令瞬间返回。</p>
 *
 * <p><b>为什么不把参数转交给 Spring</b>：Spring 会把 {@code --xxx=yyy} 形式的参数当作配置属性
 * 注入 Environment（例如 {@code --plain} 会变成属性 {@code plain}）。CLI 参数与 Spring 配置
 * 是两个语义域，混在一起会让「这个值到底谁在读」变得不可控，故 {@code app.run()} 不传参数。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lucky.agent")
public class CliApplication {

    public static void main(String[] args) {
        // 第一件事：把 System.out / System.err 换成强制 UTF-8 的单例流。
        // 必须早于 Spring 启动 —— logback 的 ConsoleAppender 在 start() 时就捕获 System.err 引用，
        // 之后再换流会让「日志」与「我们的输出」握两个 PrintStream 写同一个 FileDescriptor，
        // 出现半行交错乱码。这里先换，日志与 CLI 输出就共用同一条 UTF-8 流。
        OutputSink.utf8Stdout();
        OutputSink.utf8Stderr();

        CliOptions options = new CliOptions();
        CommandLine cmd = new CommandLine(options);

        try {
            cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            System.err.println(e.getMessage());
            cmd.usage(System.err);
            System.exit(CliExitCode.USAGE);
            return;
        }
        if (options.help) {
            cmd.usage(System.out);
            System.exit(CliExitCode.SUCCESS);
            return;
        }
        if (options.version) {
            System.out.println("lucky-agent-cli " + CliOptions.VERSION);
            System.exit(CliExitCode.SUCCESS);
            return;
        }

        // 关闭 Web 容器：CLI 是纯终端进程（决策 D11）
        System.setProperty("spring.main.web-application-type", "none");
        // 编码：即使调用方没有 -Dfile.encoding=UTF-8，也保证中文按 UTF-8 处理
        if (System.getProperty("file.encoding") == null
                || !System.getProperty("file.encoding").toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
            System.setProperty("file.encoding", "UTF-8");
        }

        SpringApplication app = new SpringApplication(CliApplication.class);
        app.setDefaultProperties(Map.of(
                // 启动横幅会写 stdout，污染管道输出与首屏
                "spring.main.banner-mode", "off",
                // 日志默认只留 WARN+：TERMINAL 处于行编辑状态时，任何一行 INFO 日志都会冲掉提示符
                "logging.level.root", "WARN",
                // 日志不带 ANSI：headless 重定向到文件时不应混入转义序列
                "spring.output.ansi.enabled", "never"));

        ConfigurableApplicationContext ctx;
        try {
            ctx = app.run();
        } catch (Exception e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(CliExitCode.FAILURE);
            return;
        }

        int code;
        try {
            code = ctx.getBean(CliRunner.class).run(options);
        } catch (Exception e) {
            System.err.println("运行失败：" + e.getMessage());
            code = CliExitCode.FAILURE;
        } finally {
            try {
                ctx.close();
            } catch (Exception ignored) {
                // 关闭失败不影响退出码
            }
        }
        System.exit(code);
    }
}
