package com.lucky.agent.model.config;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本地设置密钥加密器（D8：API Key 禁止明文写盘）。
 *
 * <p>落盘格式：{@code aesgcm:&lt;base64(iv + ciphertext)&gt;}。密钥为首次运行时随机生成的
 * 32 字节主密钥（存于 {@code <frameworkRoot>/.settings-key}，本机单用户私有文件），
 * AES-GCM 认证加密保证密文完整性。未以 {@code aesgcm:} 前缀开头的存量明文值视为
 * 历史遗留，按原样透传（下次保存时自动迁移为密文）。</p>
 *
 * <p>密钥文件丢失即无法解密旧配置（本地工具的固有取舍），加载时对无法解密的 Key
 * 返回 {@code null}，端点视为未配置，不影响其余配置读取。</p>
 */
@Slf4j
public class SettingsKeyCipher {

    /** 密文前缀：识别已加密值，与明文（历史遗留）区分。 */
    private static final String PREFIX = "aesgcm:";

    private static final int IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final int KEY_BYTES = 32;

    private final Path keyFile;
    private volatile byte[] masterKey;

    public SettingsKeyCipher(Path keyFile) {
        this.keyFile = keyFile;
    }

    /**
     * 加密明文 Key；{@code null}/{@code blank}/已加密值原样返回。
     *
     * @param plain 明文 Key
     * @return 加密后的密文字符串（含前缀）
     */
    public String encryptIfNeeded(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        if (plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] key = masterKey();
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.warn("API Key 加密失败（按原样保存，后续会重试迁移）：{}", e.getMessage());
            return plain;
        }
    }

    /**
     * 解密 Key；非密文（历史明文）原样返回，解密失败返回 {@code null}（端点视为未配置）。
     *
     * @param token 密文（含前缀）或历史明文
     * @return 明文 Key
     */
    public String decryptIfNeeded(String token) {
        if (token == null || token.isBlank() || !token.startsWith(PREFIX)) {
            return token;
        }
        try {
            byte[] key = masterKey();
            byte[] raw = Base64.getDecoder().decode(token.substring(PREFIX.length()));
            if (raw.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[raw.length - IV_LEN];
            System.arraycopy(raw, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败（端点视为未配置）：{}", e.getMessage());
            return null;
        }
    }

    /** 主密钥：首次访问时若密钥文件缺失则生成并落盘（用户本机私有）。 */
    private byte[] masterKey() throws IOException {
        byte[] k = masterKey;
        if (k == null) {
            synchronized (this) {
                k = masterKey;
                if (k == null) {
                    k = loadOrCreateKey();
                    masterKey = k;
                }
            }
        }
        return k;
    }

    private byte[] loadOrCreateKey() throws IOException {
        if (keyFile != null && Files.exists(keyFile)) {
            byte[] raw = Files.readAllBytes(keyFile);
            if (raw.length >= 16) {
                return trim(raw);
            }
        }
        byte[] random = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(random);
        if (keyFile != null) {
            Files.createDirectories(keyFile.getParent());
            Files.write(keyFile, random);
        }
        return random;
    }

    private byte[] trim(byte[] raw) {
        return raw.length == KEY_BYTES ? raw : java.util.Arrays.copyOf(raw, KEY_BYTES);
    }
}