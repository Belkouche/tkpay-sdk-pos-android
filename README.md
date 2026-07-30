# TKpay NAPS POS SDK — Android (Kotlin)

Android SDK for integrating NAPS Pay terminals into your point-of-sale application.  
Two integration modes — pick the one that matches your hardware setup.

---

## Table of Contents

1. [How it works](#how-it-works)
2. [Pick your mode](#pick-your-mode)
3. [Installation](#installation)
4. [Permissions](#permissions)
5. [MODE 1 — TCP/M2M (`NapsPayClient`)](#mode-1--tcpm2m-napspayclient)
   - [Quick start](#quick-start-tcpm2m)
   - [Process a payment](#process-a-payment)
   - [Utilities before the first payment](#utilities-before-the-first-payment)
   - [Cancel a transaction](#cancel-a-transaction)
   - [Reprint a receipt](#reprint-a-receipt)
   - [Settlement (end of day)](#settlement-end-of-day)
   - [Reset the terminal](#reset-the-terminal)
6. [MODE 2 — App2App (`App2AppClient`)](#mode-2--app2app-app2appclient)
   - [Quick start](#quick-start-app2app)
   - [Process a payment](#process-a-payment-1)
   - [Handle the result](#handle-the-result)
   - [Reverse a transaction](#reverse-a-transaction)
7. [Receipt API](#receipt-api)
8. [Response codes](#response-codes)
9. [Full API reference](#full-api-reference)
10. [Project structure](#project-structure)
11. [Building](#building)
12. [Security](#security)

---

## How it works

### The NAPS M2M TLV protocol (TCP mode)

Every message between your app and the terminal is a **TLV string** — a sequence of  
`TAG(3 digits) + LENGTH(3 digits) + VALUE` fields concatenated with no separator.

```
001003001002012000000010000003007010000000414252025015006142520
│         │                 │
TM=001    MT=10000 (100 MAD) NCAI=0100001 …
```

A payment requires **two TCP phases on the same open socket**:

```
Your App           SDK                Terminal
   │                │                    │
   │ processPayment()│                   │
   │───────────────>│                   │
   │                │──── TM=001 ──────>│  Phase 1: payment request
   │                │    (customer taps card / enters PIN)
   │                │<─── TM=101 ───────│  RC=000 + STAN
   │                │──── TM=002 ──────>│  Phase 2: confirmation  ← same socket!
   │                │<─── TM=102 ───────│  receipt (DP tag)
   │  PaymentResult │                   │
   │<───────────────│                   │
```

> **Critical:** Phase 2 must be sent **within 40 seconds** on the exact same TCP connection.  
> The SDK handles this automatically — you call `processPayment()` and get a single result.

---

## Pick your mode

| | TCP / M2M | App2App |
|---|---|---|
| **Class** | `NapsPayClient` | `App2AppClient` |
| **Transport** | Raw TCP socket — port 4444 | Android Intent |
| **Where NAPS Pay runs** | Different device (external terminal) | Same device (Sunmi) |
| **Who reads the card** | Terminal hardware | NAPS Pay app |
| **Receipt control** | Full — you get raw receipt data | Optional — NAPS Pay can print, or SDK builds it |
| **Coroutines needed** | Yes — all calls are `suspend fun` | No — result delivered via callback |

**Rule of thumb:** external terminal on the network → TCP. NAPS Pay installed on the same Sunmi → App2App.

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

Add to your `AndroidManifest.xml`:

```xml
<!-- TCP/M2M mode: network access to the terminal -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- App2App mode: allows querying the NAPS Pay package (API 30+) -->
<queries>
    <package android:name="com.m2mgroup.napspay" />
</queries>
```

---

## MODE 1 — TCP/M2M (`NapsPayClient`)

Use this mode when your Android app talks to a **separate NAPS Pay terminal** over the local network.

### Quick start (TCP/M2M)

```kotlin
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig

// Step 1 — configure once at app startup
val client = NapsPayClient(
    NapsConfig(
        host = "192.168.1.100",     // Terminal IP on your LAN
        port = 4444,                 // Default NAPS M2M port
        timeout = 120_000L,          // 2 min — customer has time to tap
        confirmationTimeout = 40_000L// 40 s — hard limit for Phase 2
    )
)

// Step 2 — first time only: sync terminal config
lifecycleScope.launch {
    val ref = client.referencing("01", "00001")
    check(ref.success) { "Referencing failed: ${ref.error}" }
}

// Step 3 — take a payment
lifecycleScope.launch {
    val result = client.processPayment(
        PaymentRequest(amount = 49.90, registerId = "01", cashierId = "00001")
    )
    if (result.isApproved()) {
        println("Approved — STAN: ${result.stan}")
    }
}
```

**`registerId`** — your POS register number, 2 digits (`"01"`).  
**`cashierId`** — the cashier identifier, 5 digits (`"00001"`).  
Together they form the **NCAI** (`"0100001"`) sent in every TLV message.

---

### Process a payment

```kotlin
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.NapsError

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
            // Store these for cancellations or duplicate receipts:
            val stan     = result.stan            // e.g. "444491"
            val sequence = result.sequence        // e.g. "000001"

            println("Card:  ${result.getFormattedCardNumber()}")  // 516794******3315
            println("Auth:  ${result.authNumber}")
            println("Expiry:${result.getFormattedExpiry()}")      // 10/30
            println("Mode:  ${result.entryMode}")                 // CC or SC

            // Print receipts (both copies come from the same transaction)
            result.merchantReceipt?.let { printReceipt(it) }
            result.customerReceipt?.let { printReceipt(it) }
        } else {
            // RC != "000" — terminal declined
            println("Declined [${result.responseCode}]: ${result.error}")
        }

    } catch (e: NapsError) {
        // Connection lost, timeout, or bad TLV response
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

### Utilities before the first payment

#### Referencing — sync terminal config (TM=013)

**Run this once** before the first payment of the day (or after a new terminal install).  
It downloads the merchant configuration from the NAPS server.

```kotlin
lifecycleScope.launch {
    val result = client.referencing(registerId = "01", cashierId = "00001")

    if (result.success) {
        println("Terminal configured — RC: ${result.responseCode}")
        result.receipt?.let { printReceipt(it) }  // config summary receipt
    } else {
        println("Referencing failed: ${result.error}")
    }
}
```

#### Network test — protocol-level health check (TM=009)

Unlike `testConnection()` (TCP only), `networkTest()` sends an actual NAPS M2M ping and  
verifies the terminal responds RC=000. Use this to confirm the terminal is truly ready.

```kotlin
lifecycleScope.launch {
    val result = client.networkTest(registerId = "01", cashierId = "00001")

    println("Terminal ready: ${result.success}")
    println("Round-trip: ${result.rttMs}ms")

    if (!result.success) {
        println("Error: ${result.error}")
    }
}
```

---

### Cancel a transaction

**TM=003** voids the last approved transaction. You need the `stan` and `sequence`  
from the original `PaymentResult` — save them after every approved payment.

```kotlin
// After processPayment() succeeds:
val savedStan     = result.stan     ?: return
val savedSequence = result.sequence ?: return

// Later — customer asks for a void:
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

> Only the **last approved transaction** can be cancelled. If the session has moved on,  
> use your payment processor's reversal API instead.

---

### Reprint a receipt

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

### Settlement (end of day)

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

### Reset the terminal

**TM=012** sends the terminal back to idle ("Attente Caisse") state.  
Use when the terminal is stuck waiting for a card tap after an interrupted payment.

```kotlin
lifecycleScope.launch {
    val result = client.resetPinPad(registerId = "01", cashierId = "00001")

    if (result.success) {
        println("Terminal reset — ready")
    } else {
        println("Reset failed: ${result.error}")
    }
}
```

---

## MODE 2 — App2App (`App2AppClient`)

Use this mode when NAPS Pay (`com.m2mgroup.napspay`) is installed **on the same Sunmi device**  
as your app. Your app launches NAPS Pay via Intent; NAPS Pay handles the card interaction  
and delivers the result back.

### Quick start (App2App)

```kotlin
import ma.tkpay.naps.app2app.App2AppClient
import ma.tkpay.naps.app2app.App2AppResult

class CheckoutActivity : AppCompatActivity() {

    // Step 1 — register BEFORE the activity starts (in onCreate)
    private lateinit var napsClient: App2AppClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        napsClient = App2AppClient.register(
            activity = this,
            onResult = { result -> handlePaymentResult(result) }
        )
    }

    // Step 2 — launch a payment when the customer is ready
    fun onChargeButtonClick() {
        if (!napsClient.isNapsPayInstalled()) {
            showError("NAPS Pay not installed on this device")
            return
        }

        napsClient.pay(
            orderId        = "ORDER-0042",
            amountCentimes = 14990L,   // 149.90 MAD
            printReceipt   = false     // SDK builds receipts for you
        )
    }
}
```

> **`App2AppClient.register()` must be called in `onCreate()`** — the `ActivityResultLauncher`  
> it registers internally must be set up before the activity becomes started.

---

### Process a payment

```kotlin
napsClient.pay(
    orderId        = "ORDER-0042",  // Your unique order reference (max 20 chars)
    amountCentimes = 14990L,        // Amount in centimes — 14990 = 149.90 MAD
    printReceipt   = false          // true = NAPS Pay prints; false = SDK builds Receipt objects
)
// Result is delivered asynchronously to the onResult callback you registered
```

**`printReceipt` behaviour:**

| Value | NAPS Pay prints? | SDK returns receipts? |
|---|---|---|
| `true` (default) | Yes — terminal prints automatically | No — `merchantReceipt` and `customerReceipt` are `null` |
| `false` | No | Yes — `Receipt` objects ready to print, store, or email |

---

### Handle the result

```kotlin
private fun handlePaymentResult(result: App2AppResult) {
    when {
        result.isApproved() -> {
            // Transaction approved
            println("Approved!")
            println("STAN:     ${result.stan}")
            println("RRN:      ${result.rrn}")
            println("Approval: ${result.approvalCode}")
            println("Card:     ${result.getFormattedCardNumber()}")  // 516794******3315
            println("Scheme:   ${result.cardScheme}")               // Visa / Mastercard

            // printReceipt=false → receipts are ready
            result.merchantReceipt?.let { printOnSunmi(it) }
            result.customerReceipt?.let { sendByEmail(it) }
        }

        result.isCancelled -> {
            // Customer pressed Cancel inside NAPS Pay
            showToast("Payment cancelled")
        }

        else -> {
            // Declined or error
            println("Failed [${result.responseCode}]: ${result.errorType.defaultMessage}")
        }
    }
}
```

**`App2AppResult` fields:**

| Field | Type | Description |
|---|---|---|
| `stan` | `String?` | System Trace Audit Number — save for reversal |
| `rrn` | `String?` | Retrieval Reference Number |
| `approvalCode` | `String?` | Bank approval code |
| `receiptNumber` | `String?` | Receipt number |
| `maskedCardNumber` | `String?` | `516794******3315` |
| `cardScheme` | `String?` | Visa, Mastercard, … |
| `terminalId` | `String?` | NAPS terminal ID |
| `merchantName` | `String?` | Merchant name from terminal |
| `merchantCity` | `String?` | Merchant city |
| `transactionDate` | `String?` | Date from NAPS Pay |
| `transactionTime` | `String?` | Time from NAPS Pay |
| `merchantReceipt` | `Receipt?` | Set when `printReceipt=false` |
| `customerReceipt` | `Receipt?` | Set when `printReceipt=false` |
| `isCancelled` | `Boolean` | Customer cancelled |
| `errorType` | `App2AppErrorType` | Typed error classification |
| `responseCode` | `String` | Raw NAPS Pay response code |

**`App2AppErrorType` values:**

| Value | Meaning |
|---|---|
| `DECLINED` | Card declined by bank |
| `INSUFFICIENT_FUNDS` | Not enough funds |
| `EXPIRED_CARD` | Card is expired |
| `INVALID_PIN` | Wrong PIN |
| `PIN_TRIES_EXCEEDED` | PIN locked |
| `TIMEOUT` | No card presented in time |
| `SYSTEM_ERROR` | NAPS server error |
| `CANCELLED` | Customer cancelled |
| `NOT_INSTALLED` | NAPS Pay not installed |
| `LAUNCH_FAILED` | Intent could not launch |
| `UNKNOWN` | Other |

---

### Reverse a transaction

```kotlin
napsClient.reverse(
    originalStan   = result.stan ?: return,  // STAN from the approved App2AppResult
    amountCentimes = 14990L,                 // optional — for reference
    orderId        = "ORDER-0042",           // optional — for reference
    printReceipt   = false
)
// Result delivered to the same onResult callback
```

---

## Receipt API

Both modes return `Receipt` objects with the same structure.

```kotlin
// Option A — plain text (for logging or email body)
val text: String = receipt.toPlainText()

// Option B — formatted text with alignment markers (for debugging)
val formatted: String = receipt.toFormattedText()
// Example line: "[B][C]TKpay" = bold, centered

// Option C — line-by-line for Sunmi thermal printer
receipt.lines.forEach { line ->
    when (line.alignment) {
        Alignment.CENTER -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_CENTER)
        Alignment.RIGHT  -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_RIGHT)
        Alignment.LEFT   -> sunmiPrinter.printTextWithAlign(line.text, ALIGN_LEFT)
    }
    if (line.bold) sunmiPrinter.setFontSize(28f)
}
```

**`ReceiptLine` fields:**

| Field | Type | Description |
|---|---|---|
| `lineNumber` | `String` | Sequential line number from terminal |
| `text` | `String` | Line content |
| `bold` | `Boolean` | Print in bold |
| `alignment` | `Alignment` | `LEFT`, `CENTER`, or `RIGHT` |

Receipt format: **58 mm / 32 characters wide**. Both merchant and customer copies are available.

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

### `NapsPayClient` (TCP/M2M)

All methods are `suspend` — call from a coroutine scope (e.g. `lifecycleScope.launch`).

| Method | TM | Description |
|---|---|---|
| `processPayment(request)` | 001/002 | Full two-phase payment |
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
| `timeout` | `120_000` ms | Payment timeout (customer has 2 min to tap) |
| `confirmationTimeout` | `40_000` ms | Phase 2 hard deadline (40 s, NAPS protocol limit) |

### `App2AppClient` (App2App)

| Method | Description |
|---|---|
| `register(activity, onResult)` | Must call in `onCreate()` — registers the result launcher |
| `pay(orderId, amountCentimes, printReceipt)` | Launch NAPS Pay for a new payment |
| `reverse(originalStan, amountCentimes?, orderId?, printReceipt)` | Launch NAPS Pay for a reversal |
| `isNapsPayInstalled()` | Check if `com.m2mgroup.napspay` is installed |

---

## Project structure

```
tkpay-sdk-pos-naps-android/
├── naps-sdk/                                   # SDK library module
│   └── src/main/java/ma/tkpay/naps/
│       ├── NapsPayClient.kt                    # TCP/M2M entry point — all suspend funs
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
│       ├── gateway/
│       │   └── GatewayNotifier.kt             # Fire-and-forget TKpay gateway ping
│       └── app2app/
│           ├── App2AppClient.kt               # App2App entry point
│           ├── App2AppResult.kt               # Result + App2AppErrorType
│           ├── App2AppReceiptBuilder.kt        # Builds Receipt when printReceipt=false
│           └── App2AppConstants.kt            # Intent action + extra keys
├── sample-app/                                # Demo app — shows both modes
└── README.md
```

---

## Building

```bash
# Build the SDK AAR
./gradlew :naps-sdk:build

# Build and install the sample app (debug)
./gradlew :sample-app:assembleDebug
adb install sample-app/build/outputs/apk/debug/sample-app-debug.apk
```

---

## Security

- **PAN masking** — card number exposed only as `516794******3315` (first 6 + last 4 digits). Full PAN is never returned by the SDK.
- **No persistence** — the SDK never writes sensitive data to disk, SharedPreferences, or logs.
- **TLV parsing** — raw TLV strings are discarded after parsing; masked values only are kept.
- **PCI DSS** — compliant with payment card industry data security standards.

---

## Requirements

- Android 5.0+ (API 21)
- Kotlin 1.9+
- `kotlinx-coroutines` (TCP mode)
- `AppCompatActivity` (App2App mode)

---

## License

Copyright 2025 TKpay. All rights reserved.

## Support

- GitHub Issues: https://github.com/Belkouche/tkpay-sdk-pos-android/issues
- Email: support@tkpay.ma
