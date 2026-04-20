# TKPAY NAPS POS SDK

Android SDK for integrating NAPS Pay terminals with your point-of-sale applications.

[![GitHub](https://img.shields.io/badge/GitHub-tkpay--sdk--pos-blue)](https://github.com/Belkouche/tkpay-sdk-pos)

## Features

- **M2M Protocol Support** - Full implementation of NAPS Pay M2M TLV protocol
- **TCP Communication** - Direct socket communication with NAPS Pay terminals
- **Coroutines Support** - Non-blocking async operations with Kotlin coroutines
- **PCI-DSS Compliant** - Automatic PAN masking (first 6 + last 4 digits only)
- **Receipt Parsing** - Parse and format merchant/customer receipts
- **TKPAY Branding** - Automatic branding on receipts
- **Error Handling** - Comprehensive error codes and messages
- **Kotlin First** - Modern Kotlin API

## Installation

### Gradle (JitPack)

Add JitPack repository to your root `build.gradle`:

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.Belkouche:tkpay-sdk-pos:1.0.0'
}
```

### Local Module

Clone and add as a module:

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

## Quick Start

### 1. Initialize the SDK

```kotlin
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig

val config = NapsConfig(
    host = "192.168.24.214",  // Terminal IP address
    port = 4444,               // M2M port (default)
    timeout = 120000,          // Request timeout (2 minutes)
    confirmationTimeout = 40000 // Confirmation timeout (40 seconds)
)

val napsClient = NapsPayClient(config)
```

### 2. Process a Payment

```kotlin
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.NapsError
import kotlinx.coroutines.launch

// In a coroutine scope (e.g., viewModelScope, lifecycleScope)
lifecycleScope.launch {
    try {
        val request = PaymentRequest(
            amount = 100.00,       // Amount in MAD
            registerId = "01",     // Register ID (2 digits)
            cashierId = "00001"    // Cashier ID (5 digits)
        )

        val result = napsClient.processPayment(request)

        if (result.isApproved()) {
            // Payment successful
            println("STAN: ${result.stan}")
            println("Auth: ${result.authNumber}")
            println("Card: ${result.getFormattedCardNumber()}")  // 516794******3315

            // Display receipts
            result.merchantReceipt?.let { displayReceipt(it) }
            result.customerReceipt?.let { displayReceipt(it) }
        } else {
            // Payment declined
            println("Declined: ${result.error}")
        }

    } catch (e: NapsError) {
        when (e.code) {
            ErrorCode.CONNECTION_FAILED -> println("Cannot connect to terminal")
            ErrorCode.TIMEOUT -> println("Transaction timeout")
            else -> println("Error: ${e.message}")
        }
    }
}
```

### 3. Display Receipt

```kotlin
fun displayReceipt(receipt: Receipt) {
    // Plain text format
    println(receipt.toPlainText())

    // Or iterate lines for custom formatting
    receipt.lines.forEach { line ->
        val text = if (line.bold) "**${line.text}**" else line.text
        when (line.alignment) {
            Alignment.CENTER -> printCentered(text)
            Alignment.LEFT -> printLeft(text)
            Alignment.RIGHT -> printRight(text)
        }
    }
}
```

### 4. Test Connection

```kotlin
lifecycleScope.launch {
    val isConnected = napsClient.testConnection()
    if (isConnected) {
        println("Terminal is reachable")
    } else {
        println("Cannot connect to terminal")
    }
}
```

## Payment Flow

The SDK handles the complete two-phase NAPS Pay M2M flow automatically:

```
┌─────────────┐          ┌──────────────┐          ┌─────────────┐
│   Your App  │          │     SDK      │          │  Terminal   │
└──────┬──────┘          └──────┬───────┘          └──────┬──────┘
       │                        │                         │
       │  processPayment()      │                         │
       │───────────────────────>│                         │
       │                        │                         │
       │                        │  Phase 1: TM 001        │
       │                        │────────────────────────>│
       │                        │                         │
       │                        │        Customer taps card
       │                        │                         │
       │                        │  Response: TM 101       │
       │                        │<────────────────────────│
       │                        │                         │
       │                        │  Phase 2: TM 002        │
       │                        │────────────────────────>│
       │                        │                         │
       │                        │  Response: TM 102       │
       │                        │<────────────────────────│
       │                        │                         │
       │  PaymentResult         │                         │
       │<───────────────────────│                         │
       │                        │                         │
```

**Important**: Phase 2 confirmation must be sent within 40 seconds on the same TCP connection.

## TLV Protocol

The SDK uses NAPS Pay M2M TLV (Tag-Length-Value) format:

| Tag | Name | Description |
|-----|------|-------------|
| 001 | TM | Message Type (001=Request, 002=Confirm) |
| 002 | MT | Amount in minor units (centimes) |
| 003 | NCAI | Register (2) + Cashier (5) |
| 004 | NS | Sequence number |
| 007 | NCAR | Card number (masked) |
| 008 | STAN | System Trace Audit Number |
| 009 | NA | Authorization number |
| 010 | DP | Receipt data |
| 012 | DE | Currency code (504 = MAD) |
| 013 | CR | Response code |

## Response Codes

| Code | Description |
|------|-------------|
| 000 | Approved |
| 909 | Terminal or server down |
| 302 | Transaction not found |
| 482 | Transaction already cancelled |
| 480 | Transaction cancelled |

## Security

- **PAN Masking**: Card numbers automatically masked (516794******3315)
- **No Storage**: SDK never stores sensitive card data
- **Secure Logging**: Raw TLV data not logged
- **PCI-DSS**: Compliant with payment card industry standards

## Requirements

- Android 5.0+ (API 21)
- Kotlin 1.9+
- Coroutines support
- Network access to NAPS Pay terminal (same network)

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Project Structure

```
tkpay-sdk-pos/
├── naps-sdk/                    # SDK library
│   └── src/main/java/ma/tkpay/naps/
│       ├── NapsPayClient.kt     # Main SDK entry point
│       ├── config/
│       │   └── NapsConfig.kt    # Configuration
│       ├── connection/
│       │   └── NapsConnection.kt # TCP socket management
│       ├── models/
│       │   ├── PaymentRequest.kt
│       │   ├── PaymentResult.kt
│       │   ├── Receipt.kt
│       │   └── NapsError.kt
│       └── protocol/
│           ├── TlvProtocol.kt   # TLV builder/parser
│           └── ReceiptParser.kt # Receipt parsing
├── sample-app/                  # Demo application
└── README.md
```

## Sample App

The `sample-app` module contains a complete demo application showing:
- Connection testing
- Payment processing
- Receipt display
- Error handling

## Building

```bash
# Clone the repository
git clone https://github.com/Belkouche/tkpay-sdk-pos.git
cd tkpay-sdk-pos

# Build the SDK
./gradlew :naps-sdk:build

# Build sample app
./gradlew :sample-app:assembleDebug
```

## License

Copyright 2025 TKPAY. All rights reserved.

## Support

- GitHub Issues: https://github.com/Belkouche/tkpay-sdk-pos/issues
- Email: support@tkpay.ma
