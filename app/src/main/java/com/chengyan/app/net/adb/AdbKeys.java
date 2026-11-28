package com.chengyan.app.net.adb;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * 无线调试 ADB 密钥（RFC：adbkey PKCS8 PEM + 自签 X509 PEM）。
 * 首次使用按装安装生成，持久在 filesDir/adb/ 下；配对时证书公钥会被写入设备 adb_keys 白名单。
 */
public final class AdbKeys {
    private static final Object LOCK = new Object();
    private static PrivateKey cachedKey;
    private static String cachedCertPem;

    private AdbKeys() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "adb");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 生成（如缺）并返回 PKCS8 PEM 私钥。 */
    public static String privateKeyPem(Context ctx) {
        ensure(ctx);
        return cachedKey == null ? null : pem("PRIVATE KEY", java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cachedKey.getEncoded()));
    }

    /** 自签证书 PEM（与私钥同对）。 */
    public static String certPem(Context ctx) {
        ensure(ctx);
        return cachedCertPem;
    }

    public static PrivateKey privateKey(Context ctx) { ensure(ctx); return cachedKey; }

    /** 公钥指纹（显示给用户，可与 adb 设备白名单核对）。 */
    public static String fingerprint(Context ctx) {
        try {
            ensure(ctx);
            byte[] h = java.security.MessageDigest.getInstance("MD5").digest(cachedCertPem.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02X", b)).append(":");
            sb.setLength(sb.length() - 1);
            return sb.toString();
        } catch (Exception e) { return "-"; }
    }

    private static void ensure(Context ctx) {
        synchronized (LOCK) {
            if (cachedKey != null) return;
            try {
                File kf = new File(dir(ctx), "adbkey");
                File cf = new File(dir(ctx), "adbcert.pem");
                if (kf.isFile() && cf.isFile()) {
                    cachedKey = loadPkcs8(new String(java.nio.file.Files.readAllBytes(kf.toPath()), "UTF-8"));
                    cachedCertPem = new String(java.nio.file.Files.readAllBytes(cf.toPath()), "UTF-8");
                    return;
                }
                KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
                kg.initialize(2048);
                KeyPair kp = kg.generateKeyPair();
                X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                        new org.bouncycastle.asn1.x500.X500Name("CN=chengyan"),
                        BigInteger.valueOf(System.currentTimeMillis()),
                        new Date(System.currentTimeMillis() - 86_400_000L),
                        new Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L),
                        new org.bouncycastle.asn1.x500.X500Name("CN=chengyan"),
                        kp.getPublic());
                X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                        b.build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate())));
                String certPem = pem("CERTIFICATE", java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded()));
                FileOutputStream fo = new FileOutputStream(kf);
                fo.write(pem("PRIVATE KEY", java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())).getBytes("UTF-8"));
                fo.close();
                PrintWriter pw = new PrintWriter(new FileOutputStream(cf), false);
                pw.print(certPem); pw.flush(); pw.close();
                cachedKey = kp.getPrivate();
                cachedCertPem = certPem;
            } catch (Exception e) {
                throw new RuntimeException("ADB 密钥生成失败：" + e.getMessage(), e);
            }
        }
    }

    private static String pem(String tag, String b64) {
        return "-----BEGIN " + tag + "-----\n" + b64 + "\n-----END " + tag + "-----\n";
    }

    private static PrivateKey loadPkcs8(String pem) throws Exception {
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(b64)));
    }
}
