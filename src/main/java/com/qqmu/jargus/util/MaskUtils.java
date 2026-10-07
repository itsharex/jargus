package com.qqmu.jargus.util;

/**
 * 敏感信息展示脱敏工具：收敛 MailSenderConfigService / RemoteAuthConfigService
 * 中各自复制的密钥脱敏实现。入参为 CryptoUtil 密文，内部解密后按「前4 + **** + 后4」展示。
 */
public final class MaskUtils {

    private MaskUtils() {
    }

    /**
     * 脱敏加密的密钥/密码：明文长度 ≤8 一律 ********；解密失败同样返回 ********；
     * null/空返回 null（无此凭据，前端显示空）。
     */
    public static String secret(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        try {
            String plain = CryptoUtil.decrypt(encrypted);
            if (plain.length() <= 8) {
                return "********";
            }
            return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "********";
        }
    }
}
