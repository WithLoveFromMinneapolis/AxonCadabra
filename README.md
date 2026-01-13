# AxonCadabra

Android BLE tool to wirelessly triggering Axon Body Cams to start recording

## Features

- Scan for nearby Axon BLE devices to check for presence of Cameras
- Broadcast BLE advertising data to trigger camera recording (leverages https://www.axon.com/products/axon-signal)
- Fuzz mode: iterate through payload values at 500ms intervals 

## Notes
- Dont bet your life on this working, though there are no reports of any agencies having done so the cameras can be configured to ignore non allow listed Axon serial numbers
- Cameras can only be forced to START recording, the camera operator always has the ability to hit the action button on the camera and stop the recording
- Once a given BLE device has triggered a given camera, there is some cool down before that device can trigger it again. The fuzz mode is an attempt to bypass this trigger cool down restriction.

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

If you're making money I should be too, otherwise run wild
