package com.bytefuture.toolnest.features.filehash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希计算核心逻辑。
 *
 * <p>与界面解耦，便于独立验证。要点：
 * <ul>
 *   <li><b>流式读取</b>：文件按固定大小缓冲区分块喂给 {@link MessageDigest}，
 *       不会把整个文件读进内存 —— 校验几百 MB 的安装包时这一点是必须的</li>
 *   <li><b>大小写统一</b>：默认输出小写十六进制（与 {@code sha256sum}、
 *       {@code certutil} 等命令行工具一致），避免比对时因大小写不一致误判</li>
 * </ul>
 */
public final class HashOps {

    private HashOps() {
    }

    /** 流式读取的缓冲区大小，64KB 在多数文件系统上是吞吐与内存的平衡点。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 支持的算法。 */
    public enum Algorithm {
        MD5("MD5", "速度最快，仅用于校验完整性，不可用于安全场景"),
        SHA1("SHA-1", "已不推荐用于签名，校验场景仍常见"),
        SHA256("SHA-256", "推荐默认，安全性与性能平衡"),
        SHA512("SHA-512", "更高的安全强度，摘要更长");

        private final String label;
        private final String note;

        Algorithm(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }

        /** JCA 中该算法的标准名称。 */
        public String jcaName() {
            return switch (this) {
                case MD5 -> "MD5";
                case SHA1 -> "SHA-1";
                case SHA256 -> "SHA-256";
                case SHA512 -> "SHA-512";
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 单个文件的计算结果。 */
    public record FileHash(String fileName, long sizeBytes, String digest) {

        /** 人类可读的文件大小。 */
        public String readableSize() {
            return humanSize(sizeBytes);
        }
    }

    /**
     * 计算字符串的摘要。
     *
     * @param text      源文本，null 视为空串
     * @param algorithm 算法
     * @param upper     是否输出大写十六进制
     */
    public static String ofText(String text, Algorithm algorithm, boolean upper) {
        MessageDigest digest = newDigest(algorithm);
        byte[] bytes = (text == null ? "" : text)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return format(digest.digest(bytes), upper);
    }

    /**
     * 计算文件的摘要（流式，内存占用恒定）。
     *
     * @param path      文件路径
     * @param algorithm 算法
     * @param upper     是否输出大写十六进制
     * @throws IOException 文件不存在或读取失败
     */
    public static FileHash ofFile(Path path, Algorithm algorithm, boolean upper)
            throws IOException {
        MessageDigest digest = newDigest(algorithm);
        long size;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                total += read;
            }
            size = total;
        }
        String fileName = path.getFileName() == null ? path.toString()
                : path.getFileName().toString();
        return new FileHash(fileName, size, format(digest.digest(), upper));
    }

    private static MessageDigest newDigest(Algorithm algorithm) {
        try {
            return MessageDigest.getInstance(algorithm.jcaName());
        } catch (NoSuchAlgorithmException ex) {
            // 标准 JDK 必然支持这些算法，走到这里说明运行环境异常
            throw new IllegalStateException(
                    "当前运行环境不支持 " + algorithm.jcaName(), ex);
        }
    }

    private static String format(byte[] digest, boolean upper) {
        String hex = HexFormat.of().formatHex(digest);
        return upper ? hex.toUpperCase() : hex;
    }

    /** 字节数转人类可读形式。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s", value, units[unitIndex]);
    }
}
