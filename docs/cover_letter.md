# Cover letter (paste into the ScholarOne "Cover Letter" box)

Dear Editor,

Please consider the manuscript "Post-Quantum Key Establishment over Bluetooth
Low Energy Between a Smartphone and an ESP32: Link Parameters Dominate" for
publication in IEEE Embedded Systems Letters.

The letter reports a measurement study of ML-KEM-768 (FIPS 203) carried over
the BLE GATT link between an Android phone and an ESP32. A full factorial of
ATT MTU, connection interval and microcontroller clock (3600 verified
handshakes) shows that the link configuration, not the post-quantum
computation, sets the handshake latency. It also documents two practical
findings for developers: Android 16 negotiates a 517-byte MTU whatever the app
requests, and the phone's fuel gauge cannot resolve per-handshake energy.

The work is original, has not been published, and is not under consideration
elsewhere. I have one other letter currently under review at this journal, on
reinforcement-learning-based deception for post-quantum ESP32 IoT gateways.
The two manuscripts address different questions and share no data, figures or
text.

The firmware, Android app, raw data and analysis scripts are public
(https://github.com/zyeemzia/pqc-ble-phone) and archived at
https://doi.org/10.5281/zenodo.24507281.

An AI assistant was used for code and text drafting; this is disclosed in the
Acknowledgment in line with IEEE policy. I designed the study, ran all
experiments and take full responsibility for the content.

Sincerely,
Zyeem Bin Zia
Independent researcher, Dhaka, Bangladesh
ORCID: 0009-0003-6903-5068
