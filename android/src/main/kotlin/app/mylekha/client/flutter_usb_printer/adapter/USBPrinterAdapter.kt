package app.mylekha.client.flutter_usb_printer.adapter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset

class USBPrinterAdapter private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: USBPrinterAdapter? = null

        fun getInstance(): USBPrinterAdapter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: USBPrinterAdapter().also { INSTANCE = it }
            }
        }
    }

    private val LOG_TAG = "Flutter USB Printer"

    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIntent: PendingIntent? = null

    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null

    private var isReceiverRegistered = false

    private val ACTION_USB_PERMISSION =
        "app.mylekha.client.flutter_usb_printer.USB_PERMISSION"

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            when (action) {

                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val usbDevice =
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

                        if (intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false
                            )
                        ) {
                            usbDevice?.let {
                                Log.i(
                                    LOG_TAG,
                                    "Permission granted -> deviceId=${it.deviceId}, vendor=${it.vendorId}, product=${it.productId}"
                                )
                                mUsbDevice = it
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Permissão USB negada",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (mUsbDevice != null) {
                        Toast.makeText(
                            context,
                            "Dispositivo USB desconectado",
                            Toast.LENGTH_LONG
                        ).show()
                        closeConnectionIfExists()
                    }
                }
            }
        }
    }

    fun init(context: Context?) {
        if (context == null) return

        // 🔴 CRÍTICO: usar applicationContext para evitar leak de Activity
        mContext = context.applicationContext

        mUSBManager = mContext?.getSystemService(Context.USB_SERVICE) as UsbManager

        mPermissionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                mContext,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                mContext,
                0,
                Intent(ACTION_USB_PERMISSION),
                0
            )
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (!isReceiverRegistered) {
            try {
                mContext?.registerReceiver(mUsbDeviceReceiver, filter)
                isReceiverRegistered = true
                Log.v(LOG_TAG, "Receiver registrado com sucesso")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Erro ao registrar receiver: ${e.message}")
            }
        }
    }

    fun dispose() {
        try {
            if (isReceiverRegistered && mContext != null) {
                mContext?.unregisterReceiver(mUsbDeviceReceiver)
                isReceiverRegistered = false
                Log.v(LOG_TAG, "Receiver removido com sucesso")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Erro ao remover receiver: ${e.message}")
        }

        closeConnectionIfExists()
    }

    fun closeConnectionIfExists() {
        try {
            mUsbDeviceConnection?.let { connection ->
                mUsbInterface?.let { intf ->
                    connection.releaseInterface(intf)
                }
                connection.close()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Erro ao fechar conexão: ${e.message}")
        } finally {
            mUsbInterface = null
            mEndPoint = null
            mUsbDeviceConnection = null
        }
    }

    fun getDeviceList(): List<UsbDevice> {
        val manager = mUSBManager ?: return emptyList()

        return try {
            ArrayList(manager.deviceList.values)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Erro ao listar dispositivos: ${e.message}")
            emptyList()
        }
    }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        val manager = mUSBManager ?: return false

        if (mUsbDevice == null ||
            mUsbDevice?.vendorId != vendorId ||
            mUsbDevice?.productId != productId
        ) {
            closeConnectionIfExists()

            val devices = getDeviceList()

            for (usbDevice in devices) {
                if (usbDevice.vendorId == vendorId &&
                    usbDevice.productId == productId
                ) {

                    Log.v(
                        LOG_TAG,
                        "Solicitando permissão -> vendor=${usbDevice.vendorId}, product=${usbDevice.productId}"
                    )

                    manager.requestPermission(usbDevice, mPermissionIntent)
                    mUsbDevice = usbDevice
                    return true
                }
            }
            return false
        }

        return true
    }

    private fun openConnection(): Boolean {
        val device = mUsbDevice ?: return false
        val manager = mUSBManager ?: return false

        if (mUsbDeviceConnection != null) return true

        val usbInterface = device.getInterface(0)

        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)

            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {

                val connection = manager.openDevice(device) ?: return false

                return if (connection.claimInterface(usbInterface, true)) {

                    mEndPoint = ep
                    mUsbInterface = usbInterface
                    mUsbDeviceConnection = connection

                    Toast.makeText(mContext, "Dispositivo conectado", Toast.LENGTH_SHORT).show()
                    true

                } else {
                    connection.close()
                    false
                }
            }
        }

        return false
    }

    fun printText(text: String): Boolean {
        if (!openConnection()) return false

        Thread {
            try {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                val result = mUsbDeviceConnection?.bulkTransfer(
                    mEndPoint,
                    bytes,
                    bytes.size,
                    100000
                )
                Log.i(LOG_TAG, "printText result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Erro printText: ${e.message}")
            }
        }.start()

        return true
    }

    fun printRawText(data: String): Boolean {
        if (!openConnection()) return false

        Thread {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val result = mUsbDeviceConnection?.bulkTransfer(
                    mEndPoint,
                    bytes,
                    bytes.size,
                    100000
                )
                Log.i(LOG_TAG, "printRawText result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Erro printRawText: ${e.message}")
            }
        }.start()

        return true
    }

    fun write(bytes: ByteArray): Boolean {
        if (!openConnection()) return false

        Thread {
            try {
                val result = mUsbDeviceConnection?.bulkTransfer(
                    mEndPoint,
                    bytes,
                    bytes.size,
                    100000
                )
                Log.i(LOG_TAG, "write result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Erro write: ${e.message}")
            }
        }.start()

        return true
    }
}