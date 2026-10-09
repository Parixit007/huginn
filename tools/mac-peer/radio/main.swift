// Raven Mac test peer: the Bluetooth helper (build plan 5.6, D95). Dev-only, never shipped.
//
// It drives the Mac's Bluetooth through Apple's CoreBluetooth and nothing else, and only moves raw
// fragments. Raven's real engine (Kotlin) runs in the parent process and talks to it over stdin/stdout,
// one line per event:
//   out  STATE <on|off|unauthorized|unsupported|resetting|unknown>
//        UP <id> <maxWrite>   a phone is connected and we subscribed to its OUT characteristic (PROTOCOL.md §8.3)
//        RX <id> <hex>        one fragment the phone notified
//        DONE <id>            the last TX was handed to the radio; the next one may follow (PROTOCOL.md §8.5)
//        DOWN <id>            the link is gone
//   in   TX <id> <hex>        write one fragment to the phone's IN characteristic (without response)
//        CLOSE <id>           disconnect
// It only dials and never advertises: macOS can't put the link token in an advert (D100).

import CoreBluetooth
import Foundation

let serviceID = CBUUID(string: "21F4AEC6-C5B9-4784-86B5-D37334400940")
let inID = CBUUID(string: "3084CE15-1725-4635-B7BB-D9E9807A29FF")
let outID = CBUUID(string: "0FF2FCB6-7980-41DB-845A-4F495C69BA63")
let maxLinks = 4 // D98

func emit(_ line: String) {
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}

final class Link {
    let id: Int
    let peripheral: CBPeripheral
    var input: CBCharacteristic?
    var up = false
    /** A fragment waiting for the radio's send buffer to drain. */
    var waiting: Data?

    init(id: Int, peripheral: CBPeripheral) {
        self.id = id
        self.peripheral = peripheral
    }
}

final class Radio: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    let queue = DispatchQueue(label: "radio")
    var central: CBCentralManager!
    var links: [UUID: Link] = [:]
    var nextID = 1

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    // ------------------------------------------------------------------ central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            emit("STATE on")
            central.scanForPeripherals(withServices: [serviceID], options: nil)
        case .poweredOff: emit("STATE off")
        case .unauthorized: emit("STATE unauthorized")
        case .unsupported: emit("STATE unsupported")
        case .resetting: emit("STATE resetting")
        default: emit("STATE unknown")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi: NSNumber
    ) {
        guard links[peripheral.identifier] == nil, links.count < maxLinks else { return }
        links[peripheral.identifier] = Link(id: nextID, peripheral: peripheral)
        nextID += 1
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        drop(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        drop(peripheral)
    }

    // ------------------------------------------------------------------ one phone

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else {
            return close(peripheral)
        }
        peripheral.discoverCharacteristics([inID, outID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard
            let link = links[peripheral.identifier],
            let input = service.characteristics?.first(where: { $0.uuid == inID }),
            let output = service.characteristics?.first(where: { $0.uuid == outID })
        else { return close(peripheral) }
        link.input = input
        peripheral.setNotifyValue(true, for: output)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard let link = links[peripheral.identifier], characteristic.uuid == outID else { return }
        if error != nil || !characteristic.isNotifying { return close(peripheral) }
        if !link.up {
            link.up = true
            emit("UP \(link.id) \(peripheral.maximumWriteValueLength(for: .withoutResponse))")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard
            let link = links[peripheral.identifier], link.up,
            characteristic.uuid == outID, let value = characteristic.value
        else { return }
        emit("RX \(link.id) \(value.hex)")
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard let link = links[peripheral.identifier], let data = link.waiting else { return }
        link.waiting = nil
        write(link, data)
    }

    // ------------------------------------------------------------------ helpers

    func drop(_ peripheral: CBPeripheral) {
        guard let link = links.removeValue(forKey: peripheral.identifier) else { return }
        if link.up { emit("DOWN \(link.id)") }
    }

    func close(_ peripheral: CBPeripheral) {
        central.cancelPeripheralConnection(peripheral)
    }

    func write(_ link: Link, _ data: Data) {
        guard let input = link.input else { return }
        if link.peripheral.canSendWriteWithoutResponse {
            link.peripheral.writeValue(data, for: input, type: .withoutResponse)
            emit("DONE \(link.id)")
        } else {
            link.waiting = data // sent from peripheralIsReady
        }
    }

    func command(_ line: String) {
        let parts = line.split(separator: " ")
        guard parts.count >= 2, let id = Int(parts[1]),
              let link = links.values.first(where: { $0.id == id }) else { return }
        switch parts[0] {
        case "TX" where parts.count == 3:
            if let data = Data(hex: String(parts[2])) { write(link, data) }
        case "CLOSE":
            close(link.peripheral)
        default:
            break
        }
    }
}

let radio = Radio()
DispatchQueue.global().async {
    while let line = readLine() {
        radio.queue.async { radio.command(line) }
    }
    exit(0) // the engine went away
}
dispatchMain()
