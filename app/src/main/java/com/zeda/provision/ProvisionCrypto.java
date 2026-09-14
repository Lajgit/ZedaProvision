package com.zeda.provision;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 配网协议 v2 的凭据加密和设备回执校验。 */
final class ProvisionCrypto {

    static final int PROTOCOL_VERSION = 2;

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_FIELD_BYTES = 4096;

    private ProvisionCrypto() {
    }

    static EncryptedCredentials encryptCredentials(
            String secret,
            String configUuid,
            long timestamp,
            String ssid,
            String password
    ) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(deriveSessionKey(secret, configUuid), "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(buildAad(configUuid, timestamp));

        byte[] encrypted = cipher.doFinal(encodeCredentials(ssid, password));
        return new EncryptedCredentials(
                encodeBase64Url(iv),
                encodeBase64Url(encrypted));
    }

    static Credentials decryptCredentials(
            String secret,
            String configUuid,
            long timestamp,
            String ivBase64,
            String ciphertextBase64
    ) throws GeneralSecurityException {
        byte[] iv = decodeBase64Url(ivBase64);
        if (iv.length != GCM_IV_BYTES) {
            throw new GeneralSecurityException("配网加密 IV 长度非法");
        }

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(deriveSessionKey(secret, configUuid), "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(buildAad(configUuid, timestamp));
        return decodeCredentials(cipher.doFinal(decodeBase64Url(ciphertextBase64)));
    }

    static String signAcknowledgement(
            String secret,
            String configUuid,
            String deviceNo,
            String status,
            long timestamp
    ) throws GeneralSecurityException {
        String text = PROTOCOL_VERSION
                + "|" + requireText(configUuid, "configUuid")
                + "|" + requireText(deviceNo, "deviceNo")
                + "|" + requireText(status, "status")
                + "|" + timestamp;
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(deriveSessionKey(secret, configUuid), HMAC_SHA256));
        return encodeBase64Url(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean verifyAcknowledgement(
            String secret,
            String configUuid,
            String deviceNo,
            String status,
            long timestamp,
            String signature
    ) throws GeneralSecurityException {
        byte[] expected = decodeBase64Url(signAcknowledgement(
                secret,
                configUuid,
                deviceNo,
                status,
                timestamp));
        byte[] actual = decodeBase64Url(signature);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] deriveSessionKey(
            String secret,
            String configUuid
    ) throws GeneralSecurityException {
        String material = requireText(secret, "secret")
                + "\n"
                + requireText(configUuid, "configUuid");
        return MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildAad(String configUuid, long timestamp) {
        String value = PROTOCOL_VERSION
                + "|"
                + requireText(configUuid, "configUuid")
                + "|"
                + timestamp;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeCredentials(String ssid, String password) {
        byte[] ssidBytes = safe(ssid).getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = safe(password).getBytes(StandardCharsets.UTF_8);
        if (ssidBytes.length > MAX_FIELD_BYTES || passwordBytes.length > MAX_FIELD_BYTES) {
            throw new IllegalArgumentException("配网字段过长");
        }

        return ByteBuffer.allocate(8 + ssidBytes.length + passwordBytes.length)
                .putInt(ssidBytes.length)
                .put(ssidBytes)
                .putInt(passwordBytes.length)
                .put(passwordBytes)
                .array();
    }

    private static Credentials decodeCredentials(byte[] plaintext)
            throws GeneralSecurityException {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(plaintext);
            if (buffer.remaining() < 8) {
                throw new GeneralSecurityException("配网凭据数据不完整");
            }

            int ssidLength = buffer.getInt();
            if (ssidLength < 0 || ssidLength > MAX_FIELD_BYTES
                    || buffer.remaining() < ssidLength + 4) {
                throw new GeneralSecurityException("配网 SSID 长度非法");
            }
            byte[] ssid = new byte[ssidLength];
            buffer.get(ssid);

            int passwordLength = buffer.getInt();
            if (passwordLength < 0 || passwordLength > MAX_FIELD_BYTES
                    || buffer.remaining() != passwordLength) {
                throw new GeneralSecurityException("配网密码长度非法");
            }
            byte[] password = new byte[passwordLength];
            buffer.get(password);

            return new Credentials(
                    new String(ssid, StandardCharsets.UTF_8),
                    new String(password, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Throwable e) {
            throw new GeneralSecurityException("解析配网凭据失败", e);
        }
    }

    private static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64Url(String value) throws GeneralSecurityException {
        try {
            return Base64.getUrlDecoder().decode(requireText(value, "base64"));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Base64Url 数据非法", e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class EncryptedCredentials {
        final String iv;
        final String ciphertext;

        EncryptedCredentials(String iv, String ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    static final class Credentials {
        final String ssid;
        final String password;

        Credentials(String ssid, String password) {
            this.ssid = ssid;
            this.password = password;
        }
    }
}
