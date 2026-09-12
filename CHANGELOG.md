# Changelog

All notable changes to the TKpay NAPS POS SDK (Android) are documented here.

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
