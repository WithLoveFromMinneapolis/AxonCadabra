# AxonCadabra

Android BLE tool for interacting with Axon body cameras (OUI 00:25:DF).

## Features

- Scan for nearby Axon BLE devices
- Broadcast BLE advertising data to trigger camera recording
- Fuzz mode: iterate through payload values at 500ms intervals

## Requirements

- Android 8.0+ (API 26)
- Bluetooth LE support
- Location and Bluetooth permissions

## Build

```
./gradlew assembleDebug
```

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

For research and educational purposes only.
