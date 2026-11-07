# Changelog

All notable changes to the TKpay NAPS POS SDK (Android) are documented here.

## [1.4.0] - 2026-11-06

### NapsPay v5.4.4 sync with the canonical C# SDK (1.4.0)

- **Confirmation frame (TM=002).** No longer sends DATR (018) / HETR (019). Field
  order now matches the reference exactly: TM, NCAI, NS, [MT, DE], STAN, [NCAR],
  [DAEX], DA, HE.
- **Receipts.** The merchant copy now comes from the Payment Response (TM=101) DP
  and the customer copy from the Confirmation Response (TM=102) DP. Previously both
  were parsed from the TM=102 DP.
- **Declined payments.** `PaymentResult` now keeps the masked PAN (NCAR) and the
  decline receipt (DP) as `merchantReceipt`. The error text follows the reference:
  `909` "Terminal or server is down", `302` "Transaction not found", `482`
  "Transaction already cancelled", `480` "Transaction cancelled", otherwise
  "<description> — code: <CR>".
- **TCP read loop.** The first read now waits for the full exchange timeout. Before,
  it gave up after 1 s and reported `CONNECTION_FAILED` when the terminal took longer
  to answer, e.g. while waiting for the card. After the first chunk, the loop drains
  until the frame *ends* with `'!'`/`'?'` or 1 s of silence. Before, it stopped after
  two chunks even without a terminator, which truncated multi-segment receipts. A
  silent terminal now raises `TIMEOUT` instead of `CONNECTION_FAILED`.
  The whole read is bounded by the exchange timeout and a 64 KB frame cap, so a
  peer that trickles bytes without a terminator cannot hold the call open.
- **Management frames carry NS (004).** Duplicate (008), network test (009),
  reset (012) and referencing (013) now send `TM, NCAI, NS, DA, HE` with a generated
  sequence number (`TlvProtocol.buildManagementRequest`).
- **Referencing TIDE (020).** `referencing(registerId, cashierId, tide = "01")` sends
  the parameter-set selector (`TlvProtocol.Tide.MERCHANT` "01" / `RECHARGE` "02");
  `ReferencingResult.parameterSet` echoes it.
- **Totals report.** New `totals(registerId, cashierId)` (TM=010, management form)
  → `TotalsResult { success, responseCode, receipt, error }`.
- **`BinaryTlvProtocol`.** Port of the reference binary Sunmi framing:
  `buildRequest`, `wrapWithLength`, `parseResponse`.
- **Amounts.** `TlvProtocol.toCentimes` rounds to the nearest centime (19.99 → 1999).
  Before, it truncated (1998). Used by the payment request and the confirmation.
- **Sequence numbers.** Wrap from 999999 to 000001 atomically, using a CAS loop
  because `minSdk` is 21.
- **Network test.** The error message now uses `describeResponse(code)`.
- **Receipts (DP, spec §5).** `ReceiptParser` now skips the `'*'` line separator and
  stops at the `'?'` end marker. Before, it lost its place after the first line, so a
  real terminal receipt came back with one line. `TlvProtocol.parse` now only treats
  `'!'`/`'?'` as the frame terminator at a field boundary, so the `'?'` inside the DP
  value no longer cuts off the fields after it.
- **Receipts follow spec §5 exactly.** Every DP line is kept, in order, as sent, including
  lines with empty text (they were dropped). The SDK no longer adds or replaces lines: the
  "TKPAY" header substitution and the "Powered by NAPS" line are removed. Card numbers of 13 to
  19 digits in receipt text are masked (was 16 only), and the line is kept. A new line number
  also ends the previous line when no `'*'` separates them.
- **Void customer copy.** New `CancellationResult.customerReceipt` carries the TM=104 DP
  (customer copy). `merchantReceipt` stays the TM=103 DP, and pending voids keep it.
- **Sample app** compiles again: the 6 missing buttons are back in the layout, and the void
  passes the amount and shows pending voids. AGP 8.1.4 → 8.2.2, because 8.1.x fails `jlink` on
  the JDK 21 bundled with current Android Studio.
- **Confirmation refusal.** A Confirmation Response (TM=102) with CR≠000 (e.g. 302
  sale not found, 482 already cancelled; the terminal auto-reverses) is now a failed
  `PaymentResult`. Before, it was reported as `success = true`.
