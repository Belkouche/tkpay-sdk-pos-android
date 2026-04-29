package ma.tkpay.naps.app2app

/**
 * NAPS Pay App-to-App integration constants.
 *
 * Sourced from decompiled NAPS Pay 5.3.0 (com.m2mgroup.napspay).
 * Intent contract is unchanged from 4.1.1 — same package, same activity,
 * same extras. The 5.3.0 update added ECR protocol improvements (port 4444)
 * but the App2App Intent API is stable.
 */
internal object App2AppConstants {

    const val PACKAGE_NAME = "com.m2mgroup.napspay"
    const val ACTIVITY_CLASS = "com.m2mgroup.napspay.MainActivity"

    // Request types (REQUESTCODE extra)
    const val REQUEST_AUTHORIZATION = "001"
    const val REQUEST_REVERSAL = "002"
    const val REQUEST_CANCELLATION = "003"

    // Order types
    const val ORDER_TYPE_AUTHORIZATION = "001"

    // Printing flags
    const val PRINTING_ENABLED = "1"
    const val PRINTING_DISABLED = "0"

    // ---- Intent extras: REQUEST keys ----
    object Request {
        const val REQUEST_CODE = "REQUESTCODE"
        const val AMOUNT = "AMOUNT"           // centimes as String, e.g. "5000" = 50.00 MAD
        const val ORDER_ID = "ORDERID"
        const val ORDER_TYPE = "ORDERTYPE"
        const val PRINTING = "PRINTING"
    }

    // ---- Intent extras: RESPONSE keys ----
    object Response {
        const val RESPONSE_CODE = "RESPONSECODE"    // "000" = success
        const val RESPONSE_MESSAGE = "RESPONSEMESSAGE"
        const val AUTHORIZATION_NUMBER = "AUTHORIZATIONNUMBER"
        const val CARD_NUMBER = "CARDNUMBER"        // already masked by NAPS Pay
        const val CARD_SCHEME = "CARDSCHEME"
        const val CARD_NATIONALITY = "CARDNATIONALITY"
        const val TRANSACTION_NUMBER = "TRANSACTIONNUMBER"
        const val MERCHANT_ID = "MERCHANTID"
        const val MERCHANT_NAME = "MERCHANTNAME"
        const val MERCHANT_CITY = "MERCHANTCITY"
        const val TERMINAL_ID = "TERMINALID"
        const val TRANSACTION_DATE = "TRANSACTIONDATE"
        const val TRANSACTION_TIME = "TRANSACTIONTIME"
        const val RECEIPT_NUMBER = "RECEIPTNUMBER"
        const val APPROVAL_CODE = "APPROVALCODE"
        const val RRN = "RRN"
        const val STAN = "STAN"
        const val AMOUNT = "AMOUNT"
        const val ORDER_ID = "ORDERID"
    }

    // ---- Response codes ----
    object ResponseCode {
        const val SUCCESS = "000"
        const val SUCCESS_ALT = "00"   // some firmware versions use 2-digit
        const val DECLINED = "005"
        const val INSUFFICIENT_FUNDS = "051"
        const val EXPIRED_CARD = "054"
        const val INVALID_PIN = "055"
        const val PIN_TRIES_EXCEEDED = "075"
        const val TIMEOUT = "091"
        const val SYSTEM_ERROR = "096"
        const val CANCELLED = "099"
    }
}
