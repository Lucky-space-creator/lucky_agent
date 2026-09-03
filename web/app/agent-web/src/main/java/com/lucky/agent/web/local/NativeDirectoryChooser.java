package com.lucky.agent.web.local;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileSystemView;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本机原生目录选择框（AWT/Swing）。
 *
 * <p>浏览器受沙箱限制无法唤起系统目录选择器，只能由本机后端进程代劳：本类是唯一入口，
 * 仅用于「工作空间路径选择」这类本机交互场景，不参与任何 Agent 业务编排。</p>
 *
 * <p>模态对话框是<b>阻塞式</b>API，调用方必须放到 {@code Schedulers.boundedElastic()} 上执行，
 * 严禁在 Reactor Netty 事件循环线程直接调用（该线程被 Reactor 明令禁止阻塞）。</p>
 *
 * <p>headless 环境（无图形桌面的服务器）无法弹框，直接抛业务异常由前端回退为手工填写。</p>
 */
@Slf4j
public final class NativeDirectoryChooser {

    private NativeDirectoryChooser() {
    }

    /**
     * 弹出「选择目录」模态对话框并等待用户操作。
     *
     * @param startPath 初始目录（可空；不存在时回退用户主目录）
     * @param title     对话框标题（可空）
     * @return 选中的目录绝对路径；用户取消或关闭对话框返回空
     * @throws IllegalStateException 当前为 headless 环境，或对话框创建失败
     */
    public static Optional<String> choose(String startPath, String title) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境无图形界面，无法打开目录选择框，请手动填写路径");
        }
        JFileChooser chooser = new JFileChooser(resolveStart(startPath), FileSystemView.getFileSystemView());
        chooser.setDialogTitle(title == null || title.isBlank() ? "选择目录" : title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        return showOnEdt(chooser);
    }

    /** 对话框必须在 AWT 事件派发线程上显示，非 EDT 调用方需切线程等待结果。 */
    private static Optional<String> showOnEdt(JFileChooser chooser) {
        if (EventQueue.isDispatchThread()) {
            return readSelection(chooser);
        }
        AtomicReference<Optional<String>> result = new AtomicReference<>(Optional.empty());
        try {
            EventQueue.invokeAndWait(() -> result.set(readSelection(chooser)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("打开目录选择框失败", cause);
            throw new IllegalStateException("打开目录选择框失败：" + cause.getMessage());
        }
        return result.get();
    }

    /** 显示对话框并读取选择结果。 */
    private static Optional<String> readSelection(JFileChooser chooser) {
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }
        File selected = chooser.getSelectedFile();
        return selected == null ? Optional.empty() : Optional.of(selected.getAbsolutePath());
    }

    /** 初始目录解析：空或不存在时回退用户主目录。 */
    private static File resolveStart(String startPath) {
        if (startPath != null && !startPath.isBlank()) {
            try {
                Path path = Paths.get(startPath.trim()).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    return path.toFile();
                }
            } catch (Exception e) {
                log.debug("初始目录无效，回退用户主目录：{}", startPath, e);
            }
        }
        return FileSystemView.getFileSystemView().getHomeDirectory();
    }
}