- **Void outcome fails closed (spec §4.3/§4.4).** TM=103 CR=000 only means
  "transaction found"; TM=004 executes the reversal. Only TM=104 CR=000 or 480 is a
  success now. If no TM=104 reply arrives (timeout or connection loss after TM=004),
  or CR=995 comes back, the result is `success = false` with the new
  `CancellationResult.pending = true`, the STAN of the transaction and the TM=103
  receipt (new `merchantReceipt`). Verify with `printDuplicate(..., stan)`. CR=995 is
  "Server processing error" in the spec, but was seen with successful voids on the
  live v5.4.4 terminal. Before, 995 was reported as success and a missing reply as a
  plain failure. Every other CR is a failure.
- **TM=004 sequence.** Confirmed and pinned by tests: TM=004 carries the TM=003
  sequence in NS (004), never in NSA (005), and echoes the MT=103 amount (TAG 002).
- **Kept on purpose:** `cancelPayment` stays two-phase (TM=003 → MT=103 → TM=004 →
  MT=104), as the NapsPay app does. Verified against the v5.4.4 emulator, which
  implements TM=004.

## [1.3.0] - 2026-09-12

### USB serial transport (Ingenico Lane/3000)

- **New `USB_SERIAL` transport** alongside TCP. `NapsConfig` gains `transport`
  (`TransportType.TCP_SOCKET` default / `USB_SERIAL`), `serialPortName` (null =
  auto-discover) and `serialBaudRate` (default 115200). Use `NapsConfig.usbSerial()`
  or `NapsConfig(transport = TransportType.USB_SERIAL)`; pass a `Context` to
  `NapsPayClient(config, context)` for the USB path. TCP behaviour is unchanged.
- **USB-host connection.** `UsbSerialConnection` opens the Ingenico device via the
  platform `UsbManager` + [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android)
  (CDC-ACM), requesting the runtime USB permission via a broadcast flow when needed,
  then writes the raw TLV request and reads until the NAPS terminator (`'!'`/`'?'`)
  with a short inter-chunk silence fallback — the same framing as the TCP path.
- **Discovery.** `UsbSerialDiscovery` selects the terminal by USB vendor id
  `0x0B00` (2816), falling back to the first CDC-ACM device; exposes
  `select` / `findDeviceName` / `isIngenico` plus `UsbSerialConnection.listDevices`
  and `findDeviceName` helpers.
- **Per-transport protocol default.** When `protocolVersion` is unset,
  `NapsConfig.resolveProtocolVersion()` returns v1 (`'?'`) for USB serial and v2
  (`'!'`) for TCP; an explicit `protocolVersion` always wins.
- **Transport abstraction.** Introduced `NapsTransport` (implemented by
  `NapsConnection` and `UsbSerialConnection`); `NapsPayClient` selects the transport
  from `config.transport`.
- **Not device-verified.** The serial path requires USB-host hardware and a granted
  USB permission and cannot run on the TCP emulator. Only the discovery selection and
  the per-transport protocol default are unit-tested (JVM, no hardware); the on-device
  serial I/O has not been verified on physical hardware.
- **Config change.** `NapsConfig.host` is now nullable (required only for TCP).

## [1.2.1] - 2026-09-12

### NAPS TLV v2

- **Enriched payment confirmation (TM=002), spec §4.2.** `buildConfirmationRequest`
  now accepts optional `amountCentimes`, `currency` (default `504`), `ncar` and
  `daex`. When provided, the confirmation carries MT (002) + DE (012), NCAR (007)
  and DAEX (017) in the reference field order (TM, NCAI, NS, MT, DE, STAN, NCAR,
  DAEX, DA, HE, DATR, HETR). Existing callers keep working — the new arguments
  default to omitted. The payment flow threads the amount, masked PAN and card
  expiry from the approved payment response (TM=101) into the confirmation.
- **Response terminator.** The socket read loop now stops as soon as a NAPS
  end-of-message terminator arrives — `'!'` (v2) or `'?'` (v1) — and the TLV
  parser strips everything from the terminator onward.
- **Protocol version flag.** New `ProtocolVersion` enum and `NapsConfig.protocolVersion`
  option (default `V2`) select the expected response terminator; both terminators
  remain tolerated on read.
- **Response-code table (spec §6).** Added `TlvProtocol.RESPONSE_CODES`,
  `describeResponse(code)` and `isApproved(code)` (approved set `{000,001,003,007}`).
  Declined payment results now use `describeResponse` for their message.

## [1.2.0] - 2026-08-13

- NapsPay v5.4.4 compatibility: amount (TAG 002) in cancellation, MT=104 success handling.

## [1.1.0] - 2026-07-30

- Added NAPS M2M message types TM=003 (cancel), 008 (duplicate), 009 (network test),
  012 (reset), 013 (referencing).
