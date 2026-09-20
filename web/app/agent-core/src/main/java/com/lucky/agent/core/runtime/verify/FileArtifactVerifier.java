package com.lucky.agent.core.runtime.verify;

import com.lucky.agent.core.runtime.contract.Artifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件产物验证器（客观）：校验执行产出的文件是否真实落盘。
 *
 * <p>识别 {@link VerificationRequest#evidence()} 中两种形式的产物声明：</p>
 * <ul>
 *   <li>{@code files}：{@code List<String>} 路径；</li>
 *   <li>{@code artifacts}：{@code List<Artifact>}（取 {@link Artifact#ref()}）。</li>
 * </ul>
 * 未提供任何产物声明时返回 {@code null}（不表态），交由链上下一个验证器，
 * 这是「客观优先、缺失即降级」得以成立的关键。</p>
 */
public class FileArtifactVerifier implements Verifier {

    @Override
    public String name() {
        return "file-artifact";
    }

    @Override
    public boolean objective() {
        return true;
    }

    @Override
    public VerificationOutcome verify(VerificationRequest request) {
        List<String> paths = collectPaths(request);
        if (paths.isEmpty()) {
            return null; // 无产物声明：不表态
        }
        List<String> evidence = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String path : paths) {
            boolean exists = false;
            try {
                exists = Files.exists(Path.of(path));
            } catch (RuntimeException e) {
                // 非法路径视为不存在
                exists = false;
            }
            evidence.add(path + (exists ? " (exists)" : " (missing)"));
            if (!exists) {
                missing.add(path);
            }
        }
        if (missing.isEmpty()) {
            return VerificationOutcome.done("声明的 " + paths.size() + " 个产物文件均存在", evidence);
        }
        return new VerificationOutcome(false, 1.0, true, evidence,
                "产物文件缺失: " + missing, request.goal());
    }

    private List<String> collectPaths(VerificationRequest request) {
        List<String> paths = new ArrayList<>();
        Object files = request.evidence().get("files");
        if (files instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(s -> !s.isBlank()).forEach(paths::add);
        }
        Object artifacts = request.evidence().get("artifacts");
        if (artifacts instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Artifact artifact && artifact.ref() != null && !artifact.ref().isBlank()) {
                    paths.add(artifact.ref());
                }
            }
        }
        return paths;
    }
}
