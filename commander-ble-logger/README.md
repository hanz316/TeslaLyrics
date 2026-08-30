# Commander BLE Logger

Android BLE reverse-engineering helper for the Tesla Commander investigation.

Features:
- BLE scan with name/address/RSSI
- Direct GATT connection and full service/characteristic enumeration
- Automatic subscription to Notify/Indicate characteristics
- Automatic reads of readable characteristics
- Full timestamped RX/TX logging in HEX and printable ASCII
- Manual HEX writes to writable characteristics for reproducing handshakes/queries
- User event markers for correlation with vehicle actions
- Exportable log file
- Import and parse standard Android Bluetooth HCI snoop (`btsnoop`) logs, extracting ATT/GATT traffic including Write Request/Command, Read, Notification and Indication packets

Important: a normal Android app cannot passively intercept another app's BLE GATT session directly. To analyze a WeChat-to-Commander session, enable Android Bluetooth HCI snoop logging, perform the session in WeChat, obtain the btsnoop file, then import it here. Direct mode lets this app connect to the Commander itself and inspect its GATT behavior.
