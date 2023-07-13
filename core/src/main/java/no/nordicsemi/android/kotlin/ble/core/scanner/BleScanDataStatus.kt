package no.nordicsemi.android.kotlin.ble.core.scanner

import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The data status
 *
 * @property value Native Android API value.
 * @see [ScanResult.getDataStatus](https://developer.android.com/reference/kotlin/android/bluetooth/le/ScanResult?hl=en#getdatastatus)
 */
@RequiresApi(Build.VERSION_CODES.O)
enum class BleScanDataStatus(val value: Int) {

    /**
     * For chained advertisements, indicates tha the data contained in this scan result is complete.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    DATA_COMPLETE(ScanResult.DATA_COMPLETE),

    /**
     * For chained advertisements, indicates that the controller was unable to receive all chained packets and the scan result contains incomplete truncated data.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    DATA_TRUNCATED(ScanResult.DATA_TRUNCATED);

    companion object {
        fun create(value: Int): BleScanDataStatus {
            return values().firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Cannot create BleScanDataStatus for value: $value")
        }
    }
}
