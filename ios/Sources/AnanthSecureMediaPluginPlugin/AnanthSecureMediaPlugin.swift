import Foundation

@objc public class AnanthSecureMediaPlugin: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
