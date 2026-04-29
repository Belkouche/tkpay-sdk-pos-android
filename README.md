# TKPAY NAPS POS SDK — Android

Android SDK for integrating NAPS Pay terminals with your point-of-sale applications.
Supports two independent integration modes: **TCP/M2M** (socket) and **App2App** (Android Intent).

[![GitHub](https://img.shields.io/badge/GitHub-tkpay--sdk--pos-blue)](https://github.com/Belkouche/tkpay-sdk-pos)

---

## Integration Modes

| Mode | Class | Use case |
|------|-------|----------|
| **TCP / M2M** | `NapsPayClient` | Custom terminal on a different device — socket on port 4444 |
| **App2App** | `App2AppClient` | NAPS Pay installed on the **same** Sunmi device — launched via Android Intent |

---

## Features

- **Two integration modes** — TCP/M2M and App2App
- **M2M Protocol** — Full NAPS Pay TLV protocol (port 4444)
- **App2App** — Launches NAPS Pay via Intent, result delivered via `ActivityResultLauncher`
- **Receipt building** — When `printReceipt = false`, SDK builds merchant + customer thermal receipts (58 mm)
- **PCI DSS compliant** — Only masked card number is exposed (first 6 + last 4 digits)
- **Kotlin coroutines** — Non-blocking async for TCP mode
- **NAPS Pay 5.3.0** — Verified compatible; App2App Intent API unchanged from 4.1.1

---

## Installation

### Gradle (JitPack)

```gradle
// settings.gradle / build.gradle (root)
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

```gradle
// app/build.gradle
dependencies {
    implementation 'com.github.Belkouche:tkpay-sdk-pos:1.0.0'
}
```

### Local Module

```bash
git clone https://github.com/Belkouche/tkpay-sdk-pos.git
```

```gradle
// settings.gradle
include ':naps-sdk'
project(':naps-sdk').projectDir = new File('../tkpay-sdk-pos/naps-sdk')

// app/build.gradle
dependencies {
    implementation project(':naps-sdk')
}
```

---

## MODE 1 — TCP / M2M (`NapsPayClient`)

Direct socket connection to NAPS Pay on port 4444.
Best for custom terminals or when NAPS Pay runs on a different device.

### Setup

```kotlin
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig

val config = NapsConfig(
    host = "192.168.24.214",   // Terminal IP address
    port = 4444,                // M2M port (default)
    timeout = 120000,           // Request timeout (2 minutes)
    confirmationTimeout = 40000 // Phase-2 confirmation timeout (40 seconds)
)

val napsClient = NapsPayClient(config)
```

### Process a Payment

```kotlin
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.NapsError

lifecycleScope.launch {
    try {
        val result = napsClient.processPayment(
            PaymentRequest(amount = 100.00, registerId = "01", cashierId = "00001")
        )

        if (result.isApproved()) {
            println("STAN: ${result.stan}")
            println("Auth: ${result.authNumber}")
            println("Card: ${result.getFormattedCardNumber()}")  // 516794******3315
            result.merchantReceipt?.let { printer.print(it) }
            result.customerReceipt?.let { printer.print(it) }
        } else {
            println("Declined: ${result.error}")
        }
    } catch (e: NapsError) {
        println("Error [${e.code}]: ${e.message}")
    }
}
```

### Test Connection

```kotlin
lifecycleScope.launch {
    val ok = napsClient.testConnection()
    println(if (ok) "Terminal reachable" else "Cannot connect")
}
```

### Payment Flow (TCP)

```
┌─────────────┐          ┌──────────────┐          ┌─────────────┐
│   Your App  │          │     SDK      │          │  Terminal   │
└──────┬──────┘          └──────┬───────┘          └──────┬──────┘
       │  processPayment()      │                         │
       │───────────────────────>│                         │
       │                        │  Phase 1: TM 001        │
       │                        │────────────────────────>│
       │                        │    Customer taps card   │
       │                        │  Response: TM 101       │
       │                        │<────────────────────────│
       │                        │  Phase 2: TM 002        │
       │                        │────────────────────────>│
       │                        │  Response: TM 102       │
       │                        │<────────────────────────│
       │  PaymentResult         │                         │
       │<───────────────────────│                         │
```

> Phase 2 confirmation must be sent within **40 seconds** on the same TCP connection.

---

## MODE 2 — App2App (`App2AppClient`)

Launches NAPS Pay (`com.m2mgroup.napspay`) via Android Intent.
NAPS Pay handles the card interaction; the result is returned to your app.

Best for **Sunmi POS terminals** where both apps run side-by-side.

### Print receipt options

| `printReceipt` | NAPS Pay prints? | SDK receipts? |
|---|---|---|
| `true` (default) | Yes — NAPS Pay prints on terminal | No — only transaction data returned |
| `false` | No | Yes — SDK builds `merchantReceipt` + `customerReceipt` you can print, store, or email |

### Setup — register in `onCreate()`

```kotlin
import ma.tkpay.naps.app2app.App2AppClient
import ma.tkpay.naps.app2app.App2AppResult

class MyActivity : AppCompatActivity() {

    private lateinit var napsClient: App2AppClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MUST be in onCreate before the activity starts
        napsClient = App2AppClient.register(
            activity = this,
            onResult = { result -> handlePaymentResult(result) }
        )
    }
}
```

### Process a Payment

```kotlin
// Check NAPS Pay is installed first
if (!napsClient.isNapsPayInstalled()) {
    showError("NAPS Pay not installed — install com.m2mgroup.napspay")
    return
}

napsClient.pay(
    orderId       = "00000042",     // Unique order ID (digits recommended, max 20 chars)
    amountCentimes = 15000L,        // 150.00 MAD
    printReceipt  = false           // SDK builds receipts; developer decides what to do
)
```

### Handle the Result

```kotlin
private fun handlePaymentResult(result: App2AppResult) {
    when {
        result.isApproved() -> {
            println("STAN: ${result.stan}")
            println("RRN: ${result.rrn}")
            println("Approval: ${result.approvalCode}")
            println("Card: ${result.getFormattedCardNumber()}")  // 516794******3315
            result.cardScheme?.let { println("Scheme: $it") }

            // printReceipt = false → receipts ready to use
            result.merchantReceipt?.let { printer.print(it) }        // print on Sunmi
            result.customerReceipt?.let { emailService.send(it) }    // or email
        }
        result.isCancelled -> {
            println("Cancelled by user")
        }
        else -> {
            println("Failed [${result.responseCode}]: ${result.errorType.defaultMessage}")
        }
    }
}
```

### Reversal (void)

```kotlin
napsClient.reverse(
    originalStan   = "001234",   // STAN from the original App2AppResult
    amountCentimes = 15000L,     // Optional — for reference
    orderId        = "00000042", // Optional — for reference
    printReceipt   = false
)
```

### App2App Result Fields

| Field | Type | Description |
|-------|------|-------------|
| `stan` | `String?` | System Trace Audit Number |
| `rrn` | `String?` | Retrieval Reference Number |
| `approvalCode` | `String?` | Approval code |
| `receiptNumber` | `String?` | Receipt number |
| `maskedCardNumber` | `String?` | Masked PAN — first 6 + last 4 only (PCI DSS) |
| `cardScheme` | `String?` | Card scheme (Visa, Mastercard, …) |
| `terminalId` | `String?` | NAPS terminal ID |
| `merchantName` | `String?` | Merchant name from terminal |
| `merchantCity` | `String?` | Merchant city |
| `transactionDate` | `String?` | Date string from NAPS Pay |
| `transactionTime` | `String?` | Time string from NAPS Pay |
| `merchantReceipt` | `Receipt?` | Built by SDK when `printReceipt=false` |
| `customerReceipt` | `Receipt?` | Built by SDK when `printReceipt=false` |
| `isCancelled` | `Boolean` | User cancelled in NAPS Pay |
| `errorType` | `App2AppErrorType` | Typed error classification |
| `responseCode` | `String` | Raw NAPS Pay response code |

### Error Types

| `App2AppErrorType` | Description |
|---|---|
| `DECLINED` | Card declined |
| `INSUFFICIENT_FUNDS` | Insufficient funds |
| `EXPIRED_CARD` | Card expired |
| `INVALID_PIN` | Invalid PIN |
| `PIN_TRIES_EXCEEDED` | PIN tries exceeded |
| `TIMEOUT` | Transaction timeout |
| `SYSTEM_ERROR` | NAPS system error |
| `CANCELLED` | User cancelled |
| `NOT_INSTALLED` | NAPS Pay not installed |
| `LAUNCH_FAILED` | Could not launch NAPS Pay |
| `UNKNOWN` | Other error |

### Important Notes

- **`App2AppClient.register()`** must be called in `onCreate()` — before the activity starts.
- **Do NOT** add `FLAG_ACTIVITY_NEW_TASK` to the intent — it breaks `ActivityResultLauncher` and the result will never be delivered.
- Only **one payment** can be in-flight at a time.
- Requires NAPS Pay (`com.m2mgroup.napspay`) installed on the device.
- Tested against **NAPS Pay 5.3.0**; App2App Intent API is stable since 4.1.1.

---

## Receipt API

Both modes return `Receipt` objects (when available):

```kotlin
// Plain text — ready to display or store
val text: String = receipt.toPlainText()

// Structured lines — for custom rendering or Sunmi printer
receipt.lines.forEach { line ->
    when (line.alignment) {
        Alignment.CENTER -> printer.printCenter(line.text, line.bold)
        Alignment.LEFT   -> printer.printLeft(line.text, line.bold)
        Alignment.RIGHT  -> printer.printRight(line.text, line.bold)
    }
}
```

Receipt format: 58 mm / 32 characters wide, with merchant and customer copies.

---

## Permissions

```xml
<!-- TCP mode: network access to terminal -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- App2App mode: query NAPS Pay package -->
<!-- Add to AndroidManifest.xml if targeting API 30+ -->
<queries>
    <package android:name="com.m2mgroup.napspay" />
</queries>
```

---

## Requirements

- Android 5.0+ (API 21)
- Kotlin 1.9+
- `AppCompatActivity` (for App2App mode)
- Coroutines (for TCP mode)
- Network access to terminal (TCP mode only)

---

## TLV Protocol Reference (TCP mode)

| Tag | Name | Description |
|-----|------|-------------|
| 001 | TM | Message Type (001=Request, 002=Confirm) |
| 002 | MT | Amount in minor units (centimes) |
| 003 | NCAI | Register (2 digits) + Cashier (5 digits) |
| 004 | NS | Sequence number |
| 007 | NCAR | Masked card number |
| 008 | STAN | System Trace Audit Number |
| 009 | NA | Authorization number |
| 010 | DP | Receipt data |
| 012 | DE | Currency code (504 = MAD) |
| 013 | CR | Response code |

---

## Project Structure

```
tkpay-sdk-pos-naps-android/
├── naps-sdk/                          # SDK library
│   └── src/main/java/ma/tkpay/naps/
│       ├── NapsPayClient.kt           # TCP/M2M entry point
│       ├── app2app/
│       │   ├── App2AppClient.kt       # App2App entry point
│       │   ├── App2AppResult.kt       # Result + error types
│       │   ├── App2AppReceiptBuilder.kt  # Thermal receipt builder (internal)
│       │   └── App2AppConstants.kt    # NAPS Pay Intent constants
│       ├── config/
│       │   └── NapsConfig.kt
│       ├── connection/
│       │   └── NapsConnection.kt
│       ├── models/
│       │   ├── PaymentRequest.kt
│       │   ├── PaymentResult.kt
│       │   ├── Receipt.kt
│       │   └── NapsError.kt
│       └── protocol/
│           ├── TlvProtocol.kt
│           └── ReceiptParser.kt
├── sample-app/                        # Demo application (both modes)
└── README.md
```

---

## Building

```bash
# Build SDK
./gradlew :naps-sdk:build

# Build sample app
./gradlew :sample-app:assembleDebug
```

---

## Security

- **PAN masking** — card numbers masked as `516794******3315` (first 6 + last 4). Full PAN never exposed.
- **No storage** — SDK never stores sensitive card data.
- **Secure logging** — raw TLV data not logged; masked card only.
- **PCI DSS** — compliant with payment card industry standards.

---

## License

Copyright 2025 TKpay. All rights reserved.

## Support

- GitHub Issues: https://github.com/Belkouche/tkpay-sdk-pos/issues
- Email: support@tkpay.ma
