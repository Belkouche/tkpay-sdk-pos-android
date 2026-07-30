# TKpay NAPS POS SDK — Android (Kotlin)

Android SDK for integrating NAPS Pay terminals into your point-of-sale application  
via a direct **TCP/M2M socket connection** on port 4444.

---

## Table of Contents

1. [How it works](#how-it-works)
2. [Installation](#installation)
3. [Permissions](#permissions)
4. [Quick start](#quick-start)
5. [Before the first payment](#before-the-first-payment)
6. [Process a payment](#process-a-payment)
7. [Cancel a transaction](#cancel-a-transaction)
8. [Reprint a receipt](#reprint-a-receipt)
9. [Settlement (end of day)](#settlement-end-of-day)
10. [Reset the terminal](#reset-the-terminal)
11. [Receipt API](#receipt-api)
12. [Response codes](#response-codes)
13. [Full API reference](#full-api-reference)
14. [Project structure](#project-structure)
15. [Building](#building)
16. [Security](#security)

---

## How it works

Every message between your app and the terminal is a **TLV string** — a sequence of  
`TAG(3 digits) + LENGTH(3 digits) + VALUE` fields concatenated with no separator:

```
001003001 002012000000010000 003007010000 013003504 …
│         │                  │             │
TM=001    MT=10000 (100 MAD) NCAI=0100001  DE=504 (MAD)
```

A payment requires **two TCP phases on the same open socket**:

```
Your App           SDK                Terminal
   │                │                    │
   │ processPayment()│                   │
   │───────────────>│                   │
   │                │──── TM=001 ──────>│  Phase 1: payment request
   │                │   (customer taps card / enters PIN)
   │                │<─── TM=101 ───────│  RC=000 + STAN
   │                │──── TM=002 ──────>│  Phase 2: confirmation  ← same socket!
   │                │<─── TM=102 ───────│  receipt (DP tag)
   │  PaymentResult │                   │
   │<───────────────│                   │
```

> **Critical:** Phase 2 must be sent **within 40 seconds** on the exact same TCP connection.  
> The SDK handles this automatically — you call `processPayment()` and get a single result.

All 8 NAPS M2M message types are supported:

| TM | Direction | Function |
|---|---|---|
| 001 / 101 | Request / Response | Payment |
| 002 / 102 | Request / Response | Confirmation (Phase 2) |
| 003 / 103 | Request / Response | Cancellation |
| 008 / 108 | Request / Response | Duplicate receipt |
| 009 / 109 | Request / Response | Network test |
| 010 / 110 | Request / Response | Settlement |
| 012 / 112 | Request / Response | Reset PinPAD |
| 013 / 113 | Request / Response | Referencing |

---

## Installation

### Option A — JitPack (recommended)

```gradle
// settings.gradle (root)
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

```gradle
// app/build.gradle
dependencies {
    implementation 'com.github.Belkouche:tkpay-sdk-pos-android:v1.1.0'
}
```

### Option B — Local module

```bash
git clone https://github.com/Belkouche/tkpay-sdk-pos-android.git
```

```gradle
// settings.gradle
include ':naps-sdk'
project(':naps-sdk').projectDir = new File('../tkpay-sdk-pos-android/naps-sdk')
```

```gradle
// app/build.gradle
dependencies {
    implementation project(':naps-sdk')
}
```

---

## Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## Quick start

```kotlin
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.PaymentRequest

// 1 — Configure once (at app startup or per-transaction)
val client = NapsPayClient(
    NapsConfig(
        host = "192.168.1.100",      // Terminal IP on your LAN
        port = 4444,                  // Default NAPS M2M port
        timeout = 120_000L,           // 2 min — customer has time to tap
        confirmationTimeout = 40_000L // 40 s — NAPS hard limit for Phase 2
    )
)

// 2 — First time only: sync terminal config from NAPS server
lifecycleScope.launch {
    val ref = client.referencing("01", "00001")
    check(ref.success) { "Referencing failed: ${ref.error}" }
}

// 3 — Take a payment
lifecycleScope.launch {
    val result = client.processPayment(
        PaymentRequest(amount = 49.90, registerId = "01", cashierId = "00001")
    )
    if (result.isApproved()) println("Approved — STAN: ${result.stan}")
}
```

**`registerId`** — your POS register number, exactly 2 digits (e.g. `"01"`).  
**`cashierId`** — the cashier identifier, exactly 5 digits (e.g. `"00001"`).  
Together they form the **NCAI** (`"0100001"`) sent in every TLV message.

---

## Before the first payment

### Referencing — sync terminal config (TM=013)

Run this **once before the first payment** of the day (or after a new terminal install).  
It downloads the merchant configuration from the NAPS server.

```kotlin
lifecycleScope.launch {
    val result = client.referencing(registerId = "01", cashierId = "00001")

    if (result.success) {
        println("Terminal configured")
        result.receipt?.let { printReceipt(it) }  // optional config summary
    } else {
        println("Referencing failed: ${result.error}")
    }
}
```

### Network test — protocol-level health check (TM=009)

Unlike `testConnection()` which only opens a TCP socket, `networkTest()` sends an actual  
NAPS M2M ping and verifies the terminal responds RC=000. Use this to confirm the terminal  
is truly ready before the first transaction.

```kotlin
lifecycleScope.launch {
    val result = client.networkTest(registerId = "01", cashierId = "00001")

    println("Ready: ${result.success}")
    println("Round-trip: ${result.rttMs}ms")
}
```

---

## Process a payment

```kotlin
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.ErrorCode

lifecycleScope.launch {
    try {
        val result = client.processPayment(
            PaymentRequest(
                amount     = 149.99,   // MAD — converted to centimes internally
                registerId = "01",
                cashierId  = "00001"
                // sequence = "000042" // optional — auto-generated if omitted
            )
        )

        if (result.isApproved()) {
            // Save these — needed for cancellation
            val stan     = result.stan      // e.g. "444491"
            val sequence = result.sequence  // e.g. "000001"

            println("Card:   ${result.getFormattedCardNumber()}")  // 516794******3315
            println("Auth:   ${result.authNumber}")
            println("Expiry: ${result.getFormattedExpiry()}")      // 10/30
            println("Mode:   ${result.entryMode}")                 // CC or SC

            result.merchantReceipt?.let { printReceipt(it) }
            result.customerReceipt?.let { printReceipt(it) }
        } else {
            println("Declined [${result.responseCode}]: ${result.error}")
        }

    } catch (e: NapsError) {
        when (e.code) {
            ErrorCode.TIMEOUT           -> showToast("Terminal not responding")
            ErrorCode.CONNECTION_FAILED -> showToast("Cannot reach terminal")
            else                        -> showToast("Error: ${e.message}")
        }
    }
}
```

**`PaymentResult` fields:**

| Field | Type | Description |
|---|---|---|
| `success` | `Boolean` | `true` only when RC = 000 |
| `responseCode` | `String` | Raw 3-digit NAPS code |
| `stan` | `String?` | System Trace Audit Number — **save for cancellation** |
| `sequence` | `String?` | Sequence used — **save for cancellation** |
| `maskedCardNumber` | `String?` | First 6 + last 4: `516794******3315` |
| `cardExpiry` | `String?` | YYMM format (`"3010"`) — use `getFormattedExpiry()` |
| `cardholderName` | `String?` | As embossed on card |
| `entryMode` | `String?` | `CC` = contactless, `SC` = chip |
| `authNumber` | `String?` | Bank authorization number |
| `transactionDate` | `String?` | DDMMYYYY |
| `transactionTime` | `String?` | HHMMSS |
| `merchantReceipt` | `Receipt?` | Merchant copy |
| `customerReceipt` | `Receipt?` | Customer copy |
| `error` | `String?` | Human-readable error when `success = false` |

---

## Cancel a transaction

**TM=003** voids the last approved transaction. You need the `stan` and `sequence`  
from the original `PaymentResult` — save them immediately after every approved payment.

```kotlin
// After processPayment() succeeds, save these:
val savedStan     = result.stan     ?: return
val savedSequence = result.sequence ?: return

// When customer requests a void:
lifecycleScope.launch {
    val cancel = client.cancelPayment(
        stan       = savedStan,
        registerId = "01",
        cashierId  = "00001",
        sequence   = savedSequence
    )

    if (cancel.success) {
        println("Voided — STAN: ${cancel.stan}")
    } else {
        println("Void failed [${cancel.responseCode}]: ${cancel.error}")
    }
}
```

> Only the **last approved transaction on the current session** can be cancelled.

---

## Reprint a receipt

**TM=008** asks the terminal for a duplicate of a previous receipt.

```kotlin
lifecycleScope.launch {
    // Reprint the last receipt:
    val result = client.printDuplicate(registerId = "01", cashierId = "00001")

    // Or reprint a specific transaction by STAN:
    val result = client.printDuplicate(
        registerId = "01",
        cashierId  = "00001",
        stan       = "444491"
    )

    if (result.success) {
        result.merchantReceipt?.let { printReceipt(it) }
    } else {
        println("Duplicate failed: ${result.error}")
    }
}
```

---

## Settlement (end of day)

**TM=010** sends batch totals to the NAPS server. Run once at end of business day.  
The terminal must be idle (not waiting for a card) and referencing must have run.

```kotlin
lifecycleScope.launch {
    val result = client.settlement(registerId = "01", cashierId = "00001")

    if (result.success) {
        println("Settlement done — ${result.date} ${result.time}")
    } else {
        println("Settlement failed: ${result.error}")
    }
}
```

---

## Reset the terminal

**TM=012** sends the terminal back to idle ("Attente Caisse").  
Use when the terminal is stuck waiting for a card tap after an interrupted payment.

```kotlin
lifecycleScope.launch {
    val result = client.resetPinPad(registerId = "01", cashierId = "00001")
    println(if (result.success) "Terminal reset — ready" else "Reset failed: ${result.error}")
}
```

---

## Receipt API

```kotlin
// Plain text — for logging or email body
val text: String = receipt.toPlainText()

// Formatted text with alignment markers — for debugging
val formatted: String = receipt.toFormattedText()
// e.g. "[B][C]TKpay" = bold, centered

// Line-by-line — for Sunmi thermal printer
receipt.lines.forEach { line ->
    when (line.alignment) {
        Alignment.CENTER -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_CENTER)
        Alignment.RIGHT  -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_RIGHT)
        Alignment.LEFT   -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_LEFT)
    }
}
```

**`ReceiptLine` fields:**

| Field | Type | Description |
|---|---|---|
| `lineNumber` | `String` | Sequential line number |
| `text` | `String` | Line content |
| `bold` | `Boolean` | Print in bold |
| `alignment` | `Alignment` | `LEFT`, `CENTER`, or `RIGHT` |

Receipt format: **58 mm / 32 characters wide**.

---

## Response codes

| Code | Meaning |
|---|---|
| `000` | Approved |
| `001` | Refer to card issuer |
| `005` | Do not honour |
| `012` | Invalid transaction |
| `013` | Invalid amount |
| `051` | Insufficient funds |
| `055` | Invalid PIN |
| `057` | Transaction not permitted to cardholder |
| `061` | Exceeds withdrawal amount limit |
| `065` | Exceeds withdrawal frequency limit |
| `075` | PIN tries exceeded |
| `091` | Issuer or switch inoperative |
| `096` | System malfunction |
| `302` | Transaction not found |
| `480` | Transaction cancelled |
| `482` | Transaction already cancelled |
| `909` | Terminal or server down |

---

## Full API reference

All methods are `suspend` — call from a coroutine scope (e.g. `lifecycleScope.launch`).

| Method | TM | Description |
|---|---|---|
| `processPayment(request)` | 001 / 002 | Full two-phase payment |
| `referencing(registerId, cashierId)` | 013 | Sync merchant config — run once before first payment |
| `networkTest(registerId, cashierId)` | 009 | Protocol-level health check with RTT |
| `cancelPayment(stan, registerId, cashierId, sequence)` | 003 | Void last approved transaction |
| `printDuplicate(registerId, cashierId, stan?)` | 008 | Reprint last or specific receipt |
| `settlement(registerId, cashierId)` | 010 | End-of-day batch settlement |
| `resetPinPad(registerId, cashierId)` | 012 | Reset terminal to idle |
| `testConnection()` | — | TCP-only socket test (no TLV sent) |

### `NapsConfig` parameters

| Parameter | Default | Description |
|---|---|---|
| `host` | *(required)* | Terminal IP address |
| `port` | `4444` | NAPS M2M TCP port |
| `timeout` | `120_000` ms | Payment timeout (2 min for customer to tap) |
| `confirmationTimeout` | `40_000` ms | Phase 2 hard deadline (NAPS protocol limit) |

---

## Project structure

```
tkpay-sdk-pos-naps-android/
├── naps-sdk/                                   # SDK library module
│   └── src/main/java/ma/tkpay/naps/
│       ├── NapsPayClient.kt                    # Entry point — all suspend funs
│       ├── config/
│       │   └── NapsConfig.kt                  # Host, port, timeouts
│       ├── connection/
│       │   └── NapsConnection.kt              # TCP socket lifecycle + send/receive
│       ├── models/
│       │   ├── PaymentRequest.kt
│       │   ├── PaymentResult.kt
│       │   ├── SettlementResult.kt
│       │   ├── CancellationResult.kt
│       │   ├── NetworkTestResult.kt
│       │   ├── DuplicateReceiptResult.kt
│       │   ├── ResetResult.kt
│       │   ├── ReferencingResult.kt
│       │   ├── Receipt.kt                     # Lines, alignment, bold
│       │   └── NapsError.kt                   # ErrorCode enum + factory methods
│       ├── protocol/
│       │   ├── TlvProtocol.kt                 # TLV builders + parser + PAN masking
│       │   └── ReceiptParser.kt               # DP tag → Receipt lines
│       └── gateway/
│           └── GatewayNotifier.kt             # Fire-and-forget TKpay gateway ping
├── sample-app/                                # Demo app — all 8 message types
└── README.md
```

---

## Building

```bash
# Build the SDK AAR
./gradlew :naps-sdk:build

# Build and install the sample app
./gradlew :sample-app:assembleDebug
adb install sample-app/build/outputs/apk/debug/sample-app-debug.apk
```

---

## Requirements

- Android 5.0+ (API 21)
- Kotlin 1.9+
- `kotlinx-coroutines`

---

## Security

- **PAN masking** — card number exposed only as `516794******3315`. Full PAN never returned.
- **No persistence** — the SDK never writes sensitive data to disk, SharedPreferences, or logs.
- **PCI DSS** — compliant with payment card industry data security standards.

---

## License

Copyright 2025 TKpay. All rights reserved.

## Support

- GitHub Issues: https://github.com/Belkouche/tkpay-sdk-pos-android/issues
- Email: support@tkpay.ma
