# Changelog

All notable changes to the TKpay NAPS POS SDK (Android) are documented here.

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
