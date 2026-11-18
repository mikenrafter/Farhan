package ly.com.tahaben.farhan_rescue.ui

import ly.com.tahaben.farhan_rescue.R
import ly.com.tahaben.farhan_rescue.capture.FailureCode
import ly.com.tahaben.farhan_rescue.capture.Stage
import ly.com.tahaben.farhan_rescue.convert.ConversionFailure

/**
 * Exhaustive maps from reasons to string resources. No `else` branch: adding a code forces a message here
 * (CLAIM(every-failure-worded): the compiler enforces it).
 */
internal fun failureMessage(code: FailureCode): Int = when (code) {
    FailureCode.BAD_REQUEST -> R.string.failure_bad_request
    FailureCode.BUSY -> R.string.failure_busy
    FailureCode.PAIR_FAILED -> R.string.failure_pair
    FailureCode.CONNECT_FAILED -> R.string.failure_connect
    FailureCode.PAIRING_REQUIRED -> R.string.failure_pairing_required
    FailureCode.IDENTITY_FAILED -> R.string.failure_identity
    FailureCode.RUN_AS_NOT_DEBUGGABLE -> R.string.failure_not_debuggable
    FailureCode.RUN_AS_PACKAGE_NOT_FOUND -> R.string.failure_not_found
    FailureCode.RUN_AS_USER_REJECTED -> R.string.failure_user_rejected
    FailureCode.RUN_AS_UNAVAILABLE -> R.string.failure_run_as_unavailable
    FailureCode.RUN_AS_UNRECOGNIZED -> R.string.failure_run_as_unknown
    FailureCode.UID_USER_MISMATCH -> R.string.failure_uid_mismatch
    FailureCode.FORCE_STOP_FAILED -> R.string.failure_force_stop
    FailureCode.LISTING_FAILED -> R.string.failure_listing
    FailureCode.STAT_FAILED -> R.string.failure_stat
    FailureCode.NOTHING_TO_EXPORT -> R.string.failure_nothing
    FailureCode.SIDECAR_WITHOUT_DATABASE -> R.string.failure_sidecar
    FailureCode.TOO_MANY_WIDGET_FILES -> R.string.failure_too_many_widgets
    FailureCode.FILE_TOO_LARGE, FailureCode.CAPTURE_TOO_LARGE -> R.string.failure_too_large
    FailureCode.OUTPUT_BUDGET_EXCEEDED -> R.string.failure_output_budget
    FailureCode.COPY_INCOMPLETE -> R.string.failure_copy_incomplete
    FailureCode.DIGEST_MISMATCH -> R.string.failure_digest
    FailureCode.SOURCE_CHANGED -> R.string.failure_source_changed
    FailureCode.STORAGE_FAILED -> R.string.failure_storage
    FailureCode.CONVERSION_FAILED -> R.string.failure_conversion
    FailureCode.TIMED_OUT -> R.string.failure_timeout
    FailureCode.BRIDGE_DIED -> R.string.failure_bridge_died
    FailureCode.CANCELLED -> R.string.failure_cancelled
    FailureCode.INTERNAL -> R.string.failure_internal
}

internal fun conversionMessage(failure: ConversionFailure): Int = when (failure) {
    ConversionFailure.FILES_MISSING -> R.string.conversion_files_missing
    ConversionFailure.COPY_UNREADABLE -> R.string.conversion_unreadable
    ConversionFailure.NOT_UTF8 -> R.string.conversion_not_utf8
    ConversionFailure.DATASTORE_CORRUPT -> R.string.conversion_datastore_corrupt
    ConversionFailure.DATASTORE_UNSUPPORTED_TYPE -> R.string.conversion_datastore_type
    ConversionFailure.DATABASE_CORRUPT -> R.string.conversion_db_corrupt
    ConversionFailure.DATABASE_MISSING_TABLES -> R.string.conversion_db_tables
    ConversionFailure.DATABASE_UNREADABLE -> R.string.conversion_db_unreadable
    ConversionFailure.FILE_TOO_LARGE -> R.string.conversion_too_large
    ConversionFailure.WIDGET_PROFILE_INVALID -> R.string.conversion_widget
    ConversionFailure.SETTINGS_REJECTED -> R.string.conversion_rejected
    ConversionFailure.SELF_CHECK_FAILED -> R.string.conversion_self_check
}

internal fun stageMessage(stage: Stage): Int = when (stage) {
    Stage.PROBING -> R.string.progress_probing
    Stage.READING_VERSION -> R.string.progress_reading_version
    Stage.LISTING -> R.string.progress_listing
    Stage.STOPPING_SOURCE -> R.string.progress_stopping
    Stage.COPYING -> R.string.progress_copying
    Stage.VERIFYING -> R.string.progress_verifying
}
