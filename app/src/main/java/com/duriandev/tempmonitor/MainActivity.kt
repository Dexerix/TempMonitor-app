package com.duriandev.tempmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Éléments de l'interface
    private lateinit var tvStatus: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var btnConnect: Button

    // Variables Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    // Les UUIDs identiques à ceux de ton ESP32
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    // UUID standard pour activer les notifications BLE
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Lier les variables aux éléments du fichier XML
        tvStatus = findViewById(R.id.tvStatus)
        tvTemperature = findViewById(R.id.tvTemperature)
        tvHumidity = findViewById(R.id.tvHumidity)
        btnConnect = findViewById(R.id.btnConnect)

        // Initialisation du Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Action du bouton
        btnConnect.setOnClickListener {
            if (hasPermissions()) {
                startBleScan()
            } else {
                requestPermissions()
            }
        }
    }

    // --- 1. GESTION DES PERMISSIONS ---
    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, 1)
        Toast.makeText(this, "Veuillez accepter les permissions et recliquer sur le bouton", Toast.LENGTH_LONG).show()
    }

    // --- 2. SCAN DU BLUETOOTH ---
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Veuillez activer le Bluetooth sur votre téléphone", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter!!.bluetoothLeScanner
        if (!isScanning) {
            tvStatus.text = "Statut : Recherche de Frigo_I1..."
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            isScanning = true
            scanner.startScan(scanCallback)

            // Arrête le scan après 10 secondes pour économiser la batterie
            Handler(Looper.getMainLooper()).postDelayed({
                if (isScanning) {
                    scanner.stopScan(scanCallback)
                    isScanning = false
                    if (bluetoothGatt == null) tvStatus.text = "Statut : Appareil introuvable"
                }
            }, 10000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Si on trouve notre frigo !
            if (device.name == "Frigo_I1") {
                isScanning = false
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                tvStatus.text = "Statut : Connexion en cours..."

                // On se connecte à l'ESP32
                bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    // --- 3. CONNEXION ET LECTURE DES DONNÉES ---
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Statut : Connecté !"
                    tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                }
                // Une fois connecté, on cherche les services (les "tiroirs")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Statut : Déconnecté"
                    tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
                gatt.close()
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {
                    // On s'abonne aux notifications pour recevoir les données en direct
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        // Cette fonction s'active à chaque fois que l'ESP32 envoie une nouvelle température
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Lecture des données brutes envoyées par l'ESP32
            val data = characteristic.value
            val dataString = String(data) // Ex: "25.0|55.0"

            Log.d("BLE_FRIGO", "Reçu : $dataString")

            // On sépare la température de l'humidité en utilisant le caractère "|"
            val values = dataString.split("|")
            if (values.size == 2) {
                val temp = values[0]
                val hum = values[1]

                // Mise à jour de l'interface graphique (Obligatoire de le faire sur le thread principal)
                runOnUiThread {
                    tvTemperature.text = "$temp °C"
                    tvHumidity.text = "$hum %"
                }
            }
        }
    }
}