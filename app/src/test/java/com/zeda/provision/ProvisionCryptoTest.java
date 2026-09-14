package com.zeda.provision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Base64;

public class ProvisionCryptoTest {

    private static final String SECRET = "test-secret";
    private static final String CONFIG_UUID = "0123456789abcdef";
    private static final long TIMESTAMP = 123456789L;

    @Test
    public void encryptDecryptPreservesCredentials() throws Exception {
        ProvisionCrypto.EncryptedCredentials encrypted =
                ProvisionCrypto.encryptCredentials(
                        SECRET,
                        CONFIG_UUID,
                        TIMESTAMP,
                        "门店 Wi-Fi",
                        "密码12345678");

        ProvisionCrypto.Credentials decrypted = ProvisionCrypto.decryptCredentials(
                SECRET,
                CONFIG_UUID,
                TIMESTAMP,
                encrypted.iv,
                encrypted.ciphertext);

        assertEquals("门店 Wi-Fi", decrypted.ssid);
        assertEquals("密码12345678", decrypted.password);
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        ProvisionCrypto.EncryptedCredentials encrypted =
                ProvisionCrypto.encryptCredentials(
                        SECRET,
                        CONFIG_UUID,
                        TIMESTAMP,
                        "ZB",
                        "12345678");

        byte[] tamperedBytes = Base64.getUrlDecoder().decode(encrypted.ciphertext);
        tamperedBytes[0] ^= 0x01;
        String tampered = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tamperedBytes);

        try {
            ProvisionCrypto.decryptCredentials(
                    SECRET,
                    CONFIG_UUID,
                    TIMESTAMP,
                    encrypted.iv,
                    tampered);
            fail("篡改后的密文不应通过 AES-GCM 校验");
        } catch (GeneralSecurityException expected) {
            // 预期结果：GCM 标签校验失败。
        }
    }

    @Test
    public void acknowledgementSignatureBindsDeviceAndStatus() throws Exception {
        String signature = ProvisionCrypto.signAcknowledgement(
                SECRET,
                CONFIG_UUID,
                "DEVICE-001",
                "received",
                TIMESTAMP);

        assertTrue(ProvisionCrypto.verifyAcknowledgement(
                SECRET,
                CONFIG_UUID,
                "DEVICE-001",
                "received",
                TIMESTAMP,
                signature));
        assertFalse(ProvisionCrypto.verifyAcknowledgement(
                SECRET,
                CONFIG_UUID,
                "DEVICE-002",
                "received",
                TIMESTAMP,
                signature));
    }
}
