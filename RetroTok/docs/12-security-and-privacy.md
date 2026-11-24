# RetroTok — Security, Privacy & Kiosk Integrity Specification

**Document ID:** RT-DOC-12  
**Version:** 1.0.0  
**Security Classification:** Local Standalone Appliance / Zero Cloud Exposure  
**Target Environment:** Public Event Kiosk (Unattended Operation)  

---

## 1. Zero-Cloud Privacy Guarantee

RetroTok is designed with a strict zero-cloud privacy boundary. Unlike consumer apps that upload video clips to third-party CDNs, RetroTok:
1. **Never Transmits Media Online:** All recorded video, captured audio, thumbnails, and guest nicknames remain strictly within the physical storage partition of the kiosk tablet/computer.
2. **Zero Analytics or Third-Party SDKs:** The application does not bundle telemetry, crash analytics (e.g., Crashlytics), advertising trackers, or fingerprinting SDKs that communicate externally.
3. **Offline Operation:** The kiosk can (and should) operate with Wi-Fi and Bluetooth disabled in system settings.

---

## 2. Administrator Authentication & PIN Security

### 2.1 Cryptographic PIN Storage
The master administrator PIN is never stored as plaintext. It is hashed using **PBKDF2WithHmacSHA256** with a randomly generated 16-byte cryptographic salt:

```kotlin
object PinSecurityManager {
    private const val ITERATIONS = 10_000
    private const val KEY_LENGTH = 256

    fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + 
               Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verifyPin(enteredPin: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[1], Base64.NO_WRAP)
        
        val spec = PBEKeySpec(enteredPin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val testHash = factory.generateSecret(spec).encoded
        
        return MessageDigest.isEqual(expectedHash, testHash) // Constant-time comparison
    }
}
```

### 2.2 Brute-Force Rate Limiting & Lockout
To prevent curious party guests from guessing the PIN:
- **Threshold:** 5 consecutive incorrect PIN entries.
- **Lockout Action:** Keypad input is disabled for **60 seconds**.
- **Escalation:** If failed attempts persist after the initial lockout, the lockout window doubles exponentially (120s, 240s, max 10 minutes).
- **Audit Logging:** Every failed attempt is logged with a timestamp in `kiosk_events.log`.

### 2.3 Obfuscated Admin Entry Gesture
To prevent guests from noticing an obvious "Admin" button on the screen:
- The entry trigger is a **3-second continuous long press** on the top-left `RetroTok` branding logo.
- The touch gesture provides no immediate visual affordance until held for 1.5 seconds, at which point a subtle amber glow emerges.

---

## 3. Kiosk Lockdown & Android Lock Task Mode

Running an Android tablet in an unattended event environment presents risks: guests might attempt to exit to the Android launcher, launch the web browser, access system settings, or adjust volume buttons.

### 3.1 Lock Task Mode Realities: Standard App vs Device Owner

> [!IMPORTANT]
> **Technical Reality Notice:** A standard unmanaged Android application cannot completely lock down the system without device-level administration. RetroTok supports two operational tiers:

| Tier | Android Mode | Provisioning Requirement | Lockdown Capabilities |
| :--- | :--- | :--- | :--- |
| **Tier 1 (Unmanaged Kiosk)** | **App Pinning (`startLockTask()`)** | Standard user-installed APK | Pins app to screen, but user can unpin if they perform the standard two-finger swipe gesture unless system gestures are restricted in OS settings. |
| **Tier 2 (Enterprise COSU)** | **Device Owner Lock Task Mode** | Provisioned via `dpm set-device-owner` or QR code during setup | **100% Locked Appliance.** Home button, Recents button, Status bar pull-down, Notification shade, and Power menu are completely disabled. |

### 3.2 Recommended Event Kiosk Provisioning (Device Owner)
For professional rental companies and kiosk builders, RetroTok includes a `DeviceAdminReceiver` class. Organizers can provision the kiosk tablet via ADB during staging:

```bash
# Provision RetroTok as Device Owner (prior to adding Google accounts)
adb shell dpm set-device-owner com.aistudio.retrotok.kiosk/.receiver.RetroTokDeviceAdminReceiver
```

Once provisioned as Device Owner, RetroTok automatically calls:
```kotlin
val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
val adminComponent = ComponentName(this, RetroTokDeviceAdminReceiver::class.java)

dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
dpm.setLockTaskFeatures(
    adminComponent,
    DevicePolicyManager.LOCK_TASK_FEATURE_NONE // Disable all system UI, status bar, and home
)
startLockTask()
```

---

## 4. Participant Privacy & Onscreen Consent

Because the kiosk records video footage of individuals at events, legal frameworks (such as GDPR, CCPA, and general privacy torts) require clear notice:

1. **Onscreen Confirmation Notice:** Prior to adding any video to the local event feed, the guest is presented with the clear modal notice:
   > *"Notice: Your video will be saved on this device and displayed on the RetroTok event feed for attendees to enjoy. It will not be uploaded to the internet or shared on external social networks."*
2. **Physical Kiosk Signage Recommendation:** Event organizers should place a clear placard near the kiosk:
   > *"RetroTok Video Station: Videos recorded here are displayed on-screen during tonight's event. By recording, you consent to your video being shown to fellow guests on this kiosk."*
3. **Minor Protection:** If children are attending the event (e.g., family reunions or birthdays), organizers must ensure parental supervision prior to recording.
